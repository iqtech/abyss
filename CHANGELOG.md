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
