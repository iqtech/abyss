# Widen container.transaction into a multi-schema transaction

TODO 4.13.

## Context

`HeterogeneousSchemaGraph`/`HomogeneousSchemaGraph` currently expose two separate
transactional entry points:

- `AbyssGraphSchema<ID>.transaction { }` — full node/edge ops, but scoped to **one**
  registered schema's `ID` type.
- `container.transaction { }` (`CrossSchemaTransactionLike`) — spans schemas, but
  **only** `addCrossEdge`/`removeCrossEdge`; no way to also `addNode`/`addEdge`/etc.
  against the schemas the edge connects, in the same atomic commit.

Both ultimately reduce to one call: `AbyssSchemaWorker.transaction(ops: List<NodeOp>,
checkIntegrity)` (`AbyssSchemaWorker.kt:250`), which is schema-agnostic — every schema
registered on a container shares the same `worker` instance. The only reason
`container.transaction` can't already do full multi-schema writes is that its buffer
(`CrossSchemaTransactionBuffer`) only records `addCrossEdge`/`removeCrossEdge` calls.

Rather than add a second, wider method alongside it (two ways to do the same thing),
this widens `container.transaction` itself: same method name, same signature shape,
existing cross-edge-only call sites keep compiling unchanged, but the block can now
also stage node/edge ops against any registered schema via `on(schema)`, committed
in the same atomic `worker.transaction` call as the cross edges.

Caller side, once done:

```kotlin
val users = container.register(userTag, UserIdAdapter)
val posts = container.register(postTag, PostIdAdapter)

container.transaction {
    on(users).addNode(User(id = uid, name = "cane"))
    on(posts).addNode(Post(id = pid, title = "..."))
    on(posts).modifyNode(otherPid) { it!!.copy(commentCount = it.commentCount + 1) }
    addCrossEdge(AuthoredBy(fromId = pid, toId = uid))   // @CrossSchemaEdge-annotated
    removeCrossEdge<LivesOn>(uid, planetId)
}
```

## Approach

