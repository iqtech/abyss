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

- **✅ 1.21 `batchTransaction{}` for bulk YSQL loads**
  New independent `batchTransaction{}` API added at every layer (`AbyssEngineLike`/
  `AbyssTransactionLike` DSL → `AbyssGraphSchema` → `AbyssSchemaWorker` → `AbyssStoreLike` →
  `YugabytePersistentStore`), never calling into `transaction()`/`commitYsql` at any layer.
  `AbyssStoreLike.batchTransaction` has a default implementation delegating to `transaction()`
  (unchunked) so the 4 existing test-fake `AbyssStoreLike` implementers needed no changes;
  `YugabytePersistentStore` overrides it with a new `commitYsqlBatched`: one connection for the whole
  call, one `conn.commit()` per `batchSize`-sized chunk (JDBC `addBatch()`/`executeBatch()`,
  run-length-grouped by statement type within a chunk to preserve the caller's original op order
  across mixed add/remove-same-key sequences), plus `reWriteBatchedInserts = true` added to the
  Hikari datasource config. `AbyssSchemaWorker.batchTransaction` runs `expandCascades`/
  `integrityError` ONCE over the full op list before any chunking (chunking only happens inside the
  store), so cascade-deletes and cross-op integrity checks are unaffected by chunk boundaries.
  Trade-off vs `transaction()`: batch commits are not atomic across the whole call — a failing chunk
  rolls back, but chunks already committed before it stay committed (documented, and intended for
  idempotent bulk-load scenarios where `saveNode`/`saveEdge`'s upsert semantics make retrying safe).
  `batchSize` defaults to 1000. Covered by a new `BatchTransactionTest` (worker-level, fake store, in
  `abyss-graph`) and 4 new `LoadTest` cases against a live YugabyteDB (`abyss-store-yugabyte`):
  multi-chunk commit correctness, add-then-remove and remove-then-add same-key ordering preservation,
  and partial-chunk-failure semantics (via a `FailAfterNCommitsDataSource` JDBC proxy forcing a
  deterministic mid-batch failure). YCQL/ephemeral batching stays out of scope (TODO 2.25). Design
  plan: `ai-scripts/BatchTransactionPlan.md`.

- **✅ 1.22 `preloadOut`/`preloadIn` stop re-hitting the persistent store on every warm-cache call**
  `AbyssSchemaWorker.outAt`/`inAt` (plus `outEdgeFlow`/`inEdgeFlow` and `cascadeEdgeRemovals`) called
  `preloadOut`/`preloadIn` unconditionally before every adjacency-cache read, and `preloadOut`/
  `preloadIn` unconditionally called `persistentStore.loadEdges`/`loadInEdges` on every invocation —
  even when the adjacency cache was already fully warm for that node+direction. Every multi-hop
  traversal over a warm cache with persistence enabled paid one YSQL round trip per hop it didn't
  need. Fixed with check-then-load instead of load-then-check: `adjacencyRead` now fetches the node's
  real adjacency shards first (a read it already had to do) and only calls `preloadOut`/`preloadIn`
  if that fetch comes back empty; `outAt`'s edgesMap fast path, `outEdgeFlow`, and
  `cascadeEdgeRemovals` (which all bypass `adjacencyRead`) share a small `ensureOutWarm` helper doing
  the same check. No new persisted state, no new Hazelcast map/config/serializer — `preloadOut`/
  `preloadIn` themselves are unchanged. Accepted, documented ceiling: a node with genuinely zero
  edges in a direction is indistinguishable from "never preloaded" (no shard entry to tell them
  apart), so it retries the store on every call rather than caching "confirmed empty" — this also
  means a transient store failure self-corrects for free (next call just retries) with no special-
  cased success/failure handling needed. An earlier design added a synthetic warm-marker shard key to
  solve this; rejected as unneeded complexity once the simpler check-then-load restructuring covered
  the actual bug. Covered by 3 new `GraphTest` cases (call-count assertions on an extended
  `WarmingFakeStore`, including one locking in the accepted-ceiling behavior) and a new perf test,
  `AdjacencyPreloadPerformanceTest` (`-Pperf`): 500 repeated `outEdges` calls on the same warm node
  went from 500 store hits / 6.18ms avg / 3091ms total before the fix to 1 store hit / 1.91ms avg /
  953ms total after. Design plan: `ai-scripts/AdjacencyPreloadWarmCheckPlan.md`.

- **✅ 1.23 `AbyssStoreLike` has no DB scan/query capability**
  `allNodeIds()` is Hazelcast-cache-only (misses cold/evicted nodes — disqualifying for admin/orphan
  sweeps) and there's no tag-based lookup either; both need the same missing piece: a scan capability
  on `AbyssStoreLike`, which doesn't exist today (point-gets and writes only). YSQL is cheap to fix
  (existing GIN-indexed `tags` column covers tag lookup, no schema change); YCQL is structurally
  harder (secondary indexes conflict with per-row TTL). Full writeup:
  `ai-scripts/StoreScanCapabilityRFC.md`. Design plan: `ai-scripts/StoreScanCapabilityPlan.md`.
  Both of the RFC's open feasibility questions are now answered empirically against the live
  YugabyteDB container, not just assumed:
  - `abyss-store-yugabyte/src/test/kotlin/pl/iqtech/abyss/store/yugabyte/TokenRangeScanFeasibilityTest.kt` —
    confirms YCQL's `TokenMap`/`TokenRange` API (`com.yugabyte:java-driver-core`) correctly enumerates
    every row via token-range scanning, no `ALLOW FILTERING`; covers blocking `execute()`, async
    `executeAsync()` consumed as a `Flow`, and a 4-coroutine `channelFlow` fan-out over disjoint range
    quarters.
  - `abyss-store-yugabyte/src/test/kotlin/pl/iqtech/abyss/store/yugabyte/YsqlPartitionScanFeasibilityTest.kt` —
    confirms YugabyteDB's `yb_hash_code()` (the YSQL-side analog of YCQL's `token()`) exists, is
    deterministic, and is bounded to 0..65535; covers an N-coroutine fan-out over disjoint
    `yb_hash_code` ranges, each coroutine on its own JDBC connection from a pool (`java.sql.Connection`
    isn't thread-safe, unlike YCQL's shared `CqlSession`), merged via `channelFlow`.
  Negative-but-valuable result from the same file: combining `yb_hash_code(id) BETWEEN ? AND ?`
  with the GIN tag lookup (`tags @> ARRAY[?]`) does **not** parallelize a tag-filtered scan — tested
  in `combined yb_hash_code range + GIN tag filter recovers exactly the tagged subset`. `EXPLAIN
  ANALYZE` showed `Index Scan using idx_nodes_tags` with `yb_hash_code(...)` applied as a plain
  `Filter`, not used to prune the index scan (`Rows Removed by Filter: 224` out of 300 total tagged
  rows, for a query meant to only touch ~1/4 of them) — every one of N coroutines running this
  combination independently re-scans the *entire* tag match via the index and discards ~3/4 of it,
  making N-way fan-out strictly worse than one plain tag query, not better. Root cause is structural,
  not a missed optimization: YugabyteDB's secondary indexes (including GIN) are independently
  sharded by the *indexed expression* (the tag value), not by the base table's primary-key hash, so
  `yb_hash_code(id)` and the GIN index's own partitioning are unrelated dimensions with nothing to
  prune against — the `Filter` already runs per-row inside the index-scan loop, it just can't skip
  visiting an entry ahead of time. Whether YugabyteDB could do something smarter here is worth an
  upstream question, not something to design around today. Conclusion: hash-range fan-out (unfiltered
  sweep) and GIN tag lookup (already fast alone) solve different problems and don't compose — if a
  tag-filtered result set is ever large enough to need client-side parallel consumption, the right
  chunking key would be something over that result set itself (e.g. keyset pagination on `id`), not
  the whole table's hash space.
  De-risks the design; design plan now written (`ai-scripts/StoreScanCapabilityPlan.md`), ready for build.
  Shipped per that plan: `scanNodeIds(tag?, parallelism)`/`scanEdgeIds(parallelism)` safe defaults
  (`= emptyFlow()`) added to `AbyssStoreLike`/`AbyssEphemeralStoreLike`. `YugabytePersistentStore`
  implements both — `tag == null` fans out over `yb_hash_code(id)` ranges via `channelFlow`;
  `tag != null` is the single plain GIN `tags @>` query, no fan-out (per the proven non-composition
  finding above). `YugabyteEphemeralStore.scanNodeIds` does the proven token-range `channelFlow`
  fan-out over `ephemeral_nodes`, filtering `tag` **client-side** against the row's `tags` column
  (not a CQL `WHERE tags CONTAINS ?`, which would need `ALLOW FILTERING` — the column has no
  secondary index). `HazelcastEphemeralStore.scanNodeIds` is a plain `ephNodes.keys` enumeration;
  `tag != null` returns `emptyFlow()` since `NodeLike<ID>` carries no tags field post-1.24 (tags live
  in the DB table only, not cache-resident — structurally unfilterable here, not a judgment call).
  `AbyssSchemaWorker.scanNodeIds` merges `persistentStore`+`ephemeralStore` via `channelFlow`; a tag
  filter with no `persistentStore` configured fails loudly (`error(...)`, this codebase's existing
  fail-fast convention) rather than silently dropping the filter. `scanEdgeIds` delegates to
  `persistentStore` only (`emptyFlow()` if absent) — ephemeral edges are TTL'd/outgoing-only, not an
  orphan concept. Exposed as plain (non-`NodeIdEngine`) methods on `HomogeneousSchemaGraph`/
  `HeterogeneousSchemaGraph`, unscoped by schema tag, deliberately not added to
  `AbyssGraphSchema`/`AbyssEngineLike<ID>`/`SingleSchemaGraph`. Covered by new `LoadTest` cases
  (live YugabyteDB: untagged YSQL scan, GIN-tagged YSQL scan, `scanEdgeIds`, tagged/untagged YCQL
  scan), a new `HazelcastEphemeralStoreTest` case, and a new `ScanCapabilityTest` (worker merge with
  no dupes/drops, ephemeral-only fallback, tag-without-store fail-loudly, `scanEdgeIds`
  persistent-only delegation, unscoped-by-tag contract across 2 registered schemas).

