# Audit — Does a successful transaction guarantee no data loss?

## Verdict

Conditionally yes — safe only under a specific, disciplined configuration (`persistentStore`
configured, durable writes going through `transaction { }`, not `ephemeral { }`, for anything that
can't be lost), and even then two cache-warmth-dependent correctness bugs (#4, #5 below) exist
*inside* that disciplined configuration, not just as misuse. Not addressed anywhere in `TODO.md`
today.

## What's actually solid

`AbyssSchemaWorker.transaction()` (`AbyssSchemaWorker.kt:219-239`) commits to `persistentStore`
*first* and only touches the Hazelcast cache after that succeeds:

```kotlin
if (persistentStore != null) {
    val storeResult = persistentStore.transaction { ops.forEach { applyPersistentOp(it) } }
    if (storeResult.isLeft()) { ... return storeResult }   // cache never touched on store failure
}
...
populateCache(ops, ...)   // only reached after store commit succeeded
```

`YugabytePersistentStore.commitYsql` (`YugabytePersistentStore.kt:130-180`) is a real ACID
transaction: one JDBC connection, `autoCommit = false`, every op in the batch executed, then
`conn.commit()`; any exception triggers `conn.rollback()` before rethrowing. All ops in one
`transaction { }` block commit or roll back together. So: **if `persistentStore` is configured and
writes go through `transaction { }`, a reported success means YugabyteDB has durably committed it.**
This is correct and is the guarantee that matters most.

## Where it breaks down

**1. No store configured → "success" means nothing durable, silently.**
Pure in-memory (no `persistentStore`, no `ephemeralStore`): `transaction()` skips straight to
`populateCache()` and returns `Right`. Confirmed via `grep` — no Hazelcast Hot Restart / MapStore /
MapLoader configured anywhere in this codebase. Hazelcast here is purely volatile. A crash or
restart loses everything, with zero API signal that this is happening.

**2. Configuration trap: `ephemeralStore`-only + `transaction { }` writes are never persisted.**
`transaction()`'s use of `ephemeralStore` is *only* for delete-fanout (`AbyssSchemaWorker.kt:230-234`)
— regular `addNode`/`addEdge` inside `transaction { }` never touch `ephemeralStore` at all. Configure
only an ephemeral store and use the wrong builder, and "durable" writes are cache-only: no TTL, no
persistence, gone on restart.

**3. `YugabyteEphemeralStore.commitYcql` is NOT atomic across multiple ops** (`YugabyteEphemeralStore.kt:145-173`).
Each op is its own independent `ycql.execute(...)` call in a plain loop — no batching, no rollback.
If op 3 of 5 throws, ops 1-2 are already durably written to YCQL, but the whole `ephemeral { }` call
still returns `Left` (reported failure). The inverse of the headline question: data can survive a
reported *failure*, so "failure" can't be trusted to mean "nothing happened" for multi-op ephemeral
transactions.

**4. Cascade delete silently skips edges the cache hasn't warmed.**
`cascadeEdgeRemovals` (`AbyssSchemaWorker.kt:285-296`) computes which edges to cascade-delete by
scanning `edgesMap`/`reverseEdgesMap` — **the cache**, not the store. If a node's edges aren't
cache-resident (fresh restart, evicted partition, cold path never queried), the scan finds nothing,
so `removeNode` deletes only the node row from YSQL while its edges remain — dangling references to
a deleted node, permanently, in the durable store. A real referential-integrity bug under ordinary
operational conditions, not an exotic edge case.

**5. Integrity checks can spuriously fail on cache-cold data.**
`integrityError` (`AbyssSchemaWorker.kt:269-281`) checks `nodesMap[addOp.fromId]` — a raw cache read,
not the self-healing `readNode`/`nodeExists` path. A genuinely-existing node not yet cache-warmed
makes `addEdge` fail with `IntegrityError`, even though the node is right there in the store.
Availability bug, not data loss, but a false rejection on legitimate writes.

**6. A rare but real stale-read window.**
`populateCache` failures are only logged, never surfaced as a transaction failure
(`AbyssSchemaWorker.kt:236`, comment: *"Cache update failed after store commit; cache may be
stale"*). If a `removeNode`'s cache-side removal specifically fails post-commit, the node stays
cache-resident with no TTL — reads keep returning *deleted* data until something else overwrites
that key. `asyncCachePopulation = true` widens this window further, since success is returned before
cache writes even start.

## Caveat inherited from the deployment, not the code

Even a fully correct `Right` from `YugabytePersistentStore.transaction()` is only as durable as
YugabyteDB's own deployment — replication factor, fsync settings. Single-node/RF=1 has its own
crash-loss exposure independent of anything Abyss's code does right. This audit only covers what
Abyss's own code guarantees on top of whatever the store durably commits.

## Suggested next step

Findings #4 and #5 are the two that would bite a well-configured production system after any
restart or partition eviction — not misuse, just normal operation. Worth a TODO entry (or two) to
either read-through the store when the cache scan comes up empty (mirroring the self-heal pattern
`readNode`/`nodeExists` already use) or explicitly document the cache-warmth precondition.
