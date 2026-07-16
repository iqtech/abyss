# Implement TODO 1.21 — `batchTransaction{}` for bulk YSQL loads

## Context

`transaction{}` already commits an arbitrarily large op list as one atomic JDBC transaction, but
`YugabytePersistentStore.commitYsql` issues one `executeUpdate()` per op — for ~1M elements that's
1M sequential round-trips inside a single, ever-growing distributed transaction, which fights
YugabyteDB's own guidance (large transactions accumulate provisional records/intents across
tablets; bulk loaders should use bounded sub-transactions). `batchTransaction{}` is a new,
throughput-oriented sibling API for populating huge graphs: it chunks the op list into batches
(default 1000), commits each chunk as its own DB transaction using JDBC `addBatch()`/
`executeBatch()`, and documents the resulting trade-off (per-chunk atomicity, not whole-call
atomicity) instead of silently changing `transaction{}`'s existing guarantees.

Per explicit direction: **`batchTransaction{}` must not call into `transaction{}` or
`commitYsql` at any layer.** Where a genuinely shared, non-orchestration helper exists (op
buffering, cross-edge validation, cascade/integrity checks), reusing it is fine — those aren't the
"transaction path," they're pure logic `transaction{}` also happens to call. The new store-level
commit function (`commitYsqlBatched`) is written with its own inline parameter binding rather than
refactoring `commitYsql` to share it — a deliberate small duplication (~30 lines) traded for zero
risk to the existing, already-tested `transaction()` code path.

Two design decisions already confirmed with the user:
- Real batching happens at the **store level** (JDBC `addBatch`/`executeBatch`, not just chunking
  the op list into repeated `transaction()` calls) — this is what actually cuts round-trips.
- Within a chunk, ops are executed via **run-length grouping by statement type**, preserving the
  caller's original relative order across mixed add/remove-same-key sequences (matches
  `commitYsql`'s current op-by-op semantics), rather than blindly batching by type across the whole
  chunk (which would silently reorder mixed add/remove sequences on the same key).

## Files to change

### 1. `abyss-store-api/src/main/kotlin/pl/iqtech/abyss/store/api/AbyssStoreLike.kt`

Add a new method to `AbyssStoreLike` (lines 14-20) with a default implementation, so the 4 existing
test fakes (`GraphTest.FakeStore`/another at line 785, `MultiSchemaTest.RecordingStore`,
`MixedTraversalTest`'s fake) don't need any changes:

```kotlin
interface AbyssStoreLike {
    ...
    suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit>

    // Bulk-load path, deliberately independent of transaction(): commits ops in chunks of
    // `batchSize`, each chunk its own DB transaction, instead of one atomic transaction for the
    // whole list. Default just delegates to transaction() unchunked (correct, not faster) — only
    // YugabytePersistentStore overrides this with real JDBC batch commits.
    suspend fun batchTransaction(
        batchSize: Int = 1000,
        block: suspend AbyssStoreTransactionLike.() -> Unit
    ): Either<AbyssError, Unit> = transaction(block)
}
```

`AbyssEphemeralStoreLike`/`AbyssEphemeralStoreTransactionLike` (lines 29-42) are untouched — YCQL
batching is TODO 2.25, explicitly out of scope here.

### 2. `abyss-store-yugabyte/src/main/kotlin/pl/iqtech/abyss/store/yugabyte/YugabytePersistentStore.kt`

- **`create(...)` companion factory (lines 191-209):** add one Hikari data source property
  alongside the existing `prepareThreshold`:
  ```kotlin
  addDataSourceProperty("reWriteBatchedInserts", "true")
  ```
  This is pgjdbc's batch-rewrite flag (rewrites multi-row `addBatch()` calls into one multi-values
  `INSERT ... VALUES (...),(...),...` wire message). It's a no-op for `commitYsql`'s existing
  per-op `executeUpdate()` path (nothing there calls `addBatch()`), so it's safe to set globally
  on the shared `DataSource` without affecting `transaction()`.