- **✅ 1.24 Move tags off domain objects and into the table; add `tags` param to `transaction{}`**
  Remove tags from domain objects, keep them inside the table (backing store column, not a
  node/edge property). Introduce an additional parameter to `transaction { }` — `tags` — so each
  added/modified element can carry a set of tags. Tags are system-wide (e.g. indexing/admin
  metadata), not user-specific domain data.
  Design plan: `ai-scripts/TransactionTagsPlan.md`.
  Follow-up: writes are additive, not full-replace — YSQL upserts union tags via
  `ARRAY(SELECT DISTINCT UNNEST(...))` against the existing row, YCQL uses `UPDATE ... SET tags =
  tags + ?`, both in the same single write statement (no extra read, no second op/path). YCQL's
  `tags` column changed from `LIST<TEXT>` to `SET<TEXT>` so repeated appends dedupe at the DB level.
  Tag removal is intentionally not implemented — an operational task done directly via
  `cqlsh`/`ysqlsh` when needed, not a code path.

- **✅ 1.25 Cold-hub preload resolves neighbor tags from the edge scan, not per-neighbor reads**
  `preloadOut`/`preloadIn` called `readNode(neighbor)` per edge purely to fill `AdjacencyEntry.nodeTypeTag`
  (the neighbor's `@TypeTag`, consumed by typed-traversal fetch avoidance — see `nodeTypeTag` intent).
  On a cold hub that was O(E) sequential YSQL point-reads inside the first `outEdges`/`inEdges` call.
  Fixed via "the tag rides the edge scan" (Solution 1, chosen over batching/denormalizing/deferring):
  `StoredEdge` gained `neighborType: String?`; YSQL `loadEdges`/`loadInEdges` add one
  `LEFT JOIN nodes ON n.id = <neighbor_col>` returning the neighbor's `@SerialName` in the same round
  trip (indexed PK join → probes not round trips; dangling edge → null); `TypeTagRegistry.nodeTagOf`
  maps that name → `@TypeTag`; preload resolves the tag from it with zero neighbor reads (null → typed
  traversal's existing fetch fallback). Not the reverted null-the-tag approach — the tag stays correct
  and present, so typed traversal stays fast on preloaded hubs. Measured (`AdjacencyPreloadPerformanceTest`,
  300-edge cold hub): **300 neighbor reads / ~900ms → 0 reads / 107ms**; typed-traversal-after-warm
  fetches 0 (was 300), dangling variant fetches only its null-tag neighbors. Covered by that perf/
  integration test (count==N proves tag correct, loadNode==0 proves it came from the scan), a
  `nodeTagOf` unit test, and live-Yugabyte `LoadTest` cases for the JOIN incl. dangling→null.

- **✅ 1.26 `adjacencyRead` has no pagination; public `pageSize` is silently dropped**
  `adjacencyRead` (`AbyssSchemaWorker.kt:164`) is fully unbounded: it `getAll`s every shard, flattens
  all neighbors into one `List<Hop>`, and for `needValue=true` does a single `getAll` over every edge
  key. No limit/offset/cursor exists. Worse, the public API advertises paging and discards it —
  `AbyssEngineLike.outEdges/inEdges(nodeId, pageSize=100)` is implemented by `AbyssGraphSchema` (`:115`)
  as `worker.outEdges(nid)` with `pageSize` never passed on, and the returned `Flow` is lazy in name
  only: `outEdgeFlow` emits from a fully-materialized Hazelcast `values(predicate)` and `inEdgeFlow`
  from the whole `adjacencyRead` list. A caller paging a supernode still drags every edge (plus an
  N-key `getAll`) into one member's heap before the first emit.
  **Milestone 1 (done):** introduced the pluggable `AdjacencyIndex` seam (`AdjacencyIndex.kt`) with
  `ShardedAdjacencyIndex` — the existing Set-per-shard structure, but reads walk shards in bounded
  **windows** instead of one `getAll` over all shards (the fallback strategy; see
  `ai-scripts/AdjacencyIndexInterfaceRFC.md`). `inEdges` is now bounded/streamed and honors `pageSize`
  (batched value fetch); `isEmpty` warm-check is first-window-bounded. Per-edge and edgesMap-index
  shapes rejected on memory/scatter-gather math.
  **Follow-up (a) — done:** `outEdges` is bounded/paged via `adjacencyEdgeFlow(OUT)` and honors
  `pageSize`. Ephemeral (TTL) out-edges now carry an **OUT adjacency entry** (IN still absent —
  outgoing-only) so they ride the same paged index — cache-native, works with or without an ephemeral
  store (the plan's "read ephemeral from the store" idea was dropped: ephemeral can be cache-only). An
  expired edge leaves a stale entry that reads back null and is skipped. Measured
  (`OutEdgePagingPerformanceTest`, 2000-edge hub, pageSize=100): peak single edges-map materialization
  **2000 → 100**; `.take(1)` materializes **2000 → 100**.
  **Follow-up (b) — done:** `outAt`/`inAt` return `Flow<Hop>` (`NodeIdEngine`); `TraversalBuilder`
  consumes them streamed — `filterFrontierByEdge`/`ByEdgeType` short-circuit via `firstOrNull`,
  `addHop`/`addNodeHop` fold each node's hops into the accumulator instead of holding every node's list
  then flattening, `countEdges` → `Flow.count`, and `paths` DFS/BFS `collect` the hop flow (`BOTH` =
  out-then-in concat). Fast-path typed-OUT+needValue stays materialized (can't page an `entrySet`).
  Measured (`HopStreamingPerformanceTest`, `hasOutgoing` over a 2000-edge hub, match in shard 0):
  reads past the match's window **1 → 0** getAll calls. Non-goal (documented): `allTraversedHops` still
  accumulates every hop for `subgraph` (TODO 1.2), O(total edges) by design.
  **Follow-up (c):** Milestone 2 — paging-native `PagedAdjacencyIndex` (ordered K-page) behind the same
  seam — is TODO 3.11, gated on measuring supernode write-hotness + delete rate.

- **✅ 1.27 Index-always-alive + reliable ephemeral traversal (ship as ONE commit)**
  Implement two dependency-ordered RFCs together in a single commit — Phase 1 is the precondition for
  Phase 2, so they land atomically.
  **Phase 1 — ephemeral store-only** (`ai-scripts/EphemeralTraversalReliabilityPlan.md`): revert the
  1.26a adjacency hack; ephemeral (TTL) edges leave the adjacency index **and** `edgesMap` → store-only
  (durable YCQL); `loadAndCacheEdge` stops re-caching ephemeral; traversal reaches them only via an
  explicit `includeEphemeral` flag (OUT-only, default false) that reads the ephemeral store (survives
  eviction). Persistent fast path kept. Cache-only ephemeral (no store) unsupported. Postcondition:
  `adjacency` and `edgesMap` are both persistent-only.
  **Phase 2 — index always alive** (`ai-scripts/IndexAlwaysAliveRFC.md`): the adjacency index is the
  authoritative in-memory topology, **never evicted (this is the DEFAULT)**; `nodesMap`/`edgesMap` are
  evictable caches over the store. Shared read-path edit: `adjacencyHopFlow`'s value fetch flips
  skip-null → **batched self-heal-null** from `persistentStore` (a null with a present adjacency entry =
  evicted, not removed — reload). `needValue=false` traversal becomes pure-index (no value/store reads).
  **Startup fail-fast guard**: refuse to boot if eviction/TTL is configured on the adjacency map (partial
  eviction is invisible to the per-node warm-check → silently incomplete topology). Cache-mode
  (evictable adjacency) stays available behind the `AdjacencyIndex` seam for topology-exceeds-memory, but
  is NOT the default.

- **✅ 1.28 Hide raw traversal primitives behind a `TraversalScope` facade**
  `TraversalBuilderLike<ID>` mixed raw primitives (`addHop`, `addNodeHop`, `filterFrontierByNode`,
  `filterFrontierByOutEdgeTo(Type)`, `filterFrontierByInEdgeFrom(Type)`, `filterFrontierByTraversal`)
  with typed/structural members (`count()`, `countEdges`, `collectSubgraph`, `flushFrontierNodes`,
  `flushHopEdges`, `checkReaches`, `pathTo`, `exhaustReachable`, `detectCycle`, `paths(...)`). Every
  raw primitive already had a reified, type-safe wrapper in `Extensions.kt`, but the raw methods
  themselves were public on the interface and autocompleted/compiled identically to the sugar
  inside any DSL block — `@DslMarker` didn't hide them (verified by compile: it only fixes
  implicit-receiver leakage across nested same-marked blocks, not direct calls). Added a
  `TraversalScope<ID>` facade (public constructor, `@PublishedApi internal val raw` — same pattern
  as `AnnotationCache.kt`) that is now the DSL block receiver everywhere (`from`, `checkReaches`,
  `pathTo`, `exhaustReachable`, `detectCycle`, `hasTraversal`); `TraversalBuilderLike<ID>` stays
  unchanged as the public impl contract `TraversalBuilder` implements (required — implementer lives
  in a different Gradle module). Only `addHop`/`addNodeHop`/`filterFrontierBy*` ended up hidden
  behind `.raw` — `flushFrontierNodes`/`flushHopEdges`/`countEdges`/`collectSubgraph` were pulled
  back to plain pass-through members on `TraversalScope` after implementation surfaced
  `TraversalTest.kt`, which legitimately calls `flushFrontierNodes()` directly as a `from(){}`
  block's last expression (documented raw-`Flow` behavior collected by the caller after `from`
  returns) — hiding it wasn't part of what was actually asked, only "filter*, addHop etc." was.
  Also removed the `@DslMarker`/`TraversalDsl` annotation added earlier — dead weight once
  `TraversalBuilderLike` was no longer a lambda-receiver type anywhere in the DSL surface. Dedicated
  regression coverage added in `TraversalScopeTest.kt`. Full design, deviations, and file-by-file
  changes: `ai-scripts/TraversalScopeFacadePlan.md`.

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
  Fixed (follow-up, TODO 1.23): `exportGraphLines` still walked only `allNodeIds()`
  (Hazelcast-cache-only), so a cold/evicted node silently dropped out of every export — the exact
  reliability gap 1.23's `scanNodeIds` was built to close, but export wasn't wired to it yet. Fixed
  by sourcing ids from `merge(allNodeIds(), scanNodeIds())` (deduped via a `seen` set), not either
  alone: `scanNodeIds()` alone would export nothing for a graph with no `persistentStore` configured
  (common in pure-cache/test setups, since it never reads the cache); `allNodeIds()` alone still
  misses cold/evicted nodes on a real persisted graph. Receiver narrowed from `AbyssEngineLike<ID>`
  to the concrete `AbyssGraphSchema<ID>` (its sole production implementer — `SingleSchemaGraph` and
  both containers' `register()` all return it) since the new per-schema `scanNodeIds()` (mirrors
  `allNodeIds()`'s `ownsNodeId` filter + `fromNodeId` conversion, sourced from
  `worker.scanNodeIds()`) is a plain method there, not on the general interface — same boundary
  `HomogeneousSchemaGraph`/`HeterogeneousSchemaGraph`'s own `scanNodeIds`/`scanEdgeIds` already draw.
  `outEdges(id)` per node needed no change — already store-backed/self-healing (TODO
  1.20/1.22/1.26). Covered by a new `ScanCapabilityTest` case planting a node directly into a fake
  store (never added via `transaction`, so genuinely never cache-warm) and confirming
  `exportGraphLines` recovers it alongside a normally-committed node.

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

- **✅ 2.22 Traversal DSL: `collectEdges<E>()` terminal**
  Added `flushHopEdges(): Flow<EdgeLike<*, *>>` to `TraversalBuilderLike` (mirrors
  `flushFrontierNodes()`) plus `collectEdges<E>()` DSL sugar (mirrors `collectNodes<N>()`).
  `TraversalBuilder` tracks the most recent `addHop`/`addNodeHop`'s own hops separately from the
  cumulative `allTraversedHops`, filtered at flush-time by `it.target(dir) in frontier` so a later
  frontier-narrowing filter (`nodes<N>()`, `hasOutgoing`, …) correctly narrows `collectEdges` too,
  not just the raw unfiltered hop. Supporting refactor: `resolveHopEdges` now returns
  `Map<Hop, EdgeLike<*, *>>` instead of a positional `List` (the old `mapNotNull` could silently
  drop a hop on resolution failure, desyncing any positional pairing) — `collectSubgraph`/
  `exhaustReachable` updated to `.values.toList()`, unchanged behavior. Covered by `TraversalTest`.

- **✅ 2.23 Traversal DSL: `pathTo(targetId, block)` convenience**
  Originally scoped as `shortestPath(targetId)`, renamed after review: this model has no edge
  weights, so there's no Dijkstra to run — what's achievable is the fewest-hops path (BFS-optimal
  by edge count), and `shortestPath` overclaimed a guarantee the API doesn't make. `pathTo` mirrors
  `reaches`/`checkReaches`'s exact signature shape (`targetId, block`) and BFS-by-block structure —
  deliberately not built on `paths()` (untyped/any-edge, would ignore the caller's typed hop
  `block`). Unlike `checkReaches`'s single batched sub-traversal per BFS level, `pathTo` runs one
  single-node sub-traversal per current-frontier entry so each hop's edge can be attributed back to
  the specific path that produced it. Matches `checkReaches`'s contract exactly: a target already
  in the starting frontier does not count as reached (only a hop-away match does). Covered by
  `TraversalTest` (fewest-hops-among-multiple-routes, unreachable → `null`, already-in-frontier
  parity with `checkReaches`).

- **➡️ 2.24 Traversal DSL: negated connectivity filter**
  `hasOutgoing<E>(toId)`/`hasOutgoing<E, N>()` (and the `hasIncoming` symmetric pair) keep frontier
  nodes that *have* a matching edge. There's no way to keep nodes that *don't* (e.g. "users who
  haven't purchased anything") — today that requires computing the positive set and diffing it
  outside the DSL. Add a negated form (e.g. a `negate: Boolean` param or `hasNoOutgoing`/
  `hasNoIncoming` variants).

- **➡️ 2.25 Batch ephemeral (YCQL) writes**
  `YugabyteEphemeralStore.commitYcql` is strictly one `ycql.execute()` per op, serial and blocking;
  `SaveNode`/`SaveEdge` also build ad-hoc `SimpleStatement` text per call instead of a cached
  `PreparedStatement` like deletes do. Driver already supports `BatchStatement`/`BatchStatementBuilder`
  (`com.yugabyte:java-driver-core:4.19.0-yb-1`, DataStax 4.x line) — no new dependency needed.
  Partition-key-aware plan required, not a blanket batch:
  - `ephemeral_edges`: partition key is `from_id` — group same-`from_id` ops into one UNLOGGED
    single-partition `BatchStatement`, genuine win.
  - `ephemeral_nodes`: partition key is `id` (every node its own partition) — cross-partition
    `BatchStatement` is an anti-pattern here (adds coordinator/batchlog overhead, defeats
    token-aware routing). Use `executeAsync()` fan-out with bounded concurrency instead, not
    `BatchStatement`.
  Do not touch this yet.

- **✅ 2.26 Traversal DSL: `from(<set of nodes>)` entry point**
  `from(nodeId, block)` (`AbyssGraphSchema.kt`) only seeds a traversal frontier from a single id —
  `TraversalBuilder(traversalEngine, setOf(adapter.toNodeId(nodeId)), adapter).block()`. `TraversalBuilder`
  already accepts a multi-node frontier (`Set<NodeId>`) internally, so a `from(nodeIds: Set<ID>, block)`
  overload is wiring, not new machinery — same shape already scoped for TODO 2.12's index-seeded entry
  point, but useful standalone (e.g. seeding a frontier from `scanNodeIds`/`scanEdgeIds` results, or any
  caller-assembled id set) without waiting on 2.12's column-index machinery.

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

- **➡️ 3.10 Type-level `SchemaGraph` model for schema visualization**
  Derive a `NodeType --EdgeType--> OtherNodeType` graph straight from `@TypeTag`/`@SerialName`/
  `@EdgeConstraint` annotations, for visualizing the schema itself rather than instance data.
  `Subgraph`/`GraphExport` can't be reused as-is — they hold real `NodeLike`/`EdgeLike` instances,
  serialized via the polymorphic serializer, and a bare `KClass` has no value to serialize. Needs a
  parallel type-level shape (`SchemaNodeType`/`SchemaEdgeType`/`SchemaGraph`), built by reusing
  `TypeTagRegistry`'s existing `SubclassCollector` walk. Edge types with no `@EdgeConstraint`, or
  with either `fromTypes`/`toTypes` side empty, are dropped from the strict edge list and reported
  separately by name. Full design: `ai-scripts/SchemaGraphVisualizationPlan.md`.

- **➡️ 3.11 Milestone 2: paging-native `PagedAdjacencyIndex` behind the `AdjacencyIndex` seam**
  The "different engine" from TODO 1.26's M1 (which shipped the seam + `ShardedAdjacencyIndex`
  fallback). Replaces hash-shards with ordered, degree-adaptive K-pages (`key = (owner, direction,
  pageNo)` pinned to the owner, value = up to K entries): a read is a bounded page-walk with a real
  keyset cursor, and it uses *less* memory than the Set at large degree (bigger chunks amortize
  Hazelcast's ~100 B/entry overhead better). Opt-in per graph; drops in behind the existing interface
  with no worker change. **Gated on two measurements before building:** how write-hot a single
  supernode's tail is, and how delete-heavy the graph is — those decide ordered-split pages (keyset,
  in-place delete, harder writes) vs. append-log (trivial writes, positional cursor, tombstone +
  compaction). Warm bounding of the cold path uses an injected page-loader + value-sink, not a store
  handle in the engine (keeps the 1.25 single-scan co-warm). Design:
  `ai-scripts/AdjacencyIndexInterfaceRFC.md`.

- **✅ 3.12 `dfsCycle` unbounded recursion depth (fable.md 2.5)**
  `TraversalBuilder.detectCycle`'s private `dfsCycle` helper (`TraversalBuilder.kt:265`) recurses
  one call per node with no depth cap. Each recursive call routes through `sub.block()` ->
  `addHop`'s `coroutineScope { async(engine.hopDispatcher) }.awaitAll()`, a genuine suspension
  point — so this does *not* overflow the native JVM stack (Kotlin's CPS transform unwinds it);
  instead it chains one heap-allocated `Continuation` object per recursion depth (holding `nodeId`,
  `visited`, `inStack`, `block`, `sub`). A long chain (e.g. 50k+ linearly-connected nodes) means
  50k live chained continuations for the traversal's duration — unbounded, unmonitored heap growth
  that could surface as `OutOfMemoryError`, not a classic `StackOverflowError` despite the naive
  recursive-function mental model suggesting one. Distinct from 2.4.2's parallelization note
  above (line ~866) — that one is throughput (fan out DFS siblings across `hopDispatcher`) and is
  arguably in tension with this one, since parallelizing siblings adds concurrent branches on top
  of the existing recursive depth rather than reducing it.
  Fix: rewrite as an explicit-stack iterative DFS (white/gray/black coloring via `visited`/
  `inStack`), same frontier-loop shape `exhaustReachable`/`checkReaches` already use elsewhere in
  this file. Neighbor-list computation still needs one suspend call (`sub.block()`) per
  newly-discovered node — compute it once at push time and store `neighbors.iterator()` in the
  explicit stack frame, so the driving `while` loop's `hasNext()`/`next()`/backtrack is plain
  synchronous code with zero recursion. Memory becomes a resizable `ArrayList`-backed stack instead
  of a continuation chain.
  Verify with a synthetic linear chain of ~50k-100k nodes (fake in-memory `NodeIdEngine`, no real
  Hazelcast/store needed) through `detectCycle` — demonstrate the unbounded continuation-chain
  growth pre-fix, clean bounded pass post-fix.

- **✅ 3.13 `allNodeIds()`/`allNodeIdsRaw()` block off-dispatcher and are undocumented full scans (fable.md 3.2)**
  `AbyssSchemaWorker.kt:141,269` call `nodesMap.keys` directly inside a `flow { }` builder — that
  Hazelcast call runs on the caller's coroutine context, unlike every other Hazelcast call in this
  file, which already wraps in `withContext(Dispatchers.IO)` (e.g. `outAtPersistent`,
  `resolveEdges`). Two-part fix, per fable.md's own framing:
  1. Wrap in `withContext(Dispatchers.IO) { nodesMap.keys }.forEach { emit(it) }` — `emit()` must
     stay *outside* the `withContext` block (Flow's context-preservation rule; the existing
     precedent in this file already gets this right).
  2. Document the memory profile: `nodesMap.keys` eagerly materializes the *entire* key set into
     memory before the flow starts emitting — not a streaming/paginated scan. At README-cited scale
     (36k users × 500 nodes = 18M keys) that's a multi-GB in-memory `Set`. The dispatcher fix does
     not reduce this footprint, only keeps the blocking fetch off the caller's thread.
     `connectedComponents` (`abyss-dsl/Extensions.kt`) has zero doc comment today and needs the
     caveat; `GraphExport.kt`'s `exportGraphLines` already references `connectedComponents`'s
     access pattern in its own comment, so a one-line pointer there is enough.
  Avoiding the full materialization itself is out of scope — already tracked as TODO 1.23
  (`AbyssStoreLike` has no DB scan/query capability), still open, no design plan yet.

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

- **➡️ 4.10 Traversal DSL: bidirectional single-hop combinator**
  `outgoingAny()`/`incomingAny()` are untyped (any edge type) hops in *one* direction. There's no
  "either direction of type E" combinator — following an edge type regardless of whether it points
  in or out currently requires two separate hops and manually merging frontiers.

- **➡️ 4.11 Traversal API: multi-source `from(nodeIds: Set<ID>, block)` entry point**
  Only a single-ID `from(nodeId, block)` entry point exists (`AbyssGraphSchema.kt:126`). Overlaps
  with TODO 2.12's already-tracked `from(nodeIds: Set<ID>, block)` overload (needed there to seed a
  traversal frontier from indexed-query results) — noting it here too since it's also a
  traversal-API gap on its own, independent of the indexed-query feature.

- **➡️ 4.12 Other `TraversalBuilder` methods share `pathTo`'s pre-fix serial-fan-out bottleneck**
  `pathTo` (`TraversalBuilder.kt:366`) was fixed in two passes — entry-level fan-out (many frontier
  nodes processed one at a time) and per-entry fan-out (many neighbors of one high-degree node
  processed one at a time) — both replaced with `coroutineScope`/`async(engine.hopDispatcher)`/
  `awaitAll()`. The same two bottleneck shapes exist, unfixed, in several sibling methods in this
  file:
  - `flushFrontierNodes` (`TraversalBuilder.kt:158`) — `for (nid in frontier) engine.nodeAt(nid)`
    is a plain sequential loop; `collectSubgraph`/`exhaustReachable` already do the parallel
    version of exactly this (`TraversalBuilder.kt:176`, `197`) so this is a one-line-shape fix.
  - `addNodeHop` (`TraversalBuilder.kt:76`) — frontier nodes already fan out in parallel, but
    inside each one, `hops(...).filter { engine.nodeAt(hop.target(direction)) ... }` (line 81)
    resolves every hop's target node sequentially. A single frontier node with high out-degree
    (supernode) pays one round trip per neighbor with no other frontier node to hide behind —
    the exact case TODO's `pathTo` supernode fix addressed.
  - `filterFrontierByEdgeType` (`TraversalBuilder.kt:119`) — same shape at line 123
    (`hops(...).any { engine.nodeAt(...) }`); `.any` short-circuits on the first type match, so
    it's only the worst case (no match, or a late match) that pays the full sequential cost.
  - `paths()` (`TraversalBuilder.kt:227`), both strategies — `dfsLoop` (line 252) and `bfsLoop`
    (line 297) each call `engine.nodeAt(nextNid)` (lines 272, 316) inside a sequential `for (hop in
    edges)` loop over one node's hop list — the per-entry bottleneck. `bfsLoop` additionally
    processes its queue one `Entry` at a time (`queue.removeFirst()`, line 308) with no fan-out
    across entries at the same conceptual depth — the entry-level bottleneck, i.e. `bfsLoop` has
    both of `pathTo`'s pre-fix problems at once. `dfsLoop`'s per-hop `nodeAt` loop is fixable the
    same way; its recursive depth-first structure is not a good fit for entry-level fan-out (would
    change DFS ordering/early-exit semantics), so leave that part alone.
  - `detectCycle`/`dfsCycle` (`TraversalBuilder.kt:202`) — siblings at each DFS level are visited
    one at a time (`for (neighbor in sub.frontier)`, line 219), each potentially triggering a full
    recursive sub-search before the next sibling starts. Same entry-level shape as the others, but
    parallelizing it safely needs care: `visited`/`inStack` are shared mutable state read *during*
    the fan-out (not just merged after, like `pathTo`'s fix does), and the early "cycle found" exit
    would need to cancel sibling coroutines rather than just skip remaining loop iterations.
  Fix shape for the straightforward cases: same `coroutineScope { xs.map { async(engine.hopDispatcher)
  { ... } } }.awaitAll()` pattern already used four times in this file. Worth doing given the
  public/unknown-graph-shape performance stance — see `pathTo`'s benchmark files
  (`PathToPerformanceTest.kt`, `-Pperf`) as the template: isolate with a synthetic supernode/wide-
  frontier graph, measure baseline, fix, remeasure.

- **✅ 4.13 Widen container.transaction into a multi-schema transaction**
  `HeterogeneousSchemaGraph`/`HomogeneousSchemaGraph`'s `container.transaction { }` is currently
  cross-edge-only (`CrossSchemaTransactionLike`: `addCrossEdge`/`removeCrossEdge`) — no way to
  atomically `addNode`/`addEdge`/`modifyNode`/etc against two or more registered schemas plus a
  cross edge in one commit, even though the underlying `AbyssSchemaWorker.transaction(ops:
  List<NodeOp>)` is already schema-agnostic and shared by every schema registered on a container.
  Plan: widen `CrossSchemaTransactionLike`/`CrossSchemaTransactionBuffer` in place into
  `MultiSchemaTransactionLike`/`MultiSchemaTransactionBuffer` with an added `on(schema)` accessor,
  rather than adding a second parallel transaction method. Full plan in
  `ai-scripts/multi-schema-transaction.md`.
