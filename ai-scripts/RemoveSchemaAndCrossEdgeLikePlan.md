# Remove `SchemaEdgeLike` and `CrossEdgeLike`

## Context

`Model.kt` defines three edge interfaces where one suffices:

```kotlin
interface EdgeLike<FID, TID> { ... }              // @Polymorphic root
interface SchemaEdgeLike<ID> : EdgeLike<ID, ID>       // same-schema convenience
interface CrossEdgeLike<FID, TID> : EdgeLike<FID, TID>  // cross-schema marker
```

Both sub-interfaces add **zero** members — they are pure aliases. `EdgeLike<FID, TID>`
covers every case (`EdgeLike<ID, ID>` for same-schema). Evidence they carry no weight:

- **Cross edges never used `SchemaEdgeLike`.** The only cross-edge entry points —
  `AbyssGraph.addCrossEdge` / `AbyssSchemaWorker.putCrossEdge` — already take
  `EdgeLike<NodeId, NodeId>`. `CrossEdgeLike` is only a documentation marker on two
  test fixtures (`CrossRefEdge`, `InterestedIn`/`LivesOn`).
- **Serialization base is already `EdgeLike`.** Graph registers concrete edges under
  `polymorphic(EdgeLike::class)` and (de)serializes with `PolymorphicSerializer(EdgeLike::class)`
  (`SerializationTest.kt:87,101`, `AbyssGraphSchema.kt:157`). Only the Yugabyte stores use
  `SchemaEdgeLike::class` — an inconsistency this change removes.

Outcome: one edge interface, `EdgeLike<FID, TID>`, everywhere. Smaller API surface, one
polymorphic base for serialization.

## Change

Delete `Model.kt:24-28` (both interfaces + their comments); keep only `EdgeLike`.
Then apply these substitutions repo-wide (main + tests), fixing imports as you go
(drop `import ...SchemaEdgeLike` / `...CrossEdgeLike`, add `...EdgeLike` where missing):

| From | To |
|------|-----|
| `SchemaEdgeLike<ID>` (one type arg) | `EdgeLike<ID, ID>` |
| `SchemaEdgeLike<Long>` / `<String>` / `<Uuid>` | `EdgeLike<Long, Long>` etc. |
| `SchemaEdgeLike<*>` | `EdgeLike<*, *>` |
| `SchemaEdgeLike::class` | `EdgeLike::class` |
| `CrossEdgeLike<FID, TID>` | `EdgeLike<FID, TID>` |

The `reified E : SchemaEdgeLike<*>` / `SchemaEdgeLike<ID>` bounds in the DSL extensions
become `EdgeLike<*, *>` / `EdgeLike<ID, ID>` respectively.

### Files (main source)

- `abyss-store-api/.../Model.kt` — delete the two interfaces.
- `abyss-store-api/.../AbyssStoreLike.kt` — `StoredEdge.edge`, `loadEdge`/`saveEdge` signatures (×4) + header comments.
- `abyss-store-api/.../KeyAdapter.kt` — comment text only (line ~14).
- `abyss-dsl/.../AbyssEngineLike.kt` — `edge`/`outEdges`/`inEdges`/`addEdge`/`modifyEdge` signatures.
- `abyss-dsl/.../Extensions.kt` — reified bounds + `removeEdge` overloads (×6).
- `abyss-graph/.../AbyssGraphSchema.kt` — `Op.AddEdge`, `readEdge` types, `edge`/`outEdges`/`inEdges`/`typed()`, comment at :149.
- `abyss-graph/.../AbyssSchemaWorker.kt` — `NodeOp.AddEdge`, `edgesMap`, edge flows, predicate type params, `readEdge`/`loadAndCacheEdge`/`schemaCheck`, comment at :52.
- `abyss-store-yugabyte/.../YugabytePersistentStore.kt` — `edgeSer = PolymorphicSerializer(EdgeLike::class)`, query/save signatures.
- `abyss-store-yugabyte/.../YugabyteEphemeralStore.kt` — same as above.

### Files (tests)

- `abyss-graph/src/test/.../SerializationTest.kt`, `UniverseFixture.kt`, `MultiSchemaTest.kt`,
  `GraphTest.kt`, `TraversalTest.kt`, `LongPerformanceTest.kt`, `StringPerformanceTest.kt`,
  `UuidPerformanceTest.kt`
- `abyss-store-yugabyte/src/test/.../LoadTest.kt` — including
  `polymorphic(SchemaEdgeLike::class) { subclass(...) }` → `polymorphic(EdgeLike::class) { ... }`
  (this is the registration that unifies Yugabyte onto the `EdgeLike` base).

## Verification

1. `grep -rn "SchemaEdgeLike\|CrossEdgeLike" --include="*.kt"` → **no matches** (definitions and all references gone).
2. `./gradlew compileKotlin compileTestKotlin` — clean compile across all modules.
3. `./gradlew :abyss-graph:test --tests "*SerializationTest" --tests "*MultiSchemaTest" --tests "*GraphTest"` —
   confirms polymorphic round-trip and cross-edge traversal still pass under the single base.
4. If a Yugabyte instance is available, run `:abyss-store-yugabyte:test` (`LoadTest`) to confirm the
   `PolymorphicSerializer(EdgeLike::class)` / registration change round-trips; otherwise compile is sufficient.