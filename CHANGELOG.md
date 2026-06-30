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
