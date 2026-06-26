# Abyss — Implementation Plan

Steps follow the dependency graph: store-api → dsl → graph → store-yugabyte.
Each step is one focused coding session. Mark `[x]` when done.

---

## Phase 1 — `abyss-store-api` (foundation contracts)

- [x] **1. Core node/edge interfaces**
  `NodeLike`, `EdgeLike` sealed interfaces with `id`, `tags`, `createdAt`, `updatedAt`.
  File: `pl.iqtech.abyss.store.api.model`

- [x] **2. Error model**
  `AbyssError` sealed interface: `NodeNotFound`, `EdgeNotFound`, `Unexpected`.
  File: `pl.iqtech.abyss.store.api.AbyssError`

- [x] **3. Store interfaces**
  `AbyssStoreLike` + `AbyssStoreTransactionLike` — load/save/delete with TTL.
  File: `pl.iqtech.abyss.store.api.AbyssStoreLike`

---

## Phase 2 — `abyss-dsl` (engine contract + traversal API)

- [x] **4. EdgeKey + CompactSerializer**
  `EdgeKey(fromId, toId, type)` data class, `PartitionAware<UUID>` returning `fromId`,
  `EdgeKeySerializer : CompactSerializer<EdgeKey>`.
  File: `pl.iqtech.abyss.dsl.EdgeKey`

- [x] **5. Engine and transaction interfaces**
  `AbyssEngineLike` + `AbyssTransactionLike` — CRUD + Flow-based edge reads + `transaction {}`.
  File: `pl.iqtech.abyss.dsl.AbyssEngineLike`

- [x] **6. Traversal DSL interface**
  `TraversalBuilderLike` — empty marker interface; all operations are reified extensions.
  `from()` declared on `AbyssEngineLike`.
  File: `pl.iqtech.abyss.dsl.TraversalBuilderLike`

- [x] **7. Reified extension functions**
  `node<N>`, `edge<E>`, `edgeExists<E>`, `removeEdge<E>`, `outEdges<E>`, `inEdges<E>`,
  `outgoing<E>`, `incoming<E>`, `outgoing<E,N>`, `incoming<E,N>`, `nodes<N>`.
  File: `pl.iqtech.abyss.dsl.Extensions`

---

## Phase 3 — `abyss-graph` (Hazelcast engine)

- [x] **8. AbyssGraph skeleton + IMap wiring**
  `AbyssGraph(hazelcast, nodesMapName, edgesMapName, store?)` class.
  Acquire `IMap<UUID, NodeLike>` and `IMap<EdgeKey, EdgeLike>` in init.
  Register `EdgeKeySerializer` via `CompactSerializationConfig`.
  File: `pl.iqtech.abyss.graph.AbyssGraph`

- [x] **9. Polymorphic serialization**
  `NodeLike` / `EdgeLike` value serializer for Hazelcast using
  `createPolymorphicJsonSerializer` (or equivalent) — kotlinx polymorphic JSON with
  unknown-type fallback (`UnknownNode`, `UnknownEdge`).
  File: `pl.iqtech.abyss.graph.serialization.AbyssSerializer`

- [x] **10. MapLoader**
  On cold miss: fire `store.loadNode(id)` (YSQL + YCQL in parallel via coroutines),
  return first non-null. Wired as `MapLoader` on both IMap configs.
  File: `pl.iqtech.abyss.graph.loader.NodeMapLoader`, `EdgeMapLoader`

- [x] **11. Point reads**
  `node()`, `edge()`, `nodeExists()`, `edgeExists()` — Hazelcast get, MapLoader handles miss.
  Implemented on `AbyssGraph`.

- [x] **12. Edge traversal as Flow**
  `outEdges()` — `PartitionPredicate` + `__key.fromId` HASH index + `PagingPredicate`.
  `inEdges()` — `__key.toId` HASH index + `PagingPredicate` (scatter-gather).
  Both emit as `Flow<EdgeLike>` with configurable `pageSize`.
  Implemented on `AbyssGraph`.

- [x] **13. Transaction — store-first write ordering**
  `transaction {}`: accumulate ops in `AbyssTransactionLike` impl, commit to store atomically,
  then bulk-put into Hazelcast (best-effort; cache-put failure is non-fatal, logged WARN).
  Implemented on `AbyssGraph`.

- [x] **14. Traversal DSL — `from()` implementation**
  `TraversalBuilderLike` impl backed by `AbyssGraph`.
  `outgoing<E>()` / `incoming<E>()` compile to predicate queries on `__key.fromId` / `__key.toId` + edge type.
  `nodes<N>()` — fetch node for each matched edge endpoint, filter by type discriminator, emit as `Flow`.
  `traverse {}` + `reaches(targetId)` — BFS with visited set; `traverse` holds traversal-only edges,
  `reaches` holds terminal edge; returns `Boolean`.
  File: `pl.iqtech.abyss.graph.traversal.TraversalBuilder`

---

## Phase 4 — `abyss-store-yugabyte` (durable + ephemeral persistence)

- [ ] **15. DB schema scripts**
  YSQL: `graph.nodes`, `graph.edges` with indexes (GIN tags, btree type, GIST geo stub).
  YCQL: `graph.ephemeral_nodes`, `graph.ephemeral_edges` with per-row TTL.
  Directory: `abyss-store-yugabyte/src/main/resources/db/`

- [ ] **16. YugabyteAbyssStoreLike skeleton + connections**
  `YugabyteAbyssStoreLike(ysql: DataSource, ycql: CqlSession, ...)` class.
  HikariCP `DataSource` for YSQL; `CqlSession` (YugabyteDB Java driver) for YCQL.
  File: `pl.iqtech.abyss.store.yugabyte.YugabyteAbyssStoreLike`

- [ ] **17. `loadNode()` / `loadEdge()`**
  Fire YSQL and YCQL queries in parallel (`async` + `awaitFirst`); return first non-null.
  Serialization: JSONB column → `NodeLike` / `EdgeLike` via kotlinx polymorphic JSON.

- [ ] **18. `transaction()` — TTL routing**
  `null` TTL → YSQL (`BEGIN` / `INSERT OR UPDATE` / `COMMIT`).
  Non-null TTL → YCQL (`INSERT … USING TTL <seconds>`).
  Mixed transactions (durable + ephemeral ops) commit YSQL first, YCQL second.

---

## Phase 5 — Hazelcast config

- [ ] **19. Hazelcast YAML config**
  `abyss-nodes` and `abyss-edges` maps: HASH indexes on `__key.fromId` / `__key.toId`,
  LRU eviction, `FREE_HEAP_PERCENTAGE` max-size, `max-idle-seconds = 86400`.
  File: `abyss-graph/src/main/resources/hazelcast.yaml`

---

## Phase 6 — Logging

- [ ] **20. SLF4J log points**
  Wire log calls at levels defined in the concept doc:
  `AbyssGraph` init/close → INFO; cache miss → DEBUG; transaction summary → DEBUG;
  cache-put failure → WARN; store error → ERROR.
