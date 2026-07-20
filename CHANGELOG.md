## [0.31.3] - 2026-07-20

- Widen `container.transaction { }` on `HeterogeneousSchemaGraph`/`HomogeneousSchemaGraph` into a multi-schema transaction: `on(schema)` now stages `addNode`/`addEdge`/`removeNode`/`removeEdge`/`modifyNode`/`modifyEdge` against any schema registered on the container, committed atomically alongside `addCrossEdge`/`removeCrossEdge` in the same `worker.transaction` call. Source-compatible with every existing cross-edge-only call site; also fixes `HeterogeneousSchemaGraph`'s `allowCrossSchemaEdges` gate to only apply when the transaction actually contains cross edges

## [0.31.2] - 2026-07-17

- Stop `preloadOut`/`preloadIn` from re-hitting the persistent store on every warm-cache `outAt`/`inAt` call (TODO 1.22): `adjacencyRead` now checks the already-cached adjacency shards first and only falls through to the store when that comes back empty, instead of preloading unconditionally on every call; measured 500 store hits down to 1 on a repeated warm-node read (`AdjacencyPreloadPerformanceTest`, `-Pperf`)
- Move `hazelcast.yaml` from `abyss-graph`'s shipped `main/resources` into `test/resources` — it was only ever consumed by tests, not by any main-source code path, so it no longer risks becoming a consuming app's default Hazelcast config via classpath auto-discovery

## [0.31.1] - 2026-07-16

- Add `batchTransaction { }` (TODO 1.21): chunked JDBC `addBatch`/`executeBatch` commits (default `batchSize` 1000, one DB transaction per chunk) for bulk YSQL loads, replacing one `executeUpdate()` per op so million-op loads don't pay sequential round-trips inside a single ever-growing distributed transaction; cascade expansion and integrity checks still run once over the full op list before chunking
- Parallelize `pathTo`'s per-level frontier fan-out and per-entry neighbor resolution with `coroutineScope`/`async`/`awaitAll` (sharing the existing hop dispatcher), fixing sequential `nodeAt` round trips per frontier node and per supernode neighbor; measured ~19.5x and ~21x speedups respectively (`PathToPerformanceTest`, `-Pperf`)

## [0.31.0] - 2026-07-15

- First release published to Maven Central

## [0.30.2] - 2026-07-09

- Add traversal DSL `collectEdges<E>()` (mirrors `collectNodes<N>()`, narrows with any later frontier filter) and `pathTo(targetId, block)` (fewest-hops path to a target, sibling to `reaches`/`checkReaches`; TODO 2.22/2.23); wire the already-declared `edgesAdjacencyMapName` container parameter through to `AbyssSchemaWorker` on `SingleSchemaGraph`/`HomogeneousSchemaGraph`/`HeterogeneousSchemaGraph` (previously silently ignored); add `MultiMemberClusterTest` (gated behind `-Pcluster`) validating `PartitionAware` co-location and partition-scoped reads against a real 3-member Hazelcast cluster instead of the single-embedded-member setup every other test uses (TODO 2.1)

## [0.30.0] - 2026-07-07

- Make traversal hop-fanout parallelism a per-engine constructor parameter: `hopFanoutParallelism` (default 256) sits alongside `adjacencyShardCount` on `AbyssGraphSchema`/`SingleSchemaGraph`/`HomogeneousSchemaGraph`/`HeterogeneousSchemaGraph`, replacing `TraversalBuilder`'s static JVM-wide dispatcher with one owned per `NodeIdEngine` so different graphs in the same process can be tuned independently

## [0.29.0] - 2026-07-07

- Bound `TraversalBuilder`'s per-frontier-node coroutine fan-out behind one shared `Dispatchers.IO.limitedParallelism(256)` dispatcher so a single supernode-heavy traversal can no longer starve concurrent callers (TODO 2.19); replace `reverseEdgesMap`/`ReverseEdgeKey` with a sharded, `EntryProcessor`-backed adjacency index (`AdjacencyKey`/`AdjacencyEntry`/`AdjacencyValue`) giving `outAt`/`inAt` a real, batched-point-get index for untyped, incoming, and existence-only reads instead of an unindexed partition scan — measurably faster (3-hop traversal down from ~5ms to sub-millisecond); add `@TypeTag`/`TypeTagRegistry` (eager, collision-guarded) and `outgoingAny()`/`incomingAny()` DSL sugar for mixed-type hops (TODO 2.21)

## [0.28.1] - 2026-07-06

