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

- **✅ 1.12 Schema concept: per-node-ID-type schemas, multi-schema graph, cross-schema edges**
  **Superseded by 1.14** (`ai-scripts/UnifiedGraphEngineRFC.md`) — see that entry for the
  replacement design (`RawEdgeLike` hierarchy, `SchemaTagWidth.NONE`, unified traversal engine,
  tag+widened-native encoding). Kept here as the historical record of what shipped and why.
  Design in `ai-scripts/SchemaConceptRFC.md`; implemented heterogeneous-first. `AbyssGraph<ID>` was
  renamed to `AbyssGraphSchema<ID>` (unchanged single-schema engine); a new `AbyssGraph` container
  holds many schema views keyed by a width-configurable schema tag (`SchemaTagWidth.BYTE` default,
  `SHORT`/`INT`/`LONG` variants). `SchemaKeyAdapter<ID>` prefixes every `NodeId` with the tag
  (`[tag | payload]`) so schemas coexist in shared maps with globally-unique, self-describing keys;
  inner adapters stay schema-agnostic. Cross-schema edges are NodeId-level (`EdgeLike<NodeId>`) in a
  separate container `<edges>-cross` map, gated by `allowCrossSchemaEdges` (default off =
  private-by-default), with per-endpoint integrity via `resolveSchema`; `crossHop` advances a NodeId
  frontier across them. Container edges use uniform hex encoding (via `UniformHexAdapter`, existing
  `STRING` shape, zero serializer changes) because one Hazelcast instance allows only one Compact
  schema per class — so heterogeneous ID shapes can't share a native `EdgeKey`. Native `Int64`/
  `Int64Pair` encoding is preserved for standalone single-schema `AbyssGraphSchema`. Cross edges are
  cache-only for now (no store persistence). Covered by `MultiSchemaTest`.

- **✅ 1.13 Outgoing-only traversal for ephemeral edges**
  YCQL has no cross-table transactions, so the old ephemeral edge write was two non-atomic INSERTs
  (reverse row + primary row) guarded by a reverse-first order and a `healEdge` retry loop for the
  "benign dangling entry" window. Ephemeral (TTL) edges are now **outgoing-only**: a single-row
  INSERT into `ephemeral_edges`, atomic by construction. Deleted the `ephemeral_reverse_edges` table,
  its prepares, `healEdge`/`writePrimaryEdge`/`healScope`; `loadInEdges` returns `emptyList`. Cache
  matches the store — `AbyssGraphSchema.applyToCacheAsync` skips `reverseEdgesMap` when `ttl != null`
  and `preloadIn` no longer warms ephemeral in-edges — so incoming lookups never see a cached
  ephemeral edge the store won't return. **Contract (the note's "VERIFY!", with its outgoing/incoming
  typo corrected):** `outEdges`/`outgoing` find an ephemeral edge; `inEdges`/`incoming` return empty.
  Reverse traversal is the caller's job via an explicit opposite outgoing edge
  (`Person —IsInGroup→ G1` **and** `G1 —HasMember→ Person`). Accepted asymmetry: deleting the TO-node
  can't cascade an ephemeral edge (no reverse index) — it expires via TTL; deleting the FROM-node
  still cascades. Persistent (YSQL, transactional, `to_id`-scan) edges stay bidirectional, untouched.
  Covered by `GraphTest` (engine-level) and `LoadTest` (store-level, outgoing-only).

