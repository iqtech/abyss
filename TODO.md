# TODO

## 1. High

- **✅ 1.1 Ephemeral and persistent elements cannot share a transaction**
  Elements with a TTL go to YCQL; elements without go to YSQL. A single `transaction { }` block
  that mixes both is not atomic — if YSQL commits and YCQL fails (or vice versa), the graph is
  silently inconsistent. Currently logs a warning and continues.
  Ephemeral elements are created via a separate `ephemeral { }` builder (analogous to
  `transaction { }`), which accepts a TTL and routes exclusively to YCQL. The two builders are
  intentionally separate and cannot be combined into one atomic operation.

- **✅ 1.2 Traversal subgraph extraction**
  After a traversal, callers need the full subgraph: all visited nodes as a list and all traversed
  edges as a list. Currently only the frontier nodes are accessible via `nodes<T>()`.

- **✅ 1.3 Edge integrity check on creation**
  When an edge is added, verify that both `fromId` and `toId` nodes exist in the graph. Return a
  new `AbyssError.IntegrityError` variant when either node is missing, rather than silently
  creating a dangling edge. Integrity checks should be disable-able (e.g. a flag on the builder)
  for bulk operations such as graph import, where node existence is guaranteed by the caller.

- **✅ 1.4 Edge modification (retarget)**
  Allow changing an existing edge's endpoint — e.g. A→B becomes A→C — without having to
  `removeEdge` + `addEdge` manually. A `modifyEdge(edge, newFromId?, newToId?)` operation should
  atomically replace the old edge with the new one (removing it from both the forward and reverse
  maps). Integrity check must apply to the new `fromId`/`toId` when `checkIntegrity` is enabled.

- **✅ 1.5 Graph self-healing for non-atomic YCQL edge writes**
  YCQL cannot atomically write both the edge table and the reverse-edge table. When the second
  write fails, start an asynchronous retry procedure: up to 5 attempts with exponentially growing
  intervals (via a multiplier). Arrow's `Schedule` primitive covers this pattern.

- **✅ 1.6 Ephemeral node/edge TTL restoration on cache miss**
  When a cache miss causes a read from YCQL disk, the TTL must be restored correctly before
  populating the cache. This likely requires a technical column `ttl_expiration` (absolute
  timestamp) on the ephemeral node/edge tables, so the remaining TTL can be calculated at
  read time and applied to the cache entry.

## 2. Medium

- **2.1 Single Hazelcast node**
  `PartitionAware` and partition-predicate routing only matter in a cluster. On one node it
  degrades to a smaller in-memory scan. No cluster topology awareness, near-cache, or partition
  migration hooks.

- **✅ 2.2 No schema enforcement**
  `@SerialName` type strings are unchecked. Nothing prevents a `Knows` edge connecting two
  non-`Person` nodes. Invalid graphs are silently possible.

- **2.3 Graph export / import (property graph JSON)**
  Export the full graph (or a subgraph) to the nodes + relationships flat JSON format compatible
  with Neo4j, Gephi, and similar tools. Import in the same format via `transaction { }`.
  Node labels and edge types map to `@SerialName` values.

- **✅ 2.4 Graph algorithms**
  BFS/DFS traversal, cycle detection, connected components — see `ai-scripts/AbyssGraphConcept.md`
  (Future development section). Hazelcast in-memory maps make these fast without DB round-trips.

  - **✅ 2.4.1 `exhaustReachable`**
    BFS exhaust from the current frontier following caller-defined edge hops, returns a `Subgraph`
    (all visited nodes + all traversed edges). Distinct from `reaches` (single target) and manual
    hop-chaining (unknown depth). Adds `exhaustReachable` to `TraversalBuilderLike`; DSL alias
    `allReachable`. Implementation mirrors the `checkReaches` BFS loop.

  - **✅ 2.4.2 `detectCycle`**
    DFS cycle detection using a recursion stack (back-edge method). Caller's block defines which
    edge types count. Adds `detectCycle` to `TraversalBuilderLike`; DSL alias `hasCycle`. Private
    `dfsCycle` helper in `TraversalBuilder` reuses the `TraversalBuilder(engine, setOf(nodeId))`
    pattern already present in `checkReaches`.

  - **✅ 2.4.3 `connectedComponents`**
    Weakly connected component grouping over all graph nodes (edges treated as undirected). Requires
    adding `allNodeIds(): Flow<Uuid>` to `AbyssEngineLike` (implemented in `AbyssGraph` via
    `nodesMap.keys`). Top-level extension in `Extensions.kt` uses existing untyped `outEdges` /
    `inEdges` overloads; no block parameter needed.

- **2.5 Schema export / import as JSON**
  Export and import the registered `SerializersModule` (node and edge type definitions) as JSON,
  so tooling and downstream clients can discover the graph schema without inspecting source code.

- **✅ 2.6 Configurable sync vs async cache population after store commit**
  Currently `applyToCache` runs synchronously after the store transaction commits (line 245 in
  `AbyssGraph.kt`). For write-heavy workloads the caller blocks on Hazelcast puts that are
  best-effort anyway. Add a config flag (e.g. `asyncCachePopulation: Boolean`) to fire cache
  puts on a separate coroutine and return to the caller as soon as the store commits. Trade-off:
  async mode widens the window where a read after write lands a cache miss.

- **✅ 2.7 Async Hazelcast reads via `IMap.getAsync()`**
  Cache reads (`loadNode`, `loadEdge`, `containsNode`, `containsEdge`) use blocking `IMap.get()`
  wrapped in `withContext(Dispatchers.IO)`, pinning an IO thread for the full network round-trip.
  `IMap.getAsync()` returns a `CompletionStage` (same pattern already used for writes via
  `setAsync`/`removeAsync`), freeing the IO thread entirely. Worth switching under high cache-miss
  rates where IO thread exhaustion becomes a bottleneck.

- **🔴 ~~2.8 Async predicate and bulk reads via Hazelcast async APIs~~**
  `reverseEdgesMap.keySet(predicate)`, `edgesMap.getAll(keys)`, and `edgesMap.values(paging)` still
  use `withContext(Dispatchers.IO)`. Hazelcast 5.6 exposes `keySetAsync(Predicate)` and
  `getAllAsync(Set<K>)` as `CompletionStage`-based equivalents. The existing `asDeferred()` bridge
  would handle them. Worth switching under high-miss-rate or traversal-heavy workloads where IO
  thread pressure from bulk reads becomes measurable.
  **Removed:** Hazelcast 5.6.0 `IMap` does not expose `keySetAsync(Predicate)` or `getAllAsync(Set<K>)` —
  only single-key `getAsync(K)` is available. The premise cannot be implemented without a Hazelcast
  API that doesn't exist yet.

## 3. Low

- **✅ 3.1 YSQL connection acquired per cache-miss query** (`queryNodeYsql` / `queryEdgeYsql`)
  HikariCP pools connections but will queue under burst cold-cache misses.

- **3.2 Dual parallel YSQL + YCQL query on every cache miss**
  Half the queries always return nothing. Wasteful under high miss rate.

- **3.3 YSQL as ephemeral store in `abyss-store-yugabyte`**
  Allow configuring YSQL (instead of YCQL) as the ephemeral backend. Benefit: YSQL supports
  full transactions, so ephemeral edge and reverse-edge writes are atomic. Trade-off: TTL requires
  an `expires_at` column and a background cleanup job rather than native YCQL TTL.
