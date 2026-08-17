# Consistency audit findings (TODO 1.29)

Source: an 8-item audit sweep of TODO.md's ✅ ledger, re-verifying atomicity/concurrency/
completeness claims against real code and runtime behavior instead of trusting the checkmark
(triggered by finding TODO 2.27 — `HazelcastEphemeralStore.transaction()` — was falsely marked
done). Confirmed clean: 4.13 (multi-schema tx), 4.8 (cross-schema cascade), 3.13 (dispatcher fix).
Below are the items that aren't clean, ranked by severity. Each is investigation-only — nothing
here has been fixed yet.

## 1. Mixed-store cold-read fallback is broken (was TODO 1.6) — most severe — ✅ FIXED

`AbyssSchemaWorker.loadAndCacheNode`/`loadAndCacheEdge` (`AbyssSchemaWorker.kt:517-546`):
```kotlin
persistentStore?.loadNode(nid)?.getOrNull() ?: ephemeralStore.loadNode(nid).getOrNull()
```
`loadNode` returns `Either<Error, Pair<Node?, Duration?>>`; a genuine miss is `Right(Pair(null,
null))` — a **non-null Pair**. `.getOrNull()` unwraps the `Either`, not the inner Pair, so the
elvis never falls through. Once a persistent store is configured, ephemeral-only data reads as
not-found on any cold-cache lookup (post-eviction, restart, partition migration) — invisible
while the cache stays warm, which is why it went unnoticed. Verified live with a throwaway test.

**Fix direction:** check `.first == null` explicitly instead of relying on Pair-nullness through
`.getOrNull()`, in both `loadAndCacheNode` and `loadAndCacheEdge`.

**Fixed**: both functions now check `p?.first != null` before falling through to the ephemeral
store. Regression coverage in `MultiSchemaTest.kt` (`cold node read falls back to ephemeral store
when persistent store reports a genuine miss` / same for edges) — both reproduced the bug against
the pre-fix code and pass against the fix. Full module (`:abyss-graph:test`) green. Plan:
`ai-scripts/MixedStoreColdReadFallbackFixPlan.md`.

## 2. Batch-transaction partial failure leaves cache stale (was TODO 1.21) — ✅ FIXED

`AbyssSchemaWorker.batchTransaction` (`AbyssSchemaWorker.kt:360-377`) skips `populateCache(...)`
entirely on any `storeResult.isLeft()`, regardless of how many chunks the store actually
committed. Store and cache diverge silently — a reader hitting a warm cache entry gets stale data
with no error signal. Verified live with a throwaway test (fake store applies one op then fails,
mirrors the real chunk-1-committed/chunk-2-failed shape already proven against real YugabyteDB in
`LoadTest.kt`).

**Fix direction:** on partial failure, populate the cache for whatever ops the store actually
committed (needs the store to report which ops succeeded), not all-or-nothing skip.

**Fixed**: new `AbyssError.BatchPartiallyCommitted(committedOps, cause)` case carries the
committed-op count out of `YugabytePersistentStore.commitYsqlBatched` (tracked per successfully
`conn.commit()`-ed chunk, thrown as a carrier exception on the failing chunk). The worker now does
`populateCache(ops.take(committed), ...)` instead of skipping the cache entirely. Regression
coverage: `BatchTransactionTest.kt` (`batchTransaction populates cache for ops that committed
before a partial failure`, unit-level, reproduced the bug pre-fix and passes post-fix) and
`LoadTest.kt`'s existing partial-failure test tightened to assert the exact `committedOps` count
against a real YugabyteDB. Full `:abyss-graph:test`, `:abyss-store-yugabyte:test`, and `./gradlew
build` all green. Plan: `ai-scripts/BatchTransactionCacheStalenessFixPlan.md`.

## 3. Self-heal gaps under TODO 1.27 ("index-always-alive") — ✅ FIXED

Two distinct gaps, reasoning-verified (code shape is unambiguous, not independently re-run):
- **Client-mode guard is inert.** The startup fail-fast guard against misconfigured eviction on
  the adjacency map (`AbyssSchemaWorker.kt:105-115`) only works via
  `hazelcast.config.findMapConfig(...)`, which the code's own comment admits is skipped on a
  Hazelcast **client** instance. All existing tests use embedded members. Client-server is a
  plausible topology for this project's own K8s pitch.
- **Cache-only mode silently drops evicted edges.** In pure in-memory mode (no `persistentStore`
  — README documents this as supported), an evicted value-map entry can't self-heal
  (`persistentStore?.loadEdge(...)` is `null`). The adjacency index still lists the edge; the
  traversal read comes back empty and the edge is silently filtered out of results
  (`AbyssSchemaWorker.kt:265-270`) — no exception, no log. Zero test coverage for this
  combination.

**Fix direction:** extend the client-mode guard to also work (or fail loudly) against a client
connection; decide whether cache-only mode should hard-fail on eviction misconfiguration the same
way persistent-backed mode does, since there's no self-heal to fall back on.

**Fixed**: both gaps closed by extending the existing adjacency-map guard mechanism rather than
adding a second one. The guard (now `requireNoEviction(mapName, why)`) is called for the adjacency
map unconditionally, and additionally for `edgesMapName` when `persistentStore == null`. Verified
against Hazelcast 5.6.0 sources that a client instance's `findMapConfig` always throws
`UnsupportedOperationException` (client Config is add-only dynamic config) — that specific
exception now fails loudly by default instead of being silently swallowed; a new
`evictionVerifiedExternally` constructor parameter (default `false`, threaded through
`AbyssSchemaWorker`/`AbyssGraphSchema`/`HomogeneousSchemaGraph`/`HeterogeneousSchemaGraph`) is the
explicit opt-out for an operator who has verified the server-side config another way — without it,
client-mode construction would become unconditionally impossible. `nodesMapName` deliberately not
guarded — a missing/evicted node already surfaces as `Either.Left(NodeNotFound)`, not a silent drop.
Regression coverage: 4 new tests in `IndexAlwaysAliveTest.kt` (client-mode fail-fast, the
`evictionVerifiedExternally` escape hatch, cache-only-mode fail-fast on the edges map, and a
not-over-tightened check confirming the same config is fine with a `persistentStore`) — the two
fail-fast tests reproduced the bug pre-fix (`AssertionError`, no exception thrown) and pass
post-fix. Full `:abyss-graph:test` and `./gradlew build` green. Plan:
`ai-scripts/SelfHealGapsFixPlan.md`.

## 4. Integrity-check TOCTOU (was TODO 1.3) — minor — 🟡 investigated, viable fix found

`integrityError` reads node existence, then `applyPersistentOp`'s edge write happens later with no
re-check and no lock between the two (`AbyssSchemaWorker.kt:449`). A concurrent `removeNode` on
either endpoint in that window can still land a dangling edge — the exact failure 1.3 claims to
prevent, just concurrently instead of sequentially. Narrow window, reasoning-only, no test either
way.

Addressed as TODO 2.28

## 5. TODO 1.5 is stale documentation, not a live bug

Describes retrying a "reverse-edge table" write via Arrow's `Schedule` — that whole mechanism was
deleted by TODO 2.21 (adjacency index replaced the reverse-edge-table concept). No correctness
risk today; the ledger entry just misdescribes the current architecture and should be reworded or
closed as superseded, separately from the fixes above.
