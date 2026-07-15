# Schema graph model (type-level, annotation-derived)

## Context

We discussed visualizing the schema itself as a graph — `NodeType --EdgeType--> OtherNode Type` —
derived from the `@TypeTag`/`@SerialName`/`@EdgeConstraint` annotations already carried on
`NodeLike`/`EdgeLike` descendants. The instinct was to return this as a `Subgraph` so it could ride
the existing traversal/export plumbing, but that doesn't type-check: `Subgraph`
(`abyss-dsl/TraversalBuilderLike.kt:38`) holds real instances (`List<NodeLike<*>>`,
`List<EdgeLike<*,*>>`), and `GraphJsonCodec.encodeNode/encodeEdge`
(`abyss-graph/serialization/GraphJsonCodec.kt:48-56`) serializes those instances through the
registered polymorphic serializer — there's no value to serialize for a bare `KClass`. This needs a
parallel, type-level shape instead of reusing `Subgraph`.

Decision from discussion: edge types with no `@EdgeConstraint`, or with either `fromTypes`/`toTypes`
side left empty (partial constraint), have no single "OtherNode" to draw an arrow to — they're
dropped from the strict node/edge view and reported separately by name.

## Design

Add three small data types plus a builder, colocated in
`abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/TypeTagRegistry.kt` (same file as
`TypeTagRegistry`, since the builder reuses that file's existing private `SubclassCollector` —
a `SerializersModuleCollector` that already walks `SerializersModule.dumpTo(...)` once and gathers
every registered `(baseClass, actualClass)` pair for `NodeLike`/`EdgeLike`). No new reflection
machinery, no new dependency.

```kotlin
data class SchemaNodeType(val name: String, val tag: Short)

// One graph edge per (fromType, edgeType, toType) triple — only edge types whose @EdgeConstraint
// names both fromTypes and toTypes land here; anything less specific has no single OtherNode to
// point to, so it's reported via SchemaGraph.unconstrainedEdgeTypes instead.
data class SchemaEdgeType(val name: String, val tag: Short, val fromType: String, val toType: String)

data class SchemaGraph(
    val nodeTypes: List<SchemaNodeType>,
    val edges: List<SchemaEdgeType>,
    val unconstrainedEdgeTypes: List<String>,
) {
    companion object {
        fun of(module: SerializersModule): SchemaGraph {
            val collector = SubclassCollector()
            module.dumpTo(collector)

            val nodeTypes = mutableListOf<SchemaNodeType>()
            val edges = mutableListOf<SchemaEdgeType>()
            val unconstrained = mutableListOf<String>()

            for ((base, actual) in collector.found) when (base) {
                NodeLike::class -> nodeTypes += SchemaNodeType(actual.serialName(), actual.typeTag())
                EdgeLike::class -> {
                    @Suppress("UNCHECKED_CAST")
                    val edgeClass = actual as KClass<out EdgeLike<*, *>>
                    val name = edgeClass.serialName()
                    val c = edgeClass.cachedAnnotation<EdgeConstraint>()
                    if (c == null || c.fromTypes.isEmpty() || c.toTypes.isEmpty()) {
                        unconstrained += name
                    } else {
                        val tag = edgeClass.typeTag()
                        for (from in c.fromTypes) for (to in c.toTypes) {
                            edges += SchemaEdgeType(name, tag, from.serialName(), to.serialName())
                        }
                    }
                }
            }
            return SchemaGraph(nodeTypes, edges, unconstrained)
        }
    }
}
```

New imports needed in `TypeTagRegistry.kt`: `pl.iqtech.abyss.dsl.cachedAnnotation`,
`pl.iqtech.abyss.store.api.EdgeConstraint`.

**Entry point**: `SchemaGraph.of(module)` is a standalone pure function of the same
`SerializersModule` the caller already builds and passes to `AbyssGraphSchema` /
`HomogeneousSchemaGraph` / `HeterogeneousSchemaGraph` — exactly how `TypeTagRegistry.of(module)` is
already called directly in tests. No threading through `AbyssSchemaWorker` (which doesn't store
`module` today — only derives `tagRegistry` from it once and discards it), no new public accessor
on the facades, no change to `AbyssEngineLike`. Callers derive it themselves from their own module
instance.

**Explicitly out of scope for this change** (raise separately if wanted):
- Any DOT/Mermaid/JSON rendering of `SchemaGraph` — format not decided yet.
- Wiring `schemaGraph()` into `AbyssGraphSchema`/the container facades or `AbyssEngineLike`.
- Tag-uniqueness validation in `SchemaGraph.of` itself — `TypeTagRegistry.of` already enforces that
  for the same module in normal use; `SchemaGraph` is a read-only descriptive view, not a second
  enforcement point.

## Test

New file `abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/SchemaGraphTest.kt`, mirroring
`TypeTagRegistryTest.kt`'s style (local `@Serializable @SerialName @TypeTag` fixture classes, a
`SerializersModule` built with `polymorphic(NodeLike::class) { ... }` / `polymorphic(EdgeLike::class)
{ ... }`, plain `kotlin.test` assertions). Cover:
- A fully-constrained edge type (`@EdgeConstraint(fromTypes = [...], toTypes = [...])`) produces the
  expected cross-product of `SchemaEdgeType` entries.
- An edge type with no `@EdgeConstraint` lands in `unconstrainedEdgeTypes`.
- An edge type with only one side constrained (e.g. `fromTypes` set, `toTypes` empty) also lands in
  `unconstrainedEdgeTypes`, not the strict edge list.
- `nodeTypes` matches every registered node class's `@SerialName`/`@TypeTag`.

## Verification

`./gradlew :abyss-graph:test --tests "*SchemaGraphTest*"` and
`./gradlew :abyss-graph:test --tests "*TypeTagRegistryTest*"` (regression check on the shared file).
