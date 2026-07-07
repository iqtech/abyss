# Multi-schema graph test: Universe fixture + traversals

## Context

TODO 1.14's unified multi-schema graph engine (cross-hops, `AbyssGraph` + `AbyssGraphSchema`,
`CrossEdgeLike`) was just merged but only has narrow, mechanical tests (`MultiSchemaTest.kt` etc. —
two tiny schemas, generic `TestNode`/`TestEdge` fixtures). There's no test that exercises the engine
the way a real application would: several real schemas, a real type hierarchy, cross-schema edges
whose target type varies per instance, and traversals that actually answer a question. This plan adds
that — a reusable "universe" graph builder (users / astronomy / interests) plus a handful of
traversals that read like real queries, so future graph-engine tests have a non-toy fixture to build
on instead of re-deriving one each time.

## Data model

Three schemas on one `AbyssGraph` container (`allowCrossSchemaEdges = true`):

| Schema | Tag | ID type | Node types |
|---|---|---|---|
| Users | 1 | `String` | `User(name, age)` |
| Astronomy | 2 | `Long` | `Star`, `Planet`, `Moon`, `Singularity` (all `name, mass`) |
| Interests | 3 | `Uuid` | `Interest(name)` |

Astronomy is one ID keyspace shared by all four node types (a shared `nodes` map keyed by tag+id) —
IDs must be unique across `Star`/`Planet`/`Moon`/`Singularity`, not just within one type. Use one
sequential counter for the whole schema when building.

Edges:
- `Orbits : SchemaEdgeLike<Long>` — intra-schema, Astronomy. `Moon→Planet`, `Planet→Star`, `Star→Singularity`.
- `SubdomainOf : SchemaEdgeLike<Uuid>` — intra-schema, Interests. Self-referencing hierarchy.
- `InterestedIn : CrossEdgeLike<NodeId, NodeId>` — Users → Interests.
- `LivesOn : CrossEdgeLike<NodeId, NodeId>` — Users → Astronomy, target type varies per edge (`Moon` or `Planet`).

Sample data:
- **Interests** (7 nodes): `Science`, `Art` (roots); `Music` (child of `Art`); `Astronomy`, `Math`,
  `Chemistry` (children of `Science`); `Singing`, `PlayingInstrument` (children of `Music`).
- **Astronomy** — Sol system (`Sagittarius A*` ← `Sun` ← `Mercury`/`Venus`/`Earth`/`Mars`; `Earth` ←
  `Luna`; `Mars` ← `Phobos`, `Deimos`), Kepler system (`Kepler-186` ← `Kepler-186f`, also orbiting
  `Sagittarius A*`), TRAPPIST system (`TRAPPIST-1` ← `TRAPPIST-1e`, `TRAPPIST-1f`). Sharing one
  `Singularity` across systems exercises fan-in on a cross-system node.
- **Users** (8): alice/bob/frank live on Earth; carol on Luna; dave/heidi on Mars-ish bodies (dave on
  Mars, heidi on Phobos); eve on Kepler-186f; grace on TRAPPIST-1e. Interests mixed across the tree so
  the "Art" query has to actually discriminate (alice lives on Earth but isn't interested in Art;
  bob/frank do and are).

## Files

### `abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/UniverseFixture.kt` (new)

The externalized, reusable builder. Follows the existing fixture convention in this package
(`GraphTest.kt`, `MultiSchemaTest.kt`: plain `@Serializable` data classes + a `SerializersModule` +
helpers, all at file scope, reused via same-package visibility) — no new abstraction layer, no
interfaces.

- Node/edge data classes as above (`NodeLike<Long/String/Uuid>`, `SchemaEdgeLike`, `CrossEdgeLike`),
  matching the shape already used in `README.md` / `MultiSchemaTest.kt`.
- `object UniverseTags { const val USERS = 1L; const val ASTRONOMY = 2L; const val INTERESTS = 3L }`.
- `val universeModule: SerializersModule` registering all node/edge types.
- `val universeEdgeAdapter = MultiSchemaAdapter(SchemaTagWidth.BYTE, mapOf(UniverseTags.USERS to StringKeyAdapter, UniverseTags.ASTRONOMY to LongKeyAdapter, UniverseTags.INTERESTS to UuidKeyAdapter))`.
- `class UniverseGraph(hz: HazelcastInstance, nodesMapName = "uni-nodes", edgesMapName = "uni-edges")` —
  wraps an `AbyssGraph(hz, SchemaTagWidth.BYTE, nodesMapName, edgesMapName, allowCrossSchemaEdges = true)`,
  registers the 3 schemas via `container.register(tag, adapter)`, exposes `users`, `astronomy`,
  `interests` (`AbyssGraphSchema<String>` / `<Long>` / `<Uuid>`).
