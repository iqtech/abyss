# RFC — Persist cross-schema edges (TODO / RFC O7)

## Context

Cross-schema edges are cache-only today: `AbyssGraph.addCrossEdge` (`AbyssGraph.kt:111`) writes the
shared Hazelcast `edgesMap`/`reverseMap` and nothing else, and the per-schema store fanout skips
them. The original justification ("a cross edge has two ID shapes, the serializer can't encode it")
was about the **Hazelcast Compact predicate key**, not the DB. The store's edge table keys are
opaque bytes:

```sql
-- ysql-schema.sql:38-40 (comment l.36: "from_id/to_id are raw NodeId bytes")
CREATE TABLE abyss.edges ( from_id BYTEA, to_id BYTEA, type ..., data ..., PRIMARY KEY (from_id, to_id, type) )
-- idx_edges_to_id already exists (l.49) for incoming lookups
```

and the write path already collapses domain id → NodeId bytes immediately
(`YugabytePersistentStore.kt:97,165-166`: `setBytes(adapter.toNodeId(id).bytes)`). A cross edge is
already held as `NodeId → NodeId` in the container — two opaque byte arrays, the exact shape the DB
stores. So persistence is "write the NodeId bytes as-is"; the only real blocker is that the store
API is domain-`ID`-typed (`saveEdge(SchemaEdgeLike<ID>)` runs both endpoints through one adapter)
and the container never fans cross edges out to it.

Goal: cross edges survive cache eviction / restart. `addCrossEdge` keeps its `NodeId, NodeId`
signature (the persistence-native form); we add a NodeId-level store write path and warm cross edges
back on cold reads.

## Design

Cross edges persist into the **existing `edges` table** — no new table. Rows are written at the
NodeId level (bytes as-is, no adapter). Two consequences on the store:

1. **Decode root moves `SchemaEdgeLike` → `RawEdgeLike`.** Every edge class (intra and cross alike)
   is already registered under `polymorphic(RawEdgeLike::class)` (`UniverseFixture.kt:76-78`,
   `GraphTest.kt:70-73`), and the cache serializer already roots there (`AbyssSerializer.kt:68`).
   The store's `edgeSer = PolymorphicSerializer(SchemaEdgeLike::class)` (`YugabytePersistentStore.kt:60`)
   is the odd one out; rooting it at `RawEdgeLike` lets one query decode both kinds (and aligns the
   store with the registration base).
2. **Intra vs cross split by type, not by table.** `edges` now holds both, so the existing
   `loadEdges`/`loadInEdges` filter `.filterIsInstance<SchemaEdgeLike<ID>>()` (their public return
   type is unchanged — no widening), and new NodeId-level `loadCrossEdges`/`loadInCrossEdges` filter
   `.filterIsInstance<CrossEdgeLike<*, *>>()` off the same `WHERE from_id/to_id = ?` query.

**Which store instance.** A cross edge is written to **both** endpoint schemas' persistent stores:
the from-side owns it for outgoing warm (`from_id` partition), the to-side for incoming warm
(`to_id` index). When both schemas resolve to the same store instance (shared DB — the common case),
dedupe to a single write. This is a non-atomic dual-write across distinct stores — acceptable for a
cache-first artifact; noted ceiling. Cross edges are persistent-only (ephemeral/TTL out of scope).

## Components

### 1. `abyss-store-api/.../AbyssStoreLike.kt` — NodeId-level cross methods (additive, defaulted)
- `AbyssStoreLike<ID>`:
  `suspend fun loadCrossEdges(fromNid: NodeId): Either<AbyssError, List<RawEdgeLike<NodeId, NodeId>>> = Either.Right(emptyList())`
  and `loadInCrossEdges(toNid: NodeId)` likewise.
- `AbyssStoreTransactionLike<ID>`:
  `fun saveCrossEdge(fromNid: NodeId, toNid: NodeId, edge: RawEdgeLike<*, *>) {}` and
  `fun deleteCrossEdge(fromNid: NodeId, toNid: NodeId, type: String) {}`.
- No `<ID>` in the signatures → no adapter, schema-agnostic. No-op / empty defaults keep every other
  store (and the test `FakeStore`s) compiling unchanged.

### 2. `abyss-store-yugabyte/.../YugabytePersistentStore.kt`
- `edgeSer` root `SchemaEdgeLike::class` → `RawEdgeLike::class`.
- `queryEdgesYsql` decodes via that serializer; `loadEdges`/`loadInEdges`/`loadEdge` add
  `.filterIsInstance<SchemaEdgeLike<ID>>()` so their contract is unchanged (cross rows excluded).
