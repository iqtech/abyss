# New module: `abyss-store-neo4j`

## Context

TODO.md item 2.20 (already recorded) and README's "Other stores are a real option, not just a
theoretical one" section (Pluggable storage) both call out Neo4j as a concrete second backend for
`AbyssStoreLike`/`AbyssEphemeralStoreLike`, alongside the existing `abyss-store-yugabyte` reference
implementation. The point isn't query-path performance — all real traversal already runs in
Hazelcast, the store is only hit on cache miss / write-through — it's turning the durable copy into
a second, decoupled surface: Neo4j's Graph Data Science library (PageRank, community detection,
centrality, weighted shortest paths) can run as an offline job against the same data, and the store
becomes human-browsable via Cypher/Neo4j Browser instead of an opaque byte-keyed table. Both docs
are explicit that this only pays off if `NodeId`'s self-describing bytes are decoded into real Neo4j
labels/properties at the store boundary — a thin `MERGE`-by-id KV wrapper (Yugabyte's own pattern,
one opaque JSON blob column keyed by raw bytes) would get none of that payoff.

This plan designs and implements that module: a Neo4j-native mapping, not a KV wrapper.

## `NodeId` decoding — the central design problem

`AbyssStoreLike`/`AbyssEphemeralStoreLike` are untyped — the store only ever sees raw `NodeId`
bytes, never a `KeyAdapter`. But `NodeId`'s byte shape differs across the three container tiers
(confirmed by reading `abyss-store-api/.../KeyAdapter.kt` directly):

- **Headered** (`[header:1][tag:width.bytes][rawId]`) — produced by a bare canonical `KeyAdapter`
  used directly (`toNodeId` default: `NodeKey.compose(SchemaTagWidth.NONE, ...)`) or by
  `SchemaKeyAdapter` inside `HeterogeneousSchemaGraph` (`NodeKey.compose(tagWidth, ...)`). Decoded
  via `NodeKey.width/kind/tag/rawId(nodeId)`.
- **Headerless, tagged** (`[tag:width.bytes][rawId]`, no header byte) — `HeaderlessSchemaKeyAdapter`
  inside `HomogeneousSchemaGraph`. Neither `width` nor `kind` self-describes; both are fixed per
  container. Decoded via `NodeKey.tagHeaderless(nodeId, width)` / `NodeKey.rawIdHeaderless(nodeId, width)`.
- **Headerless, untagged** (`NodeId.bytes == encodeIdBytes(id)`, nothing else) — `HeaderlessKeyAdapter`,
  used by `SingleSchemaGraph` **and** standalone `AbyssGraphSchema` (its own constructor wraps the
  adapter in `HeaderlessKeyAdapter` — confirmed in `AbyssGraphSchema.kt`, so "standalone" and
  "single-schema tier" are the same byte shape, not the Headered one). Nothing to decode against;
  `kind` must be supplied externally.

So the store must be told which shape it's decoding — same reasoning as
`registerAbyssSerializers(adapter, module)` requiring a matching adapter for Hazelcast. New sealed
type in the new module:

```kotlin
sealed interface NodeIdCodec {
    data object Headered : NodeIdCodec
    data class HeaderlessTagged(val width: SchemaTagWidth, val kind: NodeKeyKind) : NodeIdCodec
    data class HeaderlessUntagged(val kind: NodeKeyKind) : NodeIdCodec
}

data class DecodedNodeId(val kind: NodeKeyKind, val tag: SchemaTag?, val rawId: ByteArray)

fun NodeIdCodec.decode(nodeId: NodeId): DecodedNodeId = when (this) {
    is NodeIdCodec.Headered -> NodeKey.width(nodeId).let { w ->
        DecodedNodeId(NodeKey.kind(nodeId), if (w == SchemaTagWidth.NONE) null else NodeKey.tag(nodeId), NodeKey.rawId(nodeId))
    }
    is NodeIdCodec.HeaderlessTagged -> DecodedNodeId(kind, NodeKey.tagHeaderless(nodeId, width), NodeKey.rawIdHeaderless(nodeId, width))
    is NodeIdCodec.HeaderlessUntagged -> DecodedNodeId(kind, null, nodeId.bytes)
}
```

`NodeIdCodec` is a **required** constructor/factory parameter on both store classes — no default.
Only `kind`/`tag` are actually persisted (as technical properties, see below); `rawId` is decoded
for completeness but not separately stored, since the domain payload's own `id`/`fromId`/`toId`
JSON fields (already flattened from the polymorphic body) are the human-readable identity.

## Class shape: two classes, one shared driver

