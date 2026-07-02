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

- **✅ 1.7 Traversal DSL: non-terminal `nodes` filter + `collectNodes()` terminal**
  `nodes<N>(filter)` in `Extensions.kt:80` is currently a terminal that returns `Flow<N>` directly.
  The desired syntax `outgoing { edgePredicate }; nodes { nodePredicate }; collectNodes()` requires
  a separate non-terminal node-filter step and a no-arg `collectNodes()` terminal. Needs a
  `nodePredicate` field on `TraversalBuilder` (or a staging step) and a `collectNodes()` extension
  that materialises using it.

- **✅ 1.8 Traversal DSL: frontier connectivity filters (`hasOutgoing` / `hasIncoming`)**
  Add `hasOutgoing<E>(toId)`, `hasOutgoing<E, N>()` and symmetric `hasIncoming` variants that
  filter the frontier in place (without advancing it), keeping only nodes that have the specified
  edge to a particular target node or to any node of a given type.

- **✅ 1.9 Add modifyNode + unify modifyEdge to lambda pattern**
  `AbyssTransactionLike` and `AbyssEphemeralTransactionLike` expose `modifyEdge(old, new)` requiring
  the caller to pre-fetch the old edge. Replace with a consistent read-modify-write lambda pattern:
  `suspend fun modifyNode(id, transform: (NodeLike?) -> NodeLike)` and
  `suspend fun modifyEdge(fromId, toId, type, transform: (EdgeLike?) -> EdgeLike)`.
  Both fetch the current value internally. `BufferedTransaction` and `BufferedEphemeralTransaction`
  gain `readNode`/`readEdge` constructor params; four test call sites updated; two new `modifyNode` tests added.

- **✅ 1.10 Generic ID refactor**
  Introduce `NodeId(ByteArray)` as the internal universal key (content-based equals/hashCode) and
  `KeyAdapter<ID>` to bridge domain ID types. Make `AbyssGraph<ID>` typed per instance with the
  adapter injected at construction and invisible to callers. All interfaces become generic:
  `NodeLike<ID>`, `EdgeLike<ID>`, `AbyssEngineLike<ID>`, `AbyssTransactionLike<ID>`,
  `AbyssEphemeralTransactionLike<ID>`, `AbyssStoreLike<ID>`, `TraversalBuilderLike<ID>`,
  `Path<ID>`, `Subgraph<ID>`. `EdgeKey` and `ReverseEdgeKey` store `NodeId` fields.
  Standard adapters provided: `UuidKeyAdapter`, `LongKeyAdapter`, `StringKeyAdapter`.
  DB schema: `BYTEA` PK columns. See plan `so-the-quick-summary-typed-hamster.md`.

- **✅ 1.11 Adapter-aware compact serializers for EdgeKey / ReverseEdgeKey**
  `EdgeKeySerializer` and `ReverseEdgeKeySerializer` write `fromId`/`toId` as hex strings
  (workaround for Hazelcast predicates rejecting `byte[]` as non-Comparable). This causes string
  allocation and character-by-character comparison on every scanned partition entry, and doubles
  the encoded size vs the native representation. Fix: pass `EdgeAdapter` into
  `registerAbyssSerializers`; let each serializer write `fromId`/`toId` in the native
  Hazelcast-comparable form the adapter knows (e.g. `writeLong` for `LongKeyAdapter`, two longs or
  UUID string for `UuidKeyAdapter`, raw string for `StringKeyAdapter`). Predicates in `AbyssGraph`
  must be updated to match the native type. Serializers become per-adapter instances rather than
  global singletons.

- **➡️ 1.12 Schema concept: per-node-ID-type schemas, multi-schema graph, cross-schema edges**
  Discuss adding a schema concept keyed per node ID type, where a single graph can host multiple
  schemas concurrently. `NodeId` would carry additional bytes identifying which schema it belongs
  to. This would also enable cross-schema edges — e.g. an edge from `NodeLike<Long>` to
  `NodeLike<Uuid>` — which the current generic ID refactor (single adapter per `AbyssGraph<ID>`
  instance, see 1.10) does not support.

## 2. Medium

- **➡️ 2.1 Single Hazelcast node**
  `PartitionAware` and partition-predicate routing only matter in a cluster. On one node it
  degrades to a smaller in-memory scan. No cluster topology awareness, near-cache, or partition
  migration hooks.

- **✅ 2.2 No schema enforcement**
  `@SerialName` type strings are unchecked. Nothing prevents a `Knows` edge connecting two
  non-`Person` nodes. Invalid graphs are silently possible.

- **➡️ 2.3 Graph export / import (property graph JSON)**
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

- **➡️ 2.5 Schema export / import as JSON**
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