- Add a `@CrossSchemaEdge` annotation so cross-schema edges can be declared with real domain types instead of pre-built `NodeId`s, and route `addCrossEdge`/`removeCrossEdge` through the same `transaction{}`/`ephemeral{}` commit pipeline as ordinary node/edge ops (atomic, persisted, `@EdgeConstraint`-checked) instead of an independent store commit; fix `cascadeEdgeRemovals` leaving cross-schema edges dangling after their endpoint node is deleted (a `sameSchema()` filter wrongly excluded them, and is now deleted entirely as dead weight); and cache reflective `findAnnotation<SerialName>`/`<EdgeConstraint>`/`<CrossSchemaEdge>` lookups instead of re-running JVM reflection on every edge/node/hop, measurably speeding up typed traversal (3-hop median down 12-39% across adapters)

## [0.28.0] - 2026-07-05

- Add property-graph JSON export/import codec, fix `allNodeIds()` to scope by schema tag so per-schema export/import and `connectedComponents` no longer leak sibling schemas' data in `HomogeneousSchemaGraph`/`HeterogeneousSchemaGraph`, persist cross-schema edges through the configured `persistentStore`/`ephemeralStore` (previously cache-only), and fix cache-warmth-dependent correctness bugs from the durability audit: `cascadeEdgeRemovals` and `integrityError` (ordinary and cross-schema) now self-heal from the store instead of reading the Hazelcast cache directly, so a cold cache after a restart or partition eviction can no longer leave dangling edges or spuriously reject a valid write

## [0.27.0] - 2026-07-04

- Make `HomogeneousSchemaGraph` registry-free with 128-bit, headerless tags: widens schema tags from `Long` to a real 128-bit `SchemaTag` (fixing a dead, previously-broken `SchemaTagWidth.UUID` path) so a `Uuid` can be used directly as a per-tenant tag; replaces the registration-guard pattern with on-the-fly `forTag()` views; and drops the 1.15 header byte from its keys entirely (like `SingleSchemaGraph`), restoring clean 16/32-byte alignment for `Long`/`Uuid` tag+id combinations

## [0.26.0] - 2026-07-04

