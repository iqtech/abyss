# Plan: nodes-history v1

Created: 2026-10-31. Decisions live in `idea.md` items 1–6; this file is the build order.

## Context

Keep every committed state of a `@WithHistory` node in `abyss.node_history`, written in the same
statement as the node upsert, ordered by a per-node `version` that every node upsert increments.
Measured through the real store (`idea.md`, Performance doubts): single op +6 to +12% on near-all
inserts, batched 19–22% under plain, exact under contention. The 40001/40P01 retry (`3470582`) is in, so
contended history commits no longer surface as `Left`.

**Status 2026-11-02:** phases A, B+C and D are in (`96f1ab2`, `40385de`), `NodeVersionTest` is green,
annotated types get gap-free history on both commit paths, plain types pay the `version` increment
(+5.3% batched, none measurable single), and deleting a node removes its history. See Open below.

## Probes (before code)

**Status 2026-10-31:** both run, numbers in `idea.md` (Performance doubts, P1 and P2).
- P2: the increment costs +0.3% to +4.7% and keeps the batch rewrite. Passed.
- P1: the join form failed (+18–24%, root cause YB's Batched Nested Loop). The scalar-subquery form
  costs +5%: small, but outside noise. **Decided 2026-11-02: accept +5% on plain deletes, the scalar
  subquery is the v1 form. The `RETURNING` fallback isn't measured.** Real store: +5.7%.

- **P1, delete CTE** (`idea.md` item 3). Raw JDBC on the dev container, `-Pperf`:
  - The CTE runs through `addBatch`/`executeBatch` without "a result was returned" errors.
  - Semantics: a plain type leaves `node_history` alone; history and unknown types cascade.
  - Cost of a plain-type delete, CTE against bare `DELETE`: single op, and 1003/10007-op batches.
  - The bar was "within noise of the bare delete". +5% misses it and was accepted on 2026-11-02.
    Unused fallback, the original Ad 1 form: `DELETE … RETURNING type` run with `executeQuery`, then
    the history delete when the type isn't a known plain type. The store can't choose a statement up
    front, because it learns the type only from the deleted row. That costs one round trip per delete
    on the batched path.
- **P2, plain baseline with the increment.** The plain upsert plus `version = n.version + 1`, against
  today's upsert, single op and batched. idea.md item 2 requires this before history is compared to
  "plain".

## Phases (each a commit, both commit paths in the same commit)

### A. Schema plus version increment
- `ysql-schema.sql`: `version BIGINT NOT NULL DEFAULT 1` on `nodes`, and the `node_history` DDL
  (item 1). Below it, as a comment, the `ALTER TABLE` for existing deployments, pointing at the
  idempotent `CREATE TABLE` rather than repeating it.
- Apply both to the shared test DB `abyss_test_graph` by hand (`ysqlsh`), as the comment says.
- Both node upserts in `YugabytePersistentStore` gain `version = n.version + 1`.
- `NodeVersionTest` builds its schema with `LIKE abyss.nodes`, which will now copy `version`, so its
  `ADD COLUMN` becomes `ADD COLUMN IF NOT EXISTS`.
- Red already: `NodeVersionTest`. Goes green here.
- **Done 2026-11-02.** Red: versions `[1,1,1]` vs `[4,2,1]`, and 1 vs 3. Green after, with each path's
  increment removed in turn failing exactly that path's test. Test DB migrated: 39869 rows read as
  version 1, 0 FAILED tablets, `/home` unchanged at 16 GB free.

### B+C. History write, batched grouping and delete cascade (one commit)
These land together: history without the cascade reopens the re-create collision (item 3).
- `@WithHistory` in `abyss-store-api`, next to `TypeTag.kt`.
- Store construction: walk the module with a `SerializersModuleCollector`, as `TypeTagRegistry`
  does. Take each node subclass's serial name from its serializer descriptor and fill `historyClasses` /
  `plainTypes` using `java.isAnnotationPresent(WithHistory::class.java)`. No `abyss-dsl` dependency.
- **`commitYsql`**: a `SaveNode` whose class is in `historyClasses` runs the per-row history CTE;
  otherwise the plain upsert. `DeleteNode` runs the P1 form. The `LOCK_ORDER` sort is unchanged:
  history rows share the node's id, so lock order holds.
- **`commitYsqlBatched`**:
  - Group by the statement an op binds, not by op class.
  - Within each maximal run of node saves, stably partition by statement. Deletes stay in place as
    boundaries.
    - Per-row order holds because an id always binds the same statement (type is immutable) and the
      partition is stable.
    - Ops never cross a chunk boundary, so the worker's `ops.take(committed)` replay stays exact.
  - History groups run the multi-row `unnest` statement, from `HistoryBatchRewriteTest`
    `upsertNodesWithHistoryMultirow`. Tags are bound as `createArrayOf("text", tags).toString()`
    literals, never a hand join.
  - The duplicate-key cut applies inside every group, the multi-row one included.
  - Chunk retry: unchanged. The multi-row statement isn't batched, so `clearBatch` doesn't concern it.
- **Done 2026-11-02.** `NodeHistoryTest` (cases 1-11, 16 tests): all red on phase A, then green. Full
  suites, forced fresh: store 97/97, graph 386/386. Guards, each removed in turn:
  - grouping by op class: #5, #6; partition crossing deletes: #5;
  - cascade removed: #5, #7, #8; unknown type as plain: #8;
  - old upsert on one path: that path's #2, #7, #9, #10, #11 (and #1, #3 single; #4, #5, #6 batched);
  - no duplicate-key cut: #2, #4, #7 (batched); `Timestamp[]` instants: #10.
- Changed from the plan while building:
  - **Multi-row instants go as ISO text**, not the probe's `createArrayOf("timestamptz", Timestamp[])`.
    That form sends local time with no offset, so an instant in the fall-back hour lands wrong
    (`#10`, red with Europe/Warsaw at 2026-10-25). Production's per-row `setTimestamp` isn't affected.
  - **Saves pick their statement by class** (`historyClasses`), not by the type name from `jsonPair`:
    grouping runs before binding, and the name would serialize every save twice. Deletes still bind
    the plain serial names, as item 4 decided.
  - **Partitioning covers runs of node saves only**, not edge saves: only node saves have two
    statements. An edge between node saves ends the run. That was noted as one more group, which is
    wrong on alternating node and edge saves: 15.5x, `ai/flow/0. Issues/batch-partition-node-and-edge-saves`.
- No test fails without the partition: `NodeHistoryCostTest` passes its row counts with it bypassed
  and takes 8.2x as long (Verification 3), and by reading `NodeHistoryTest` stays green too, since only
  the statement count changes. A performance regression there shows only under `-Pperf`.

### D. Wiring check
- `AbyssSchemaWorker` init, next to the existing `persistentStore == null` block (`:148`): a module
  containing a `@WithHistory` node class with no persistent store fails at construction.
- **Done 2026-11-02.** `TypeTagRegistry` collects the names during its existing module walk, so there's
  no second walk. Case 12 lives in `IndexAlwaysAliveTest`: red (construction succeeded), green with the
  `require`, and the same module with a store still constructs.

## Tests (red first; dev container, true read committed)

New `NodeHistoryTest` (abyss-store-yugabyte), through the real store, on its own schema built with
`LIKE abyss.*` as `NodeVersionTest` does, with `@WithHistory` and plain test node classes.

| # | case | red before |
|---|---|---|
| 1 | 16 coroutines × 21 saves on one id → history exactly v1..336, last row's data = node | B |
| 2 | distinct ids, odd counts, non-zero starting state, both paths | B |
| 3 | plain type writes no history; history type writes one row per save, same transaction | B |
| 4 | batch 4 over 7 ops crossing a chunk boundary, two saves of one id in a chunk → two history rows | B |
| 5 | **mixed chunk** `S(x,H) S(p,P) D(x) S(x,H) S(q,P)` → x at v1 with exactly 1 history row (from the last save), p and q present with no history: per-row order kept across the split | B |
| 6 | mixed history/plain interleaved, 10007 ops → no lost rows on either statement (guards "group by class") | B |
| 7 | delete then re-create a history id → no orphan rows, re-create commits at v1. Red with the cascade removed | C |
| 8 | delete of an **unknown** type (row and history planted by SQL with a name not in the module) → history gone | C |
| 9 | plain delete leaves other ids' history untouched | C |
| 10 | non-empty tags with commas, quotes, braces, `NULL`, data and fall-back-hour instants land verbatim on both paths | B |
| 11 | commit aborted with 40001 once (`FailAfterNCommitsDataSource`) on a history save → exactly one history row (the rollback leaves nothing) | B |
| 12 | wiring: `@WithHistory` in the module with no persistent store → construction fails (abyss-graph) | D |

**Guard checks**, each must turn its tests red:
- grouping back to op class (#5, #6);
- cascade removed (#7);
- unknown type treated as plain (#8);
- the old upsert on one path (#1, #2).

## Verification

1. P1 and P2 numbers written into `idea.md`, before and after, medians of 5.
2. Each phase: new tests red, then green, then the guard check, then the full `:abyss-store-yugabyte:test`
   and `:abyss-graph:test`.
3. Performance, through the real store (`-Pperf`): single op and batched 1003/10007, plain only, history
   only, and 50/50 mixed. Compare with today's numbers (plain batched 1375 ms; multirow 1138 ms; mixed
   split 1338 ms). Report before and after; a slower result loses to the measurement.
   **Done 2026-11-02** (`NodeHistoryCostTest`, numbers in `idea.md`): plain batched +5.3%, plain delete
   +5.7%, history batched 19–22% under plain, the partition worth 8.2x on mixed batches.
4. After the test-DB migration, check that no tablet is FAILED and `/home` has headroom (the 2026-10-29
   incident). **Done 2026-11-02:** 34 tablets RUNNING, 0 FAILED, 15 GB free at 90%. The incident isn't
   described anywhere in `ai/`, so the headroom bar is unknown.

## Open

- **Rollout order.** The new store fails every node upsert without the `version` column, so the
  `ALTER` lands first. Only `abyss_test_graph` is migrated; whether sniper runs Abyss on a database of
  its own isn't checked.

## Decided

- **History is the persistent layer's record, per id (agreed 2026-11-02).** `ephemeral()` writes never
  reach `node_history`, so there's no gap: an ephemeral state was never a persistent one. Rejecting
  `@WithHistory` types in `ephemeral()` is out, because the layer is per id, not per class. An `ephemeral`
  `removeNode` of a persistent id still fans out and cascades. The two `modifyNode` cases that cross
  layers moved to gap 2: `ai/flow/0. Issues/modifynode-crosses-layers-unguarded`. Until that lands, a
  persistent `modifyNode` on an ephemeral id writes it to `nodes` and `node_history`.
- **Don't remove `@WithHistory` and add it back on the same class (2026-11-02).** See `idea.md` item 4.

## Decided by me, override if wrong

- **Test DB migration by hand.** Altering the shared `abyss_test_graph` (`version` DEFAULT 1,
  `node_history`) is harmless to every existing test: no test counts columns, and the only `SELECT *`
  on it (`ConnectionTest`) asserts non-null. Verified by grep; applied 2026-11-02.

## Records

- `changelog.md` moves as phases land (`4. Plan → 5. Implement → 6. Test`).
- Commit messages carry the mechanism and the numbers; commit on `dev`, push only when asked.
- Out of v1 (item 6): read API, retention, Kafka redelivery dedup, CAS guard; plain batched upsert
  to `unnest` is its own idea (`discussion-summary.md` step 4).