- **✅ 2.9 Traversal DSL: `hasTraversal { }` frontier filter**
  Filter frontier nodes by whether a sub-traversal from each node yields a non-empty result.
  Single `hasTraversal { }` (not split by direction — the block expresses direction via
  `outgoing`/`incoming` calls). Adds `filterFrontierByTraversal(block)` to `TraversalBuilderLike`;
  implementation spawns a child `TraversalBuilder` per frontier node and keeps the node if
  `sub.frontier.isNotEmpty()`. Sub-traversal edges/visited IDs stay siloed — don't bleed into
  the parent, consistent with predicate-check semantics.

- **✅ 2.10 Two separate stores: ephemeral and persistent**
  Currently a single store is configured and both ephemeral (TTL) and persistent (no-TTL) data are
  written to it at once. Split into two independent store configurations — one for ephemeral data,
  one for persistent data. Each store should be nullable so callers can create an ephemeral-only
  (in-memory) setup or a persistent-only setup without requiring both backends to be present.

- **✅ 2.11 Neo4j-style `loop` traversal with DFS/BFS, path context, and `Flow<Path>` result**
  Add `loop(strategy, maxDepth, edgeVisitor, nodeEvaluator): Flow<Path>` to `TraversalBuilderLike`.
  New types: `TraversalStrategy { DFS, BFS }`, `Evaluation { INCLUDE_AND_CONTINUE, INCLUDE_AND_PRUNE,
  EXCLUDE_AND_CONTINUE, EXCLUDE_AND_PRUNE }`, and `Path(nodes, edges)` (all nodes in Path are accepted
  by definition). Visitors receive the current accepted `Path` and the candidate edge/node; `Flow<Path>`
  emits one `Path` per INCLUDE_AND_PRUNE (or terminal INCLUDE_AND_CONTINUE) node reached.

