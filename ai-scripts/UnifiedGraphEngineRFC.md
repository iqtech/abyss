# RFC — Unified single/multi-schema engine, first-class cross-hops, `RawEdgeLike` hierarchy

Status: **Proposed design.** Supersedes parts of `SchemaConceptRFC.md` (TODO 1.12, already implemented
— see "What this supersedes" below). No production code changes land with this RFC.

---

## Context

TODO 1.12 shipped `AbyssGraph` as a container holding many `AbyssGraphSchema<*>` views, tagged
`NodeId`s (`SchemaKeyAdapter`), and cross-schema edges (`EdgeLike<NodeId>`) in a separate
`<edges>-cross` map, gated by `allowCrossSchemaEdges`. Using it surfaced three problems, worked
through in review:

1. **The hex tax is not scoped to cross-edges.** `SchemaKeyAdapter.encodeKey`/`keyEncodingShape`
   unconditionally delegate to `UniformHexAdapter` (`KeyAdapter.kt:149-152`), so *every* schema
   registered via `AbyssGraph.register()` (`AbyssGraph.kt:55-66`) pays hex-string comparison cost
   on its own intra-schema `outEdges`/`inEdges` — even a container with one `Long`-only schema that
   never creates a cross edge. Measured cost is real but bounded (~10-25% vs native `Int64`, see
   `README.md#performance`), not catastrophic — but it's paid by callers who get nothing for it.
2. **`EdgeLike<ID>` conflates two roles.** It's used both as "an edge, both ends the same schema"
   (the 99% case: `Road`, `Knows`, every domain edge type) and, via `EdgeLike<NodeId>`, as the
   type for genuinely cross-schema edges. The latter loses static domain typing — callers building
   a cross edge must manually convert both endpoints via `SchemaKeyAdapter(tag, width, adapter)
   .toNodeId(id)` (see `MultiSchemaTest.kt:55-56`) instead of writing plain `Uuid`/`Long` values.
3. **Cross-schema walks are not traversal.** `AbyssGraph.crossHop` (`AbyssGraph.kt:128-139`) is a
   standalone method outside `TraversalBuilderLike`. A cross-schema walk today means: run a typed
   traversal to its end, drop out of the DSL, call `crossHop` by hand, call `resolveSchema` by hand,
   then start an entirely new `from(...)` call in the target schema (`MultiSchemaTest
   .crossHopWalksIntoTargetSchema`). There's no single expression that walks across a schema
   boundary.

This RFC's goal: one traversal engine, not two — the same engine serves a plain single-schema graph
and an N-schema container with cross-hops as first-class DSL steps, with **zero overhead** for the
single-schema case and **no worse than native-adjacent** cost for the multi-schema case.

### What this supersedes

`SchemaConceptRFC.md` §4 chose uniform hex for **all** container edges (intra- and cross-alike) as
the pragmatic fix for "one Compact schema per class per instance." §5 left cross-schema traversal as
"secondary effort... detail deferred to the build phase." This RFC replaces both: §4's uniform-hex
gets replaced by a tag+widened-native encoding that only String-shaped schemas still pay a string
cost for, and §5's deferred cross-traversal gets a concrete design (§3 below).

---

## Decided shape

- **New edge hierarchy:** `RawEdgeLike<FID, TID>` is the true base. `SchemaEdgeLike<ID> :
  RawEdgeLike<ID, ID>` replaces today's `EdgeLike<ID>` (same-schema, mechanical rename — every
  existing domain edge class needs zero changes). `CrossEdgeLike<FID, TID> : RawEdgeLike<FID, TID>`
  is new, a sibling of `SchemaEdgeLike`, not a subtype — for edges whose endpoints belong to
  different (or possibly the same, but not statically fixed) schemas.
- **`SchemaTagWidth` gains `NONE(0)`.** A container built with `NONE` and exactly one registered
  schema degenerates byte-for-byte to today's standalone, untagged `AbyssGraphSchema` — no tag
  bytes written, no hex forced, full native `Int64`/`Int64Pair`/`String` Compact encoding. Multi-
  schema mode (`BYTE`/`SHORT`/`INT`/`LONG`) is unchanged in spirit from 1.12.
