# Fix TODO 1.29 item 3: self-heal gaps under TODO 1.27 ("index-always-alive")

## Context

TODO 1.27 gave `AbyssSchemaWorker` a "fail fast rather than lie" guard at construction (`init`
block): the adjacency index must never have eviction/TTL configured (partial eviction there
silently corrupts traversal), so construction inspects
`hazelcast.config.findMapConfig(edgesAdjacencyMapName)` and throws if eviction is misconfigured.
The consistency audit (`ai-scripts/ConsistencyAuditFindings.md` item 3) found two gaps in that
guarantee:

1. **The guard is inert on a Hazelcast client connection.** Verified against Hazelcast 5.6.0
   sources: a client instance's `Config` is `ClientDynamicClusterConfig`
   (`com.hazelcast.client.impl.clientside.ClientDynamicClusterConfig`), whose `findMapConfig`/
   `getMapConfig` **always** throw `UnsupportedOperationException` — the client Config API is
   add-only (dynamic config), it cannot read back the cluster's real static map config. The
   existing code did `runCatching { hazelcast.config.findMapConfig(...) }.getOrNull()?.let { ... }`
   — on a client this exception was silently swallowed and the whole check skipped, so a
   misconfigured adjacency map on a client-server deployment (a real topology for this project's
   K8s pitch) passed construction silently. All existing tests used embedded members only, so this
   was never exercised.

