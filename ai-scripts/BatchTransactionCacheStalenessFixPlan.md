# Fix TODO 1.29 item 2: batch-transaction partial failure leaves cache stale

## Context

`AbyssSchemaWorker.batchTransaction` (`abyss-graph/.../AbyssSchemaWorker.kt:360-380`) commits a
batch of ops to the persistent store in `batchSize`-sized chunks — each chunk its own DB
transaction (`YugabytePersistentStore.commitYsqlBatched`), deliberately trading whole-call
atomicity for throughput. On any failure it currently does:

```kotlin
if (storeResult.isLeft()) {
    log.error("Batch transaction failed partway; chunks committed before the failure remain persisted [...]")
    return storeResult
}
```

...and skips `populateCache(...)` **entirely**, regardless of how many chunks the store actually
committed. Proven live against a real YugabyteDB in
`LoadTest.kt: batchTransaction failure partway leaves earlier chunks committed and later chunks absent`
(chunk 1 durably persisted, chunk 2 rolled back) — the store already has this exact partial-commit
shape today. Result: after a partial batch failure, the store holds chunk 1's writes but the
Hazelcast cache holds none of them. A reader hitting a warm cache entry gets silently stale data
with no error signal (full details: `ai-scripts/ConsistencyAuditFindings.md` item 2).

Root cause: the store contract (`AbyssStoreLike.batchTransaction`) returns a bare
`Either<AbyssError, Unit>` — no way to learn how many ops committed before a failure. That
information exists (chunk boundaries are known inside `commitYsqlBatched`) but never escapes the
`Either.catch { ... }.mapLeft { AbyssError.Unexpected(it) }` wrapper. Fix: thread the committed-op
count through a new `AbyssError` case, and have the worker call `populateCache` with just the
sub-list of ops that actually landed.

## Design

### 1. New `AbyssError` case
`abyss-store-api/src/main/kotlin/pl/iqtech/abyss/store/api/AbyssError.kt`

```kotlin
data class BatchPartiallyCommitted(val committedOps: Int, val cause: Throwable) : AbyssError
```

Verified additive/safe: no exhaustive `when` over `AbyssError` exists anywhere in the repo (checked
via grep) — every error is consumed through `Either`/`.fold`/`is` checks, so this new sealed-interface
member breaks nothing.

### 2. `YugabytePersistentStore` — report the committed count on partial failure
`abyss-store-yugabyte/src/main/kotlin/pl/iqtech/abyss/store/yugabyte/YugabytePersistentStore.kt`

- Add a small private carrier exception next to `PersistentTransaction`:
  ```kotlin
  private class PartialBatchFailure(val committedOps: Int, val original: Throwable) : RuntimeException(original)
  ```
- In `commitYsqlBatched`, track a running `committed` count and throw the carrier on failure instead
  of the bare exception:
  ```kotlin
  var committed = 0
  for (chunk in ops.chunked(batchSize)) {
      try {
          ... unchanged addBatch()/executeBatch() loop ...
          conn.commit()
          committed += chunk.size
      } catch (e: Throwable) {
          conn.rollback()
          throw PartialBatchFailure(committed, e)
      }
  }
  ```
- In `batchTransaction`, branch the `mapLeft`:
  ```kotlin
  .mapLeft { e ->
      if (e is PartialBatchFailure) AbyssError.BatchPartiallyCommitted(e.committedOps, e.original)
      else AbyssError.Unexpected(e)
  }
  ```
- `commitYsql` (the non-batched `transaction()` path) is untouched — it's already atomic (single
  commit/rollback), so a failure there always means zero ops committed; existing
  `AbyssError.Unexpected` handling is already correct.
- The default `AbyssStoreLike.batchTransaction` (delegates to `transaction()`) is also untouched —
  any non-Yugabyte store stays atomic and never produces `BatchPartiallyCommitted`, so the worker's
  fallback-to-0 path preserves today's behavior for those stores exactly.

### 3. `AbyssSchemaWorker.batchTransaction` — populate cache for whatever committed
`abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AbyssSchemaWorker.kt:360-380`