- **✅ 1.14 Unified single/multi-schema engine with first-class cross-hops**
  Design in `ai-scripts/UnifiedGraphEngineRFC.md`; supersedes parts of 1.12 (SchemaConceptRFC).
  Replaces `EdgeLike<ID>` with a `RawEdgeLike<FID,TID>` hierarchy (`SchemaEdgeLike<ID>` same-schema,
  new `CrossEdgeLike<FID,TID>` cross-schema). Adds `SchemaTagWidth.NONE` so a single-schema
  container degrades byte-for-byte to today's untagged, native-encoded `AbyssGraphSchema` — no hex
  tax paid unless multi-schema mode is actually used. Traversal frontier becomes universal
  `Set<NodeId>`; cross-hops become ordinary DSL hops (`outgoing<E>()` dispatches on `E`'s shape) in
  one engine instead of the standalone `crossHop` escape hatch. Multi-schema storage moves off
  forced `UniformHexAdapter` hex encoding to tag + widened-native (`Int64`/`Int64Pair`) fields, real
  predicates instead of string compares for `Long`/`Uuid`-shaped schemas. `Path`/`Subgraph` go raw
  (`NodeId`-based) with a typed resolve step. Several open questions deferred to build phase
  (naming, `Path`/`Subgraph` ergonomics, cross-edge construction ergonomics, dispatch caching,
  `@EdgeConstraint` for cross-edges, Compact field layout, cross-edge persistence, `UnknownEdge`
  schema-awareness). No production code changes land with the RFC itself.

- **✅ 1.15 Self-describing NodeId keys (1-byte header: tag-width + id-shape)**
  Plan in `ai-scripts/SelfDescribingNodeKeyPlan.md`. Prepend one header byte to every `NodeId`
  (high nibble = `SchemaTagWidth.ordinal`, low nibble = `NodeKeyKind`) so keys decode standalone —
  no graph-global `tagWidth` and no tag→schema→adapter lookup to recover width/shape. Drops
  `MultiSchemaAdapter`'s registry; format break (pre-1.0, no migration).

- **✅ 1.16 Untyped `AbyssSchemaWorker`; derive-don't-map container; single shared NodeId store**
  Design in `ai-scripts/SchemaWorkerRFC.md`. Extracts the whole engine (reads, traversal,
  transaction/ephemeral commit, cascade, cache population, schema enforcement) into an untyped
  `AbyssSchemaWorker` operating on `NodeId`/`NodeLike<*>`/`SchemaEdgeLike<*>`; `AbyssGraphSchema<ID>`
  collapses to a typed `ID⇄NodeId` facade over it. `SchemaDescriptor.of(nid)` derives
  `(edgeAdapter, tagWidth, tag)` straight from the self-describing key, so `AbyssGraph` drops its
  `schemas`/`fallback`/`resolveSchema` registry — routing is a pure function of the NodeId (the
  container keeps only a `Set<Long>` of tags for duplicate/cross-edge guards). API change:
  `schema<ID>(tag)`/`resolveSchema` removed — hold the facade `register`/`singleSchema` returns.
  Stores unify: `AbyssStoreLike`/`AbyssEphemeralStoreLike` lose `<ID>` and key on `NodeId` (PK is
  already BYTEA); scans return `StoredEdge` carrying both endpoint NodeIds so preload stays untyped.
  One shared store per container. Covered by the existing suites + `schemaDescriptorDerivesFromKey…`.

- **✅ 1.17 Remove `SchemaEdgeLike` and `CrossEdgeLike` — `EdgeLike` is enough**
  Both are zero-member aliases over `EdgeLike<FID,TID>` (`Model.kt:24-28`); cross-edge entry points
  already take `EdgeLike<NodeId,NodeId>` and graph serialization already uses `EdgeLike::class` as the
  polymorphic base. Delete both interfaces and rewrite all references to `EdgeLike` (unifying the
  Yugabyte stores' `SchemaEdgeLike::class` polymorphic base onto `EdgeLike`).
  Plan: `ai-scripts/RemoveSchemaAndCrossEdgeLikePlan.md`.

- **✅ 1.18 `paths()` drops natural-terminal INCLUDE_AND_CONTINUE paths**
  Emission fires only on `INCLUDE_AND_PRUNE` and on `INCLUDE_AND_CONTINUE` at the depth cap. An
  included node *below* `maxDepth` with no followable edge (edges exhausted / all visited / all
  continuations pruned) is a natural terminal that is never emitted — e.g. chain `a→b→c` all
  `INCLUDE_AND_CONTINUE` with large `maxDepth` emits nothing. Fix: `dfsLoop` returns a Boolean
  ("did this subtree emit?") and emits an `INCLUDE_AND_CONTINUE` node's own path when its expansion
  emitted nothing; `bfsLoop` mirrors this with a per-entry "produced continuation" flag plus a
  `nextDepth < maxDepth` enqueue guard. Must not emit per-prefix paths. Add DFS/BFS regression tests
  + update the `paths()` doc (emission fires on prune, depth cap, natural terminal).
  Plan: `ai-scripts/PathsNaturalTerminalPlan.md`.

- **✅ 1.19 Three-tier container specialization: `SingleSchemaGraph` / `HomogeneousSchemaGraph` / `HeterogeneousSchemaGraph`**
  `AbyssGraph` (single, tagWidth-parameterized container) is replaced by three types. `SingleSchemaGraph`
  is a thin factory (no state to hold — exactly one schema by definition) over `AbyssGraphSchema`'s
  standalone constructor, now genuinely headerless: a new `HeaderlessKeyAdapter<ID>` builds `NodeId`
  as raw `encodeIdBytes(id)` with no 1.15 header byte at all (reverses that 1.15 "deliberate
  regression" for the single-schema case). `HomogeneousSchemaGraph`/`HeterogeneousSchemaGraph` are two
  independent top-level classes (no shared base, each with its own `register()`/cross-edge logic)
  replacing `AbyssGraph`'s `register()` path. The one thing that differs across all three tiers —
  how a worker resolves a key's `EdgeAdapter`, and whether two keys share a schema — is now injected
  into `AbyssSchemaWorker` as a `SchemaResolution` (new, internal): `SingleSchemaResolution` (fixed
  adapter, `sameSchema` trivially true — no cross-edges exist), `HomogeneousSchemaResolution`
  (computes one `MultiSchemaAdapter(tagWidth)` once at construction, returned unconditionally — zero
  `NodeKey` parsing per key), `HeterogeneousSchemaResolution` (today's unchanged per-key derivation via
  `SchemaDescriptor.of`). Homogeneous vs Heterogeneous is a real code-path difference, not a cosmetic
  rename — proven by a reference-identity test (`HomogeneousSchemaTest.homogeneousResolutionIsConstantHeterogeneousIsPerKey`)
  since `MultiSchemaAdapter` is stateless and width-only, so the two *values* it produces are
  structurally interchangeable even though the *work done per key* differs. Test fixtures that
  pre-seed Hazelcast maps directly (bypassing the facade) needed matching updates: any `EdgeKey`/
  `NodeId` built outside `AbyssGraphSchema` for a single-schema graph must go through
  `HeaderlessKeyAdapter` too, since it's now the Compact-serializer-bound shape for that Hazelcast
  instance (`GraphTest.kt`, `LongPerformanceTest.kt`, `StringPerformanceTest.kt`,
  `UuidPerformanceTest.kt`, `AlgorithmsTest.kt`, `PathsTraversalTest.kt`, `TraversalTest.kt`).
  Pre-1.0, no migration shims: `AbyssGraph.kt` deleted outright.

- **✅ 1.20 Fix cache-warmth-dependent correctness bugs from the durability audit**
  See `ai-scripts/TransactionDurabilitySafetyAudit.md` (findings #4 and #5) — these hit a
  well-configured production system after any restart or partition eviction, not just misuse:
  - `cascadeEdgeRemovals` (`AbyssSchemaWorker.kt`) computed cascade deletes by scanning the Hazelcast
    cache, not the store. A cold cache for a node's edges meant `removeNode` deleted only the node
    row from YSQL, leaving dangling edge rows referencing the deleted node permanently. Fixed by
    calling `preloadOut`/`preloadIn` (the same self-heal `outAt`/`inAt` already use) before the scan.
  - `integrityError` (`AbyssSchemaWorker.kt`) checked `nodesMap[addOp.fromId]` — a raw cache read,
    not the self-healing `readNode`/`nodeExists` path. A genuinely-existing but not-yet-warmed node
    spuriously failed `addEdge`'s integrity check. Fixed by routing through `readNode`.
  - A third instance of the identical bug, found while implementing this fix:
    `HomogeneousSchemaGraph`/`HeterogeneousSchemaGraph.integrityError` gated `addCrossEdge` on
    `worker.containsNodeInCache(...)` — same raw-cache-read pattern, with an inline
    `// TODO this should be rewritten` comment already sitting on that line. Fixed by swapping in
    `worker.nodeExists(...)` (already suspend, self-healing); `containsNodeInCache` deleted as dead
    code. Covered by `GraphTest`/`MultiSchemaTest`.

## 2. Medium

- **➡️ 2.1 Single Hazelcast node**
  `PartitionAware` and partition-predicate routing only matter in a cluster. On one node it
  degrades to a smaller in-memory scan. No cluster topology awareness, near-cache, or partition
  migration hooks.

- **✅ 2.2 No schema enforcement**
  `@SerialName` type strings are unchecked. Nothing prevents a `Knows` edge connecting two
  non-`Person` nodes. Invalid graphs are silently possible.

- **✅ 2.3 Graph export / import (property graph JSON)**
  Reuses the existing polymorphic NodeLike/EdgeLike JSON machinery (`customJsonSerializer` +
  `createPolymorphicJsonSerializer`, already powering the Hazelcast Compact serializers) rather than
  inventing a new wire format — encoding through `PolymorphicSerializer(NodeLike::class)` already
  produces a flat object with a `"type"` field equal to the class's `@SerialName` plus every property
  inline, which is exactly the "flat, type-labeled" shape asked for. New `GraphJsonCodec` interface
  (`abyss-graph/.../serialization/GraphJsonCodec.kt`) is the seam for a different output format later;
  `AbyssJsonLinesCodec` is the one built-in implementation — JSON Lines, one object per line, an outer
  `"kind": "node" | "relationship"` field (distinct from the inner `@SerialName`-driven `"type"`)
  telling a node-line from a relationship-line apart, chosen for streamability. `exportGraphLines`
  walks `allNodeIds()` + per-node `outEdges(id)` (same access pattern as `connectedComponents`, minus
  the `inEdges` half, so each directed edge is emitted once); `Subgraph.exportLines` exports an
  already-computed subgraph (e.g. from `allReachable { }`) instead of the whole graph; `importGraphLines`
  decodes a `Flow<String>` of lines and commits via one `transaction { }` call (`checkIntegrity`
  defaults to `false`, matching the bulk-import convention already documented in the README).
  Lives in `abyss-graph` (`GraphExport.kt`), not alongside `connectedComponents`/`ensureSubgraph` in
  `abyss-dsl/Extensions.kt` — `abyss-dsl` can't depend on `abyss-graph`'s JSON serialization package
  (dependency direction is the other way). Literal external-tool schema compatibility (e.g. Neo4j
  APOC's nested `labels`/`properties` format) is explicitly out of scope — it would also collide with
  using `@SerialName` as the type discriminator. Covered by `GraphExportTest`.
  Fixed (follow-up): `exportGraphLines`/`importGraphLines`/`connectedComponents` all walked
  `allNodeIds()`, which iterated every key in the shared `nodesMap` unfiltered by schema tag — a
  per-tag facade from `HomogeneousSchemaGraph`/`HeterogeneousSchemaGraph` returned and mis-decoded
  every other registered schema's nodes too. Fixed via a new `KeyAdapter.ownsNodeId(NodeId): Boolean`
  (default `true`; overridden in `SchemaKeyAdapter`/`HeaderlessSchemaKeyAdapter` to compare tag+width,
  mirroring `SchemaResolution.sameSchema`'s existing cascade-delete scoping logic) that
  `AbyssGraphSchema.allNodeIds()` filters through before decoding. Cross-schema edges (built via a
  container's `addCrossEdge`) remain outside `exportGraphLines`/`importGraphLines`'s reach — a
  pre-existing, still-open limitation, not addressed by this fix. Covered by
  `GraphExportMultiSchemaTest`.

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

- **✅ 2.13 Real multi-schema graph test: Universe fixture (users/astronomy/interests)**
  Add a reusable multi-schema graph test fixture exercising TODO 1.14's engine with real schemas
  instead of the existing toy `MultiSchemaTest` fixtures — Users (`String` id), Astronomy (`Long`
  id: `Star`/`Planet`/`Moon`/`Singularity` via `Orbits` edges), Interests (`Uuid` id: hierarchy via
  `SubdomainOf` edges), plus cross-schema `InterestedIn` and `LivesOn` edges. Builder externalized
  so other tests can reuse it. Full design in `ai-scripts/UniverseGraphTestPlan.md`.

- **✅ 2.14 Edge hops shouldn't always require reading edge data**
  Traversal hops that advance the frontier via an edge type currently deserialize the full edge
  payload even when no filter predicate is applied. If the hop has no filter, only the target
  `NodeId` (from the `EdgeKey`/`ReverseEdgeKey`) is needed to advance the frontier — the edge
  value fetch should be skipped in that case.

- **✅ 2.15 `ensureSubgraph` — walk a Path array, creating missing nodes**
  New graph method that guarantees a given subgraph exists. Takes a `Path` array (or similar
  structure), walks each path, and creates any nodes (and edges along the way) that are missing,
  leaving existing ones untouched. Effectively an idempotent upsert of a described subgraph.

- **✅ 2.16 Traversal DSL: `count()` terminal**
  Add a `count()` terminal to `TraversalBuilderLike` returning the number of distinct nodes in the
  current frontier without materializing any node (`frontier.size`). Usage:
  `from(id) { outgoing<E>(); count() }` → `Either<AbyssError, Int>`.

- **✅ 2.17 Traversal DSL: `countEdges<E>()` terminal**
  Add a `countEdges<E>(direction = OUTGOING)` terminal counting raw edges of type `E` from the
  current frontier, without fetching edge values or target nodes. Unlike `outgoing<E>(); count()`,
  it doesn't collapse fan-in (multiple frontier nodes sharing a target) into one. Usage:
  `from(id) { countEdges<E>() }` or `from(id) { countEdges<E>(HopDirection.INCOMING) }`.

- **❓ 2.18 Neo4j compatible import/export adapters**
  TODO 2.3 built `GraphJsonCodec` as an extension seam specifically for this — a literal
  external-tool schema was explicitly deferred out of 2.3's scope. Add a `Neo4jJsonCodec` (or
  similar) implementing `GraphJsonCodec` against Neo4j's actual export/import shape (e.g. APOC's
  nested `labels`/`properties` JSON Lines format). Open question: APOC's own `"type": "node" |
  "relationship"` discriminator collides with using `@SerialName` as the domain type — needs a
  decision on where the domain type is expressed once nested under `"properties"` isn't backed by
  `@SerialName` alone the way `AbyssJsonLinesCodec` does it.

- **✅ 2.19 Chunk large per-hop edge fan-out instead of one all-at-once async wave**
  `TraversalBuilder.addHop` and its per-frontier-node sibling methods (`addNodeHop`,
  `filterFrontierByNode/ByEdge/ByEdgeType/ByTraversal`, `countEdges`, `collectSubgraph`,
  `exhaustReachable`) launched one unbounded `async { }` per frontier node, letting one
  supernode-heavy traversal monopolize `Dispatchers.IO` out from under concurrent callers. Fixed with
  a single shared `Dispatchers.IO.limitedParallelism(256)` dispatcher (a companion-object `val`, not
  per-call), routed through every one of those fan-out sites — a dispatcher-level semaphore, so no
  one hop can occupy more than 256 execution slots regardless of how many traversals are running
  concurrently. Chunking (batches + sequential-between) was considered and rejected: it only bounds
  concurrency within one call, not across concurrently-running traversals sharing the pool. Covered
  by `SupernodeTraversalTest` (wide fan-out correctness at 1500 edges, plain and predicate-filtered,
  plus two concurrent supernode traversals not cross-contaminating results).

- **➡️ 2.20 `abyss-store-neo4j` implementation**
  A new module implementing `AbyssStoreLike`/`AbyssEphemeralStoreLike` against Neo4j, alongside
  `abyss-store-yugabyte`. Same untyped, `NodeId`-keyed contract — Abyss's own traversal still runs
  entirely in Hazelcast, so this is a durability backend swap, not a query-path change. The point of
  doing it over just using Yugabyte: the durable copy becomes a second, decoupled surface — Neo4j's
  Graph Data Science library (PageRank, community detection, centrality, weighted shortest paths)
  can run as an offline job against the same data without Abyss implementing any of it, and the
  store is human-browsable natively (Neo4j Browser/Cypher) instead of an opaque byte-keyed table.
  Needs `NodeId`'s self-describing bytes decoded into real Neo4j labels/properties at the store
  boundary to be worth it — a thin `MERGE`-by-id KV adapter gets none of the analytics/browsability
  payoff. See README's "Other stores are a real option, not just a theoretical one" for the full
  tradeoff discussion (including the honest cost: this means operating a graph database, which is
  otherwise what Abyss exists to let you avoid). Full design plan (NodeId decode modes, Neo4j label/
  property mapping, Cypher identifier-injection safeguard, transaction/TTL handling, file-by-file
  breakdown): `ai-scripts/Neo4jStorePlan.md`.

- **✅ 2.21 Sharded adjacency index replacing `reverseEdgesMap`, giving `outAt` a real index**
  New Hazelcast map `<edgesMapName>-adjacency`, key `AdjacencyKey(NodeId, Shard: Byte)` packing
  direction + shard index into one byte, value `AdjacencyValue(Set<AdjacencyEntry(neighborId,
  nodeTypeTag: Short?, edgeTypeTag: Short)>)` — carrying edge type (not just neighbor node type) makes
  `RemoveEdge` exact (no reference-count read needed) and lets the index serve typed traversal too,
  not just mixed/untyped. `nodeTypeTag` is nullable: a neighbor node may not be resolvable at write
  time (`checkIntegrity=false` dangling edges, or a preload racing the node's own store row) — the
  edge write still succeeds, just without the (currently unused) type hint. `outAt`'s existing
  typed+value-needed fast path (`outgoing<E>()`) is untouched (still a direct `edgesMap` partition
  scan, 1 round trip); every other read shape (incoming, untyped/mixed, typed-existence-only) routes
  through one batched `getAll` across all N shard keys via `adjacencyRead` — always 1 round trip
  regardless of N, never a per-shard coroutine fan-out. Shard count (`adjacencyShardCount`, default
  16) is a write-concurrency knob (spreads concurrent hub-node writers off a single `EntryProcessor`
  per-key lock via `AdjacencyMutationProcessor`), decoupled from 2.19's `HOP_FANOUT_PARALLELISM` — the
  two are independently tunable. `@TypeTag(Short)` added on `NodeLike`/`EdgeLike` classes
  (developer-assigned, `@SerialName`-style, two independent namespaces, resolved via
  `AnnotationCache.typeTag()`); `TypeTagRegistry` walks a `SerializersModule` eagerly at worker
  construction (via `SerializersModule.dumpTo` + a small `SerializersModuleCollector`) — both a
  collision guard (duplicate tag in either namespace fails fast at construction) and the
  bidirectional edge-type `String ⇄ Short` lookup `RemoveEdge`/`inAt` need. `outgoingAny()`/
  `incomingAny()` DSL sugar added for untyped/mixed hops (`addHop`'s `edgeType` widened to
  nullable). `ReverseEdgeKey`/`ReverseEdgeKeySerializer` deleted outright. Covered by
  `MixedTraversalTest` (untyped-hop union, exact-type removal, fast-path/index-path parity,
  concurrent-writer `EntryProcessor` atomicity, cold-cache self-heal, restart-equivalent tag
  resolution, `@TypeTag` collision guard, shard-hash determinism) plus every existing
  `GraphTest`/`TraversalTest`/`MultiSchemaTest`/etc. suite passing unmodified in behavior. Full
  design: `ai-scripts/ShardedAdjacencyIndexRFC.md`.

## 3. Low

- **✅ 3.1 YSQL connection acquired per cache-miss query** (`queryNodeYsql` / `queryEdgeYsql`)
  HikariCP pools connections but will queue under burst cold-cache misses.

- **➡️ 3.2 Dual parallel YSQL + YCQL query on every cache miss**
  Half the queries always return nothing. Wasteful under high miss rate.

- **➡️ 3.3 YSQL as ephemeral store in `abyss-store-yugabyte`**
  Allow configuring YSQL (instead of YCQL) as the ephemeral backend. Benefit: YSQL supports
  full transactions, so ephemeral edge and reverse-edge writes are atomic. Trade-off: TTL requires
  an `expires_at` column and a background cleanup job rather than native YCQL TTL.

- **✅ 3.4 Concurrent query benchmark**
  `AstronomyConcurrencyPerformanceTest` (perf-gated, `-Pperf`) runs against the Universe fixture's
  astronomy schema (`UniverseFixture.kt`), which was **enlarged 2x** for this — a second,
  same-shaped star system (singularity "M87*" + 3 stars + 7 planets + 3 moons, mirroring
  Sol/Kepler/TRAPPIST) added alongside the original, all-new names so existing lookups by name
  (`"Luna"`, `"Earth"`, etc.) in `UniverseTraversalTest`/`UniversePerformanceTest` are untouched.
  Three sweeps (`outEdges`, `inEdges`, 3-hop `moon→planet→star→singularity` traversal) each launch
  N coroutines (`N = 1, 2, 4, 8, 16, 32`) firing 200 ops/coroutine continuously, printing throughput
  (ops/sec) and avg per-op latency at each level. The knee position is machine-dependent (the
  original TODO cites ~4 callers on Oracle Ampere A1), so the test reports the curve rather than
  asserting where it bends.

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
  **Correction (4.6): the ~5-7x gap itself was a benchmark artifact, not a real cost difference —
  see 4.6.** `LongPerformanceTest`/`StringPerformanceTest`'s 3-hop numbers were never measuring real
  traversal work; there was no gap to explain. 3.5/3.6's own isolated serde/hashCode measurements
  (`SerdeRoundtripPerformanceTest`, `NodeIdHashPerformanceTest`) remain valid as standalone facts
  about `NodeId(Uuid)` vs `NodeId(Long)`/`NodeId(String)` cost — they just were never actually
  explaining anything, since the thing they were investigating didn't exist.

- **🔴 ~~3.7 Explore a single ID-describing bit in the NodeId header byte~~**
  The 1-byte header from 1.15 (high nibble = `SchemaTagWidth.ordinal`, low nibble = `NodeKeyKind`)
  has spare bits (low nibble only uses 0/1/2, so `0x04`/`0x08` are free). Explore claiming one bit that
  self-describes something about the ID — the intended payoff being an ephemeral/persistent flag to
  route reads and kill 3.2's dual query.
  **No-go.** Routing an edge read needs ephemerality encoded in the edge's identity — i.e. in an
  endpoint `NodeId`. But flipping a bit on `fromId` diverges it from the actual FROM-node's `NodeId`
  (a non-ephemeral node has no such bit), changing its `partitionKey`/`toString`. The ephemeral edge
  then lands on a different Hazelcast partition than its source node — breaking `PartitionAware`
  co-location and endpoint integrity. Spare bits stay spare.

- **✅ 3.8 Configurable edges-adjacency map name**
  `edgesAdjacencyMapName` was added as parameter to `SingleSchemaGraph`, `HeterogenousSchemaGraph` and
  `HomogeneousSchemaGraph`, all defaulting to the prior computed name.

- **✅ 3.9 Multi-member Hazelcast cluster test**
  Every existing test ran against a single embedded Hazelcast member, leaving `PartitionAware`
  co-location (`EdgeKey`/`AdjacencyKey`) and partition-scoped reads (`outAt` fast path,
  `outEdgeFlow`, `adjacencyRead`, `cascadeEdgeRemovals`) unverified under real cross-member
  routing. Added `MultiMemberClusterTest` (gated behind `-Pcluster`, mirroring `-Pperf`): starts a
  real 3-member in-process cluster and asserts traversal/cascade-delete correctness plus genuine
  per-node key co-location and cross-member data spread. No production code changed — see
  `ai-scripts/MultiMemberClusterTestSummary.md`.

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

- **✅ 4.5 Persist cross-schema edges (lift the 1.12 cache-only limitation)**
  1.12's cross edges were cache-only because `AbyssStoreLike<ID>` was then a typed, per-schema
  interface persisting `SchemaEdgeLike<ID>` — a `NodeId`-endpointed cross edge didn't fit. Commit
  `117aafe` later rewrote `AbyssStoreLike`/`AbyssEphemeralStoreLike` to be untyped and `NodeId`-keyed
  (`saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>)`), which is exactly a cross edge's
  shape — the original blocker was gone, but `AbyssSchemaWorker.putCrossEdge`/`removeCrossEdge`
  (and the README) were never updated. `putCrossEdge` now writes through `persistentStore` before
  the cache (mirroring `transaction()`); new `putCrossEdgeEphemeral` does the same against
  `ephemeralStore` with a `ttl`, outgoing-only (no reverse index), matching 1.13's ephemeral-edge
  convention; `removeCrossEdge` fans deletes out to both stores best-effort, same as an ordinary
  delete. No store schema migration needed — `preloadOut`/`preloadIn` already warm cross edges on
  cold restart for free, since they resolve the cache key generically per `NodeId`. New
  `HomogeneousSchemaGraph`/`HeterogeneousSchemaGraph.addCrossEdge(edge, ttl, checkIntegrity)`
  overload for the ephemeral path. Covered by `MultiSchemaTest`.

- **✅ 4.6 Fix `LongPerformanceTest`/`StringPerformanceTest`'s 3-hop benchmark (see 3.5/3.6 correction)**
  Found while building a new `SingleSchemaGraph` concurrency benchmark: both tests seed their 10k-node
  graphs by writing `EdgeKey`/`ReverseEdgeKey` directly into the Hazelcast maps with the literal type
  string `"test_edge"` — but the edges stored are `LongTestEdge`/`StrTestEdge`, whose real
  `@SerialName` is `"long_test_edge"`/`"str_test_edge"` (`"test_edge"` is actually `TestEdge`'s,
  the `Uuid` fixture `UuidPerformanceTest` correctly uses). Their 3-hop tests' typed
  `outgoing<LongTestEdge>()`/`outgoing<StrTestEdge>()` hops never matched the mis-typed seeded keys,
  so both silently measured an **empty** traversal every hop instead of real work — explaining the
  suspiciously flat ~0.6-0.7ms they reported regardless of adapter. Fixed the type strings; re-measured
  all three adapters in the same session for a fair comparison: Long ~4.3ms, Uuid/String ~5.4ms
  (statistically indistinguishable from each other run-to-run) — see 3.5/3.6's correction note above.
  README's main Performance table and the "Multi-schema container overhead" narrative updated to match.

- **✅ 4.7 Best-case concurrency counterpart to `AstronomyConcurrencyPerformanceTest`**
  `LongSchemaConcurrencyPerformanceTest` — same `N=1,2,4,8,16,32` sweep/`concurrentBench` harness as
  3.4's Astronomy benchmark, but over a standalone `SingleSchemaGraph`/`LongKeyAdapter` at
  `LongPerformanceTest`'s 10k-node/5-edges-per-node ring-wrap scale instead of Astronomy's ~28-node
  fixture — the architectural opposite tier (no schema tag, no header byte) to contrast against the
  `HeterogeneousSchemaGraph` numbers. Needed an explicit warm-up pass before each sweep (mirroring
  `LongPerformanceTest`'s own convention): this fixture is seeded via direct map puts (fast setup at
  10k-node scale), unlike Astronomy's `transaction { addNode/addEdge }` seeding, which incidentally
  JIT-warms the exact query code path before its sweep starts — without a warm-up, N=1 measured
  ~275 ops/sec (cold JIT), not the ~1,130 ops/sec steady-state figure. **Caveat found while comparing
  results:** the two fixtures differ in topology, not just scale — Long's ring-wrap graph is a uniform
  5-fan-out at every hop (up to 155 nodes touched by one 3-hop traversal), while Astronomy's `Orbits`
  chain is near-linear (degree ~1, moon→planet→star→singularity). This makes `outEdges` (roughly
  fixed cost per call) the cleanest architecture-only signal; `inEdges`/3-hop numbers reflect fan-out
  degree as much as schema overhead and are not directly comparable across the two tables. See
  README's "Concurrency scaling" section.

- **✅ 4.8 Cascade-delete now removes cross-schema edges too**
  `AbyssSchemaWorker.cascadeEdgeRemovals` filtered both its outgoing and incoming scans through
  `SchemaResolution.sameSchema(...)`, silently excluding any edge whose other endpoint carried a
  different schema tag — deleting a node left every cross-schema edge referencing it dangling in the
  cache and store. There was no technical reason for the filter: a cross edge lives in the exact same
  shared `edgesMap`/`reverseEdgesMap` and is committed through the exact same single
  `persistentStore`/`ephemeralStore` pair as an intra-schema edge (one `AbyssSchemaWorker` per
  container, shared across every registered tag). Dropped the filter so cascade removes every edge
  touching the deleted node regardless of schema; `sameSchema` had no other call site anywhere in the
  repo, so it — the interface method, all three implementations, and the private `taggedSameSchema`
  helper — is deleted as dead weight along with it. No existing test asserted the old (buggy)
  survival behavior, so this needed new coverage rather than inverted assertions: `MultiSchemaTest`
  gained cases for both cascade directions (deleting the `from` node, deleting the `to` node) on
  `HeterogeneousSchemaGraph`, plus a same-fix case on `HomogeneousSchemaGraph` (which had zero
  cascade-delete tests of any kind before this). Verified via `NodeIdEngine.outAt` directly rather
  than a node-resolving traversal — once the deleted node is gone, `collectNodes<N>()` can't
  materialize it whether the edge is dangling or properly cascaded, so that style of assertion can't
  tell the two apart.

- **✅ 4.9 Cache `findAnnotation<SerialName>`/`<EdgeConstraint>`/`<CrossSchemaEdge>` lookups**
  `edge::class.findAnnotation<...>()` was re-run via JVM reflection on every call, at ~25 sites across
  `abyss-dsl/Extensions.kt` (every `outgoing<E>()`/`incoming<E>()`/`removeEdge<E>()`/etc. DSL call),
  `AbyssSchemaWorker` (`edgeType`, run per edge in `preloadOut`/`preloadIn`/cache populate; `schemaCheck`,
  run per `AddEdge` during integrity checks), and `CrossSchemaEdgeResolver` (per cross-edge resolution)
  — an annotation is a fixed, compile-time fact about a `KClass`, so re-reflecting on every data item
  processed bought nothing but reflection overhead on paths this codebase otherwise benchmarks closely
  (see 3.5/3.6/4.6/4.7). Added `KClass<*>.cachedAnnotation<A>()` (`abyss-dsl/.../AnnotationCache.kt`, a
  `ConcurrentHashMap`-memoized wrapper around `findAnnotation`) plus a `serialName()` convenience for
  the ubiquitous `findAnnotation<SerialName>()!!.value` / `?.value ?: error(...)` pattern repeated at
  nearly every call site. All call sites now route through it instead of calling `findAnnotation`
  directly; reflection now runs exactly once per edge/node class for the lifetime of the process.
