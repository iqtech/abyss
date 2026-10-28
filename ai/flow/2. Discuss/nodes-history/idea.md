# Node history via the per-node version counter

Created: 2026-10-27

Keep every committed state of a node in `abyss.node_history`, written in the same statement as the
node upsert. Ordering comes from a per-node `version` counter on `abyss.nodes`, so it needs no Kafka
and covers every write path (`modifyNode`, cascades, REST, `batchTransaction`).

This is option "N" from `ai-scripts/NodeHistoryRFC.md` §4.2, promoted from fallback to base design.
It's the same column as Phase 1 of `ai-scripts/VersionGuardPlan.md`, so this work is also the first
step of the lost-update fix. The Kafka `(ts, offset)` key from RFC §2 becomes an optional later
addition, used for dropping redeliveries and for time-based retention.

## Target statement

Replaces the node upsert in `commitYsql` and `commitYsqlBatched`
(`YugabytePersistentStore.kt:309`, `:369`):

```sql
WITH u AS (
    INSERT INTO abyss.nodes AS n (id, type, data, tags, created_at, updated_at)
    VALUES (?, ?, ?, ?, ?, ?)
    ON CONFLICT (id) DO UPDATE SET type = EXCLUDED.type, data = EXCLUDED.data,
        tags = ARRAY(SELECT DISTINCT UNNEST(n.tags || EXCLUDED.tags)),
        updated_at = EXCLUDED.updated_at,
        version = n.version + 1
    RETURNING id, version, type, data, updated_at
)
INSERT INTO abyss.node_history (node_id, version, type, data, updated_at)
SELECT id, version, type, data, updated_at FROM u;
```

The first insert takes `version` from the column default (1), so the first state is recorded too.

## What a first working solution needs

1. **Schema** (`ysql-schema.sql` plus a migration for existing deployments):
   - `ALTER TABLE abyss.nodes ADD COLUMN version BIGINT NOT NULL DEFAULT 1`. 1-based, 0 stays
     reserved as VersionGuardPlan §3.2 says. Existing rows read as 1 and have no history row; that's
     accepted (history starts at the migration).
   - The history table (shape decided 2026-10-27):
     ```sql
     CREATE TABLE IF NOT EXISTS abyss.node_history (
         node_id    BYTEA       NOT NULL,   -- raw NodeId bytes, same as nodes.id
         version    BIGINT      NOT NULL,   -- nodes.version at commit, 1-based
         type       TEXT        NOT NULL,   -- stored for consumers; not keyed, not indexed
         data       JSONB       NOT NULL,   -- the committed node state (store stays JSON)
         updated_at TIMESTAMPTZ NOT NULL,
         PRIMARY KEY (node_id HASH, version ASC)
     );
     ```
     Every access already has the id: per-node reads, the item 3 cascade
     `DELETE … WHERE node_id = ?`, and `ORDER BY version DESC LIMIT 1` for the upgrade path. All
     single-tablet, with one row per insert and no secondary index.
     Rejected:
     - an index on `type`: every history insert also writes an index entry. Measured on `nodes`:
       +18% at 8 concurrent connections, +8% in batches (`TypeIndexScanPlan.md` §5.4). It also
       hashes on `type`, so all index entries of one type land on a single tablet.
     - `(type, node_id)` as the hash key: lookup by `node_id` alone can't compute the hash, so it's
       a full scan, and it still doesn't serve `WHERE type = ?`.
     - `type` alone as the hash key: every write of a type goes to one tablet. That kills
       scale-out.
     Deleting every node of a type doesn't need type access on history: take the node ids from
     `nodes` through `idx_nodes_type` (the TODO 2.34 type scan), and item 3 cascades each one by
     `node_id`.
2. **Both commit paths** switch to the statement above. If any path still uses the old upsert, history
   silently loses versions, so both paths change in the same commit.
3. **History is deleted with the node** (decided 2026-10-27; closes RFC open question 6). The counter
   lives on the `nodes` row, so deleting a node resets it. Without this rule, re-creating the id inserts
   at `version` 1, collides with the old `(node_id, 1)` in history, and every later write to that id
   rolls back. (Found by reading the SQL, not run yet.) So `DeleteNode` on a `@WithHistory` type also
   runs `DELETE FROM abyss.node_history WHERE node_id = ?` in the same transaction: one statement on
   one tablet, and nothing extra on the write path. A re-created node starts a new history at v1.
   Deleting a node also erases its past, which suits decommissioning and GDPR-style erase, but this
   isn't an audit trail.
   - **Upgrade path if history must outlive the node:** on the insert branch only, start the
     version at `(SELECT COALESCE(MAX(version), 0) + 1 FROM node_history WHERE node_id = ?)`. That's a
     single-tablet read of the newest key. Keep that read off the update branch, or every save pays
     for it. Rejected: a generation column (it lives on the node row and is lost in the same delete),
     and soft-delete tombstones (every read path would have to filter them).