Mirror Yugabyte's two-class convention (`Neo4jPersistentStore` / `Neo4jEphemeralStore`) since the
Cypher/property logic genuinely differs (TTL/`expiresAt` handling exists only in the ephemeral
class) — but unlike YSQL/YCQL (different wire protocols, unavoidably separate pools), both classes
talk Bolt to the same server, so they should be able to **share one `Driver`** instead of forcing
two connection pools for no isolation benefit:

```kotlin
class Neo4jPersistentStore(
    private val driver: Driver,
    module: SerializersModule = EmptySerializersModule(),
    private val codec: NodeIdCodec,
    private val database: String? = null,
    private val ownsDriver: Boolean = false,
) : AbyssStoreLike, Closeable {
    companion object {
        fun create(uri: String, user: String, password: String, codec: NodeIdCodec,
                    module: SerializersModule = EmptySerializersModule(), database: String? = null) =
            Neo4jPersistentStore(GraphDatabase.driver(uri, AuthTokens.basic(user, password)), module, codec, database, ownsDriver = true)
                .also { it.ensureSchema() }
    }
    override fun close() { if (ownsDriver) runCatching { driver.close() } }
}
```
`Neo4jEphemeralStore` mirrors this shape. `create(...)` gives Yugabyte-equivalent ergonomics (own
driver, own close); a caller wanting one shared pool for both builds one `Driver` externally and
passes it to both constructors with `ownsDriver = false` (default), so neither `close()` tears down
the other's connection.

## Neo4j data model

- **Technical label `:AbyssNode`** on every node — carries the uniqueness constraint and gives one
  uniform, browsable label regardless of domain type.
- **Domain label** = the node's `type`/`@SerialName` discriminator (e.g. `:person`), alongside `:AbyssNode`.
- **Relationship type** = the edge's `type`/`@SerialName`, used as the **native** Neo4j relationship
  type (not a generic `:REL` + property) — this is the entire point per the README/TODO: GDS
  algorithms key off relationship type for orientation/projection.
- **`:AbyssEphemeral`** marker label, added only by `Neo4jEphemeralStore` writes. Unlike YSQL/YCQL
  (physically separate tables — a persistent and ephemeral write for the same `NodeId` can't
  collide), Neo4j has one shared node space keyed by `nodeId`; without this marker an
  expired-but-undeleted ephemeral node would be readable forever through the *persistent* store.
  Persistent reads filter `WHERE NOT n:AbyssEphemeral`; ephemeral reads require `n:AbyssEphemeral`.
  Relationships get no equivalent marker (a relationship has exactly one type, already spent on the
  domain discriminator) — documented as an accepted limitation: writing the identical
  `(fromId, toId, type)` triple through both stores aliases onto the same relationship. Ephemeral
  edges being outgoing-only and TTL'd makes this an unlikely real collision.
- **Technical properties**: `nodeId` (full `NodeId` hex — the unique lookup key, *not* the bare
  domain id, since the same domain id can exist under different schema tags), `abyssKind`
  (`NodeKeyKind` name), `abyssTagHi`/`abyssTagLo` (only when `DecodedNodeId.tag != null`),
  `abyssNestedFields` (see flattening below), `expiresAt` (ephemeral only, native Neo4j `DATETIME`).
  These names are reserved — a domain field colliding with one of them fails the write with
  `AbyssError.SchemaError` during pre-flight validation (see below), not a silent overwrite.

## Cypher identifier safety (labels/relationship types can't be parameterized)

`type` (and the domain label derived from it) is a runtime `String` interpolated into Cypher text —
Cypher has no parameter binding for labels/relationship types. This is a real structural-injection
surface. Mitigation, applied uniformly to every label and every relationship type before it touches
a query:

```kotlin
private val IDENTIFIER_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
private fun validIdentifier(raw: String): Either<AbyssError, String> =
    if (IDENTIFIER_REGEX.matches(raw)) raw.right() else AbyssError.SchemaError("Invalid Neo4j identifier: '$raw'").left()
```
Validated once per `transaction { }` call, **before** any session/transaction opens — every
buffered op is walked (`SaveNode`/`SaveEdge` read `type` from the encoded JSON, `DeleteEdge` reads
its `type` argument), first failure short-circuits the whole transaction with zero DB round-trips.
`loadEdge`/direct reads validate `type` the same way before querying. Once validated, the identifier
is still backtick-quoted (`` `$safeType` ``) as defense-in-depth. `AbyssError.SchemaError` (not
`Unexpected`) is used deliberately — this is a shape problem, not a runtime failure.

## Property flattening / reconstruction

Reuse the same private `Json { classDiscriminator = "type"; encodeDefaults = true; ... }` +
`PolymorphicSerializer(NodeLike::class)`/`(EdgeLike::class)` pattern Yugabyte already has. Given the
resulting `JsonObject`:

- Drop `"type"` (became the label/reltype).
- `createdAt`/`updatedAt` → Neo4j native `DATETIME` (`ZonedDateTime`, converted via
  `kotlin.time.Instant.parse(...).toJavaInstant().atZone(ZoneOffset.UTC)`), not a string.
- `tags: List<String>` and other homogeneous scalar arrays → native Neo4j array properties.
- Scalar `JsonPrimitive` → `Long`/`Double`/`Boolean`/`String` (`longOrNull ?: doubleOrNull ?: booleanOrNull ?: content`).
- **Nested objects / heterogeneous arrays** (not flattenable — Neo4j properties must be primitives
  or homogeneous primitive arrays): keep the same property key, store the field's raw JSON text as a
  `String`, and record the key name in `abyssNestedFields: List<String>` so reconstruction knows to
  re-parse it instead of treating it as a literal string. Chosen over renaming the key (risks
  colliding with a genuine same-named field) or rejecting the write outright (too strict for
  real-world nested domain models) — documented cost: that one field loses native Cypher
  queryability.

Reconstruction (`reconstructJson`) re-adds `"type"` from the label/reltype, re-parses any key listed
in `abyssNestedFields`, converts `ZonedDateTime` back to an ISO instant string, skips all reserved
technical keys, then `json.decodeFromJsonElement(nodeSer/edgeSer, rebuilt)`.

## Cypher shapes (persistent; ephemeral adds `:AbyssEphemeral` + `expiresAt` filtering)

```cypher
-- loadNode
MATCH (n:AbyssNode {nodeId: $nodeId}) WHERE NOT n:AbyssEphemeral RETURN n, labels(n) AS labels
-- loadEdge   (type pre-validated + backtick-quoted)
MATCH (a:AbyssNode {nodeId: $fromId})-[r:`Type`]->(b:AbyssNode {nodeId: $toId}) RETURN r
-- loadEdges / loadInEdges
MATCH (a:AbyssNode {nodeId: $fromId})-[r]->(b:AbyssNode) RETURN type(r) AS relType, r, b.nodeId AS toNodeId
MATCH (a:AbyssNode)-[r]->(b:AbyssNode {nodeId: $toId}) RETURN type(r) AS relType, r, a.nodeId AS fromNodeId
-- saveNode   (label pre-validated; SET n = $props, full replace so stale fields from a shape change don't linger)
MERGE (n:AbyssNode {nodeId: $nodeId}) SET n:`Label` SET n = $props
-- saveEdge   (endpoints auto-MERGEd as bare nodes if missing — mirrors Yugabyte's FK-less permissiveness)
MERGE (a:AbyssNode {nodeId: $fromId}) MERGE (b:AbyssNode {nodeId: $toId}) MERGE (a)-[r:`Type`]->(b) SET r = $props
-- deleteNode (DETACH DELETE — see divergence below)
MATCH (n:AbyssNode {nodeId: $nodeId}) DETACH DELETE n
-- deleteEdge
MATCH (a:AbyssNode {nodeId: $fromId})-[r:`Type`]->(b:AbyssNode {nodeId: $toId}) DELETE r
```