- **New method** `override suspend fun batchTransaction(...)`, placed near `transaction()` (after
  line 85) but independent of it — reuses only the existing `PersistentTransaction` buffer class
  (lines 182-188, a plain `mutableListOf<PersistentOp>()`, not commit logic):
  ```kotlin
  override suspend fun batchTransaction(
      batchSize: Int,
      block: suspend AbyssStoreTransactionLike.() -> Unit
  ): Either<AbyssError, Unit> =
      Either.catch {
          val tx = PersistentTransaction()
          tx.block()
          if (tx.ops.isNotEmpty()) withContext(Dispatchers.IO) { commitYsqlBatched(tx.ops, batchSize) }
      }.mapLeft { AbyssError.Unexpected(it) }
  ```

- **New function** `commitYsqlBatched(ops: List<PersistentOp>, batchSize: Int)`, placed after
  `commitYsql` (after line 180). One connection for the whole call (avoid re-acquiring from the
  Hikari pool per chunk), `autoCommit = false`, but **one `conn.commit()` per chunk** (bounded
  transaction size, matching YugabyteDB guidance). Within each chunk, consecutive ops of the same
  statement type are grouped into one `addBatch()`/`executeBatch()` run, preserving original
  cross-type order:
  ```kotlin
  private fun commitYsqlBatched(ops: List<PersistentOp>, batchSize: Int) {
      ysql.connection.use { conn ->
          conn.autoCommit = false
          val upsertNode = conn.prepareStatement(/* same SQL as commitYsql's upsertNode */)
          val upsertEdge = conn.prepareStatement(/* same SQL as commitYsql's upsertEdge */)
          val delNode = conn.prepareStatement(/* same SQL as commitYsql's delNode */)
          val delEdge = conn.prepareStatement(/* same SQL as commitYsql's delEdge */)

          // Binds op's fields onto its statement and returns it (own inline binding, not shared
          // with commitYsql's — see Context on why this is deliberately duplicated).
          fun bindAndGetStmt(op: PersistentOp): java.sql.PreparedStatement = when (op) {
              is PersistentOp.SaveNode -> upsertNode.apply { /* setBytes/setString/setObject/setArray/setTimestamp, same fields as commitYsql:145-153 */ }
              is PersistentOp.SaveEdge -> upsertEdge.apply { /* same fields as commitYsql:155-164 */ }
              is PersistentOp.DeleteNode -> delNode.apply { setBytes(1, op.id.bytes) }
              is PersistentOp.DeleteEdge -> delEdge.apply { setBytes(1, op.fromId.bytes); setBytes(2, op.toId.bytes); setString(3, op.type) }
          }

          for (chunk in ops.chunked(batchSize)) {
              try {
                  var i = 0
                  while (i < chunk.size) {
                      val stmt = bindAndGetStmt(chunk[i]).also { it.addBatch() }
                      var j = i + 1
                      while (j < chunk.size && chunk[j]::class == chunk[i]::class) {
                          bindAndGetStmt(chunk[j]).addBatch()
                          j++
                      }
                      stmt.executeBatch()
                      i = j
                  }
                  conn.commit()
              } catch (e: Throwable) {
                  conn.rollback()
                  throw e
              }
          }
      }
  }
  ```
  `commitYsql` itself (lines 130-180) is **not modified**.

### 3. `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AbyssSchemaWorker.kt`

New sibling method to `transaction` (lines 233-253), placed directly after it. Structurally
parallel (both legitimately need `expandCascades`/`integrityError`/`populateCache` — these are
pure helpers, not commit-strategy-specific) but calls `persistentStore.batchTransaction` instead of
`persistentStore.transaction`, and never touches `AbyssStoreLike.transaction`:

```kotlin
suspend fun batchTransaction(baseOps: List<NodeOp>, batchSize: Int, checkIntegrity: Boolean): Either<AbyssError, Unit> {
    // Cascade expansion and integrity checks run ONCE over the full (pre-chunk) op list — a
    // RemoveNode and its cascade-deleted edges must not be allowed to land in different chunks,
    // and integrity's addedInTx map needs the whole batch's adds visible regardless of chunk.
    val ops = expandCascades(baseOps)
    integrityError(ops, checkIntegrity)?.let { return it.left() }

    if (persistentStore != null) {
        val storeResult = persistentStore.batchTransaction(batchSize) { ops.forEach { applyPersistentOp(it) } }
        if (storeResult.isLeft()) {
            log.error("Batch transaction failed partway; chunks committed before the failure remain persisted [nodes={}, edges={}]", nodesMapName, edgesMapName)
            return storeResult
        }
    }
    val deletes = ops.filter { it is NodeOp.RemoveNode || it is NodeOp.RemoveEdge }
    if (ephemeralStore != null && deletes.isNotEmpty()) {
        ephemeralStore.transaction { deletes.forEach { applyEphemeralOp(it) } }
            .onLeft { log.warn("Ephemeral delete fanout failed during batch transaction; stale ephemeral data possible [nodes={}, edges={}]", nodesMapName, edgesMapName) }
    }

    populateCache(ops, "Cache update failed after batch store commit; cache may be stale")
    log.debug("Batch transaction committed [{} op(s), batchSize={}, nodes={}, edges={}]", ops.size, batchSize, nodesMapName, edgesMapName)
    return Unit.right()
}
```

Note: `populateCache` still runs once over the whole `ops` list (unchanged shape) — chunking cache
population is a separate concern (already implicitly covered by TODO 1.21's own notes on
`populateCache`'s `awaitAll()` cost) and is left as a follow-up, not part of this change.

### 4. `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AbyssGraphSchema.kt`

New sibling to `transaction` (lines 130-140), placed directly after it. Reuses `BufferedTransaction`
(lines 259-279, a pure op-buffer — same reuse boundary as `PersistentTransaction` above) and the
existing `crossEdgeTagWidth()`/`crossEdgeCheck(...)` helpers (lines ~155-185), so bulk cross-edge
ops (`addCrossEdge`/`removeCrossEdge`) work through `batchTransaction` automatically, same as they
already do through `transaction`:

```kotlin
override suspend fun batchTransaction(
    batchSize: Int,
    checkIntegrity: Boolean,
    block: suspend AbyssTransactionLike<ID>.() -> Unit
): Either<AbyssError, Unit> {
    val buffer = BufferedTransaction<ID>(
        readNode = { @Suppress("UNCHECKED_CAST") (worker.readNode(adapter.toNodeId(it)) as NodeLike<ID>?) },
        readEdge = { f, t, type -> @Suppress("UNCHECKED_CAST") (worker.readEdge(adapter.toNodeId(f), adapter.toNodeId(t), type) as EdgeLike<ID, ID>?) }
    )
    try { buffer.block() } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }
    val headerless = adapter is HeaderlessSchemaKeyAdapter<*>
    val width = crossEdgeTagWidth()
    crossEdgeCheck(buffer.ops, checkIntegrity, width, headerless)?.let { return it.left() }
    return worker.batchTransaction(buffer.ops.map { it.toNodeOp(adapter, width, headerless) }, batchSize, checkIntegrity)
}
```

`AbyssGraphSchema` is the **only** implementer of `AbyssEngineLike<ID>` (confirmed by grep) — no
other class needs updating. `HomogeneousSchemaGraph`/`HeterogeneousSchemaGraph.transaction`
(container-level, `CrossSchemaTransactionLike`-only bulk cross-edge ops between registered schemas)
are a structurally separate, narrower API — out of scope; not touched.

### 5. `abyss-dsl/src/main/kotlin/pl/iqtech/abyss/dsl/AbyssEngineLike.kt`

New abstract method on `AbyssEngineLike<ID>` (after `transaction`, lines 27-30):

```kotlin
    // Bulk-load path: commits ops in independent chunks of `batchSize` instead of one atomic
    // transaction. Trades whole-call atomicity for throughput and bounded per-DB-transaction size —
    // on partial failure, chunks already committed stay committed. Intended for populating huge
    // graphs (e.g. ~1M elements), where saveNode/saveEdge's upsert semantics make retrying the
    // whole call after a failure safe.
    suspend fun batchTransaction(
        batchSize: Int = 1000,
        checkIntegrity: Boolean = true,
        block: suspend AbyssTransactionLike<ID>.() -> Unit
    ): Either<AbyssError, Unit>
```

No changes needed to `AbyssTransactionLike<ID>` itself (lines 39-50) — `batchTransaction` reuses
the same transaction DSL surface (`addNode`/`addEdge`/.../`modifyNode`/`modifyEdge`) as `transaction`.

## Out of scope (tracked separately)

- YCQL/ephemeral batching — TODO 2.25, explicitly deferred.
- Chunking `populateCache`'s cache-population fan-out — separate concern (bounded-concurrency async
  fan-out, not chunking), not required to ship the store-side throughput win.