- **➡️ 2.12 `AbyssStoreColumn` annotation + index-seeded traversal entry point**
  Attributes today live entirely inside the opaque JSONB `data` column (`YugabytePersistentStore`);
  there is no per-attribute column or index. Add an `@AbyssStoreColumn(name)` annotation for fields
  that should be promoted to a real, indexed Postgres column via a generated column
  (`GENERATED ALWAYS AS (data->>'attr') STORED` + `CREATE INDEX`) — the annotation only declares
  intent and drives DDL generation; it does not duplicate values at write time (Postgres derives
  the generated column itself, so there's no second write path to keep in sync).
  The declared column names double as the **allow-list** for a new
  `AbyssStoreLike.queryNodeIds(column: String, value: Any): Flow<ID>` (id-only, index-driven —
  YSQL only, no Hazelcast-side equivalent) — `column` must be checked against this allow-list
  before use since it can't be bind-parameterized (SQL injection otherwise).
  Add a matching `AbyssEngineLike.from(nodeIds: Set<ID>, block)` overload so query results can seed
  a traversal frontier directly; this reuses `TraversalBuilder` unchanged (mirrors the existing
  single-ID `from(nodeId, block)` at `AbyssGraph.kt:194`), so it's wiring, not new machinery.
  Open question: whether `queryNodeIds` needs more than equality (ranges, `IN`) from the start, or
  a flat `(column, value)` pair is enough for v1.

## 3. Low

- **✅ 3.1 YSQL connection acquired per cache-miss query** (`queryNodeYsql` / `queryEdgeYsql`)
  HikariCP pools connections but will queue under burst cold-cache misses.

- **➡️ 3.2 Dual parallel YSQL + YCQL query on every cache miss**
  Half the queries always return nothing. Wasteful under high miss rate.

- **➡️ 3.3 YSQL as ephemeral store in `abyss-store-yugabyte`**
  Allow configuring YSQL (instead of YCQL) as the ephemeral backend. Benefit: YSQL supports
  full transactions, so ephemeral edge and reverse-edge writes are atomic. Trade-off: TTL requires
  an `expires_at` column and a background cleanup job rather than native YCQL TTL.

- **➡️ 3.4 Concurrent query benchmark**
  Spin up N coroutines in parallel, each firing queries continuously, and measure throughput +
  per-query latency at N = 1, 2, 4, 8, 16, 32. Find the knee of the curve where latency starts
  climbing (expected: ~4 parallel callers on Oracle Ampere A1 before CPU becomes the ceiling).
  Cover `outEdges`, `inEdges`, and 3-hop traversal.

- **✅ 3.5 Prove serde cost drives Uuid 3-hop slowdown**
  UUID 3-hop traversal consistently runs ~4 ms vs ~0.6 ms for Long/String. Hypothesis: deserializing
  `Uuid` fields in `TestEdge` costs more than `Long`/`String` fields in `LongTestEdge`/`StrTestEdge`.
  Write a focused benchmark that measures Hazelcast compact serde roundtrip cost in isolation for
  each ID type, independent of partition routing, to confirm or refute the hypothesis.
  **Result: mostly refuted.** `SerdeRoundtripPerformanceTest` isolates value-payload serde by
  building a bare `DefaultSerializationServiceBuilder` (no `HazelcastInstance`/`IMap`/partition
  routing at all) and timing `toData`/`toObject` roundtrips directly. Measured cost for Uuid vs
  Long/String is only ~1.8x (edge) / ~1.2x (node) — nowhere near the ~5-7x gap seen in the 3-hop
  benchmark (3.5ms vs 0.7ms/0.5ms, same machine, same run). Also corrects a mislabel: `NodeLike`/
  `EdgeLike` payloads are **not** Hazelcast Compact serialized — they go through a custom JSON
  `StreamSerializer` (`NodeLikeHzSerializer`/`EdgeLikeHzSerializer`); only `NodeId`/`EdgeKey`/
  `ReverseEdgeKey` use real Compact. Value serde is a real but minor contributor; the bulk of the
  Uuid 3-hop gap comes from elsewhere (candidate: `Uuid`/`NodeId` hashCode+equals cost across the
  ~125 edge/node lookups a 3-hop × 5-fanout traversal touches) — not further investigated here.

- **✅ 3.6 Prove Uuid hashCode/equals cost drives the remaining 3-hop gap**
  TODO 3.5's `SerdeRoundtripPerformanceTest` showed value-payload serde only accounts for
  ~1.8x (edge) / ~1.2x (node) of the ~5-7x Uuid vs Long/String 3-hop traversal gap, leaving most
  of the slowdown unexplained. Candidate cause: `Uuid`/`NodeId` hashCode+equals cost across the
  ~125 edge/node lookups (HashMap/Set operations in frontier dedup, IMap key lookups) a
  3-hop × 5-fanout traversal touches, compared to Long/String's cheaper hashCode. Write a focused
  benchmark isolating hashCode/equals + HashMap get/put cost for `NodeId(Uuid)` vs `NodeId(Long)`
  vs `NodeId(String)` at the volumes a 3-hop traversal touches, independent of serde, to confirm
  or refute.
  **Result: refuted.** `NodeIdHashPerformanceTest` isolates hashCode/equals + HashMap/HashSet
  cost independent of Hazelcast/serde (plain JVM `HashMap`/`HashSet`, no `IMap`). Raw
  `NodeId.hashCode()`+`equals()` calls confirm the premise in isolation: `NodeId(Uuid)` costs
  ~4-12x more per call than `NodeId(Long)` (~30-54ns vs ~4-15ns across runs), consistent with
  uncached `ByteArray.contentHashCode()`/`contentEquals()` scanning 16 bytes vs 8. But that cost
  does not survive into real container operations: `HashMap<NodeId, V>.get`/`.put` at 10k-200k
  entries show `NodeId(Uuid)` costing 0.4-0.6x of `NodeId(Long)` (Uuid *faster*, not slower)
  consistently across repeated runs, and domain-ID (`Uuid`/`Long`/`String`) `frontier.toSet()`
  rebuild at 3-hop volumes (sizes 5/25/125) is noisy with no consistent direction (0.7x-1.9x,
  dominated by allocation/GC at these sub-microsecond operation sizes, not by hashCode/equals).
  Combined with 3.5's serde findings (~1.8x/1.2x), neither serde nor hashCode/equals+HashMap
  cost explains the ~5-7x 3-hop gap — the cause remains open. Candidate not investigated here:
  coroutine/`Flow` fan-out overhead in `TraversalBuilder.addHop`'s
  `coroutineScope { frontier.map { async {...} } }.awaitAll()`, which runs once per hop
  regardless of ID type but whose per-task overhead could dominate at these small per-node costs.

## 4. Uncategorized

- **❓ 4.1 Delete dead `edgeFlow` and `edgeOrder`**
  `AbyssGraph.kt:108-111, 161-168` — `edgeFlow` is never called; `outEdgeFlow`/`inEdgeFlow` bypass it.
  `edgeOrder` is only referenced inside `edgeFlow`. Both are dead code.

- **❓ 4.2 Remove `pageSize` param from `outEdges`/`inEdges`**
  `AbyssGraph.kt:94-104`, `AbyssEngineLike.kt:17-20` — all four overrides accept `pageSize` and
  silently ignore it. The only impl that honoured it (`edgeFlow`) is dead (see 4.1).

- **❓ 4.3 Merge duplicate transaction interfaces and buffers**
  `AbyssEphemeralTransactionLike` (`AbyssEngineLike.kt:48-55`) is a byte-for-byte copy of
  `AbyssTransactionLike`. `BufferedEphemeralTransaction` (`AbyssGraph.kt:380-390`) differs from
  `BufferedTransaction` only in `null` → `ttl` for `addNode`/`addEdge`. Merge to one interface,
  one `Buffered(ttl: Duration?)` class, and remove the duplicate `removeEdge` extensions
  in `Extensions.kt:43-48`.

- **✅ 4.4 Shrink `collectNodes` filter overload**
  `TraversalBuilder.kt:85-93` — sequential re-implementation of what `collectNodes(nodeType).filter(filter)`
  does in one line using the parallel impl already present. Replace 9 lines with 1.