2. **Cache-only mode (no `persistentStore`, README-documented as supported) silently dropped
   evicted edges.** `adjacencyHopFlow`'s value-fetch step treats a null `edgesMap` read as
   "evicted, self-heal from the store" — correct when a `persistentStore` exists, but when it's
   `null`, `loadAndCacheEdge` has nothing to reload from and returns `null`, so the hop was
   silently dropped from the flow with no exception, no log — a `toList()` on a traversal just
   quietly came back short. The class comment ("Value maps may evict — they self-heal from the
   store") was simply false in cache-only mode; nothing guarded against it.

Both gaps share the same shape as the already-existing, working adjacency-map guard — extended that
one mechanism instead of inventing a second one.

## Design

### 1. Shared helper + reused guard for both gaps
`abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AbyssSchemaWorker.kt`

Factored the existing `init`-block check into a private helper, parameterized by map name and a
"why does this matter" clause, called for the adjacency map (unconditionally, as before) and for
`edgesMapName` (only when `persistentStore == null`):

```kotlin
private fun requireNoEviction(mapName: String, why: String) {
    val cfgResult = runCatching { hazelcast.config.findMapConfig(mapName) }
    val cfg = cfgResult.getOrNull()
    if (cfg != null) {
        require(cfg.evictionConfig.evictionPolicy == EvictionPolicy.NONE && cfg.timeToLiveSeconds == 0 && cfg.maxIdleSeconds == 0) {
            "Eviction/TTL is configured on map '$mapName' (evictionPolicy=${cfg.evictionConfig.evictionPolicy}, " +
            "ttl=${cfg.timeToLiveSeconds}s, maxIdle=${cfg.maxIdleSeconds}s). $why"
        }
    } else if (cfgResult.exceptionOrNull() is UnsupportedOperationException) {
        require(evictionVerifiedExternally) {
            "Cannot verify eviction/TTL is disabled on map '$mapName': this HazelcastInstance is a client " +
            "connection, and Hazelcast's client Config API can't read the cluster's real map config " +
            "(findMapConfig always throws UnsupportedOperationException on a client). $why Either connect " +
            "via an embedded member instance, or confirm server-side and pass evictionVerifiedExternally = true."
        }
    }
    // else: some other unexpected exception from hazelcast.config — out of this fix's scope,
    // preserve the prior silent-skip behavior rather than over-reaching for an unobserved case.
}

init {
    requireNoEviction(edgesAdjacencyMapName,
        "This silently corrupts traversal — partial eviction is invisible to the per-node warm-check, so reads " +
        "return an incomplete neighbor set with no error. Remove all eviction from this map; evict " +
        "'$edgesMapName'/'$nodesMapName' instead (those self-heal from the store)."
    )
    if (persistentStore == null) {
        requireNoEviction(edgesMapName,
            "No persistentStore is configured (pure in-memory / cache-only mode) — without a store to self-heal " +
            "from, an evicted edge is unrecoverable and is silently dropped from traversal results with no error " +
            "(the adjacency index still lists it, the value read comes back null). Remove eviction/TTL from " +
            "'$edgesMapName', or configure a persistentStore."
        )
    }
}
```

Both `require` messages keep the existing tone/detail level; the `else` branch preserves prior
behavior for any exception type other than the one Hazelcast is proven to throw for clients, so
this stays scoped to the audited gap instead of guessing at unrelated edge cases.

**Scope note — why `nodesMapName` is not guarded:** a missing/evicted *node* with no store already
surfaces as `Either.Left(NodeNotFound)` through `readNode`/`nodeAt` — an ordinary, already-handled
"not found" outcome, indistinguishable from (and no worse than) a node that never existed. The
audit finding is specifically that an *edge* silently vanishes from an otherwise-successful `Flow`
result with zero signal — that shape doesn't exist on the node side, so extending the guard to
`nodesMapName` would be scope creep beyond what was found.

### 2. New constructor parameter: `evictionVerifiedExternally`
Threaded exactly like `adjacencyShardCount`/`hopFanoutParallelism`, defaulting to `false` (no
behavior change for the common embedded-member case — those still get full, automatic
verification):

- `AbyssSchemaWorker` primary constructor — new trailing param, and `hazelcast` promoted from a
  plain constructor parameter to `private val` so the new `requireNoEviction` method (outside the
  primary-constructor scope) can reference it.
- `AbyssGraphSchema`'s standalone constructor — threaded through.
- `HomogeneousSchemaGraph` constructor — threaded through.
- `HeterogeneousSchemaGraph` constructor — threaded through.

Required, not optional: without an escape hatch, client-mode construction would become
*unconditionally* impossible (the client can never prove eviction is off), which would outright
block a topology the project explicitly plans to support. The flag makes the previously-silent gap
into an explicit, auditable opt-in instead.

## Tests
`abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/IndexAlwaysAliveTest.kt` (existing home for TODO
1.27 self-heal/guard tests; reused `SelfHealStore`)

Four new tests, using `HazelcastClient.newHazelcastClient(...)` — zero new dependencies:
`com.hazelcast:hazelcast:5.6.0` (already a dependency) bundles `com.hazelcast.client.HazelcastClient`
in the same jar. The client is pointed at the embedded member deterministically via its bound
address (`member.cluster.localMember.address` → `host:port`), not multicast, for a reliable test.

1. **`construction fails fast on a client connection when eviction can't be verified`** — member's
   adjacency map has eviction configured; connect a client to it; construct with default
   `evictionVerifiedExternally = false` → `assertFailsWith<IllegalArgumentException>`.
2. **`evictionVerifiedExternally lets client-mode construction proceed`** — same client setup,
   `evictionVerifiedExternally = true` → construction succeeds (no throw).
3. **`cache-only mode fails fast if the edges map has eviction configured`** — embedded member,
   `edgesMapName` has eviction configured, `persistentStore = null` (default) →
   `assertFailsWith<IllegalArgumentException>`.
4. **`edges map eviction is fine when a persistentStore is configured`** — same eviction config on
   `edgesMapName`, but with `persistentStore = SelfHealStore()` → construction succeeds (no
   over-tightening).

Verified like items 1 and 2: temporarily reverted `requireNoEviction` to the old (adjacency-only,
silently-swallow-on-client) behavior and ran the suite — tests 1 and 3 failed with a genuine
`AssertionError` (construction didn't throw), tests 2 and 4 passed unchanged (they assert
"shouldn't throw", which was already true under the old code, so they don't reproduce a bug — they
guard against over-tightening). Restored the fix — all 4 new tests plus the 2 pre-existing tests in
the file pass. Full `:abyss-graph:test` and `./gradlew build` green.

## Docs

- `ai-scripts/ConsistencyAuditFindings.md` item 3: marked ✅ FIXED.
- `TODO.md` 1.29: updated to show items 1-3 done, items 4-5 still open.

## Out of scope

Item 4 (integrity-check TOCTOU) and item 5 (stale TODO 1.5 doc) — unchanged, to be planned
separately per the existing one-at-a-time order. No version bump, no commit/push unless asked.