Widen the existing `CrossSchemaTransactionLike`/`CrossSchemaTransactionBuffer` pair in
place (rename, don't duplicate) so the type `container.transaction`'s block already
uses gains an `on(schema)` member. No new interface, no new container method.

### 1. `AbyssGraphSchema.kt` — expose reusable per-schema buffering

`Op` (private sealed interface, line 256), `Op.toNodeOp` (private fun, line 266), and
`BufferedTransaction` (private class, line 275) are currently file-private but hold
exactly the machinery a cross-schema buffer needs to reuse per registered schema.
Widen all three to `internal` (module-visible, matches `NodeOp`'s existing visibility)
and add two small internal members mirroring what `transaction()` already inlines:

```kotlin
internal fun newBuffer(): BufferedTransaction<ID> = BufferedTransaction(
    readNode = { @Suppress("UNCHECKED_CAST") (worker.readNode(adapter.toNodeId(it)) as NodeLike<ID>?) },
    readEdge = { f, t, type -> @Suppress("UNCHECKED_CAST") (worker.readEdge(adapter.toNodeId(f), adapter.toNodeId(t), type) as EdgeLike<ID, ID>?) }
)

internal fun toNodeOps(ops: List<Op>): List<NodeOp> {
    val headerless = adapter is HeaderlessSchemaKeyAdapter<*>
    val width = crossEdgeTagWidth()
    return ops.map { it.toNodeOp(adapter, width, headerless) }
}
```

Refactor `AbyssGraphSchema.transaction()` (line 130) to use `newBuffer()`/`toNodeOps()`
instead of its inline duplicate — single source of truth for both the per-schema and
the new multi-schema commit path. Leave `batchTransaction`/`ephemeral` untouched
(out of scope, no behavior change needed there).

`Op` has no generic parameter (ids/values are already stored as `Any?`/`NodeLike<*>`,
type-recovered via unchecked cast inside `toNodeOp`), so `toNodeOps(ops: List<Op>)`
works unmodified when called on a star-projected `AbyssGraphSchema<*>` — no extra
casting needed at the call site in the container.

### 2. `CrossSchemaEdgeResolver.kt` — rename + extend the buffer

- `CrossSchemaTransactionLike` → `MultiSchemaTransactionLike`, add:
  ```kotlin
  fun <ID> on(schema: AbyssGraphSchema<ID>): AbyssTransactionLike<ID>
  ```
- `CrossSchemaTransactionBuffer` → `MultiSchemaTransactionBuffer`, constructor takes
  the owning container (`NodeIdEngine`) so `on()` can validate schema ownership at
  the call site (see below):
  ```kotlin
  internal class MultiSchemaTransactionBuffer(private val container: NodeIdEngine) : MultiSchemaTransactionLike {
      val crossOps = mutableListOf<CrossSchemaOp>()
      val schemaBuffers = LinkedHashMap<AbyssGraphSchema<*>, BufferedTransaction<*>>()

      override fun addCrossEdge(edge: EdgeLike<*, *>) { crossOps += CrossSchemaOp.Add(edge) }
      override fun removeCrossEdge(edgeClass: KClass<out EdgeLike<*, *>>, fromId: Any?, toId: Any?) {
          crossOps += CrossSchemaOp.Remove(edgeClass, fromId, toId)
      }

      @Suppress("UNCHECKED_CAST")
      override fun <ID> on(schema: AbyssGraphSchema<ID>): AbyssTransactionLike<ID> {
          require(schema.traversalEngine === container) { "on(schema) called with a schema not registered on this container" }
          return schemaBuffers.getOrPut(schema) { schema.newBuffer() } as AbyssTransactionLike<ID>
      }
  }
  ```
  The ownership check reuses `AbyssGraphSchema.traversalEngine` (`internal var`,
  already set to the owning container by both `register()` and `forTag()` — no new
  state needed). Because `on()` is called from inside the `try { buffer.block() }
  catch (e: Throwable)` wrapper in both containers' `transaction()`, a wrong-container
  schema surfaces as the same `AbyssError.Unexpected(...)` any other block-body
  exception would, no new error path required.
- Add `import pl.iqtech.abyss.dsl.AbyssTransactionLike` to this file.

### 3. `HeterogeneousSchemaGraph.kt` — commit merged ops

Replace `transaction()` (line 88):

```kotlin
suspend fun transaction(checkIntegrity: Boolean = true, block: suspend MultiSchemaTransactionLike.() -> Unit): Either<AbyssError, Unit> {
    val buffer = MultiSchemaTransactionBuffer(this)
    try { buffer.block() } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }

    val crossOps = buffer.crossOps
    if (crossOps.isNotEmpty()) {
        crossEdgeGateFailure()?.let { return it.left() }
        if (checkIntegrity) {
            for (op in crossOps) {
                val (fromNid, toNid) = op.endpoints(tagWidth, headerless = false)
                tagCheckFailure(fromNid, toNid)?.let { return it.left() }
            }
        }
    }

    val ops = buffer.schemaBuffers.entries.flatMap { (schema, buf) -> schema.toNodeOps(buf.ops) } +
        crossOps.map { it.toNodeOp(tagWidth, headerless = false) }
    return worker.transaction(ops, checkIntegrity)
}
```

Behavior note: today the `allowCrossSchemaEdges` gate runs *unconditionally*, even if
`buffer.ops` turns out empty. Wrapping it in `if (crossOps.isNotEmpty())` is an
intentional fix so `container.transaction { on(a).addNode(...); on(b).addNode(...) }`
(no cross edges at all) doesn't spuriously require `allowCrossSchemaEdges=true`. This
mirrors the pattern `AbyssGraphSchema.crossEdgeCheck` already uses (line 185: `if
(crossOps.isEmpty()) return null` before consulting the gate) — bringing the
container in line with the per-schema path's existing behavior, not a new judgment
call. It changes nothing for existing cross-edge-only tests since they always have a
non-empty `crossOps`.

### 4. `HomogeneousSchemaGraph.kt` — same shape

Replace `transaction()` (line 93) the same way, using `headerless = true` and no
`crossEdgeGateFailure()` call (this container has none — `tagCheckFailure` already
only runs `if (checkIntegrity)` and its `for (op in crossOps)` loop is naturally a
no-op when `crossOps` is empty, so no equivalent gate-ordering fix is needed here):

```kotlin
suspend fun transaction(checkIntegrity: Boolean = true, block: suspend MultiSchemaTransactionLike.() -> Unit): Either<AbyssError, Unit> {
    val buffer = MultiSchemaTransactionBuffer(this)
    try { buffer.block() } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }

    if (checkIntegrity) {
        for (op in buffer.crossOps) {
            val (fromNid, toNid) = op.endpoints(tagWidth, headerless = true)
            tagCheckFailure(fromNid, toNid)?.let { return it.left() }
        }
    }

    val ops = buffer.schemaBuffers.entries.flatMap { (schema, buf) -> schema.toNodeOps(buf.ops) } +
        buffer.crossOps.map { it.toNodeOp(tagWidth, headerless = true) }
    return worker.transaction(ops, checkIntegrity)
}
```

## Compatibility

Every existing `container.transaction { addCrossEdge(...) }` / `removeCrossEdge(...)`
call site (`MultiSchemaTest.kt` lines 365, 376, 389, 403, 420, 436) keeps compiling
and behaving identically — `MultiSchemaTransactionLike` is a superset of the old
`CrossSchemaTransactionLike` surface, method name and signature are unchanged. The
reified `removeCrossEdge<E>(fromId, toId)` extension function moves to the renamed
interface with no call-site change needed.

## Verification

- `./gradlew :abyss-graph:test` — full existing suite must still pass unchanged,
  particularly `MultiSchemaTest.kt` (cross-edge-only transaction behavior, gating,
  all-or-nothing rollback) and `HomogeneousSchemaTest.kt`.
- Add a new test in `MultiSchemaTest.kt` exercising the new capability in one
  `container.transaction`:
  - `on(schemaA).addNode(...)` + `on(schemaB).addNode(...)` + `addCrossEdge(...)` in
    one call, assert all three land atomically.
  - Force a failure (e.g. persistent-store failure, mirroring the existing `` `transaction
    with addNode and addCrossEdge is all-or-nothing on store failure` `` test) and
    assert nothing partially commits.
  - `on(schema)` called with a schema from a *different* container returns
    `AbyssError.Unexpected` (the `require()` ownership guard).
  - `on(a).addNode(...); on(b).addNode(...)` with **no** cross edge succeeds even when
    `allowCrossSchemaEdges=false` (the gate-ordering fix).
