# RFC — Multi-schema graph, schema-tagged NodeId, cross-schema edges (TODO 1.12)

Status: **Decided design** (build in a separately-scoped follow-on effort). No production code
changes land with this RFC.

---

## Context

Today `AbyssGraph<ID>` is generic over a single `ID` with one `KeyAdapter<ID>` injected at
construction (TODO 1.10). Every node/edge in one graph instance therefore shares one ID type. Two
capabilities are out of reach:

1. **Multiple ID-typed subgraphs in one graph** — e.g. a `Long`-keyed catalog and a `Uuid`-keyed
   event log co-hosted in one instance sharing one Hazelcast cluster and store.
2. **Cross-schema edges** — an edge whose endpoints are different ID types (`NodeLike<Long>` →
   `NodeLike<Uuid>`). This is structurally impossible: `EdgeLike<ID>` has `fromId: ID` and
   `toId: ID` — a single type parameter (`abyss-store-api/.../Model.kt:6-21`).

TODO 1.12 proposes tagging `NodeId` with extra bytes that identify its schema, so one graph can host
many schemas and edges can span them. This RFC fixes the model and the two hard constraints it
collides with, so the build phase does not re-litigate architecture.

### The two constraints any design must respect

- **C1 — `EdgeLike<ID>` cannot hold heterogeneous endpoints.** One type parameter, two endpoints of
  the same type (`Model.kt:6-21`).
- **C2 — TODO 1.11's native encoding assumed one shape per graph.** `EdgeKeySerializer` /
  `ReverseEdgeKeySerializer` hold one `EdgeAdapter` and write both `fromId`/`toId` in that adapter's
  single `keyEncodingShape` (`Int64` / `Str` / `Int64Pair`), because Hazelcast predicates reject
  `byte[]` as non-Comparable. A cross-schema edge has two different shapes at once — it cannot be
  encoded by the current single-adapter serializer, and `keyEq`'s predicate path
  (`AbyssGraph.kt:118-126`) assumes one shape.

---

## Decided shape

- **Rename:** current `AbyssGraph<ID>` → **`AbyssGraphSchema<ID>`**, unchanged in behavior — the
  typed, single-schema engine that owns intra-schema in/out operations.
- **New container `AbyssGraph`:** holds many `AbyssGraphSchema<*>` keyed by a schema tag, owns the
  shared Hazelcast instance / stores, and provides cross-schema edge ops + cross-schema traversal.
- **Schema tag width configurable:** `Byte` default (256 schemas), extensible to `Short` / `Int` /
  `Long`. Chosen once at container creation.
- **Cross-schema edges are NodeId-level:** typed `EdgeLike<ID>` stays strictly intra-schema; cross
  edges operate on self-describing `NodeId`s. (Honors C1 without touching the typed path.)

---

## Design

### 1. Schema-tagged NodeId (self-describing)

`NodeId` stays `class NodeId(val bytes: ByteArray)` — no change to `equals`/`hashCode`/`toString`/
`compareTo` (`KeyAdapter.kt:8-23`) or its Compact serializer (`writeArrayOfInt8`). We define a
**byte-prefix convention**:

```
NodeId.bytes = [ schemaTag (width bytes, big-endian) | adapter payload ]
```

- The tag is a **prefix**, so `compareTo` (`KeyAdapter.kt:12-19`) naturally groups NodeIds by schema
  — good scan/partition locality, zero code change.
- Adapters (`UuidKeyAdapter`/`LongKeyAdapter`/`StringKeyAdapter`) stay **schema-agnostic**: they
  encode/decode only the payload. Tagging is done by the schema view, not the adapter.

`AbyssGraphSchema<ID>` (which knows its `schemaId` + tag width) wraps its adapter:

```
toTaggedNodeId(id)    = NodeId( tagBytes(schemaId, width) ++ adapter.toNodeId(id).bytes )
fromTaggedNodeId(nid) = adapter.fromNodeId( NodeId(nid.bytes.drop(width)) )
```

Container-level decode of an arbitrary `NodeId`: read first `width` bytes → look up
`AbyssGraphSchema` in the registry → delegate strip+decode. This is the single point that turns an
opaque NodeId back into a typed `ID`, and it is what makes `allNodeIds()` / cross-edge endpoints
resolvable (today `AbyssGraph.kt:82-84` blindly applies the one adapter — that becomes a registry
lookup).

### 2. `AbyssGraphSchema<ID>` — the renamed single-schema engine