- New `loadCrossEdges(fromNid)`/`loadInCrossEdges(toNid)`: `SELECT data FROM edges WHERE from_id/to_id = ?`,
  `setBytes(nid.bytes)`, decode, `.filterIsInstance<CrossEdgeLike<*, *>>()`.
- New `PersistentOp.SaveCrossEdge(fromNid, toNid, edge)` / `DeleteCrossEdge(fromNid, toNid, type)`;
  `commitYsql` reuses the `edges` upsert/delete statements with `setBytes(1, fromNid.bytes)` /
  `setBytes(2, toNid.bytes)` (no `nodeIdBytes`/adapter), value via the `RawEdgeLike` serializer.
- `PersistentTransaction` records the two new ops. No DDL change (existing `edges` +
  `idx_edges_to_id`); `ycql`/ephemeral untouched.

### 3. `abyss-graph/.../AbyssGraphSchema.kt` — store hooks + cold warm
- NodeId-level pass-throughs (schema-agnostic, delegate to `persistentStore`, `Right(Unit)` when
  null): `saveCrossEdgeToStore(fromNid, toNid, edge)` (wraps `transaction { saveCrossEdge(..) }`) and
  `deleteCrossEdgeFromStore(fromNid, toNid, type)`.
- Warm cross edges into the shared cache with **raw keys** (branch — cross endpoints are already
  NodeIds, no adapter re-encode):
  - `preloadOut`: after the intra warm, `persistentStore?.loadCrossEdges(nid)?...forEach { edgesMap.putIfAbsent(EdgeKey(it.fromId, it.toId, edgeType(it), it.fromId.toString()), it) }` (compute `nid = adapter.toNodeId(nodeId)`).
  - `preloadIn`: `loadInCrossEdges(nid)` → put the edge + its `reverseEdgesMap` entry, raw keys.
  - ponytail: one extra store round-trip per hop even with no cross edges — same shape as the
    existing `loadEdges` warm, gated on `persistentStore != null`; batch later if it shows up.

### 4. `abyss-graph/.../AbyssGraph.kt` — fan out (store first, then cache)
- `addCrossEdge`: keep gate + `integrityError`; persist to both endpoints' schemas before the cache
  (mirrors intra "store commit → populateCache"):
  ```
  val fromS = resolveSchema(edge.fromId); val toS = resolveSchema(edge.toId)
  fromS.saveCrossEdgeToStore(edge.fromId, edge.toId, edge).onLeft { return it.left() }
  if (toS !== fromS) toS.saveCrossEdgeToStore(...).onLeft { return it.left() }
  // then existing edgesMap.set / reverseMap.set
  ```
- `removeCrossEdge`: symmetric `deleteCrossEdgeFromStore` on both, then cache removal.

## Files
- `abyss-store-api/src/main/kotlin/pl/iqtech/abyss/store/api/AbyssStoreLike.kt`
- `abyss-store-yugabyte/src/main/kotlin/pl/iqtech/abyss/store/yugabyte/YugabytePersistentStore.kt`
- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AbyssGraphSchema.kt`
- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AbyssGraph.kt`
- Tests: extend `GraphTest.kt` fakes; add `MultiSchemaTest` cases + a live-gated `LoadTest` round-trip

## Verification
- **In-memory (CI):** add a cross-aware fake store (extend `WarmingFakeStore`/`FakeStore`,
  `GraphTest.kt:690-736`) recording `saveCrossEdge`/`deleteCrossEdge` and returning cross edges from
  `loadCrossEdges`/`loadInCrossEdges`. New `MultiSchemaTest` cases: (a) `addCrossEdge` records the
  store op on both endpoint schemas; (b) after `edgesMap.clear()`, an outgoing and an incoming cross
  hop still resolve (warmed from the fake store) — proves cold-cache reload; (c) `removeCrossEdge`
  issues the delete. `./gradlew :abyss-graph:test`.
- **Live DB (gated):** a `LoadTest` case: `addCrossEdge` → fresh container / empty cache → cross hop
  resolves from the `edges` table. Run against a live Yugabyte per existing `LoadTest` setup.

## Not doing
- Ephemeral (TTL) cross edges — persistent-only.
- Atomic dual-write across two distinct stores — noted ceiling; single shared-DB dedupes to one write.
- Domain-typed `addCrossEdge(edge, fromTag, toTag)` construction sugar — deferred; the NodeId form is
  what makes "write as-is" work. A `container.nodeId(tag, id)` build helper can land later if the
  manual `SchemaKeyAdapter` boilerplate still annoys.