4. **Opt-in per node type: `@WithHistory`** (decided 2026-10-27; closes RFC open question 5). History
   doubles node writes, so only annotated types get it. A class-level annotation in `abyss-store-api`,
   next to `@TypeTag`, `@EdgeConstraint` and `@CrossSchemaEdge`. A node never changes type, so the
   flag is resolved once per class at schema registration, never per write. `PersistentOp.SaveNode`
   carries the resolved `history: Boolean`, and the store picks the plain upsert or the CTE statement
   per op.
5. **Persistent store only.** Cache-only and ephemeral (YCQL) can't hold history. Registering a
   `@WithHistory` type without a YSQL store fails at wiring time (RFC open question 8).
6. **Out of v1:** a read API (`history(nodeId, from, limit)`), retention, dropping Kafka redeliveries,
   and the CAS guard itself (VersionGuardPlan Phases 2–4). Downstream reads the table directly.

## Tests (red first, on the dev container with true read committed)

- 16 coroutines write N updates each to the **same** id. Assert history holds exactly versions 1..total
  with no gaps or duplicates, and the last row's `data` equals the node's. Red on today's code: no
  column, no table.
- Repeat on distinct ids (odd counts, non-zero starting state).
- A plain (unannotated) type writes no history rows; a `@WithHistory` type does, in the same transaction.
- Every commit path: `transaction`, `batchTransaction` across chunk boundaries (batch size not a
  divisor of the op count), cascade writes. Each must increment by exactly 1 per save.
- Two saves of one id inside one transaction produce two history rows.
- Delete, then re-create: the delete leaves no history rows for the id; the re-create writes v1 and
  commits. Red with the cascade removed (the duplicate key rolls the transaction back).
- Guard check: put the old upsert back on one path and the gap assertion must fail.

## Performance doubts

Measured on 2026-09-24 (RFC §4.3, §5.4): live YB 2025.1.0.1, single dev container, 16 writers.

- **Distributed commit.** `nodes` and `node_history` are separate tables, so separate tablets, and
  every save becomes a two-tablet transaction. RFC measurement on distinct ids: 3170/s → 2690/s (−15%).
