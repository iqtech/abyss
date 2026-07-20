# Move tags off domain objects; add per-op `tags` param to transaction{}/ephemeral{}

TODO 1.24.

## Context

Today `tags: List<String>` is a required property on `NodeLike<ID>`/`EdgeLike<FID,TID>` themselves
(`abyss-store-api/.../Model.kt:9,19`) — every domain class declares `override val tags`. The value is
write-only: `YugabytePersistentStore`/`YugabyteEphemeralStore` bind `op.node.tags`/`op.edge.tags` into
a real, GIN-indexed `tags TEXT[]` YSQL column and an unindexed YCQL column, but **nothing reads it
back** — `loadNode`/`loadEdge` `SELECT data` only, never `tags` (confirmed: no `rs.getArray`/`getString
("tags")` anywhere). Because `tags` is on the polymorphic interface, it's also serialized a second,
redundant time inside the JSONB `data` blob via `PolymorphicSerializer(NodeLike::class)`.

Goal: tags become store/table-level metadata only — never a field callers set on a domain object —
supplied per add/modify operation as an explicit `transaction { }` (and `ephemeral { }`) parameter.
Purpose is system-wide bookkeeping (e.g. admin/orphan-sweep scans per TODO 1.23), not domain data.

**Precedent to copy exactly: `ttl`.** `ttl: Duration?` already flows this way — never a field on
`NodeLike`/`EdgeLike`, purely an op-level parameter threaded `Op` → `NodeOp` →
`AbyssStoreTransactionLike.saveNode/saveEdge(..., ttl)`. `tags` becomes a second such parameter,
following the identical path.

## Design

**Granularity: per-op, not per-transaction-block.** Unlike `ttl` (one value for the whole
`ephemeral { }` block, since TTL is a block-level concept), the TODO asks for "each added/modified
element" to carry its own tag set — different nodes/edges in the same `transaction { }` call may need
different tags. So `tags` is a parameter on `addNode`/`addEdge`/`modifyNode`/`modifyEdge`/
`addCrossEdge` individually, defaulting to `emptySet()`, not a single value captured once per block.