- **`AbyssGraph` becomes the sole public entry point.** `graph.singleSchema(adapter, ...)` for the
  `NONE`-width, one-schema case (no wrapping, no tag, no `register()`/tag bookkeeping visible to the
  caller); `graph.register(tag, adapter, ...)` for the multi-schema case, unchanged from 1.12.
  `AbyssGraphSchema<ID>` remains the returned handle exposing `.transaction{}`/`.from{}`/`.outEdges`
  — callers barely notice the difference between the two modes.
- **Traversal frontier becomes `Set<NodeId>`**, universal, not `Set<ID>` for one fixed domain type.
  `NodeId` already satisfies every property a frontier element needs — `Comparable<NodeId>`,
  content-based `equals`/`hashCode` (`KeyAdapter.kt:17-32`) — no new wrapper type required.
- **Cross-hops are ordinary hops.** `outgoing<E>()`/`incoming<E>()` dispatch on `E`'s declared shape
  (`SchemaEdgeLike` → query `edgesMap`; `CrossEdgeLike` → query the cross-edge map) but run through
  one hop implementation. A single traversal expression can mix both.
- **Materialization decodes on demand.** Every `NodeId` reaching `collectNodes<T>()`/`subgraph()` is
  resolved via the existing `resolveSchema` recipe (`AbyssGraph.kt:73-76`): read the tag prefix →
  look up the registered schema → decode the payload with that schema's adapter. This already exists
  for cross-edge integrity checks; this RFC makes it the universal decode path, run for every node
  a traversal touches, not just cross-edge endpoints.

---

## Design

### 1. Edge type hierarchy

```kotlin
@Polymorphic
interface RawEdgeLike<FID, TID> {
    val fromId: FID
    val toId: TID
    val tags: List<String>
    val createdAt: Instant
    val updatedAt: Instant
}

interface SchemaEdgeLike<ID> : RawEdgeLike<ID, ID>          // was EdgeLike<ID> (Model.kt:14-21)
interface CrossEdgeLike<FID, TID> : RawEdgeLike<FID, TID>   // new
```

`@Polymorphic` moves from `EdgeLike` (`Model.kt:14`) to `RawEdgeLike` — it's the actual serialization
root now (see §5). Every existing `@Serializable` edge (`Road`, `Knows`, `Likes`, …) needs a single
mechanical change: implement `SchemaEdgeLike<ID>` instead of `EdgeLike<ID>`. `@EdgeConstraint`
(TODO 2.2) stays intra-schema-only for this RFC — a cross-schema equivalent is an open question
(§Open questions).

### 2. `SchemaTagWidth.NONE` — the single-schema degenerate case

```kotlin
enum class SchemaTagWidth(val bytes: Int) { NONE(0), BYTE(1), SHORT(2), INT(4), LONG(8) }
```

With `width = NONE`, `SchemaKeyAdapter`'s prefix is a zero-length `ByteArray`, so
`toNodeId(id) == inner.toNodeId(id)` byte-for-byte — there is nothing to strip, nothing to
special-case. The container must **not** wrap the adapter in `SchemaKeyAdapter` for query encoding
in this mode either — `singleSchema()` holds the caller's raw `KeyAdapter<ID>` directly, so
`EdgeKeySerializer`/`ReverseEdgeKeySerializer` see the adapter's real `keyEncodingShape` (`Int64` /
`Int64Pair` / `Str`), not `UniformHexAdapter`'s forced `Str`:

```kotlin
class AbyssGraph(hazelcast: HazelcastInstance, tagWidth: SchemaTagWidth = SchemaTagWidth.BYTE, ...) {
    private var fallback: AbyssGraphSchema<*>? = null   // populated only when tagWidth == NONE

    fun <ID> singleSchema(adapter: KeyAdapter<ID>, ...): AbyssGraphSchema<ID> {
        require(tagWidth == SchemaTagWidth.NONE)
        require(fallback == null) { "singleSchema already set" }
        return AbyssGraphSchema(adapter, hazelcast, nodesMapName, edgesMapName, ...)
            .also { fallback = it }
    }

    fun resolveSchema(nodeId: NodeId): AbyssGraphSchema<*> =
        if (tagWidth == SchemaTagWidth.NONE) fallback ?: error("no schema registered")
        else schemas[SchemaKeyAdapter.readTag(nodeId, tagWidth)] ?: error("no schema for tag")
}
```