- **History vs the graph-native chain, measured 2026-10-27** (`HistoryVsChainWriteCostTest`, `-Pperf`,
  dev container, YB smart driver, one event per transaction, median of 5 interleaved rounds after a
  warm-up). Each variant has its own copy of `nodes`/`edges` with the production DDL and every index.
  The chain is: live upsert + event node + `has-next` edge + tail pointer moved as `RemoveEdge` +
  `AddEdge`. Database only, so the chain's Hazelcast puts and adjacency processors are **not**
  included and the chain comes out cheaper than it really is. Assertions: row counts per variant;
  each entity's history is exactly versions 1..n, with the node on version n.

  | workload | plain | history | chain |
  |---|---|---|---|
  | 1 writer, 1 entity, 1000 events | 512/s (1954 ms) | 500/s (1999 ms), **+2.3%** | 154/s (6512 ms), +233% |
  | 16 writers, own entity each, 200 events each | 3754/s (852 ms) | 3035/s (1054 ms), **+23.7%** | 860/s (3721 ms), +337% |

  - The single-writer case, the expected ingest shape, is nearly free: +2.3%, inside the noise of
    one dev box. That closes RFC §6 step 2's open measurement.
  - With concurrency it's +23.7%, worse than the RFC's −15% (that's +18% on time). The history range
    was wide (980..1450 ms) against plain's 834..889, so this number is noisy. Re-measure on a real
    cluster before reading anything into the gap.
  - The chain costs 3.3× plain with one writer and 4.4× with 16. It's 3.3× (one writer) to 3.5×
    (16 writers) history's time, before any cache cost.
- **Hot ids get worse.** Contended writes to one row already top out around 16–25/s at p50 ~1 s
  (`ConflictResolution_WaitOnConflictingTxns`, suspected `wait_queue_poll_interval_ms=100`, not
  confirmed). N measured 6–8/s, about 2× worse. Hot nodes (counters, aggregates) need their own fix
  first; history should probably stay off for them.
  - **One-id contention, measured 2026-10-28** (`HistoryVsChainWriteCostTest` "one id under
    2-4-8-16 writers", `-Pperf`, 21 events per writer, one transaction per event, raw JDBC, no retry;
    an abort is rolled back, counted and skipped; 4 runs):

    | writers | plain commits/s | CTE commits/s | aborts plain / CTE |
    |---|---|---|---|
    | 2 | 199–213 | 144–255 | 0 / 0 of 168 |
    | 4 | 182–409 | 29–51 | 0 / 0 of 336 |
    | 8 | 188–442 | 16–23 | 1 / 1 of 672 |
    | 16 | 31–50 | 7–15 | 3 / 6 of 1344 |

    - The cost of history under contention is **throughput**: from 4 writers on one id it is 4–25×
      slower than plain. With 2 writers it matches plain.
    - **Aborts are rare and not history-specific**: plain aborts with 40001 (serialization), the CTE
      with 40P01 (deadlock), 0.1–0.5% at 8–16 writers (worst single run: CTE 4 of 336). An earlier version of the test threw on
      the first abort and read as "plain completes, history fails"; that was a 0-vs-1 comparison of a
      rare event, not a real difference.
    - **Exact in every run**: history rows = commits, versions gap-free, node on the last version, so
      an abort leaves nothing behind.
    - Consequences: processor-per-id (one writer per id, occasional 2-writer overlap) is safe at full
      speed. Any production path with concurrent writers on one id can abort today, history or not, so a
      bounded retry on 40001/40P01 in `commitYsql` is a store fix in its own right, not a history
      prerequisite. The trigger form aborted with `yb_max_query_layer_retries set to 60 are
      exhausted` (40001) in the earlier runs; it is rejected on cost anyway.
- **Batched path loses insert rewriting (measured 2026-10-28).** Production sets
  `reWriteBatchedInserts=true` (`YugabytePersistentStore.kt:476`). `HistoryBatchRewriteTest`
  (`-Pperf`, dev YB, `commitYsqlBatched` shape, chunks of 1000, median of 5 interleaved rounds):
  the driver reports `SUCCESS_NO_INFO` for the plain upsert (rewritten) and row counts for the
  history CTE (not rewritten), with the flag on or off.

  | ops | plain, rewrite on | plain, rewrite off | history, rewrite on | history, rewrite off |
  |---|---|---|---|---|
  | 1003 | 124 ms (8057/s) | 800 ms (1253/s) | 878 ms (1142/s) | 877 ms (1144/s) |
  | 10007 | 1394 ms (7177/s) | 8442 ms (1185/s) | 9171 ms (1091/s) | 9118 ms (1098/s) |

  - Batched history is **6.6×** the plain batched time. Almost all of that is the lost rewrite:
    against plain with rewriting off, history costs only +8..10%.
  - Regaining it means a node save that pgjdbc can rewrite: it must start with `INSERT` and carry no
    `RETURNING`. **Trigger form measured and rejected (2026-10-28).** The plain upsert plus
    `version = n.version + 1` and a `history BOOLEAN` flag, with an AFTER INSERT OR UPDATE row trigger
    (`WHEN (NEW.history)`) copying the row into `node_history`. It is rewritten (`SUCCESS_NO_INFO`), and
    gap-free in every round, but slower than the CTE everywhere:

    | workload | plain | CTE | trigger |
    |---|---|---|---|
    | batched 10007 ops, rewrite on | 1380 ms | 9101 ms | 11942 ms (rewrite off: 13983) |
    | batched 1003 ops, rewrite on | 127 ms | 866 ms | 1125 ms |
    | 1 writer, 1000 events, 1 tx each | 2001 ms | 2073 ms (+3.6%) | 2639 ms (+31.9%) |
    | 16 writers, own id, 200 each | 847 ms | 1007 ms (+18.8%) | 1089 ms (+28.6%) |

    The rewrite saves the trigger 15% (13983 → 11942 ms), but the per-row PL/pgSQL insert costs more
    than the round trips it saves. The CTE stays.
  - **Multi-row CTE measured 2026-10-28: regains the batch and beats plain.** The same CTE fed by
    `unnest()` of six parallel arrays instead of one `VALUES` row, one statement per 1000-op chunk
    (`HistoryBatchRewriteTest` `multirow`, same run as the baselines below):

    | batched | 1003 ops | 10007 ops |
    |---|---|---|
    | plain upsert, rewritten (production) | 126 ms | 1375 ms |
    | per-row CTE | 873 ms | 9198 ms |
    | trigger | 1120 ms | 12062 ms |
    | multi-row CTE | 110 ms | 1138 ms (−17% vs plain, 8.1× faster than per-row CTE) |

    Exact in every round: each chunk's `executeUpdate()` returned the chunk size, history rows = ops,
    versions gap-free, max version 11. The rewrite flag makes no difference to it (one statement).
    - Why it beats plain (reading, not measured separately): pgjdbc splits a rewritten 1000-row batch
      into several power-of-two multi-values statements with 6 parameters per row; unnest is one
      statement, 6 array parameters, one plan. So the plain batched upsert could also move to unnest.
    - Tags travel as one text literal per row (`'{a,b}'`) cast with `tags::text[]` (PostgreSQL arrays
      are rectangular, `unnest` of a `text[][]` flattens it). The literal must come from the driver's
      quoting, `createArrayOf("text", tags).toString()`, never a hand join: a joined `{a,b,"q",{x}}`
      fails with `malformed array literal`, and quieter inputs corrupt silently (`a,b` splits into two
      tags, a bare `NULL` becomes an SQL null). Verified by execution, `HistoryBatchRewriteTest`
      "non-empty tags land verbatim and merge on conflict" (normal suite): commas, quotes, braces,
      backslash, blanks, empty string and `NULL` land verbatim over two chunks on 3 ids; conflict
      merges as a union and versions reach 2. It goes red with the hand join.
    - A group must hold distinct ids (one statement can't touch a row twice); the duplicate-key cut
      below already guarantees that.
    - `createArrayOf("bytea", Array<ByteArray>)` binds correctly on the YB driver (verified by the run).
  - A multi-row history statement inherits the plain path's duplicate-id failure
    (`BatchDuplicateIdTest`: two saves of one id in one rewritten block fail the chunk with "ON
    CONFLICT DO UPDATE command cannot affect row a second time"). Fixed in `commitYsqlBatched` by
    cutting the group before a key repeats (`bcc43a4`); collapsing duplicates was rejected because
    each save is a version.
  - **Grouping key must become the bound statement.** `commitYsqlBatched` groups by op class
    (`chunk[j]::class == chunk[i]::class`) and runs `executeBatch()` on the first op's statement only.
    Once a `@WithHistory` `SaveNode` binds a different statement than a plain one, a mixed group puts
    rows on two statements and executes one: the other's rows run later out of order or are lost
    silently. Group by the statement `bind` returns, not by op class.
- **Mixed types break up batches.** `commitYsqlBatched` groups consecutive ops that use the same
  statement into one `executeBatch()`. A transaction that mixes `@WithHistory` and plain node types
  alternates between the two statements and splits those groups into smaller batches.
  **Measured 2026-10-28** (`HistoryBatchRewriteTest` "mixed history and plain saves", `-Pperf`, dev
  YB, 10007 node saves, chunks of 1000, rewrite on, multirow for history, median of 5 interleaved
  rounds; history exact and no history rows for plain ids, every round):

  | shape | median | vs all-plain |
  |---|---|---|
  | all plain (production today) | 1462 ms | — |
  | all history, multirow | 1123 ms | −23% |
  | 50/50 alternating, op order kept (groups of one) | 16730 ms | **11.4×** |
  | 50/50, each chunk stably split by statement | 1338 ms | −8.5% |

  - Order-kept grouping is the trap: alternating types cost 11.4× plain, 15× all-history. One
    statement round trip per op.
  - Splitting each chunk by statement fixes it: one multirow + one rewritten batch per chunk, faster
    than today's all-plain path.
  - The split reorders ops across rows. Safe for the same reason `commitYsql`'s `LOCK_ORDER` sort is
    (no reads in the commit, no FKs or triggers), but only if each row's own ops keep their order: a
    `SaveNode(x)` then `DeleteNode(x)` sit on different statements, so a plain partition by statement
    would swap them. The split rule must keep per-row order (e.g. cut the chunk segment where a row
    reappears under a different statement). Design in Plan; the probe had saves only.
- **Write amplification.** Every save copies the full JSONB once more. Storage grows without bound,
  with no time column in the key and no row TTL in YSQL, so retention is a scan plus delete. Long
  per-id histories grow one tablet's key range.
- **Scale gap.** The target is a million events a minute (~16.7k/s). 2.7k/s on one dev container
  says nothing about a multi-node cluster. Before calling it viable, measure on a real topology at
  that write rate, with and without history.
- **Migration cost.** `ADD COLUMN … NOT NULL DEFAULT 1` on a large `nodes` table: whether YB does
  this without rewriting the table is unverified.