```kotlin
if (persistentStore != null) {
    val storeResult = persistentStore.batchTransaction(batchSize) { ops.forEach { applyPersistentOp(it) } }
    if (storeResult.isLeft()) {
        val committed = (storeResult.leftOrNull() as? AbyssError.BatchPartiallyCommitted)?.committedOps ?: 0
        if (committed > 0) populateCache(ops.take(committed), "Cache update failed after partial batch store commit; cache may be stale")
        log.error("Batch transaction failed partway; {} of {} op(s) committed and cache-synced before the failure [nodes={}, edges={}]", committed, ops.size, nodesMapName, edgesMapName)
        return storeResult
    }
}
```

This relies on two things verified against the real code:
- `applyPersistentOp` maps each `NodeOp` to exactly one `PersistentOp`, 1:1 and in order — so
  `ops.take(committed)` is exactly the sub-list the store actually persisted.
- `populateCache(ops, warnMsg)` already takes a plain `List<NodeOp>` and applies it unconditionally
  to the Hazelcast maps — no restructuring needed to call it with a sub-list instead of the full
  list. (Minor accepted nuance: `resolveNodeTag` inside `populateCache` falls back to `readNode` for
  an edge whose endpoint's `AddNode` didn't make the committed sub-list — same fallback path it
  already uses for any node not in the same tx, so no new behavior.)
- `Either<A, B>.leftOrNull(): A?` is a real Arrow-core extension — no new dependency.

`transaction()` (the non-batch, atomic path) is **not** changed — its `storeResult.isLeft()`
early-return-without-populateCache is already correct there, because `persistentStore.transaction`
is a single atomic commit: a Left there always means zero ops persisted, so skipping the cache is
right.

## Tests

### Unit-level regression (fast, no DB) — `abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/BatchTransactionTest.kt`

Extended `RecordingBatchStore` with a `failAfterOps: Int?` param that returns
`AbyssError.BatchPartiallyCommitted(failAfterOps, RuntimeException("simulated partial batch failure")).left()`
without applying the block — mirrors the real "N ops committed, then failure" shape without needing
to reimplement chunking in the fake.

New test: `batchTransaction populates cache for ops that committed before a partial failure` — adds
two nodes with `failAfterOps = 1`, asserts the first is cache-visible (`g.node(a.id)` is `Right`)
and the second is not (`g.node(b.id)` is `Left`). `expandCascades` preserves op order for plain adds
(no `RemoveNode` cascade involved), so `a` is index 0 and `b` is index 1 — deterministic.

Verified like item 1's fix: ran against the pre-fix code first (confirmed `AssertionError`, i.e. a
genuine reproduction, not a setup failure) — then applied the fix and confirmed it passes, then ran
the full `:abyss-graph:test` module (green).

### Live-DB tightening — `abyss-store-yugabyte/src/test/kotlin/pl/iqtech/abyss/store/yugabyte/LoadTest.kt`

The existing test `batchTransaction failure partway leaves earlier chunks committed and later
chunks absent` previously only loosely asserted `assertIs<Either.Left<AbyssError>>(result)`.
Tightened to also assert the new error shape end-to-end:
```kotlin
assertIs<Either.Left<AbyssError.BatchPartiallyCommitted>>(result)
assertEquals(2, result.value.committedOps)
```
(`batchSize = 2`, `failAtCommit = 2` → chunk 1 committed = 2 ops, matches the existing
`ids.take(2)`/`ids.drop(2)` assertions already in that test.) Ran against the local
`docker-yugabyte-1` container — passed, along with the other 3 `batchTransaction` tests in that
file.

Also ran `:abyss-store-yugabyte:test` (full module, live DB) and `./gradlew build` (whole repo) —
both green.

## Docs

- `ai-scripts/ConsistencyAuditFindings.md` item 2: marked ✅ FIXED.
- `TODO.md` 1.29: updated to show items 1-2 done, items 3-5 still open.

## Out of scope

Items 3 (index-always-alive self-heal gaps), 4 (integrity-check TOCTOU), and 5 (stale TODO 1.5 doc)
— unchanged, to be planned separately per the existing one-at-a-time order. No version bump, no
commit/push unless asked.