`resolveSchema` has the same signature and the same callers in both modes — the universal traversal
engine (§3) never branches on which mode it's in. In `NONE` mode it reads zero bytes and returns the
one thing ever registered; every existing README single-schema example becomes
`AbyssGraph(hz, SchemaTagWidth.NONE).singleSchema(LongKeyAdapter, "nodes", "edges")` (naming is a
bikeshed item, §Open questions) instead of constructing `AbyssGraphSchema` directly.

### 3. Universal `NodeId`-frontier traversal engine

`TraversalBuilder<ID>`'s frontier (`Set<ID>` today) becomes `Set<NodeId>`. Hop mechanics:

```kotlin
suspend inline fun <reified E : RawEdgeLike<*, *>> CrossTraversalBuilderLike.outgoing(
    noinline predicate: ((E) -> Boolean)? = null
)
```

`E`'s static shape decides which map the hop queries — `E : SchemaEdgeLike<*>` → the addressed
schema's `edgesMap`; `E : CrossEdgeLike<*, *>` → the container's cross-edge map — but the hop loop
itself (advance frontier, dedupe visited, collect traversed edges) is one implementation, run
identically either way. `outgoing<Knows>()` and `outgoing<LivesIn>()` (a `CrossEdgeLike<Uuid, Long>`)
are the same call shape.

**Materialization** (`collectNodes<T>()`, `subgraph<T>()`) resolves each `NodeId` in the current
frontier through `resolveSchema` (§2) before applying the type filter — a frontier that fanned out
across schemas naturally yields a heterogeneous set; `collectNodes<T>()` keeps only the `NodeId`s
whose resolved, loaded node is a `T`, generalizing the pruning `nodes<T>()` already does today.

**`Path`/`Subgraph` go raw.** `Path<ID>`/`Subgraph<ID>` (`TraversalBuilderLike.kt:23-39`) can't
commit to one `ID` ahead of a walk that might cross schemas. They become:

```kotlin
data class Subgraph(val nodes: List<NodeId>, val edges: List<RawEdgeLike<NodeId, NodeId>>)
```

with typed access pushed to an explicit resolve step (`subgraph.resolve<T>(container): List<T>`)
rather than being baked into the traversal return type. This is a visible API change for every
existing `subgraph<Person>()`/`collectNodes<Person>()` call site, including single-schema ones,
since it's the same engine underneath (§Open questions has the ergonomics tradeoff this raises).

### 4. Storage encoding — replacing "always hex" with tag + widened-native

Multi-schema mode (`width != NONE`) stops forcing `UniformHexAdapter`. Instead, `EdgeKey`/
`ReverseEdgeKey` carry a small fixed set of Compact fields, written according to each endpoint's
resolved shape:

```
fromTag:  Int8/Int16/Int32/Int64   -- width-dependent; the schema tag, read from the NodeId prefix
fromHi:   Int64                     -- 0 for Long-shaped, hi for Uuid-shaped (Int64Pair)
fromLo:   Int64                     -- value for Long-shaped, lo for Uuid-shaped
fromStr:  String?                   -- null unless the resolved schema's shape is STRING
```
(mirrored for `to*`.) `Long`/`Uuid`-shaped schemas — the two shapes the benchmarks show actually
matter (`README.md#performance`) — get real `Int64`/`Int64Pair` predicates:
`Predicates.and(tag == t, lo == value)` when disambiguation is needed (two schemas sharing a raw
shape), the bare value predicate when it isn't. Only `String`-shaped schemas still pay a string
compare, and it's the schema's own (short) string, not a hex-doubled byte dump. One Compact class
covers every schema combination ever registered — no per-`(fromShape, toShape)` class matrix, no
re-registration when a new schema tag is added at runtime.

`keyEq()` (`AbyssGraphSchema.kt:117-124`) gains the tag-equality clause only when the container has
more than one schema sharing a raw shape; single-schema (`NONE` width) and single-shape-per-container
cases keep exactly today's predicate, no `fromTag` clause needed.

### 5. Serialization layer