Behaviorally today's `AbyssGraph<ID>`, with one change: NodeId construction/deconstruction goes
through the tagging wrapper (§1) instead of the raw adapter. In the shared `edgesMap`, the schema
tag **must remain inside the edge-key encoding** — it is exactly what keeps `A:1` and `B:1` distinct
(Hazelcast identifies keys by their serialized bytes, so stripping the tag would collide edges from
different schemas; see O2). 1.11's **performance** is preserved by keeping a native comparable shape
over **tag ++ payload** (not the slow pre-1.11 hex form). Nodes are unaffected — the shared
`nodesMap` key is the full tagged `NodeId` bytes (`writeArrayOfInt8`), tag already included.

**Single shared maps across all schemas (recommended).** One `nodesMap` / `edgesMap` / `reverse` for
the whole container; every schema view reads and writes them. This works precisely because the
schema-tag prefix makes every tagged NodeId **globally unique** — schema B's node `1` and schema A's
node `1` are distinct keys, so there is nothing to collide. The battle-tested single-schema engine
(predicates, partition-awareness, native encoding) runs **unchanged**; it simply operates on a key
space that is partitioned by tag prefix. No per-schema map provisioning, no map-name namespacing —
the extra bytes on the NodeId do all the isolation work.

Motivating case — **UUID as schemaId = per-tenant schema.** Use a 16-byte tag whose value is the
tenant UUID. Each tenant gets an isolated schema inside one shared graph / cluster / store; the
tenant bytes ride inside every NodeId, keys never collide, and one code path serves every tenant.
Isolation is then enforced at the edge layer (§5): forbidding cross-schema edges by default makes a
traversal unable to leave its schema, i.e. **traversals are private-by-default** (a tenant can never
walk into another tenant's subgraph).

Encoding caveat for **native edges only** (nodes are exempt — NodeId is opaque bytes, so the shared
`nodesMap` is always safe): a single shared `edgesMap` keeps 1.11 native encoding as long as the
co-hosted schemas share one `keyEncodingShape`, which the multi-tenant case satisfies (all tenants
use the same ID type, differing only by tag). Schemas with genuinely different ID shapes (e.g. a
`Long` schema + a `Uuid` schema) cannot share one native Compact `EdgeKey` layout; give those a
per-shape edge map, or route their edges through the uniform cross-edge encoding (§4).

### 3. `AbyssGraph` container

- **Schema registry:** `Map<schemaTag, AbyssGraphSchema<*>>` + tag width. Register a schema with its
  `KeyAdapter<ID>` and its node/edge `SerializersModule`.
- **Owns:** the shared `HazelcastInstance`, persistent/ephemeral stores, the shared
  `nodesMap` / `edgesMap` / `reverse` (§2), and a container-level **cross-edge map**.
- **Delegates** all typed, single-ID operations to the addressed schema view (resolve by NodeId
  prefix or explicit schema handle).

### 4. Cross-schema edges — the C1 + C2 answer

> **Implemented (heterogeneous-first, 2026-07).** The build diverged from the native+`CrossEdgeKey`
> sketch below, for a hard reason: a single `HazelcastInstance` allows only **one Compact serializer
> per class**, so `Long`→`Int64` and `Uuid`→`Int64Pair` shapes cannot coexist in one `EdgeKey`
> schema. A multi-schema container therefore uses **uniform hex** (the existing `STRING`/`Str` shape,
> via `UniformHexAdapter`) for **all** its edges — intra and cross alike — with **zero serializer
> changes**. Native `Int64`/`Int64Pair` encoding is preserved only for **standalone** single-schema
> `AbyssGraphSchema`. Consequently there is **no separate `CrossEdgeKey` type**: cross edges are
> ordinary `EdgeKey`s: intra-schema edges live in the shared `edgesMap`, cross-schema edges in a
> separate container `<edges>-cross` map. The map split (not a type split) is what keeps intra-schema
> queries private. The original design below is retained for the homogeneous ideal.

Cross edges live **only** in a dedicated container-owned map, isolating all heterogeneity:

- **Model:** reuse `EdgeLike<NodeId>` — its `fromId: NodeId`, `toId: NodeId` fit exactly, and the
  edge payload (properties + `@SerialName` type) is schema-independent. No new edge interface, no
  `EdgeLike<F,T>` churn. (Endpoints are NodeIds that already exist as nodes in their own schemas.)
- **Key/encoding:** endpoints are encoded **uniformly** as schema-prefixed hex strings (Comparable,
  predicate-safe) — the pre-1.11 representation. For the **homogeneous** multi-tenant ideal this can
  be localized to the cross-edge map only, letting intra-schema edges keep native encoding; for the
  **heterogeneous** case (implemented) one Hazelcast instance forces uniform hex on all container
  edges (see the note above). Either way this is the honest resolution of C2: confine the string
  fallback to exactly where mixed shapes force it.
- **Partition routing:** `CrossEdgeKey` co-locates with `fromId`'s partition (source), consistent
  with intra-schema `EdgeKey` behavior (`EdgeKey.kt:11-14`).
- **Integrity (TODO 1.3 parity):** container resolves each endpoint's schema by prefix and checks
  node existence in that schema before creating the cross edge; new `IntegrityError` on a missing
  endpoint, disable-able for bulk import.
- **API (container level):**
  `addCrossEdge(edge: EdgeLike<NodeId>)`, `removeCrossEdge(fromId, toId, type)`,
  `crossOutEdges(fromId: NodeId)`, `crossInEdges(toId: NodeId)` — all NodeId-typed.

### 5. Cross-schema traversal (same effort, secondary)

Intra-schema hops keep delegating to the existing per-schema `TraversalBuilder`, and because they
only follow same-tag edges they **cannot leave their schema** — this is the private-by-default
property (§2): with cross-schema edges disabled, a walk started in tenant A's schema stays inside
tenant A. Crossing schemas is opt-in: add `crossOutgoing` / `crossIncoming` hop operators that
consult the container cross-edge map and yield NodeIds in the target schema; `AbyssGraph.from(nodeId)`
resolves the starting schema by prefix. A container policy flag (`allowCrossSchemaEdges`, default
off) gates whether cross edges can be created/followed at all. `Path`/`Subgraph` on a cross-schema
walk carry `NodeLike<*>` / `EdgeLike<*>` (erased at the join points) — detail deferred to the build
phase.

---

## Blast radius (build phase)

- **Rename** `AbyssGraph<ID>` → `AbyssGraphSchema<ID>` across `abyss-graph` + all call sites/tests
  (mechanical). `registerAbyssSerializers` stays per-adapter but is now invoked per registered
  schema.
- **New** `AbyssGraph` container + `SchemaRegistry` + `CrossEdgeKey` + its Compact serializer +
  cross-edge CRUD/integrity/traversal.
- **Store layer:** node/edge PKs are already `BYTEA` (opaque bytes) — the schema prefix rides along
  transparently, so **no store schema change for nodes/intra-edges**. Cross edges need a store table
  if they must persist beyond cache — see O3.
- **Fix baked-in UUID assumption:** `AbyssSerializer.kt:26-36` `UnknownNode`/`UnknownEdge` hardcode
  `Uuid` parsing of `id`/`fromId`/`toId`; must become schema-aware (resolve adapter by prefix) or
  per-schema fallback.

---

## Open questions

- **O1 — SerializersModule resolution.** Multi-schema means node/edge `@SerialName` type resolution
  must be either a union module across schemas or schema-scoped. Ties into TODO 2.2 / 2.5 (schema
  enforcement). Recommend schema-scoped modules held by each `AbyssGraphSchema`, union only where a
  single Hazelcast serializer must cover all.
- **O2 — Tag inside the edge-key encoding (do NOT strip).** Hazelcast identifies `IMap` keys by their
  serialized `Data` bytes, not Java `equals`/`hashCode`. If `EdgeKeySerializer` stripped the tag,
  `EdgeKey(A:1→A:2, "knows")` and `EdgeKey(B:1→B:2, "knows")` would both serialize to
  `Int64(1)/Int64(2)/"knows"` and **collide** in the shared `edgesMap` — defeating the tag-based
  uniqueness §2 relies on. Recommended: encode the *tagged* NodeId, in a native comparable shape
  widened to carry the tag (e.g. tag-as-`Int64` alongside the payload shape) so 1.11's native-vs-hex
  performance is kept; the exact wire shape is a build-phase detail.
- **O3 — Cross-edge persistence.** Whether cross edges are cache-only (Hazelcast) for v1 or need a
  YSQL/YCQL table. Recommend deferring persistence to a later item; ship in-memory cross edges first.

---

## Verification (build phase)

Not applicable to this RFC (no code). For the build phase, the end-to-end proof is:

1. Container with two schemas (`LongKeyAdapter` + `UuidKeyAdapter`, `Byte` tag width), in-memory
   Hazelcast only.
2. Add a `Long` node in schema A, a `Uuid` node in schema B, then `addCrossEdge(A→B)`; assert
   `crossOutEdges(A)` returns it and integrity rejects an edge to a non-existent endpoint.
3. Assert intra-schema `EdgeKey` encoding uses the native comparable shape (not hex) — guards the
   C2 performance no-regression claim — **and** that two schemas' identical-payload edges
   (`A:1→A:2` vs `B:1→B:2`) serialize to **distinct** keys in the shared `edgesMap` (no collision).
4. Port the existing `AbyssGraph` test suite under the `AbyssGraphSchema` rename — must pass green
   with zero behavioral change.
