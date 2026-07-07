# TODO 2.14 — Skip edge-value fetch on predicate-free hops

## Context

Traversal hops (`outgoing<E>()`/`incoming<E>()` and friends) advance the frontier through
`AbyssGraphSchema.outAt`/`inAt`, which always deserialize the full edge payload from Hazelcast
(`entrySet`/`getAll`) even when nothing downstream reads it. `EdgeKey`/`ReverseEdgeKey` already carry
both endpoints, so the target `NodeId` needed to advance the frontier is available straight off the
key — no value fetch required. The win applies whenever no edge predicate is present, which is most
hops: plain `outgoing<E>()`, `addNodeHop` (filters on the *target node*, never the edge), and
`hasOutgoing`/`hasIncoming` (`filterFrontierByEdge`/`filterFrontierByEdgeType`, which only ever
compare endpoints/target-node-type and don't even record the edge today).

The one thing that makes this non-trivial: `addHop`/`addNodeHop` unconditionally record every hop's
edge into `allTraversedEdges`, which feeds `Subgraph.edges` via `collectSubgraph()`/`exhaustReachable()`
(TODO 1.2 / 2.4.1's "all traversed edges" contract). Skipping the value fetch naively would silently
truncate that output. Fix: fetch key-only always, and resolve real edge values **lazily, batched, only
when a `Subgraph`/`traversedEdges` consumer is actually invoked** — a pure perf change, zero change to
`Subgraph`/`Path`'s public shape or behavior.

`paths()`/`dfsLoop`/`bfsLoop` (TODO 2.11's `loop`) are out of scope: `edgeVisitor` is an arbitrary
caller lambda that generally inspects edge data to decide inclusion, so there's no safe way to skip
the fetch there. YSQL cache-miss key-only queries are also out of scope (separate follow-on;
Hazelcast in-memory is the hot path this TODO targets).

**Verified simplification vs. the initial design pass:** a per-hop "origin schema" for cross-schema
pk-routing is unnecessary. `edgeKey(fromNid, toNid, type)`'s partition key is
`adapter.partitionKey(fromNid)`, and every schema registered inside a tagged `AbyssGraph` container
wraps its domain adapter in `SchemaKeyAdapter`, whose `partitionKey` is always `nodeId.toString()` —
schema-agnostic (`abyss-store-api/.../KeyAdapter.kt:162`). `edgesMap` is also the *same* Hazelcast map
instance across all schemas in one container (looked up by the same `edgesMapName`). So resolving a
batch of unresolved hops via **any** schema that owns one of the hop's endpoints is correct — no
`Hop.origin` field needed.

## Changes

### `NodeIdEngine.kt`
```kotlin
data class Hop(val fromId: NodeId, val toId: NodeId, val type: String, val edge: RawEdgeLike<*, *>?)

interface NodeIdEngine {
    suspend fun nodeAt(nid: NodeId): NodeLike<*>?
    suspend fun outAt(nid: NodeId, type: String?, needValue: Boolean = true): List<Hop>
    suspend fun inAt(nid: NodeId, type: String?, needValue: Boolean = true): List<Hop>
    suspend fun resolveEdges(hops: List<Hop>): Map<Hop, RawEdgeLike<*, *>>   // batched, keyed by Hop
    fun allNodeIdsRaw(): Flow<NodeId>
}
```
`type` is populated straight from `EdgeKey.type`/`ReverseEdgeKey.type` (already on the key) — needed to
reconstruct `EdgeKey` for later resolution.

### `AbyssGraphSchema.kt` (`outAt`/`inAt`/new `resolveEdges`)
- `outAt`: when `needValue`, unchanged (`entrySet(part)`). When `!needValue`, `map.keySet(part)` →
  `Hop(k.fromId, k.toId, k.type, null)` — no value touched.
- `inAt`: already does `reverseEdgesMap.keySet(...)` first. When `!needValue`, stop there — skip the
  subsequent `edgesMap.getAll(keys)` entirely.
- `resolveEdges(hops)`: build `edgeKey(fromId, toId, type)` per hop (existing private helper, unchanged
  logic), one `edgesMap.getAll(keys)`, map back to the input `Hop`s.

### `AbyssGraph.kt` (delegating engine)
- `outAt`/`inAt` gain the `needValue` passthrough to `resolveSchema(nid).outAt/inAt(...)`.
- `resolveEdges(hops)`: `resolveSchema(hops.first().fromId).resolveEdges(hops)` — one delegate call,
  correct for both intra- and cross-schema hops per the simplification above (empty list → `emptyMap()`
  short-circuit).

### `TraversalBuilder.kt`
- `allTraversedEdges: MutableList<RawEdgeLike<*,*>>` → `allTraversedHops: MutableList<Hop>`
  (`traversedEdges` accessor becomes `traversedHops`).
- Private `hops(nid, direction, type, needValue)` passes `needValue` through to `engine.outAt/inAt`.
- `addHop`: `needValue = edgePredicate != null`; filter becomes `edgePredicate(it.edge!!)` (safe — only
  reached when `needValue` was true); accumulate the `Hop`s (not `.edge`) into `allTraversedHops`.
- `addNodeHop`: always `needValue = false` (filters on target node via `engine.nodeAt`, never `it.edge`);
  accumulate `Hop`s.
- `filterFrontierByEdge` / `filterFrontierByEdgeType` (backing `hasOutgoing`/`hasIncoming`): always
  `needValue = false` — these never touched `it.edge` and never accumulated anything; pure win.
- New private `resolveHopEdges(hops: List<Hop>): List<RawEdgeLike<*,*>>` — partitions into
  already-resolved (`it.edge != null`) vs. unresolved, does one `engine.resolveEdges(unresolved)` call,
  reassembles in order.
- `collectSubgraph()` / `exhaustReachable()`: build `Subgraph(nodes, resolveHopEdges(allTraversedHops))`
  — single batched resolve at the point of consumption, not per-hop.
- `paths()` / `dfsLoop` / `bfsLoop` / `edgesFrom`: **unchanged calls** (interface default
  `needValue = true` applies since these call `engine.outAt(fromNid, null)` directly, not through the
  private `hops()` helper); every `hop.edge` use becomes `hop.edge!!` (provably safe — always eager here).

### `abyss-dsl` — no changes
`Subgraph`, `Path`, `TraversalBuilderLike` stay byte-identical (`.../TraversalBuilderLike.kt:23,38`).
This is confined to `abyss-graph` internals.

## Files
- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/NodeIdEngine.kt`
- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AbyssGraphSchema.kt`
- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AbyssGraph.kt`
- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/traversal/TraversalBuilder.kt`

## Verification
- Run existing suites unchanged — they're the correctness bar for "invisible perf optimization":
  - `abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/TraversalTest.kt` (`outgoing`/`incoming` with
    and without predicates, `hasOutgoing`/`hasIncoming`, `subgraph()` edge-content assertions)
  - `abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/AlgorithmsTest.kt` (`exhaustReachable`/
    `allReachable` edge-count assertions — exercises the batched multi-level resolve)
  - `abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/MultiSchemaTest.kt` (cross-schema hops —
    exercises `AbyssGraph.resolveEdges` delegation)
  - `abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/PathsTraversalTest.kt` (confirms `paths()`
    stays eager/untouched)
- `./gradlew :abyss-graph:test` — all of the above must pass with zero assertion changes.
- Optional (nice-to-have, not required for correctness signal): one small test directly asserting
  `AbyssGraphSchema.outAt(nid, type, needValue = false)` returns `Hop`s with `edge == null`, and that
  `resolveEdges(...)` on them returns the real values — pins the new contract the way
  `SerdeRoundtripPerformanceTest`/`NodeIdHashPerformanceTest` pinned theirs (TODO 3.5/3.6 style).
