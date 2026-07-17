# Fix: preloadOut/preloadIn hitting the persistent store on every call, even warm

## Context

`AbyssSchemaWorker.outAt`/`inAt` (the two `NodeIdEngine` methods every traversal hop, `outEdges`/
`inEdges`, and `cascadeEdgeRemovals` ultimately go through) unconditionally call `preloadOut(nid)`/
`preloadIn(nid)` before touching the cache. `preloadOut`/`preloadIn`
(`AbyssSchemaWorker.kt:202-229`) unconditionally call `persistentStore.loadEdges(nid)`/
`loadInEdges(nid)` on **every single invocation**, with no check for whether the adjacency cache is
already warm for that node+direction. So today, any multi-hop traversal with persistence enabled
pays one YSQL round trip per hop — even when every node it visits was visited (and fully cached)
moments earlier. This was presumably written purely as a cold-cache self-heal mechanism, but the
"only heal on an actual miss" guard was never added.

An earlier design for this fix added a synthetic "warm marker" shard key to disambiguate "cold" from
"warm but genuinely empty". Rejected as unnecessary complexity: it repurposed the shard-index byte
space, needed a new constructor invariant, and needed careful success/failure handling to avoid
caching a transient store failure as "permanently empty". The chosen design below is simpler: it
reuses the adjacency data read that already has to happen on every call, instead of adding new
persisted state.

## Design

