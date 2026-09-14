# Version guard / optimistic CAS — implementation plan

Closes `IoT.md` findings **2** (silent lost update on `modifyNode`), **B** (stale-report clobber),
and **3** (cache and DB disagree on who won). Written 2026-09-08 after TODO 1.32 closed finding 1;
rewritten 2026-09-09 after a code audit found three blocking defects in the first draft (§9).

---

## 1. The defect, in current code

`BufferedTransaction.modifyNode` (`AbyssGraphSchema.kt:319-321`) is one line:

```kotlin
ops += Op.AddNode(transform(readNode(id)), null, tags)
```

The read happens at buffer-build time on the **cache-first** read path
(`AbyssSchemaWorker.readNode:169` — `nodesMap.getAsync(nid) ?: loadAndCacheNode(nid)`); the
transform runs in memory; what reaches the DB is a blind `INSERT … ON CONFLICT DO UPDATE`
(`YugabytePersistentStore.kt:264-267`) that never references the state it read. Two coroutines both
read v5, both compute v6, both write — **last wins, no signal**. `commitYsql` discards every
`executeUpdate()` return value, so nothing could detect it even in principle.

Finding B is the same statement seen from outside: a device retransmits a reading from 40 s ago,
the packet lands late, and newer twin state is gone — last-write-wins resolves by *commit order*,
never by *reported time*.

Finding 3 is the cache half: `populateCache` → `applyToCacheAsync` does
`nodesMap.setAsync(op.id, op.node)` (`AbyssSchemaWorker.kt:535-537`) — an unconditional put,
fire-and-forget when `asyncCachePopulation` is set, with no ordering against another writer's put.

**Scope note, verified:** `populateCache` runs only *after* a successful store commit
(`AbyssSchemaWorker.kt:383-396`), so a writer that loses at the DB never reaches the cache at all.
Finding 3 is therefore a winner-vs-winner ordering window, not a loser-clobbers-winner one — real,
but narrower than the original writeup implied.

---

## 2. What was verified on the live container (2026-09-08)

Not proposals — measured, against the running YB.

### 2.1 The guard's semantics

`INSERT … ON CONFLICT (id) DO UPDATE SET …, version = n.version + 1 WHERE n.version = ?`

| # | Situation | Result | Row after |
|---|-----------|--------|-----------|
| 1 | Row absent, caller read nothing (`old = 0`) | `INSERT 0 1` | v1 — guard gates only the DO UPDATE branch |
| 2 | Row at v1, caller read 1 | `INSERT 0 1` | v2 — server-side increment |
| 3 | Row at v2, caller read 1 (stale) | `INSERT 0 0` | v2, untouched |
| 4 | Row at v2, caller read *nothing* (`old = 0`) | `INSERT 0 0` | v2, untouched |

Case 4 is the load-bearing one: "I thought this node was new" is caught, not blind-inserted — but
**only while no live row can ever hold version 0**. See §3.2; the first draft's `DEFAULT 0`
migration would have put every pre-existing row at exactly that value and voided this row of the
table.

### 2.2 The guard under contention

30 concurrent single-statement CAS writers against one row, all reading `version = 0`:

```
     29 INSERT 0 0
      1 INSERT 0 1
```