**Semantics: full replace, no read-modify-merge.** `modifyNode`/`modifyEdge`'s `transform` lambda
operates on the fetched *domain* value only (`NodeLike<ID>?`) — since tags no longer live on that
object, there is nothing to merge from. Omitting `tags` on a `modifyNode`/`modifyEdge` call therefore
writes `tags = emptySet()`, replacing whatever was there — consistent with how `data`/`updated_at`
already fully replace on every upsert (`ON CONFLICT DO UPDATE SET ... tags = EXCLUDED.tags`). This is
the only structurally sound default without adding a second store read per modify purely to fetch
current tags — no such read path exists today anyway (TODO 1.23 is exactly "no scan/query capability
yet"). Document this plainly; do not build merge machinery for a value nothing can currently read back.

**No cache impact.** Hazelcast `nodesMap`/`edgesMap` hold the domain object directly. Once tags leave
the domain object, the cache carries no tags at all — by design, matching "tags live in the table,
not the graph." Nothing in `AbyssSchemaWorker`'s cache population needs to change.

## Implementation steps

**1. `abyss-store-api/.../Model.kt:9,19`** — delete `val tags: List<String>` from both `NodeLike` and
`EdgeLike`.

**2. `abyss-dsl/.../AbyssEngineLike.kt`** — add `tags: Set<String> = emptySet()` to every op-adding
method on both transaction interfaces (default param, so existing call sites keep compiling):
```kotlin
interface AbyssTransactionLike<ID> {
    fun addNode(node: NodeLike<ID>, tags: Set<String> = emptySet())
    fun removeNode(id: ID)
    fun addEdge(edge: EdgeLike<ID, ID>, tags: Set<String> = emptySet())
    fun removeEdge(fromId: ID, toId: ID, type: String)
    fun addCrossEdge(edge: EdgeLike<*, *>, tags: Set<String> = emptySet())
    fun removeCrossEdge(edgeClass: KClass<out EdgeLike<*, *>>, fromId: Any?, toId: Any?)
    suspend fun modifyNode(id: ID, tags: Set<String> = emptySet(), transform: (NodeLike<ID>?) -> NodeLike<ID>)
    suspend fun modifyEdge(fromId: ID, toId: ID, type: String, tags: Set<String> = emptySet(), transform: (EdgeLike<ID, ID>?) -> EdgeLike<ID, ID>)
}
```
Mirror identically on `AbyssEphemeralTransactionLike` (lines 63-72). `removeNode`/`removeEdge`/
`removeCrossEdge` are untouched — nothing to tag on a delete.

**3. `abyss-graph/.../AbyssGraphSchema.kt:266-328`**
- `Op` sealed interface (`:266-273`): add `tags: Set<String>` to `AddNode`, `AddEdge`, `AddCrossEdge`:
  ```kotlin
  data class AddNode(val node: NodeLike<*>, val ttl: Duration?, val tags: Set<String>) : Op
  data class AddEdge(val edge: EdgeLike<*, *>, val ttl: Duration?, val tags: Set<String>) : Op
  data class AddCrossEdge(val edge: EdgeLike<*, *>, val ttl: Duration?, val tags: Set<String>) : Op
  ```
- `Op.toNodeOp` (`:276-283`): pass `tags` through into the corresponding `NodeOp` constructor.
- `BufferedTransaction`/`BufferedEphemeralTransaction` (`:285-328`): thread the new `tags` param from
  each overridden method into the `Op` it appends, e.g.
  `override fun addNode(node: NodeLike<ID>, tags: Set<String>) { ops += Op.AddNode(node, null, tags) }`,
  and for `modifyNode`/`modifyEdge`, into the resulting `Op.AddNode`/`Op.AddEdge` (the `Op.RemoveEdge`
  half of `modifyEdge` needs no tags — deletes don't carry them).

**4. `abyss-graph/.../AbyssSchemaWorker.kt:43-49`** — add `tags: Set<String>` to `NodeOp.AddNode`/
`AddEdge` (`RemoveNode`/`RemoveEdge` untouched, same as `ttl`):
```kotlin
internal sealed interface NodeOp {
    val ttl: Duration?
    data class AddNode(val id: NodeId, val node: NodeLike<*>, override val ttl: Duration?, val tags: Set<String>) : NodeOp
    data class RemoveNode(val id: NodeId) : NodeOp { override val ttl: Duration? get() = null }
    data class AddEdge(val fromId: NodeId, val toId: NodeId, val edge: EdgeLike<*, *>, override val ttl: Duration?, val tags: Set<String>) : NodeOp
    data class RemoveEdge(val fromId: NodeId, val toId: NodeId, val type: String) : NodeOp { override val ttl: Duration? get() = null }
}
```
Update `applyPersistentOp`/`applyEphemeralOp` (`:369-381`) to pass `op.tags` into the store calls
(next step's new signatures):
```kotlin
is NodeOp.AddNode -> saveNode(op.id, op.node, op.tags)                    // persistent
is NodeOp.AddEdge -> saveEdge(op.fromId, op.toId, op.edge, op.tags)        // persistent
is NodeOp.AddNode -> saveNode(op.id, op.node, op.ttl!!, op.tags)           // ephemeral
is NodeOp.AddEdge -> saveEdge(op.fromId, op.toId, op.edge, op.ttl!!, op.tags) // ephemeral
```
`applyToCacheAsync` (`:403-434`) is unchanged — cache never sees tags.

**5. `abyss-graph/.../CrossSchemaEdgeResolver.kt:62-75`** — add `tags: Set<String>` to
`CrossSchemaOp.Add` (mirrors `Op.AddCrossEdge`; `Remove` untouched); thread it into `NodeOp.AddEdge` in
`toNodeOp`. `MultiSchemaTransactionLike.addCrossEdge` (`:54`) and `MultiSchemaTransactionBuffer`
(`:81-89`) get the same `tags: Set<String> = emptySet()` default-param treatment as step 2.

**6. `abyss-store-api/.../AbyssStoreLike.kt`** — add `tags: Set<String>` as an explicit param, no
default (the worker always supplies one, defaulting belongs at the DSL layer only):
```kotlin
interface AbyssStoreTransactionLike {
    fun saveNode(id: NodeId, node: NodeLike<*>, tags: Set<String>)
    fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, tags: Set<String>)
    ...
}
interface AbyssEphemeralStoreTransactionLike {
    fun saveNode(id: NodeId, node: NodeLike<*>, ttl: Duration, tags: Set<String>)
    fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, ttl: Duration, tags: Set<String>)
    ...
}
```

**7. `abyss-store-yugabyte/.../YugabytePersistentStore.kt`**
- `PersistentOp.SaveNode`/`SaveEdge` (`:32-34`): add `val tags: Set<String>`.
- `saveNode`/`saveEdge` overrides (`:271-272`): capture `tags` into the op.
- Bind sites (`:164,175` non-batched; `:223,234` batched): replace `op.node.tags.toTypedArray()` /
  `op.edge.tags.toTypedArray()` with `op.tags.toTypedArray()`.

**8. `abyss-store-yugabyte/.../YugabyteEphemeralStore.kt`**
- `EphemeralOp.SaveNode`/`SaveEdge` (`:34-35`): add `val tags: Set<String>`.
- `saveNode`/`saveEdge` overrides (`:177-178`): capture `tags` into the op.
- Bind sites (`:154,167`): replace `op.node.tags`/`op.edge.tags` with `op.tags` (already a
  `List<String>`-typed bind slot — pass `op.tags.toList()`, CQL driver takes a `List`).
- DDL unchanged — `tags`/`tags TEXT[]`/`LIST<TEXT>` columns already exist in both
  `ysql-schema.sql`/`ycql-schema.cql`.

**9. Fixture cleanup — drop `override val tags = emptyList()`** from every domain class that declares
it (interface no longer requires it):
`abyss-graph/.../serialization/AbyssSerializer.kt` (`UnknownNode:29`, `UnknownEdge:40` — also delete
now-dead `emptyList()` import if unused), and test fixtures in `UniverseFixture.kt`, `GraphTest.kt`,
`MultiSchemaTest.kt`, `MixedTraversalTest.kt`, `SerializationTest.kt`, `TypeTagRegistryTest.kt`,
`abyss-store-yugabyte/.../LoadTest.kt` (`YbTestNode:39`, `YbTestEdge:50`). In `LoadTest.kt` also drop
the now-meaningless `"tags":[]` from the two hand-built JSON literals (`nodeJson`/`edgeJson`,
`:309-313`) — harmless either way since `ignoreUnknownKeys = true`, but stale once the field is gone.

**10. Call-site sweep** — grep for `.addNode(`, `.addEdge(`, `.modifyNode(`, `.modifyEdge(`,
`.addCrossEdge(` across `abyss-graph`/`abyss-store-yugabyte` tests: all existing calls compile
unchanged (new param has a default), no test behavior changes from this step alone.

## New test coverage

No existing test asserts a tags *contract* (all current references are `emptyList()` fixture
defaults), so this is net-new coverage, not a migration of existing assertions:

- **`abyss-graph` (worker-level, fake store)**: extend the existing fake `AbyssStoreLike` test double
  (pattern from `BatchTransactionTest`/`GraphTest`'s fakes) to capture the `tags` argument passed to
  `saveNode`/`saveEdge`; add a test — `transaction { addNode(n, tags = setOf("a","b")) }` then assert
  the fake recorded `tags = setOf("a","b")` for that node. One more for `addEdge`, one for
  `modifyNode`/`modifyEdge` showing tags land on the replacement `AddNode`/`AddEdge` op, one showing
  the default (`tags` omitted) records `emptySet()`.
- **`abyss-store-yugabyte` (`LoadTest.kt`, live YugabyteDB)**: `saveNode(id, node, tags =
  setOf("x"))` via a real `transaction { }` call, then read the row back with a raw JDBC query
  (`SELECT tags FROM abyss.nodes WHERE id = ?`, pattern already used by `rawYsqlDataSource()`/
  `insertYsqlNode` helpers) and assert the `TEXT[]` column round-trips `{"x"}`. Mirror for edges and
  for the ephemeral/YCQL path (raw `CqlSession` read of the `tags` column).

No performance test needed — this isn't a performance-bug fix (CLAUDE.md's perf workflow doesn't
apply); it's a data-model/API change with no hot-path cost (one extra `Set<String>` field riding
alongside `ttl`, already-cheap).

## Verification

- `./gradlew :abyss-graph:test` — full suite green, including new tag-threading tests.
- `./gradlew :abyss-store-yugabyte:test` — full suite green against live YugabyteDB, including new
  tag round-trip tests.
- No CHANGELOG/version bump as part of this task — separate, explicitly user-gated step per this
  repo's CLAUDE.md.

## Open question for implementation time

None outstanding — the one real design fork (additive-merge vs. full-replace tags on `modify*`) is
resolved above in favor of full-replace, matching every other upserted column and avoiding new
machinery for a value with no read-back path yet.