**Check-then-load, not load-then-check.** `adjacencyRead` (the function that already fetches a
node's real adjacency shards on every `inAt`/most `outAt` calls) is restructured to preload
*only if that same fetch comes back empty*:

```kotlin
private suspend fun adjacencyRead(nid: NodeId, direction: AdjacencyDirection, type: String?, needValue: Boolean): List<Hop> {
    val shardKeys = shardKeysFor(nid, direction)
    var raw = withContext(Dispatchers.IO) { adjacencyMap.getAll(shardKeys) }
    if (raw.isEmpty()) {
        if (direction == AdjacencyDirection.OUT) preloadOut(nid) else preloadIn(nid)
        raw = withContext(Dispatchers.IO) { adjacencyMap.getAll(shardKeys) }
    }
    val entries = raw.values.flatMap { it.entries }
    // ...rest unchanged...
}
```

`shardKeysFor(nid, direction)` is the existing `(0 until adjacencyShardCount).map { AdjacencyKey(nid,
packShard(direction, it), pk) }.toSet()` line, extracted into a one-line private helper so it's not
duplicated between `adjacencyRead` and the fast-path warm-check below.

`inAt` no longer needs its own `preloadIn` call — `adjacencyRead` now self-heals internally:
```kotlin
override suspend fun inAt(nid: NodeId, type: String?, needValue: Boolean): List<Hop> =
    adjacencyRead(nid, AdjacencyDirection.IN, type, needValue)
```

**`outAt`'s fast path** (`type != null && needValue`, lines 136-141) bypasses `adjacencyRead`
entirely — it scans `edgesMap` directly by design ("already-optimal hottest path... don't route it
through the adjacency index"), so it still needs an explicit warm-check before that scan, but now as
a check rather than an unconditional call:
```kotlin
private suspend fun ensureOutWarm(nid: NodeId) {
    if (withContext(Dispatchers.IO) { adjacencyMap.getAll(shardKeysFor(nid, AdjacencyDirection.OUT)) }.isEmpty()) preloadOut(nid)
}
```
Used by `outAt`'s fast path, `outEdgeFlow` (which also bypasses `adjacencyRead` for the same reason),
and `cascadeEdgeRemovals`'s own `edgesMap` fromId-scan. `cascadeEdgeRemovals`'s IN-side no longer
needs an explicit `preloadIn` call either — it already calls `adjacencyRead(..., IN, ...)`, which now
self-heals.

**`preloadOut`/`preloadIn` themselves are unchanged** — no marker-writing, no new success/failure
branching. The only change is that callers now decide *whether* to call them based on a cheap,
already-partition-local `adjacencyMap.getAll` check instead of calling them unconditionally.

**Accepted, documented ceiling** (ponytail-style comment at the `if (raw.isEmpty())` check): a node
with genuinely zero edges in a direction is indistinguishable from "never preloaded" — there's no
persisted "confirmed empty" state, so it retries the store on every call rather than caching
emptiness. This also means a transient store failure self-corrects for free (next call just retries)
instead of needing special-cased success/failure handling. Upgrade path if this ever shows up in
profiling: add a real warm-marker (the rejected design above is the reference for how).

**Accepted race** (unchanged from before): two concurrent calls on a genuinely cold node can both see
the fetch empty and both hit the store once each before either populates the cache — idempotent
`putIfAbsent` / set-union `AdjacencyMutationProcessor.Add` make this harmless.

Verified against the 7 existing self-heal tests (`GraphTest.kt`: `outEdges warms cold cache from
store`, `inEdges warms cold cache from store`, `removeNode cascades store-only edges not yet
cache-resident`, `addEdge integrity check self-heals from store on cache-cold node`;
`MixedTraversalTest.kt`: `cold-cache mixed hop self-heals the adjacency index from the store`;
`MultiSchemaTest.kt`: `addCrossEdge failure leaves the cache untouched`, `a cross edge already in the
persistent store is warmed by preloadOut without being added directly`) — none of them touch a node
more than once per direction, so all exercise only the cold→warm path, which is unchanged in
behavior (still self-heals correctly on first touch).

## Implementation steps

**1. `AbyssSchemaWorker.kt` — add `shardKeysFor` helper.** Before `adjacencyRead` (line 153):
```kotlin
private fun shardKeysFor(nid: NodeId, direction: AdjacencyDirection): Set<AdjacencyKey> {
    val pk = partitionKey(nid)
    return (0 until adjacencyShardCount).map { AdjacencyKey(nid, packShard(direction, it), pk) }.toSet()
}
```

**2. `AbyssSchemaWorker.kt` — add `ensureOutWarm` helper**, right after `shardKeysFor`:
```kotlin
private suspend fun ensureOutWarm(nid: NodeId) {
    if (withContext(Dispatchers.IO) { adjacencyMap.getAll(shardKeysFor(nid, AdjacencyDirection.OUT)) }.isEmpty()) preloadOut(nid)
}
```

**3. `AbyssSchemaWorker.kt` — rewrite `outAt`** (lines 131-143): replace `preloadOut(nid)` at line
132 with `ensureOutWarm(nid)`; body otherwise unchanged.

**4. `AbyssSchemaWorker.kt` — rewrite `inAt`** (lines 145-148): drop the `preloadIn(nid)` call,
return `adjacencyRead(...)` directly (self-heals internally now).

**5. `AbyssSchemaWorker.kt` — rewrite `adjacencyRead`** (lines 154-157 specifically, rest of the
function body unchanged): use `shardKeysFor`, add the check-then-preload-then-reread logic shown
above.

**6. `AbyssSchemaWorker.kt` — `outEdgeFlow`** (line 188): replace `preloadOut(nid)` with
`ensureOutWarm(nid)`.

**7. `AbyssSchemaWorker.kt` — `inEdgeFlow`** (line 194): drop the `preloadIn(nid)` call entirely
(the `adjacencyRead` call on the next line self-heals).

**8. `AbyssSchemaWorker.kt` — `cascadeEdgeRemovals`** (lines 340-341): replace `preloadOut(nid)` +
`preloadIn(nid)` with a single `ensureOutWarm(nid)` (the IN-side `adjacencyRead(..., IN, ...)` call a
few lines down self-heals on its own).

**9. `GraphTest.kt` — extend `WarmingFakeStore`** (lines 781-806), purely additively: add
`loadEdgesCalls`/`loadInEdgesCalls` counters, incremented at the top of each override. No other
behavior change — every existing call site (4 current tests) keeps working unchanged.

**10. `GraphTest.kt` — add 3 correctness tests** after `addEdge integrity check self-heals from
store on cache-cold node` (~line 714), following the exact `WarmingFakeStore` + `AbyssGraphSchema(...)`
+ `runBlocking` pattern already used by the neighboring tests:
   - `outEdges hits the persistent store once per node, not once per call` — a node with one real
     edge; call `g.outEdges(...)` 5x; assert `fake.loadEdgesCalls == 1`.
   - `inEdges hits the persistent store once per node, not once per call` — mirror for `inEdges`.
   - `outEdges retries the store on every call for a node with no adjacency data (accepted ceiling)`
     — a node with zero edges in the fake store; call `g.outEdges(...)` 3x; assert
     `fake.loadEdgesCalls == 3`. This documents/locks in the accepted trade-off described above so a
     future change to this behavior is a deliberate decision, not a silent regression.

**11. New file `abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/AdjacencyPreloadPerformanceTest.kt`**
— the mandatory baseline/post-fix perf test, mirroring `PathToPerformanceTest.kt`'s style (`-Pperf`
gate via `System.getProperty("perf") == null` early-return, already wired in
`abyss-graph/build.gradle.kts:2`). A `DelayedFakeStore` (implements `AbyssStoreLike`, overrides only
`loadNode`/`loadEdge`/`loadEdges`/`transaction` — `loadInEdges` uses the interface's default empty
`Right`) charges a fixed `delay(3ms)` per `loadEdges` call and counts invocations. Test: warm a single
node (with one real edge, so the "warm" path is the one under test, not the ceiling case), then call
`g.outEdges(nid).toList()` 500x on the *same* node, `measureTime` the whole loop, `println` avg
ms/call and the store's hit count. Not a hard-threshold assertion (avoids CI flakiness, matches
`PathToPerformanceTest`'s printed-not-asserted style) — the pre/post numbers are compared manually
per the workflow below.

## Execution order (CLAUDE.md performance-bug workflow)

1. Add step 11's perf test only, against **current/unfixed** code — confirms it reproduces the bug
   (expect ~500 store hits, ~3ms/call avg).
2. Run `./gradlew :abyss-graph:test -Pperf --tests "pl.iqtech.abyss.graph.AdjacencyPreloadPerformanceTest"`,
   capture baseline numbers.
3. Implement steps 1-8 (the fix).
4. Re-run the same perf test unmodified — capture post-fix numbers (expect 1 store hit, sub-ms/call
   avg after the first).
5. Add steps 9-10's fixture extension and correctness tests; run the full suite:
   `./gradlew :abyss-graph:test` (non-perf) to confirm the 7 named pre-existing self-heal tests plus
   the 3 new ones all pass.
6. Report the pre/post comparison (numbers from steps 2 and 4) to the user directly in chat.
7. Add a TODO.md entry (section `## 1. High`, next number after 1.21 → **1.22**) describing the fix
   and the measured pre/post numbers, following the existing entries' prose style/detail level.

## Verification

- `./gradlew :abyss-graph:test` — full non-perf suite green, including all pre-existing self-heal
  tests and the 3 new correctness tests.
- `./gradlew :abyss-graph:test -Pperf --tests "pl.iqtech.abyss.graph.AdjacencyPreloadPerformanceTest"`
  — printed pre/post comparison shows store hits collapse from N (one per call) to 1 (one per node).
- No CHANGELOG/version bump as part of this task — that's a separate, explicitly user-gated step per
  this repo's CLAUDE.md.