- `suspend fun UniverseGraph.build(): UniverseData` — populates everything via `transaction { addNode/addEdge }`
  per schema plus `container.addCrossEdge(...)` for `InterestedIn`/`LivesOn` (built via
  `SchemaKeyAdapter(tag, SchemaTagWidth.BYTE, adapter).toNodeId(id)`, matching the README's
  cross-edge-construction pattern since the per-schema tagged adapter isn't exposed). Returns
  `UniverseData(interestsByName: Map<String, Interest>, astroByName: Map<String, NodeLike<Long>>, users: List<User>)`
  so tests reference nodes by name instead of raw generated IDs.
- `fun clearUniverseMaps(hz: HazelcastInstance)` — clears `"uni-nodes"`, `"uni-edges"`,
  `"uni-edges-reverse"` (same pattern as `MultiSchemaTest.clear()`), for reuse in any test's
  `@BeforeTest`.

### `abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/UniverseTraversalTest.kt` (new)

`kotlin.test` style (`@BeforeTest`, `@Test fun \`backtick name\`() = runBlocking { }`), matching
`MultiSchemaTest.kt`. A `private val universeHz by lazy { Hazelcast.newHazelcastInstance(...) }` with
its own `clusterName`, `registerAbyssSerializers(universeEdgeAdapter, universeModule)`.

Four traversals, each demonstrating a distinct DSL capability against the same built fixture:

1. **`usersInterestedInArtWhoLiveOnEarth`** — the requested query. Two independent per-user checks
   (frontier ID types differ across the User→Interest and User→Astronomy hops, so `reaches`/`hasOutgoing(toId)`
   can't be used directly — they're pinned to the *home* schema's ID type):
   - Precompute the Art subtree once: `interests.from(art.id) { allReachable { incoming<SubdomainOf>() } }`
     → `{Art, Music, Singing, PlayingInstrument}` (transitive closure via the graph, not hardcoded names).
   - Per user: `users.from(id) { outgoing<InterestedIn>(); nodes<Interest> { it.id in artIds }; collectNodes<Interest>() }`
     AND `users.from(id) { outgoing<LivesOn>(); nodes<Planet> { it.name == "Earth" }; collectNodes<Planet>() }`.
   - Assert exact result: `{bob, frank}` (alice lives on Earth but isn't pruned in — her interests are
     Astronomy/Chemistry, both under Science, not Art).

2. **`subdomainReachesTransitively`** — demonstrates `reaches(targetId) { block }` used correctly
   (same-schema, so the home ID type matches the target): `interests.from(math.id) { reaches(science.id) { outgoing<SubdomainOf>() } }`
   → `true`; `interests.from(singing.id) { reaches(science.id) { outgoing<SubdomainOf>() } }` → `false`.

3. **`moonOrbitsPlanetOrbitsStarOrbitsSingularity`** — literal 3-hop chain from the spec:
   `astronomy.from(luna.id) { outgoing<Orbits>(); outgoing<Orbits>(); outgoing<Orbits>(); collectNodes<Singularity>() }`
   → `[Sagittarius A*]`.

4. **`usersLivingOnAnyMoon`** — cross-schema `hasOutgoing<E, N>()` filter (no manual hop+filter needed):
   `users.from(id) { hasOutgoing<LivesOn, Moon>(); collectNodes<User>() }` per user → `{carol, heidi}`.

## Verification

```
./gradlew :abyss-graph:test --tests "pl.iqtech.abyss.graph.UniverseTraversalTest"
```

All 4 assertions must pass with the exact expected sets above — not just "non-empty", since the point
is proving the engine discriminates correctly (e.g., alice excluded from the Art query despite living
on Earth). Also run the existing suite once (`./gradlew :abyss-graph:test`) to confirm the new fixture's
class/SerialName choices don't collide with existing same-package test fixtures (checked already — no
collisions found — but the build is the real confirmation).