`EdgeLikeHzSerializer` (`AbyssSerializer.kt:58-71`) is one `StreamSerializer` registered per
`HazelcastInstance` via `SerializerConfig().setTypeClass(EdgeLike::class.java)...`
(`AbyssGraphSchema.kt:433`) — it must cover every edge value on that instance, `SchemaEdgeLike` and
`CrossEdgeLike` alike, since both live in maps on the same instance. Both the `StreamSerializer<T>`
type parameter and the `setTypeClass(...)` target move to `RawEdgeLike::class`; its internal
`PolymorphicSerializer(EdgeLike::class)` (line 63) becomes `PolymorphicSerializer(RawEdgeLike::class)`.
Every caller-supplied `SerializersModule` changes shape to match:

```kotlin
polymorphic(RawEdgeLike::class) { subclass(Road::class); subclass(Knows::class); subclass(LivesIn::class) }
```

registering same-schema and cross-schema edge types under one root. kotlinx.serialization resolves
this fine through the `SchemaEdgeLike<ID>`/`CrossEdgeLike<FID,TID>` intermediates — no issue there.

`UnknownEdge` (`AbyssSerializer.kt:34-41`) still hardcodes `Uuid` parsing for `fromId`/`toId` — this
was already flagged as open in `SchemaConceptRFC.md`'s blast radius and remains unresolved here; it
gets more load-bearing under this RFC since fallback decode is now a core path, not an edge case
(see Open questions).

`YugabytePersistentStore`/`YugabyteEphemeralStore`'s own `PolymorphicSerializer(EdgeLike::class)`
(now `SchemaEdgeLike::class`) is **unaffected** — cross edges remain cache-only (no store
persistence, unchanged from `SchemaConceptRFC.md` O3), so no `CrossEdgeLike` value ever reaches the
store layer.

---

## Blast radius

- **`abyss-store-api`** — `Model.kt`: `RawEdgeLike`/`SchemaEdgeLike`/`CrossEdgeLike` hierarchy.
  `KeyAdapter.kt`: `SchemaTagWidth.NONE`.
- **`abyss-dsl`** — `AbyssEngineLike`, `AbyssTransactionLike`, `AbyssEphemeralTransactionLike`:
  mechanical `EdgeLike<ID>` → `SchemaEdgeLike<ID>` rename (these stay single-schema by construction,
  unaffected in shape). `TraversalBuilderLike`, `Path`, `Subgraph`: frontier/result types go
  `NodeId`-based (§3), a real signature change. `Extensions.kt`: every hop function (`outgoing`,
  `incoming`, `hasOutgoing`, `hasIncoming`, `hasTraversal`, `paths`/`loop`) re-bound to
  `RawEdgeLike<*,*>` and rewritten against the universal engine.
- **`abyss-graph`** — `AbyssGraphSchema`: constructor becomes effectively internal (reached via
  `AbyssGraph.singleSchema`/`.register`, not direct construction); `keyEq`/`EdgeKeySerializer`/
  `ReverseEdgeKeySerializer` gain the tag+widened-native layout (§4). `AbyssGraph`: `singleSchema()`,
  `resolveSchema` fallback branch, cross-hop DSL integration, `TraversalBuilder` rewritten around
  `Set<NodeId>`. `AbyssSerializer.kt`: `EdgeLikeHzSerializer` retargeted to `RawEdgeLike` (§5).
- **`abyss-store-yugabyte`** — confirmed unaffected (§5); no changes.
- **`README.md`** — every direct `AbyssGraphSchema(adapter, hz, ...)` construction example becomes
  `AbyssGraph(hz, SchemaTagWidth.NONE).singleSchema(adapter, ...)`; every
  `polymorphic(EdgeLike::class) { ... }` becomes `polymorphic(RawEdgeLike::class) { ... }`; the
  multi-schema section's manual `SchemaKeyAdapter(...).toNodeId(...)` cross-edge example simplifies
  to plain domain-typed `CrossEdgeLike` construction (pending §Open questions on endpoint→schema
  resolution for that convenience).
- **Tests** — `MultiSchemaTest` needs the new construction API and gets new cases for first-class
  cross-hop traversal; the whole `*PerformanceTest` suite needs to be re-run to confirm the §4
  encoding closes the gap it's designed to close (this is directly checkable against the existing
  `-Pperf` benchmarks in `README.md#performance`).

---

## Open questions

- **O1 — `singleSchema()` naming.** Generic `graph.singleSchema(adapter, ...)` vs per-adapter sugar
  (`AbyssGraph.long(hz, ...)`, `.uuid(hz, ...)`, `.string(hz, ...)`) mirroring `LongKeyAdapter`/
  `UuidKeyAdapter`/`StringKeyAdapter` naming. Bikeshed, not architecture.