**Documented divergence from Yugabyte**: Yugabyte's `deleteNode` never touches the edges table
(dangling rows are the caller/cascade's problem at a higher layer). Neo4j's plain `DELETE n` throws
if `n` still has relationships, so this store cascades edge deletion on node delete via
`DETACH DELETE` — a deliberate, commented behavioral difference, not an oversight.

## Transactions

Real Bolt managed transaction (`session.executeWrite { tx -> ... }`) — closer to YSQL's real-ACID
pattern than YCQL's per-statement one, and simpler (driver auto-rolls-back on any thrown exception).
`PersistentTransaction`/`EphemeralTransaction` buffer ops into a list (mirroring Yugabyte's
`PersistentOp`/`EphemeralOp` sealed types); `transaction()` runs the identifier/collision pre-flight
pass over the whole buffered list first, then flushes everything inside one `executeWrite` block.

## Ephemeral store specifics (known limitation, not solved here)

`saveNode`/`saveEdge` compute `now` once per transaction flush (mirrors
`YugabyteEphemeralStore.commitYcql`), set `expiresAt = now.plus(ttl)`, add `:AbyssEphemeral`. Reads
check `expiresAt` in Kotlin after the fetch (same defensive `isBefore(now)` pattern Yugabyte already
uses) and treat an expired row as absent. `loadInEdges` returns `emptyList()` unconditionally (TODO
1.13: ephemeral edges are outgoing-only) — no query at all, identical to `YugabyteEphemeralStore`.

Neo4j (Community Edition) has no native per-row TTL, so **nothing physically deletes expired
nodes/relationships** — this is a named limitation, documented in a KDoc comment on the class, with
a new small TODO.md follow-up item for an external cleanup job (e.g.
`MATCH (n:AbyssNode:AbyssEphemeral) WHERE n.expiresAt < datetime() DETACH DELETE n` via an external
scheduler or APOC's `apoc.periodic.repeat`). Not building a scheduler as part of this module.

## Schema setup

Two idempotent Cypher statements run from `create()` (`ensureSchema()`) — not a separate resource
file like Yugabyte's multi-table DDL, since there's nothing here that benefits from being a
standalone operator-run script:
```cypher
CREATE CONSTRAINT abyss_node_id_unique IF NOT EXISTS FOR (n:AbyssNode) REQUIRE n.nodeId IS UNIQUE;
CREATE INDEX abyss_node_expires_at IF NOT EXISTS FOR (n:AbyssNode) ON (n.expiresAt);
```

## Files

**New module `/home/cane/work/abyss/abyss-store-neo4j/`:**
- `build.gradle.kts` (mirrors `abyss-store-yugabyte/build.gradle.kts`):
  ```kotlin
  dependencies {
      implementation(project(":abyss-store-api"))
      implementation(libs.neo4j.driver)
      implementation(libs.slf4j.api)
      testImplementation(kotlin("test"))
  }
  ```
- `src/main/kotlin/pl/iqtech/abyss/store/neo4j/NodeIdCodec.kt` — `NodeIdCodec`, `DecodedNodeId`, `decode()`.
- `src/main/kotlin/pl/iqtech/abyss/store/neo4j/CypherIdentifiers.kt` — `validIdentifier()`, reserved-key set.
- `src/main/kotlin/pl/iqtech/abyss/store/neo4j/PropertyMapping.kt` — `flattenToProperties()`, `reconstructJson()`, `Instant`⇄`ZonedDateTime` helpers.
- `src/main/kotlin/pl/iqtech/abyss/store/neo4j/Neo4jPersistentStore.kt` — `PersistentOp` sealed type, the class, `PersistentTransaction`, `create()`, `ensureSchema()`.
- `src/main/kotlin/pl/iqtech/abyss/store/neo4j/Neo4jEphemeralStore.kt` — same shape + TTL/`:AbyssEphemeral` handling + KDoc limitation note.
- `src/test/kotlin/pl/iqtech/abyss/store/neo4j/LoadTest.kt` — see Verification.

**Edits:**
- `settings.gradle.kts` — add `"abyss-store-neo4j"` to `include(...)`.
- `gradle/libs.versions.toml` — add `neo4j = "5.28.4"` under `[versions]` (confirm latest 5.x at
  implementation time) and `neo4j-driver = { module = "org.neo4j.driver:neo4j-java-driver", version.ref = "neo4j" }`
  under `[libraries]`.
- Root `build.gradle.kts` — **no change needed**; `subprojects {}` already applies
  kotlin/serialization/maven-publish/coroutines/JVM21 to every module automatically.
- `TODO.md` — mark 2.20 done; add the small TTL-cleanup follow-up item noted above; add a short
  README section (new "With Neo4j persistence" subsection, mirroring the existing "With YugabyteDB
  persistence" one) once the module works end-to-end.

## Verification

- `./gradlew :abyss-store-neo4j:build` compiles cleanly.
- New `LoadTest.kt` mirrors `abyss-store-yugabyte`'s `LoadTest.kt` conventions (local
  `@Serializable @SerialName` fixtures, a `SerializersModule`, `by lazy { Neo4jPersistentStore.create(...) }`
  against a local dev Neo4j instance — same "requires a running instance" convention already
  documented in the README's Building section) covering:
  - Save/load node round-trip using a **tagged** `NodeId` (via `SchemaKeyAdapter`), then a raw
    Cypher query to assert `abyssKind`/`abyssTagHi`/`abyssTagLo` persisted correctly.
  - Save/load edge round-trip, asserting the relationship's native Neo4j type equals the edge's `@SerialName`.
  - Delete node (assert cascade removes its relationships — the documented `DETACH DELETE` divergence) and delete edge.
  - TTL expiry: readable before expiry, absent after (same style as
    `YugabyteEphemeralStore`'s existing TTL test), and still tagged `:AbyssEphemeral` (not visible
    through the persistent store).
  - `loadEdges`/`loadInEdges` scans with multiple edges.
  - Rejection test: a malicious `type` (containing a backtick or Cypher keywords) returns
    `Either.Left(AbyssError.SchemaError)` with zero mutation, and a subsequent legitimate read
    confirms unrelated data is untouched.
- `./gradlew build` (full monorepo) to confirm no regressions in other modules.