**Exactly one winner, 29 clean losers, zero errors.** Contrast `IoT.md` test 2, where the
read-then-write shape produced **19/20 exceptions** ("could not serialize access … retry isn't
possible because this is not the first command in the transaction"). The CAS form keeps the commit
a single first-statement blind write, so YB's query-layer retry still applies; the loss arrives as
a **rowcount**, not a thrown error. This is why the fix must NOT be implemented by moving the read
into the commit transaction — that buys the 19/20 failure rate back.

**Not yet measured:** §3.3 replaces the `INSERT … ON CONFLICT … WHERE` form with a plain guarded
`UPDATE` for the `expected > 0` case. It is also a single first-statement blind write, so the same
argument should hold — but "should hold" is not a measurement. Re-run 2.2's harness against the
`UPDATE` form before Phase 1 lands.

---

## 3. Design

### 3.1 The guard rides on the op, not on the model

`addNode` is a blind upsert **by intent** — the caller states the node's state, it is not an edit
of state they read. Only `modifyNode`/`modifyEdge` have read-modify-write semantics, so only they
need CAS. That means the expected version can live on the op:

```kotlin
data class AddNode(…, val expectedVersion: Long? = null)   // null → unguarded upsert
```

`NodeLike`/`EdgeLike` never learn what a version is. **No user model changes, no cache-value
wrapper, no Compact serializer churn.** This is the whole reason the design is affordable.

### 3.2 Version numbering: 1-based, 0 reserved

`0` is the sentinel for "the caller saw no row". For that to be a decidable CAS state, **no row may
ever hold version 0**:

- `ALTER TABLE abyss.nodes ADD COLUMN version BIGINT NOT NULL DEFAULT 1;` (same for `edges`) — a
  `DEFAULT 0` would park every migrated row on the sentinel, and a caller who read nothing would
  then CAS cleanly against a live row. That is precisely case 4 of §2.1, inverted.
- Every INSERT binds `version` explicitly to `1`; nothing relies on the column default after the
  migration.
- `ysql-schema.sql` gets the column at `NOT NULL DEFAULT 1` for fresh installs.

### 3.3 Three statement shapes, all boring

| `expectedVersion` | Statement | Rowcount 0 means |
|---|---|---|
| `null` (unguarded) | today's `INSERT … ON CONFLICT (id) DO UPDATE SET …, **version = n.version + 1**` | impossible — not checked |
| `0` ("saw no row") | `INSERT … ON CONFLICT (id) DO NOTHING` | the row exists → **Conflict** |
| `> 0` | `UPDATE … SET …, version = version + 1 WHERE id = ? AND version = ?` | row missing **or** stale → **Conflict** |

Three points this shape settles that a single combined statement did not:

1. **Unguarded writes still move the version.** The increment is unconditional on the DO UPDATE
   branch; only the *guard* is conditional. Without this, a blind `addNode` landing between a
   guarded reader's read and its commit leaves `version` untouched, the CAS matches, and the guard
   silently misses it — the exact lost update the feature exists to catch. The same increment must
   be added to `commitYsqlBatched`'s upsert (§6), or a bulk import goes invisible to CAS the same way.
2. **A stale non-zero expectation can no longer resurrect a deleted row.** With the
   `INSERT … ON CONFLICT … WHERE` form, the guard gates only the DO UPDATE branch, so
   "read v5 → someone deletes the row → commit" blind-inserts a fresh v1. The plain `UPDATE` has no
   insert branch: rowcount 0, Conflict, correct.
3. **`DO NOTHING` beats `DO UPDATE … WHERE n.version = 0`.** With 1-based versions the guard can
   never match, so the clause is dead weight expressing "don't update on conflict" the long way.

The `UPDATE` sets the same columns the upsert does — `type`, `data`, `updated_at`, and the tags
union `tags = ARRAY(SELECT DISTINCT UNNEST(nodes.tags || ?))` — and leaves `created_at` alone,
matching current upsert behaviour. `commitYsql` prepares two more statements alongside its existing
four; each op still runs its own `executeUpdate()`, so TODO 1.32's `LOCK_ORDER` sort is unaffected.

### 3.4 Where `expectedVersion` comes from — one read, both columns

This is the correction that decides whether the whole feature works. `modifyNode`'s value comes from
the **cache-first** `readNode`. If the version were fetched in a separate store round trip, the
transform's input and the CAS expectation would describe two different points in time, and the guard
breaks in both directions:

- fresh version + stale cached value → **CAS passes, lost update survives the fix**;
- stale version + fresh value → spurious conflicts on writes that were never in a race.

So the guarded modify path reads **value and version together, from the store**:

```sql
SELECT data, version FROM abyss.nodes WHERE id = ?
```

one statement, one snapshot; the returned value is what the transform sees; the returned version is
what the CAS binds. Missing row → `expectedVersion = 0` and `transform(null)`.

Consequences to accept deliberately:

- **A guarded modify costs one store round trip and bypasses the Hazelcast cache.** At a million
  events/minute that is the price of the guarantee. Unguarded `addNode` — the ingest path — is
  untouched and still never reads.
- **`transform` now sees DB truth rather than cache truth** for guarded modifies. Behaviour change
  for existing `modifyNode` callers; it is the *correct* value, but it is a change.
- New store-seam method, e.g. `loadNodeVersioned(id): Either<AbyssError, Pair<NodeLike<*>?, Long>>`,
  with a safe default for stores that don't version (returns the plain load + `0`).

### 3.5 Two guards, two paths — never one statement

The first draft wrote both guards into one predicate
(`WHERE n.version = ? AND n.updated_at < EXCLUDED.updated_at`) and then opened a question asking how
to tell the two failures apart. That question was self-inflicted. The answer is to never combine them.

They do catch genuinely different things, and neither implies the other:

| | Question it answers | Failure it catches |
|---|---|---|
| `version = ?` | did the row change since I read it? | concurrent read-modify-write (finding 2) |
| `updated_at < EXCLUDED.updated_at` | is my data newer than what is there? | late / out-of-order event (finding B) |

- **Version matches, event time stale.** Nobody raced you; your packet is a 40 s-late retransmit. The
  CAS passes and you write backwards. Version alone misses it.
- **Event time newer, version stale.** Another writer committed v3 at T15; your packet is T20 but was
  computed from v2's data. `T15 < T20` passes. Event time alone misses it.

Combined, a rowcount of 0 cannot say *which* guard failed — and the reactions are opposite: version
conflict → **retry**, stale event → **drop**. A caller that retries a late packet spins until the
retransmit stops; a caller that drops a version conflict silently loses the write it came to make.
One bit of information, two incompatible readings.

**The split falls out of §3.4 for free.** The guarded modify path already reads the authoritative
`data, version` from the store, so the transform sees the current `updatedAt` and can decide
event-time ordering itself:

```kotlin
modifyNode(id) { old ->
    if (old != null && old.updatedAt >= event.time) old   // late packet → no-op
    else old.applying(event)
}
```

That check is safe *because* of the version CAS: if anyone commits between the read and the commit,
the guard fails, the caller re-enters the block, and the transform re-reads a fresh `updatedAt`. The
two mechanisms compose instead of colliding. A blind `addNode` has no read to compare against, so
there the event-time guard must live in SQL — and there is no version to check, because nothing was
read.

| Path | Guard | Rowcount 0 means |
|---|---|---|
| `modifyNode` / `modifyEdge` (read-modify-write) | `version = ?` only | Conflict → retry |
| blind `addNode` carrying device event time | `updated_at < EXCLUDED.updated_at` only | late/duplicate → drop, count it |

**API consequence.** A transform that decides "my packet is old, do nothing" would still buffer an op
and issue a no-op write that bumps the version. So `modifyNode`/`modifyEdge` skip buffering when the
transform returns the **identical instance** (`===` against what was passed in) — the boring signal,
no new return type, no sentinel.

**`updated_at` ownership, verified:** already bound from `op.node.updatedAt`
(`YugabytePersistentStore.kt:285`), a plain `NodeLike`/`EdgeLike` field — caller-owned today, so the
blind-path guard needs a predicate, not an ownership change. It does need a documented contract: a
domain class that defaults `updatedAt` to `Clock.System.now()` at construction is recording *receive*
time, and the guard will then order by arrival — the exact behaviour it exists to replace.

**Precision floor:** `TIMESTAMPTZ` is microsecond-resolution, `kotlin.time.Instant` is nanosecond.
Two genuinely distinct events that truncate to the same microsecond collide, and strict `<` drops
the second **silently, by design**. `<=` is not the fix — it restores last-write-wins for the tie.
The drop counter must therefore be a first-class, observable metric, not a debug log.

Why `updated_at` can never replace the version, whichever path it is on: at 16.7k writes/s two
writers can both read `T` and both write `T`, so `= T` matches for both and the lost update goes
undetected. Only a server-side `version + 1` guarantees the value moves.

### 3.6 Rowcount handling and the error type

`commitYsql` currently ignores `executeUpdate()`. Under CAS the rowcount is load-bearing: a `0` on
any guarded op throws inside the existing `try`, the existing `catch` rolls the whole transaction
back, and the connection block rethrows.

That throw does **not** currently become `AbyssError.Conflict`:
`YugabytePersistentStore.transaction` is `Either.catch { … }.mapLeft { AbyssError.Unexpected(it) }`
(`:106-111`), so a caller pattern-matching `Conflict` would see `Unexpected` forever. The mapping
needs an explicit branch, mirroring how `batchTransaction` already special-cases `PartialBatchFailure`
(`:125-128`):

```kotlin
.mapLeft { e -> if (e is VersionConflict) AbyssError.Conflict(e.id, e.expectedVersion) else AbyssError.Unexpected(e) }
```

New variant, in **`abyss-store-api`'s `AbyssError.kt`** (alongside `BatchPartiallyCommitted`), not in
`abyss-graph`:

```kotlin
data class Conflict(val id: Any, val expectedVersion: Long) : AbyssError
```

The internal `VersionConflict` throwable carries the id from inside `commitYsql`, which has it on the
op it is executing.

### 3.7 Retrying a conflict

A `Conflict` rolls back the whole transaction, and the buffered ops are already-computed values from
an already-run transform. **Re-committing the buffer would resubmit the same stale value forever** —
a retry must re-enter the whole `transaction { }` block so the transform runs again against a fresh
read. §3.4's store-authoritative read is what makes that terminate: with a cache-first re-read, and
`asyncCachePopulation = true` (fire-and-forget, `AbyssSchemaWorker.kt:519-521`), the winner's cache
write may not have landed when the loser retries — it would re-read the same stale value and conflict
again. Bounded livelock, but livelock.

Whether Abyss ships a `retryOnConflict { }` helper or leaves the loop to callers is **Q2**.

### 3.8 Finding 3

Replace `nodesMap.setAsync(id, node)` with an `EntryProcessor` that refuses to replace a cached value
carrying a newer `updatedAt`. Same field, no new state, and it closes the winner-vs-winner ordering
window described in §1.

**Not free, and the first draft said it was.** No `HazelcastInstance` is constructed anywhere in
`src/main` — it is injected, so a client/server topology is possible, and an `EntryProcessor` executes
on the *member*: its class must be on the member classpath, or user-code deployment must be enabled.
Embedded, this is free. Client/server, it is a deployment constraint that has to be confirmed before
Phase 4 is scheduled. (TODO 2.1 says single-node today, which does not settle *embedded vs. client*.)

### 3.9 One row, one op, one expectation — per transaction

**The defect exists today, without any CAS.** Measured 2026-09-10 (probe in `GraphTest`, Hazelcast-only):

```kotlin
// row x already at name = "v0"
transaction {
    addNode(x named "staged-add")
    modifyNode(x) { old -> record(old.name); x named "mod1" }
    modifyNode(x) { old -> record(old.name); old.name + "+mod2" }
}
// seen=[v0, v0]  final=v0+mod2
```

`BufferedTransaction.modifyNode` reads through `worker.readNode` and never consults its own `ops`, so
every transform in the block sees the pre-transaction value and the last staged op silently wins. Under
CAS the same shape becomes worse: two guarded ops on x both bind the pre-transaction version, the first
bumps it, the second is a **deterministic self-conflict** that no retry can clear.

**Forbidding it was considered and rejected.** A probe over the whole `abyss-graph`/`abyss-dsl` suite
found zero same-row touches in current tests — but two readings for one device inside one batch is a
first-class ingest shape, and `batchTransaction` exists precisely for it.

**Folding was considered and rejected.** Collapsing a row's ops into one upsert per transaction fixes the
self-conflict but erases intermediate states. `IoT.md` finding D's planned fix is a post-commit hook
carrying the *committed ops*: a batch where reading 1 crosses a threshold and reading 2 drops back would
emit only reading 2 — the missed-event failure D exists to fix, reintroduced inside every batch, and
worse the bigger the batch.

**Design: every op is kept; the version is counted, never predicted.**
`BufferedTransaction` (and `BufferedEphemeralTransaction`, same bug) keeps
`rows: HashMap<RowKey, RowState>` — last staged value, mode, next expected version. Row key is the node
id, or `(from, to, type)` for edges (type is part of the edges PK). A map, not `ops.indexOfLast { }`:
`batchTransaction` uses the same buffer, and a linear scan per op makes a bulk import O(n²).

Every statement shape moves the version by exactly +1 — guarded `UPDATE`, the unguarded upsert's
`version + 1`, and an insert at v1 (= 0 + 1) — so the buffer only has to count.

**The first touch sets the row's mode for the whole transaction:**

| First touch on x | Mode | Later ops allowed on x | Expectation per op |
|---|---|---|---|
| `modifyNode` | guarded | `modifyNode`, `removeNode` | store read gives v (0 if absent); then v+1, v+2, … |
| `addNode` | blind | `addNode`, `modifyNode` (transform sees the staged value), `removeNode` | none — the version was never read |
| `removeNode` | known absent | anything | re-enters as a first touch, except `modifyNode` skips the read: transform sees `null`, expects 0 |

- **Every later transform sees the staged value**, never the cache/store — this is what fixes the
  measured probe.
- **`addNode` on a guarded row is forbidden** (edges: `addEdge` after `modifyEdge` on the same key).
  The transaction read x, then states a value that ignores the read — the modify's effect is discarded
  inside its own transaction, the exact lost update this plan exists to kill. It also has no honest guard:
  inheriting the chain makes a "blind" call guarded, skipping it breaks the chain across chunks. Fails at
  buffer time with `AbyssError.IntegrityError`, before any I/O, and is never retried — it is a
  programming error.
- **Deletes stay unguarded** (decided 2026-09-10). A `removeNode` in a guarded row is a blind delete: a
  racer's change between our read and our delete is not caught. After it the row is known absent and the
  chain restarts from 0. Deliberate, not an oversight — see §6.

**Why every op carries a guard, not just the first touch.** Inside one `commitYsql` transaction only
the first guard carries information: once op1 has written x, the transaction holds x and op2's guard
always passes. Across `batchTransaction` chunks every guard is load-bearing — each chunk commits
separately: op1 commits x at v6 in chunk 1, a racer takes x to v7, op2 in chunk 2 expects v6 →
`Conflict`. Guarding the first touch only would silently overwrite the racer there. Free either way: the
guard rides on the statement the op issues anyway.

Consequences:

- **N statements per row, not N commits.** Batching's commit-count win (`IoT.md` finding 5) is intact;
  per-statement YB work is the price of keeping every op for D.
- **The cache gets state, the hook gets events.** `populateCache` writes only the last value per id
  (`addedInTx`, `associate`, already keeps the last): N `setAsync` to one key are mutually unordered —
  finding 3 inside a single transaction. The post-commit hook (finding D) still sees every op.
- **Composes with §3.5.** The in-transform late-packet check (`old.updatedAt >= event.time → old`) sees
  the previous reading's staged value, so two readings in one batch order correctly with no SQL involved;
  returning the instance it was given hits the `===` skip — no op, nothing counted.
- **Composes with Phase 3.** Two blind readings stay two upserts, so the SQL event-time guard orders
  them; nothing to reconcile in the buffer.
- **Edges** follow the same rules keyed on `(from, to, type)`; `modifyEdge`'s internal
  `RemoveEdge` + `AddEdge` pair counts as one touch. How that pair is guarded at all is Q3.

**Verify on the live container before Phase 2** (the §2 discipline — logic, not yet measurement):
1. A chain of guarded `UPDATE … WHERE version = ?` on one row in one YB transaction: each sees the
   previous write, every rowcount is 1.
2. `DELETE` then `INSERT … ON CONFLICT DO NOTHING` of the same id in one transaction inserts (rowcount 1,
   v1).

---

## 4. Phases

**Phase 0 — read-your-writes + row modes (§3.9). Standalone bug fix, ships first, no schema change.**
`rows` map in both buffer classes: later transforms see staged values, first-touch modes, the
`addNode`-after-`modifyNode` ban, the `===` no-op skip from §3.5. Fixes the measured in-transaction lost
update today. The version counter joins in Phase 2, once there is a version to count from.

**Phase 1 — schema + store, no API change.**
Migration and `ysql-schema.sql` per §3.2 (`DEFAULT 1`, backfill existing rows to 1). Add
`expectedVersion: Long?` to `PersistentOp.SaveNode`/`SaveEdge`. Add the unconditional
`version = n.version + 1` to both `commitYsql` and `commitYsqlBatched` upserts. Add the two guarded
statements to `commitYsql`, check rowcounts, throw `VersionConflict`, map it to `AbyssError.Conflict`
(§3.6). Null expectation = today's behaviour, so nothing regresses. Re-run §2.2's contention harness
against the `UPDATE` form.

**Phase 2 — plumb the expectation into the modify path.**
`loadNodeVersioned` on the store seam (§3.4). `Op.AddNode.expectedVersion`, populated by
`BufferedTransaction.modifyNode` from that read — only on a row's first touch (§3.9); every later op on
the row counts from it (v+1, v+2, …). `AbyssSchemaWorker` carries it through `applyPersistentOp`. Decide **Q1** (batched
path) and **Q3** (`modifyEdge`) before this lands.

**Phase 3 — finding B, blind path only.**
Event-time guard on `updated_at` for blind `addNode`, opt-in, with its own reaction (drop +
observable counter, not Conflict), plus the ownership contract from §3.5. Smaller than the first
draft's Phase 3: the read-modify-write half needs **no SQL work at all** — it ships with Phase 2 as
a documented transform pattern, guarded by the version CAS.

**Phase 4 — finding 3.**
`EntryProcessor` staleness check in `applyToCacheAsync`, gated on the topology answer in §3.8.

---

## 5. Open decisions — need cane's call

**Q1. `batchTransaction` — implement the guard, or refuse guarded ops?**
The first draft excluded `commitYsqlBatched` on the grounds that it "does not do read-modify-write".
That is false: `AbyssGraphSchema.batchTransaction` builds the same `BufferedTransaction`
(`AbyssGraphSchema.kt:170-177`), which exposes `modifyNode`/`modifyEdge`. A guarded op *can* reach the
batched path today. `executeBatch()` returns the per-statement rowcounts, so implementing the check
there is cheap; the alternative is failing loudly when an op carrying `expectedVersion != null`
reaches it. Silently unguarded is the one option not available.

**Q2. Does Abyss own the retry loop?**
Ship a `retryOnConflict(times) { }` helper that re-enters the whole `transaction { }` block (§3.7), or
document the re-enter-the-block rule and leave the loop to callers? A helper is a handful of lines and
stops every caller reinventing the one detail that matters (re-enter the *block*, not the buffer); it
also owns the backoff. The counter-argument is that a retry policy is a caller's business, not a
graph store's.
*(The first draft's "do findings 2 and B share a statement?" is closed, not deferred — §3.5. They
never share a statement.)*

**Q3. `modifyEdge` emits `RemoveEdge` + `AddEdge`** (`AbyssGraphSchema.kt:322-325`), presumably so a
transform may move the edge's `(from,to,type)` key. Delete-then-insert destroys the version and
re-inserts at v1, so CAS cannot work for edges as shaped. Either `modifyEdge` becomes a pure guarded
`UPDATE` when the key is unchanged (and keeps delete+insert only when it moves), or edges are declared
out of scope for CAS in this round.

**Q4. Scope of the guard** — per-op, per-transaction, or a schema-level setting?

**Q5. Ephemeral `modifyNode` stays unguarded — how loudly?**
`BufferedEphemeralTransaction.modifyNode` (`AbyssGraphSchema.kt:341-347`) has the identical
read-modify-write shape, and YCQL CAS needs lightweight transactions (§6). Leaving it silently
unguarded while the persistent path is fixed invites the assumption that `modifyNode` is safe
everywhere. Doc-only note, KDoc warning, or a startup log?

---

## 6. Out of scope, deliberately

- **The ephemeral (YCQL) store.** CAS there needs lightweight transactions, which are expensive and a
  separate conversation. Subject to Q5's visibility decision.
- **The `commitYsqlBatched` *guard*** — pending Q1. The unconditional `version + 1` increment is **not**
  optional there and lands in Phase 1 regardless, or bulk imports become invisible to CAS.
- **Multi-row / cross-node atomicity.** This is per-row optimistic concurrency, nothing wider.
- **Guarded deletes** (decided 2026-09-10). `removeNode`/`removeEdge` stay blind `DELETE`s, including
  inside a guarded row (§3.9): a change made between the transaction's read and its delete is not
  detected.
- **Cross-schema transactions** need no special handling: `HomogeneousSchemaGraph`/
  `HeterogeneousSchemaGraph` flatten every schema's ops into one `worker.transaction` → one
  `commitYsql` (`HomogeneousSchemaGraph.kt:113-115`), so there is no partial-commit hazard to design
  around. Verified, not assumed.

---

## 7. Test plan

Mirror `CommitOrderDeadlockTest`: measure the defect first, with the guard disabled, so the test
cannot silently stop proving anything — the discipline that made TEST 5 meaningful.

1. **Lost update, pre-fix baseline.** N concurrent `modifyNode(x) { it.copy(counter = counter+1) }`;
   record the final counter and assert it is `< N`, i.e. the loss is real and silent. Do **not** assert
   an exact pre-fix value — the interleaving is nondeterministic, and a brittle baseline is a test that
   gets deleted the first time it flakes.
2. **Post-fix.** Same run: losers return `AbyssError.Conflict` (not `Unexpected` — assert the variant,
   that is finding §3.6), no exceptions, and a retry-on-conflict wrapper that re-enters the whole
   transaction block reaches exactly N.
3. **Absent-row race.** Two writers, one reading nothing, one having inserted — case 4 of §2.1, through
   the real API rather than raw SQL.
4. **Resurrect guard.** Read v5, delete the row out from under the writer, commit → `Conflict`, and the
   row stays deleted (§3.3 point 2).
5. **Unguarded writer is visible to CAS.** Guarded reader reads v3; a blind `addNode` commits; the
   guarded commit must conflict (§3.3 point 1). This is the test that fails if the increment is left
   conditional.
6. **Migration.** A row created before the ALTER is at version 1, not 0, and a `expectedVersion = 0`
   write against it conflicts (§3.2).
7. **Late packet, read-modify-write path.** A transform that sees `old.updatedAt >= event.time` and
   returns `old` must issue **no write at all** — assert the version is unchanged afterwards, which is
   what proves the `===` skip works (§3.5). No SQL guard involved.
8. **Late packet, blind path** (Phase 3) — out-of-order `updatedAt` through `addNode` must not clobber,
   and the drop must be counted in the observable metric.
9. **Regression.** Unguarded `addNode` without the event-time guard keeps today's last-write-wins
   behaviour.
10. **Phase 0 — read-your-writes.** The 2026-09-10 probe as a real test: `addNode(x)`, `modifyNode(x)`,
    `modifyNode(x)` in one block → transforms see `[staged-add, mod1]`, final is `mod1+mod2`, and all three
    ops are committed. Same for `BufferedEphemeralTransaction`; same for edges via `addEdge`/`modifyEdge`.
11. **Phase 0 — mode ban.** `modifyNode(x)` then `addNode(x)` → `IntegrityError`, nothing committed, no
    store call made. Same for `addEdge` after `modifyEdge` on one key.
12. **Phase 0 — remove restarts.** `addNode(x)`, `removeNode(x)`, `addNode(x)` keeps the remove and its
    cascade (x's old edges are gone); `modifyNode` after `removeNode` sees `null`.
13. **Phase 2 — guarded chain.** Three `modifyNode(x)` in one transaction bind v, v+1, v+2; all commit;
    final version v+3.
14. **Phase 2 — cross-chunk race.** Two `modifyNode(x)` in one `batchTransaction`, `batchSize` forcing a
    chunk boundary between them; a racer commits x between chunks (store wrapper that pauses after chunk 1)
    → chunk 2 fails with `Conflict`, the racer's value survives.
15. **Phase 2 — cache gets state.** After a multi-op row, the cache holds the last staged value, and the
    committed op list (future hook input) holds every op.

---

## 8. Blast radius

| File | Change |
|---|---|
| `ysql-schema.sql` | `version` column on `nodes`/`edges`, `NOT NULL DEFAULT 1` |
| `AbyssError.kt` (store-api) | `Conflict` variant |
| `AbyssStoreLike.kt` | `loadNodeVersioned` (+ edge analog), safe default |
| `YugabytePersistentStore.kt` | 2 guarded statements, unconditional increment (both commit paths), rowcount checks, `mapLeft` branch |
| `AbyssSchemaWorker.kt` | carry `expectedVersion` through `applyPersistentOp`; `populateCache` writes the last value per id only (§3.9); Phase 4 `EntryProcessor` |
| `AbyssGraphSchema.kt` | Phase 0: `rows` map, read-your-writes, first-touch modes and the `addNode`-after-`modifyNode` ban in `BufferedTransaction` **and** `BufferedEphemeralTransaction`, `===` no-op skip; `batchTransaction` builds its buffer via `newBuffer()` instead of the inline copy (`:175-178`). Phase 2: first-touch `modifyNode` reads versioned, later ops count; `Op.AddNode.expectedVersion` |

Untouched, and deliberately: `NodeLike`/`EdgeLike`, every Compact serializer, the Hazelcast value
shape, and the unguarded ingest path.

---

## 9. What changed from the 2026-09-08 draft

Kept: the on-the-op design (§3.1), the measured results (§2), and the "never move the read into the
commit transaction" constraint.

Corrected after auditing the code the plan described:

1. **The first draft's Q1(a) broke the guard** (that draft's "where does `expectedVersion` come from?";
   unrelated to the Q1 above) — reading only the *version* from the store while the value came
   from the cache-first `readNode` produced a torn snapshot. Now §3.4: one statement, both columns.
2. **`DEFAULT 0` voided §2.1 case 4** — every migrated row would have sat on the "saw no row" sentinel.
   Now §3.2: 1-based, 0 unreachable.
3. **Unguarded writes left `version` untouched**, so CAS was blind to every blind writer. Now §3.3:
   unconditional increment, conditional guard only.
4. **§3.3's thrown conflict never became `AbyssError.Conflict`** — `Either.catch { }.mapLeft { Unexpected }`
   swallows the type. Now §3.6.
5. **`commitYsqlBatched` was excluded on a false premise** — `batchTransaction` does expose
   `modifyNode`. Now Q1.
6. **The single-statement CAS could resurrect a deleted row** on a stale non-zero expectation. Now §3.3
   point 2, plus test 4.
7. **Finding 3 was called "free"** — an `EntryProcessor` has a member-classpath cost under a
   client/server topology. Now §3.8.
8. **Ephemeral `modifyNode`** had the same defect and no mention. Now Q5.
9. **Test 1's "pre-fix it will be ~1"** was nondeterministic. Now `< N`.

Corrected again on the second pass (2026-09-09), after cane asked why one `WHERE` checked both:

10. **The combined predicate `version = ? AND updated_at < EXCLUDED.updated_at` is gone.** The two
    guards catch different failures and neither implies the other, but sharing a statement collapses
    "retry" and "drop" into one indistinguishable rowcount. Split by path instead (§3.5): version on
    the read-modify-write path, event time on the blind path, never both.
11. **The event-time check for read-modify-write moved out of SQL entirely** — §3.4's store-authoritative
    read already hands the transform the current `updatedAt`, and the version CAS is what makes checking
    it there safe. Phase 3 shrinks accordingly.
12. **`===` no-op skip added** (§3.5): a transform that returns the instance it was given buffers no op,
    so a dropped late packet costs no write and no version bump.
13. **Old Q2 closed rather than deferred**; Q2 is now only "does Abyss ship `retryOnConflict { }`".

Third pass (2026-09-10), gap review item 1:

14. **Same row twice in one transaction** was a measured lost update today and a guaranteed
    self-conflict under CAS. Forbidding it was rejected (two readings per device per batch is a real
    ingest shape).
15. **Folding (one upsert per row) was drafted and rejected the same day**: it erases the intermediate
    states finding D's post-commit hook must emit. Replaced by §3.9: every op kept, version counted per op,
    first touch sets the row's mode.
16. **`addNode` after `modifyNode` on one row is forbidden; deletes stay unguarded** — both cane's calls.
17. **`===` no-op skip moved from Phase 2 to Phase 0** — it belongs with read-your-writes.