- A batch variant of `HomogeneousSchemaGraph`/`HeterogeneousSchemaGraph`'s container-level
  cross-schema-only `transaction { }` — narrower API, no stated need.

## Testing

1. **Worker-level unit tests** (`abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/`, new
   `BatchTransactionTest.kt` alongside `GraphTest.kt`, reusing its `FakeStore`/fixture patterns —
   no live DB needed since `AbyssStoreLike.batchTransaction`'s default delegates to `transaction`):
   - Chunk-boundary correctness: batch of e.g. 5 ops with `batchSize = 2` produces the same final
     graph state as `transaction{}` with the same ops (3 chunks via the default delegate).
   - Cascade-delete correctness across a chunk boundary: a `removeNode` whose cascaded edge removals
     would fall in different chunks under naive raw-op chunking still cascades correctly (proves
     `expandCascades` runs before chunking, not per-chunk).
   - Integrity check sees the whole batch: an edge added in a later chunk referencing a node added
     in an earlier chunk passes `checkIntegrity = true` (proves `integrityError` runs once over the
     full expanded list, not per-chunk).
   - Default `batchSize` is 1000 when omitted.
   - A `RecordingStore`-style fake overriding `batchTransaction` to record `(batchSize, opCount)`
     calls, confirming the worker actually calls `persistentStore.batchTransaction`, not
     `persistentStore.transaction`.

2. **Store-level integration test** (`abyss-store-yugabyte/src/test/kotlin/pl/iqtech/abyss/store/yugabyte/LoadTest.kt`,
   following its existing raw-JDBC-verification pattern against a live YugabyteDB on
   `localhost:5433`, same as every other test in that file):
   - A batch with more ops than `batchSize` (e.g. 2500 ops, `batchSize = 1000` → 3 executeBatch
     rounds) persists every row correctly, verified via direct JDBC `SELECT`.
   - A chunk that fails (e.g. a value that violates a constraint) leaves earlier, already-committed
     chunks persisted and later chunks absent — proves the documented non-atomic-across-chunks
     behavior, distinguishing it from `transaction{}`'s all-or-nothing rollback (add a matching test
     against `transaction{}` if none already covers this, to make the contrast explicit).
   - A batch mixing `AddNode(X)` then `RemoveNode(X)` (or the reverse) within one `batchTransaction`
     call ends in the same state `transaction{}` would for the identical op sequence — proves the
     run-length-grouped ordering preservation actually works, not just the happy path.

## Verification

- `./gradlew :abyss-graph:test` for the new worker-level suite (no external dependencies).
- `./gradlew :abyss-store-yugabyte:test` for the `LoadTest` additions — requires a running
  YugabyteDB reachable at `localhost:5433`/`localhost:9042` (same manual precondition every other
  test in that file already has; not CI-managed).
- Optional: a quick manual throughput comparison (not a formal perf-gated test, since none of the
  existing `-Pperf` suites touch the Yugabyte store) — load ~100k synthetic nodes+edges via
  `transaction{}` vs `batchTransaction{}` against a local YugabyteDB and compare wall-clock time, to
  confirm the `addBatch`/`executeBatch` + `reWriteBatchedInserts` combination actually delivers a
  measurable win before calling 1.21 done.