- Split `AbyssGraph` into `SingleSchemaGraph`/`HomogeneousSchemaGraph`/`HeterogeneousSchemaGraph` tiers (TODO 1.19): single-schema `NodeId`s drop the 1.15 header byte entirely via a new `HeaderlessKeyAdapter`, and `AbyssSchemaWorker` takes an injectable `SchemaResolution` strategy so each tier resolves its `EdgeAdapter` differently (fixed adapter / one descriptor computed once / today's per-key derivation) while sharing the same untyped engine

## [0.25.0] - 2026-07-04

- Remove `SchemaEdgeLike` and `CrossEdgeLike` (TODO 1.17): both were zero-member aliases over `EdgeLike<FID,TID>`; unified all same-schema and cross-schema edge signatures onto `EdgeLike` directly, including the Yugabyte stores' polymorphic serialization base

## [0.24.2] - 2026-07-04

- Add `countEdges<E>()` terminal to the traversal DSL: counts raw edges of type `E` from the frontier without fetching edge values or target nodes, avoiding the fan-in undercount of `outgoing<E>(); count()`

## [0.24.1] - 2026-07-04

- Fix `paths()` dropping natural-terminal `INCLUDE_AND_CONTINUE` paths (TODO 1.18): `dfsLoop` now reports whether its subtree emitted and emits an included head's own path when its expansion emitted nothing; `bfsLoop` mirrors this with a per-entry "produced" flag and a `nextDepth < maxDepth` enqueue guard, so a maximal accepted path below `maxDepth` is emitted once without per-prefix duplicates

## [0.24.0] - 2026-07-03

- Replace the typed schema registry with an untyped worker over self-describing NodeIds: `NodeKeyKind` becomes a domain type with a 1:1 canonical `KeyAdapter` map, `RawEdgeLike` is renamed to `EdgeLike`, and `ensureSubgraph` is added for idempotent create-if-missing of a described subgraph

## [0.23.1] - 2026-07-03

- Fix epoch-0 timestamps in Yugabyte stores: stamp ephemeral `created_at`/`updated_at` columns at write time, and set `encodeDefaults = true` on both store serializers so `createdAt`/`updatedAt`/`tags` always persist in the JSON blob instead of being dropped when they equal their default

## [0.23.0] - 2026-07-03

- Make ephemeral (TTL) edges outgoing-only (TODO 1.13): a single atomic YCQL row write replaces the non-atomic reverse+primary dual-write, dropping the `ephemeral_reverse_edges` table and the heal machinery; the cache no longer maintains a reverse index for TTL edges, so ephemeral edges are reachable via `outEdges`/`outgoing` only while persistent edges stay bidirectional

## [0.22.0] - 2026-07-03

- Make `NodeId` keys self-describing (TODO 1.15): a 1-byte header (tag-width ordinal + id-shape kind) prefixes every key so it decodes standalone without a graph-global width or a tag→schema→adapter lookup; `KeyAdapter` collapses to `nodeKeyKind` + raw byte conversion with shared header/Compact defaults, and `MultiSchemaAdapter` drops its adapter registry

## [0.21.0] - 2026-07-02

- Unify the single/multi-schema graph engine (TODO 1.14): `RawEdgeLike`/`SchemaEdgeLike`/`CrossEdgeLike` hierarchy, `SchemaTagWidth.NONE` + `AbyssGraph.singleSchema` zero-overhead entry point, native tag-encoded multi-schema `EdgeKey`, and a universal `NodeId`-frontier traversal engine with first-class cross-schema hops and raw `Path`/`Subgraph`

## [0.20.0] - 2026-07-01

- Add generic ID support via `KeyAdapter`/`NodeId`, adapter-native `EdgeKey`/`ReverseEdgeKey` Hazelcast Compact encoding (eliminating hex-string predicates), and a unified `modifyNode`/`modifyEdge` read-modify-write lambda pattern

## [0.19.0] - 2026-06-30

- Rename `loop` to `paths` across DSL interface, implementation, tests, and README for clarity

## [0.18.0] - 2026-06-30

- Add Neo4j-style `loop` traversal: `TraversalStrategy` (DFS/BFS), `EdgeTraversalDirection` (IN/OUT/BOTH), `Evaluation` (4-state per-node decision), `Path` with `toEitherList()`, and `Flow<Path>` result emitting one path per accepted terminal

## [0.17.0] - 2026-06-30

- Split single `AbyssStoreLike` store into separate `persistentStore` and `ephemeralStore` on `AbyssGraph`; `YugabyteAbyssStoreLike` replaced by `YugabytePersistentStore` (YSQL) and `YugabyteEphemeralStore` (YCQL), each independently nullable

## [0.16.0] - 2026-06-30

- Add `hasTraversal { }` multi-hop frontier filter to the traversal DSL, keeping only frontier nodes where an arbitrary sub-traversal yields a non-empty result

## [0.15.0] - 2026-06-30

- Add `hasOutgoing`/`hasIncoming` frontier connectivity filters to the traversal DSL, enabling in-place AND-conjunction of edge conditions without advancing the frontier

## [0.14.0] - 2026-06-30

- Make `nodes<N>` a non-terminal frontier-mutating step and introduce `collectNodes<N>()` as the explicit terminal, enabling composable multi-hop traversals with intermediate node filters

## [0.13.0] - 2026-06-28

- Switch cache reads to non-blocking `IMap.getAsync()`, freeing IO threads on cache hits; add async self-healing for failed YCQL reverse-edge writes

## [0.12.0] - 2026-06-28

- Document graph algorithms in README with warm-cache correctness notes for `connectedComponents`

## [0.11.0] - 2026-06-28

- Add graph algorithms: `exhaustReachable`/`allReachable` (BFS exhaust to `Subgraph`), `detectCycle`/`hasCycle` (DFS back-edge detection), and `connectedComponents` (weakly connected grouping via `allNodeIds`)

## [0.10.0] - 2026-06-27

- Add `@EdgeConstraint` annotation for opt-in edge endpoint type enforcement; `AbyssError.SchemaError` distinguishes schema violations from referential integrity errors

## [0.9.0] - 2026-06-27

- Add `ephemeral { }` builder for TTL-bound YCQL writes; `transaction { }` is now exclusively persistent (YSQL); TTL removed from per-operation signatures

## [0.8.0] - 2026-06-27

- Add `subgraph()` / `subgraph<N>()` terminal traversal call returning `Subgraph(nodes, edges)` — all visited nodes and traversed edges across all hops

## [0.7.0] - 2026-06-27

- Bump Kotlin to 2.3.21, Hazelcast to 5.6.0, kotlinx-serialization to 1.11.0; replace `kotlinx-datetime` with `kotlin.time.Instant` / `kotlin.time.Clock`

## [0.6.0] - 2026-06-27

- Add `modifyEdge(old, new)` for atomic edge retargeting with integrity check support
- Tune YSQL HikariCP pool: configurable `ysqlMaxPoolSize` (default 20), warm idle, server-side prepared statement caching

## [0.5.0] - 2026-06-27

- Add edge integrity check on creation (`AbyssError.IntegrityError`); disable via `checkIntegrity = false` for bulk loads
- Make YSQL schema and YCQL keyspace configurable to support multiple graphs in one application
- Switch `java.util.UUID` / `java.time.Instant` to `kotlin.uuid.Uuid` / `kotlinx.datetime.Instant` throughout public API
