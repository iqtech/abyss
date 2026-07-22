# TODO 1.26 follow-ups (a) + (b) — bound OUT reads, then stream traversal hops

## Status: both shipped

One deviation from the plan below, for correctness: **(a)** does *not* read ephemeral out-edges from
`ephemeralStore` (ephemeral can be **cache-only** — no store — which that approach misses). Instead,
ephemeral OUT edges now carry an **OUT adjacency entry** (IN still absent), so `outEdges =
adjacencyEdgeFlow(OUT)` finds them, bounded, with or without a store; a stale entry from an expired
edge reads back null and is skipped. **(b)** shipped as designed. Measured pre/post:
`OutEdgePagingPerformanceTest` (peak edges-map materialization 2000 → 100) and
`HopStreamingPerformanceTest` (reads past the match's window 1 → 0).

## Context

Milestone 1 shipped the pluggable `AdjacencyIndex` seam + `ShardedAdjacencyIndex` with bounded
shard-window reads; `inEdges` streams and honors `pageSize`. Two follow-ups remain, done in order:

- **(a) Bound `outEdges`.** Today `outEdges` still scans `edgesMap` whole (`outEdgeFlow`,
  `AbyssSchemaWorker.kt`) and `AbyssGraphSchema.outEdges` receives `pageSize` but ignores it —
  the last piece of the "advertised paging, not delivered" bug. It was deferred because ephemeral
  (TTL) edges are outgoing-only with **no adjacency entry**, so routing OUT purely through the index
  would silently drop them.
- **(b) `outAt`/`inAt` → `Flow<Hop>`.** The traversal engine's `hops()` still returns `List<Hop>`;
  a supernode in the frontier materializes fully. Streaming lets `.any`-style consumers short-circuit
  and lets `addHop` fold incrementally.

**Verified constraint (drove both):** `PagingPredicate` and `PartitionPredicate` do **not** compose in
Hazelcast 5.6 — `PartitionPredicateImpl.apply()` throws `UnsupportedOperationException`, and the query
engine dispatches on the *outer* predicate type only. So a partition-scoped `edgesMap` scan cannot be
paged. OUT boundedness must come from the adjacency index (persistent) + a separate ephemeral source.

Sequencing: **(a) applied, tested, green before (b) starts.**

---

## (a) Bound `outEdges`

Persistent out-edges have OUT adjacency entries → stream them bounded via the **existing**
`adjacencyEdgeFlow` (already direction-agnostic, built for `inEdges`). Ephemeral out-edges live only in
`edgesMap`/the ephemeral store with no adjacency entry → append them from their store. The two sources
are disjoint (persistent ∈ YSQL/adjacency, ephemeral ∈ YCQL), so concat needs no dedup.

### Changes — `AbyssSchemaWorker.kt` (decided: concat)
- New `outEdges(nid, type?, pageSize): Flow<EdgeLike<*,*>>` =
  `adjacencyEdgeFlow(nid, OUT, type, batch = pageSize)` **concat** `ephemeralOutFlow(nid, type)`.
- `ephemeralOutFlow`: emits from `ephemeralStore?.loadEdges(nid)` (filtered by `type`). When
  `ephemeralStore == null` — **the common case** — it is `emptyFlow()`, so `outEdges` is *purely* the
  bounded index path with zero ephemeral overhead. When present, ephemeral rides its own store:
  principled, not a cache bypass — ephemeral edges have no cache index by design (TODO 1.13), so their
  store *is* their index. (If ephemeral out-degree ever grows large, page it via YCQL `PagingState` —
  deferred.) Persistent (YSQL/adjacency) and ephemeral (YCQL) are disjoint → concat needs no dedup.
- Delete the now-unused `outEdgeFlow` (only `outEdges(nid)`/`outEdges(nid,type)` used it; the `outAt`
  fast path uses `edgesMap.entrySet` directly and is untouched).

### Changes — `AbyssGraphSchema.kt`
- `outEdges(nodeId, pageSize)` / `outEdges(nodeId, type, pageSize)` thread `pageSize` into
  `worker.outEdges(...)` instead of dropping it (mirror of the `inEdges` wiring already done).

### Tests (a)
- Extend the `AdjacencyIndexTest` / `GraphTest` FakeStore style: a supernode `outEdges(pageSize=k)`
  asserts value `getAll` happens in ≤k batches (peak in-flight ≤ k) and `.take(k)` short-circuits;
  parity — `outEdges` still returns every persistent **and** ephemeral out-edge (a FakeStore with
  ephemeral edges). Existing `outEdges`/`AdjacencyPreloadPerformanceTest` stay green.

---

## (b) `outAt`/`inAt` → `Flow<Hop>`

### Interface — `NodeIdEngine.kt`
- `outAt`/`inAt` return `Flow<Hop>` (cold, lazy) instead of `suspend … List<Hop>`.
- `HomogeneousSchemaGraph` / `HeterogeneousSchemaGraph` delegate 1:1 (trivial).
- Worker: back them with the adjacency-hop flow (map `adjacency.read` → `Hop`, batch `needValue`
  value fetch — the same shape as `adjacencyEdgeFlow` but yielding `Hop`). The typed-OUT+needValue
  **fast path** (`edgesMap.entrySet`) stays materialized-then-emitted — it can't be paged
  (PagingPredicate incompatible) and its consumers collect-all anyway.

### Consumers — `traversal/TraversalBuilder.kt`
- `hops()` returns `Flow<Hop>`. Rewrite per consumption pattern:
  - **Short-circuit (the real win):** `filterFrontierByEdge` / `filterFrontierByEdgeType` (`:145`,
    `:155`) become `hops(...).firstOrNull { … } != null` / `.any { … }` — a supernode stops at the
    first matching neighbor instead of fetching all. Same for `checkReaches`/`pathTo` sub-traversals.
  - **Fold-incrementally:** `addHop` / `addNodeHop` (`:80`, `:93`) collect each node's hop flow into
    the shared `frontierTags` + `allTraversedHops` accumulators directly (per-coroutine one hop at a
    time under the existing `hopDispatcher` bound), replacing `map{async{ …toList }}.awaitAll()
    .flatten()`. Removes the transient "every frontier node's full list live at once, then flatten".
  - **Count:** `countEdges` (`:207`) → `hops(...).count()` (Flow terminal).
  - **`paths` DFS/BFS (decided: convert too):** `edgesFrom` (`:281`) returns `Flow<Hop>`; `dfsLoop`
    (`:291`) / `bfsLoop` (`:336`) `collect { hop -> … }` instead of `for (hop in edges)` — `continue`
    → `return@collect`, the `emitted`/`produced` flags accumulate across the collect, recursion stays
    (suspend). `BOTH` = **concat** `flow { emitAll(outAt); emitAll(inAt) }` (out-then-in, preserving
    the current order so path-enumeration parity holds — not `merge`, which would interleave).
- Keep `resolveHopEdges`/`resolveEdges` List-based (batched value fetch, unchanged).

### Explicit non-goal (documented, not fixed here)
`allTraversedHops` accumulates every hop for `collectSubgraph` (TODO 1.2) — O(total edges) **by
design**. (b) removes the *transient* per-node materialization and gives short-circuit consumers a real
algorithmic win, but a traversal that ends in `.subgraph()` still holds all hops. Making that
accumulation lazy/opt-in is a separate item, out of scope.

### Tests (b)
- Supernode-in-frontier: `filterFrontierByOutEdgeTo`/reachability against a counting engine asserts it
  fetches only up to the first match (short-circuit), not the whole neighbor set.
- Parity: every existing `TraversalTest`/`PathsTraversalTest`/`MixedTraversalTest` stays green (same
  visited sets, same paths) — the signature change must be behavior-preserving for collect-all paths.

---

## Verification (end to end)
- After (a): `./gradlew :abyss-graph:test` green (272 + new (a) tests); commit/leave per cane's call
  before starting (b).
- After (b): full `:abyss-graph:test` green including the parity suites; new short-circuit test.
- Perf workflow (per supernode concern): baseline vs. post for (a) `outEdges(pageSize)` peak in-flight,
  and (b) `filterFrontierByOutEdgeTo` neighbor-fetch count on a supernode — report pre/post.
