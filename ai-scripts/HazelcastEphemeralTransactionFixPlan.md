# Plan: make `HazelcastEphemeralStore.transaction()` atomic (TODO 2.27)

**Status: implemented.** `transaction()` now uses `hazelcast.newTransactionContext()` +
`TransactionalMap` as planned below; rollback test added; full `abyss-graph` test suite green;
TODO 2.27 marked done.

## Context

`HazelcastEphemeralStore` (abyss-graph's default ephemeral backing when no `AbyssEphemeralStoreLike`
is configured) implements `transaction { }` by applying each op straight to the raw `IMap`
(`ephNodes.set`/`ephEdges.remove`), one call at a time, with no Hazelcast transaction wrapper. If op
3 of 5 throws, ops 1–2 are already live in the map and ops 4–5 never run — no rollback, no isolation.
That's weaker than its siblings: `YugabytePersistentStore` uses a real JDBC transaction
(commit/rollback), and `YugabyteEphemeralStore` buffers ops and applies them as one atomic CQL
logged batch. This was flagged during a README review (ephemeral-elements section) and filed as
TODO 2.27. Fix: back `transaction { }` with a real Hazelcast `TransactionContext`/`TransactionalMap`
so a failure partway through rolls back everything, matching the guarantee the other two stores give.

Verified against the actual Hazelcast 5.6.0 jar (`~/.gradle/caches/.../hazelcast-5.6.0.jar`):
`TransactionalMap<K,V>.put(K, V, long, TimeUnit)` exists, so TTL writes are fully supported inside a
real transaction — no capability gap. `HazelcastInstance.newTransactionContext()` /
`TransactionContext.beginTransaction()/commitTransaction()/rollbackTransaction()` are the primitives.

Blast-radius check (Explore agent): the only production instantiation site is
`AbyssSchemaWorker.kt:93-94` (the "no ephemeral store configured" fallback). No other class in the
ephemeral-store family has this bug — `YugabyteEphemeralStore` already buffers-then-commits. No
concurrent/parallel test exercises `HazelcastEphemeralStore.transaction` today, and the test fixture
(`graphTestHz`) has no near-cache/backup/split-brain config that would conflict with
`TransactionalMap`. `AbyssEphemeralStoreTransactionLike`'s four methods (`saveNode`/`saveEdge`/
`deleteNode`/`deleteEdge`) are plain (non-suspend) — the transactional receiver just swaps its
backing maps, same interface shape.

## Implementation

File: `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/HazelcastEphemeralStore.kt`

1. **Store the constructor params.** `hazelcast`, `ephEdgesMapName`, `ephNodesMapName` are currently
   plain (non-`val`) constructor params, only reachable from the property initializers and `init{}`.
   Make them `private val` so `transaction()` can use them.

2. **Delete the reusable `txn` object** (lines ~104-116) — the plain-`IMap` receiver is being
   replaced, not reused.

3. **Rewrite `transaction()`** to open a real transaction per call:
   ```kotlin
   override suspend fun transaction(block: suspend AbyssEphemeralStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> = Either.catch {
       withContext(Dispatchers.IO) {
           val ctx = hazelcast.newTransactionContext()
           ctx.beginTransaction()
           val txNodes = ctx.getMap<NodeId, NodeLike<*>>(ephNodesMapName)
           val txEdges = ctx.getMap<EdgeKey, EdgeLike<*, *>>(ephEdgesMapName)
           val receiver = object : AbyssEphemeralStoreTransactionLike {
               override fun saveNode(id: NodeId, node: NodeLike<*>, ttl: Duration, tags: Set<String>) {
                   txNodes.put(id, node, ttl.inWholeSeconds, TimeUnit.SECONDS)
               }
               override fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, ttl: Duration, tags: Set<String>) {
                   txEdges.put(EdgeKey(fromId, toId, edge::class.serialName()), edge, ttl.inWholeSeconds, TimeUnit.SECONDS)
               }
               override fun deleteNode(id: NodeId) { txNodes.remove(id) }
               override fun deleteEdge(fromId: NodeId, toId: NodeId, type: String) { txEdges.remove(EdgeKey(fromId, toId, type)) }
           }
           try {
               receiver.block()
           } catch (e: Throwable) {
               ctx.rollbackTransaction()
               throw e
           }
           ctx.commitTransaction()
       }
   }.mapLeft { AbyssError.Unexpected(it) }
   ```
   The receiver is built fresh per call (was a single reusable `val` before) since it closes over
   this transaction's `TransactionalMap` handles. `Either.catch` already wraps any thrown/rethrown
   exception (including one from `commitTransaction()` itself) into `AbyssError.Unexpected`, same as
   today.

4. **Update the stale comment** above the old `txn` object (the one explaining "no buffer-then-commit
   here... each IMap.set/remove is already a single fast local operation") — it describes exactly the
   behavior being removed; replace with a short note that this now goes through a real Hazelcast
   transaction for atomicity, at the cost of per-key locking for the transaction's duration.

## Test

File: `abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/HazelcastEphemeralStoreTest.kt`

Existing 6 tests already call `store.transaction { ... }` for the happy path — they should pass
unchanged (same net effect, just via `TransactionalMap` now).

Add one new test for the new logic (rollback branch), per this repo's "non-trivial logic needs one
runnable check" convention:

```kotlin
@Test fun `transaction rolls back all ops when the block throws partway through`() {
    runBlocking {
        val store = HazelcastEphemeralStore(graphTestHz, "hes-edges-rollback", "hes-nodes-rollback")
        val id = huid.toNodeId(Uuid.random())
        val node = TestNode(id = huid.fromNodeId(id), name = "hes-rollback")

        val result = store.transaction {
            saveNode(id, node, 60.seconds, emptySet())
            error("boom")
        }

        assertTrue(result.isLeft())
        assertNull(store.loadNode(id).shouldBeRight().first, "partial write must not survive a failed transaction")

        listOf("hes-edges-rollback", "hes-nodes-rollback").forEach { graphTestHz.getMap<Any, Any>(it).clear() }
    }
}
```

## Verification

1. `./gradlew :abyss-graph:test --tests "*HazelcastEphemeralStoreTest*"` — all 6 existing + 1 new
   test pass.
2. `./gradlew :abyss-graph:test` — full module test run, to catch any regression in
   `AbyssSchemaWorker`'s `ephemeral { }` path (which drives `HazelcastEphemeralStore` when no
   explicit ephemeral store is configured).
3. Mark TODO 2.27 done via the `todo` skill once tests are green.

Not in scope: no version bump, no commit/push (per CLAUDE.md, only on explicit request).