- **O2 — `Path`/`Subgraph` ergonomics.** Going fully raw (§3) is correct for a walk that might cross
  schemas, but it's a real regression for the common single-schema case, which never needed a resolve
  step before. Worth a typed convenience wrapper (`Subgraph<T>` view over the raw one, valid only
  when every node resolves to the same schema) so single-schema callers don't feel this.
- **O3 — Endpoint→schema resolution for `CrossEdgeLike` construction ergonomics.** Callers should be
  able to write `LivesIn(fromId = alice.id, toId = warsaw.id)` with plain domain values, not manual
  `NodeId` conversion — but resolving "which schema does this `Uuid` belong to" from the raw Kotlin
  type alone is ambiguous if two schemas share a raw ID type. Options: (a) require 1:1 raw-type→schema
  within a container (simplest, document as a constraint), (b) require an explicit schema/tag
  argument at cross-edge construction when types collide, (c) keep the convenience layer per-schema
  (`schema.toNodeId(id)`) rather than container-inferred. Needs a decision before §5's cross-edge
  ergonomics can be finalized.
- **O4 — `outgoing<E>()` dispatch cost.** Whether `SchemaEdgeLike` vs `CrossEdgeLike` map selection
  is resolved via reflection on `E`'s declared supertypes per call, or cached once per registered
  edge type at `register()`/`singleSchema()` time. Affects whether hop dispatch itself adds overhead
  on top of §4's storage-layer fix.
- **O5 — `@EdgeConstraint` for `CrossEdgeLike`.** TODO 2.2's schema enforcement is intra-schema-only
  today; whether cross-schema edges get an equivalent typed-endpoint constraint is deferred.
- **O6 — Compact field layout details for §4.** Exact field names/types for the tag (fixed `Int64`
  regardless of `SchemaTagWidth`, or width-dependent?), and how the nullable `fromStr`/`toStr` fields
  interact with Compact schema evolution when a container is upgraded from a smaller registered-shape
  set to a larger one.
- **O7 — Cross-edge persistence.** Still deferred per `SchemaConceptRFC.md` O3 — cache-only for this
  RFC too — but first-class cross-hops make the practical pressure to eventually persist them higher
  than when `crossHop` was a manual escape hatch.
- **O8 — `UnknownEdge` schema-awareness.** Carried over from `SchemaConceptRFC.md`'s blast radius,
  still unresolved: `AbyssSerializer.kt:34-41` hardcodes `Uuid` for unknown edge fallback endpoints;
  under this RFC the fallback path is exercised by the universal decode recipe (§3), not just an edge
  case, so this should probably be resolved as part of the build phase rather than deferred further.

---

## Verification (build phase)

Not applicable to this RFC (no code). For the build phase:

1. `AbyssGraph(hz, SchemaTagWidth.NONE).singleSchema(LongKeyAdapter, "nodes", "edges")` — assert the
   Compact-serialized `EdgeKey` bytes for an edge added through it are byte-identical to what today's
   `AbyssGraphSchema(LongKeyAdapter, hz, "nodes", "edges")` produces (the zero-overhead claim, made
   checkable).
2. Container with two schemas (`Long` + `Uuid`, `BYTE` tag width). Single traversal expression:
   `outgoing<Knows>()` (intra-schema, stays in `Uuid` schema) then `outgoing<LivesIn>()`
   (`CrossEdgeLike<Uuid, Long>`, crosses into the `Long` schema) then `collectNodes<City>()` — one
   expression, no manual `crossHop`/`resolveSchema` calls from the caller.
3. Re-run the existing `-Pperf` suite (`UuidPerformanceTest`/`LongPerformanceTest`/
   `StringPerformanceTest`) against schemas registered *inside* a multi-schema container and confirm
   `outEdges`/`inEdges` throughput is within noise of the standalone numbers already in
   `README.md#performance` — this is the concrete check that §4 actually closed the hex-tax gap
   surfaced in review, not just a theoretical improvement.
4. Port `MultiSchemaTest` to the new construction API (`register`/`singleSchema`) and add a case for
   two schemas sharing a raw shape (two `Long`-keyed schemas) to exercise the `fromTag` disambiguation
   predicate clause from §4.
