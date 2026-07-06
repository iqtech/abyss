# Abyss Graph

User-agnostic in-memory graph library backed by Hazelcast with pluggable durable storage.

**This is not a graph database.** There's no server process, no query language, and no storage
engine of its own — Abyss is a Kotlin library that layers graph semantics (typed nodes/edges,
traversal, algorithms) on top of two things you bring: a `HazelcastInstance` for the in-memory
cache/partitioning layer, and an optional store (`AbyssStoreLike`/`AbyssEphemeralStoreLike` — the
reference implementation is YugabyteDB) for durability. If you need ad-hoc query languages,
multi-tenant isolation at the infrastructure level, or a standalone graph engine, use a real graph
database. Reach for Abyss when you want graph structure and traversal inside a JVM service you
already run, without operating a separate one.

That shape isn't incidental. An embedded engine that owns its own on-disk storage (ArcadeDB, or
anything SQLite-shaped) doesn't fit how Kubernetes wants to run a pod — stateless and freely
rescheduled. Skip a `PersistentVolume` and a reschedule silently loses whatever it wrote; attach one
and you've pulled in `StatefulSet` semantics (stable identity, one volume per pod) for something you
wanted to scale like every other pod in the cluster. Hazelcast sidesteps this: `IMap` is a
distributed in-memory grid that's already Kubernetes-native — partitions rebalance automatically as
pods come and go, no local disk involved — so durability is delegated entirely to a separate,
purpose-built stateful service (YugabyteDB, or anything behind `AbyssStoreLike`) that's designed to
run as its own `StatefulSet`/Operator in the first place. Your application pods stay boring and
stateless; the two things that actually need to be stateful are each handled by something built for
exactly that job.

It's a targeted library, not a platform: the entire public surface is `suspend fun`, built for
Kotlin coroutines from the ground up rather than adapted onto them — reads and writes are
non-blocking (`IMap.getAsync()`, not a blocking call wrapped in `Dispatchers.IO`), traversal hops
fan out with `async`/`awaitAll`, and every result is `Either<AbyssError, T>` or a coroutine `Flow`.
There's no reactor of its own to run and no thread pool to tune beyond the one your application
(e.g. a Ktor server) already has — Abyss's coroutines run on your dispatcher, inside your existing
JVM process. The design goal throughout has been throughput on hardware you already have (see
[Performance](#performance)), not feature breadth: pick this over a full graph database when the
graph is a data structure inside your service, not a separate system you want to operate.

One thing you won't find in most graph databases: **first-class ephemeral (TTL) elements.**
`ephemeral { }` writes nodes/edges that expire on their own — cache entry and durable row alike —
with no cleanup job, no expiry sweep, no cron. It's a second, parallel write path
(`ephemeral { }` alongside `transaction { }`, YCQL alongside YSQL) rather than a TTL bolted onto
the same table, which is also why ephemeral edges are [outgoing-only](#pluggable-storage) — that
constraint buys atomic, heal-free expiry instead of a denormalized reverse index that could
outlive (or expire before) the row it mirrors. Useful for session-scoped relationships, presence,
temporary grants — graph data that should vanish on its own instead of being explicitly deleted.

## Modules

| Module | Purpose |
|---|---|
| `abyss-store-api` | `NodeLike<ID>` / `EdgeLike<FID, TID>` interfaces (one edge type serves both same-schema and cross-schema edges), `AbyssStoreLike` (persistent) and `AbyssEphemeralStoreLike` (TTL) contracts |
| `abyss-dsl` | Engine + traversal interfaces, reified extension functions |
| `abyss-graph` | Hazelcast `IMap` engine — `AbyssGraphSchema` (single schema, typed facade); three container tiers built on top of it: `SingleSchemaGraph`, `HomogeneousSchemaGraph`, `HeterogeneousSchemaGraph` (see [Multi-schema graphs](#multi-schema-graphs--cross-schema-edges)) |
| `abyss-store-yugabyte` | YugabyteDB stores — `YugabytePersistentStore` (YSQL) and `YugabyteEphemeralStore` (YCQL) |

## Quick start

```kotlin
@Serializable
@SerialName("city")
data class City(
    override val id: Long,
    val name: String,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = Clock.System.now(),
) : NodeLike<Long>

@Serializable
@SerialName("road")
data class Road(
    override val fromId: Long,
    override val toId: Long,
    val km: Int,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = Clock.System.now(),
) : EdgeLike<Long, Long>

val module = SerializersModule {
    polymorphic(NodeLike::class) { subclass(City::class) }
    polymorphic(EdgeLike::class) { subclass(Road::class) }
}

val hz    = Hazelcast.newHazelcastInstance(Config().registerAbyssSerializers(LongKeyAdapter, module))
val graph = SingleSchemaGraph(LongKeyAdapter, hz, nodesMapName = "nodes", edgesMapName = "edges")

graph.transaction {
    addNode(City(id = 1L, name = "Warsaw"))
    addNode(City(id = 2L, name = "Kraków"))
    addEdge(Road(fromId = 1L, toId = 2L, km = 295))
}

graph.outEdges<Road>(1L).collect { road ->
    println("${road.fromId} → ${road.toId} (${road.km} km)")
}

// traversal: cities reachable from Warsaw by road
graph.from(1L) {
    outgoing<Road>()
    collectNodes<City>().collect { city -> println(city.name) }   // Kraków
}
```

---

## Pluggable storage

`AbyssGraphSchema` accepts two independently nullable stores:

- **`persistentStore: AbyssStoreLike?`** — durable, no-TTL writes. Any `AbyssStoreLike` works here; the
  reference implementation is `YugabytePersistentStore` (YSQL/PostgreSQL-compatible).
- **`ephemeralStore: AbyssEphemeralStoreLike?`** — TTL-bound writes. Any `AbyssEphemeralStoreLike` works;
  the reference implementation is `YugabyteEphemeralStore` (YCQL/Cassandra-compatible).

Either store can be `null`. Passing neither gives a pure in-memory (Hazelcast-only) mode.

The store split decides which traversal directions an edge supports:

- **Transactional store (e.g. PostgreSQL / `YugabytePersistentStore`):** persistent edges are
  **bidirectional** — a single `edges` table with an index on `to_id` covers both directions in one
  atomic write, so `outEdges`/`outgoing` and `inEdges`/`incoming` are always in sync.
- **YCQL (`YugabyteEphemeralStore`):** ephemeral (TTL) edges are **outgoing-only**. YCQL has no
  multi-statement transactions, so instead of a denormalized reverse table kept in sync by two
  best-effort writes, an ephemeral edge is a **single atomic row write** — reachable via `outEdges` /
  `outgoing<E>()` only; `inEdges` / `incoming<E>()` never return it. To walk an ephemeral relationship
  "backwards", model the reverse direction as an explicit second outgoing edge
  (`Person —IsInGroup→ G1` **and** `G1 —HasMember→ Person`) and follow it as outgoing. The payoff is
  atomic, heal-free ephemeral writes with no dangling-entry window. One consequence: a `removeNode`
  cascades ephemeral edges only on the `fromId` side — an ephemeral edge into a deleted node is left to
  expire via its TTL.

Delete operations issued from either DSL builder (`transaction { }` or `ephemeral { }`) are fanned out
to **both** stores, so a node removed via `transaction { removeNode(id) }` is also deleted from the
ephemeral store (best-effort; fanout failure is logged but not propagated).

#### Other stores are a real option, not just a theoretical one

YugabyteDB is the *reference* implementation, not a hardcoded dependency — `persistentStore` and
`ephemeralStore` are plain interfaces (`AbyssStoreLike`/`AbyssEphemeralStoreLike`), untyped and
`NodeId`-keyed, so any backend that can do point CRUD by key qualifies. One option worth naming
explicitly: **a real graph database (e.g. Neo4j) as the durable store.** All actual traversal
already happens in Hazelcast — the store is only ever hit on a cache miss or write-through — so
Abyss wouldn't lean on Cypher for the hot path either way. What it *would* unlock is treating the
durable copy as a second, decoupled surface: Neo4j's Graph Data Science library (PageRank,
community detection, centrality, weighted shortest paths) running as an offline job against data
Abyss never has to compute itself, plus a durable graph a human can browse natively instead of an
opaque byte-keyed table. The catch is that `NodeId`'s self-describing bytes would need decoding
into real labels/properties at the store boundary to make that worthwhile — a thin KV-shaped
`MERGE`-by-id adapter over Neo4j gets none of it — and adopting it only pays for itself if you
want GDS-style analytics badly enough to justify operating a graph database, which is otherwise
exactly what Abyss exists to let you avoid (see the intro above).

---

## Usage

### Define your types

```kotlin
@Serializable
@SerialName("person")
data class Person(
    override val id: Uuid = Uuid.random(),
    val name: String,
    val age: Int = 0,
    val interests: List<String> = emptyList(),
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = Clock.System.now(),
) : NodeLike<Uuid>

@Serializable
@SerialName("interest")
data class Interest(
    override val id: Uuid = Uuid.random(),
    val name: String,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = Clock.System.now(),
) : NodeLike<Uuid>

@Serializable
@SerialName("knows")
data class Knows(
    override val fromId: Uuid,
    override val toId: Uuid,
    val since: Instant = Clock.System.now(),   // edges carry data too
    val strength: Int = 1,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = Clock.System.now(),
) : EdgeLike<Uuid, Uuid>

@Serializable
@SerialName("likes")
data class Likes(
    override val fromId: Uuid,
    override val toId: Uuid,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = Clock.System.now(),
) : EdgeLike<Uuid, Uuid>
```

Register them in a `SerializersModule`:

```kotlin
val module = SerializersModule {
    polymorphic(NodeLike::class) { subclass(Person::class) }
    polymorphic(EdgeLike::class) { subclass(Knows::class) }
}
```

---

### Key adapters

`AbyssGraphSchema<ID>` (what `SingleSchemaGraph`/`HomogeneousSchemaGraph.forTag`/
`HeterogeneousSchemaGraph.register` all return) is typed per instance. The `ID` type is fixed at
construction via a `KeyAdapter<ID>` (simplified — the real interface also covers native partition
keys and Compact edge-key encoding, see [Edge key encoding](#edge-key-encoding)):

```kotlin
interface KeyAdapter<ID> {
    fun toNodeId(id: ID): NodeId      // domain ID → internal key
    fun fromNodeId(nodeId: NodeId): ID
}
```

Three adapters are provided out of the box:

| Adapter | ID type |
|---|---|
| `UuidKeyAdapter` | `kotlin.uuid.Uuid` |
| `LongKeyAdapter` | `Long` |
| `StringKeyAdapter` | `String` |

Pass the adapter as the first argument to `SingleSchemaGraph`/`AbyssGraphSchema`. Node/edge interfaces
(`NodeLike<ID>`, `EdgeLike<FID, TID>`) carry the domain `ID` type, and the internal `NodeId(ByteArray)`
key is never exposed. Traversal **results are raw**: `Subgraph`/`Path` hold heterogeneous
`List<NodeLike<*>>` / `List<EdgeLike<*, *>>` (a walk may cross schemas), and `collectNodes<T>()` /
`Subgraph.resolve<T>()` narrow them to a concrete type.

#### Edge key encoding

`registerAbyssSerializers(adapter, module)` also takes the `KeyAdapter` (as an `EdgeAdapter`) so
`EdgeKey`/`ReverseEdgeKey` compact serializers and the `outEdges`/`inEdges` predicates encode
`fromId`/`toId` in each adapter's native, directly-comparable form instead of a hex string:

| Adapter | `fromId`/`toId` field encoding |
|---|---|
| `UuidKeyAdapter` | two `Int64` fields (`fromIdHi`/`fromIdLo`, `toIdHi`/`toIdLo`) |
| `LongKeyAdapter` | single `Int64` field |
| `StringKeyAdapter` | single `String` field (the raw ID, not hex) |

Because of this, **the `adapter` passed to `registerAbyssSerializers` must match the `KeyAdapter`
used by every `AbyssGraphSchema<ID>` sharing that `HazelcastInstance`** — one Hazelcast instance can
only carry one Compact schema per class, so only one native `EdgeKey`/`ReverseEdgeKey` encoding.
Standalone (`SingleSchemaGraph`) schemas with different `ID` types need separate Hazelcast instances
(and, if colocated on the same host, distinct `clusterName`s to avoid auto-joining) — or a single
[multi-schema container](#multi-schema-graphs--cross-schema-edges)
(`HomogeneousSchemaGraph`/`HeterogeneousSchemaGraph`), which sidesteps the limitation with a shared
`EdgeAdapter` whose `EdgeKey` layout carries the schema tag plus each endpoint's native shape. This
coexistence is not free for `HeterogeneousSchemaGraph` — its shared layout is a fixed superset (
`tag` + kind + `hi`/`lo` + nullable `str`) and its `outEdges`/`inEdges` predicate compares two fields (the
tag plus the value) instead of one, since two different `ID` shapes can otherwise collide on `lo`
within a partition. `HomogeneousSchemaGraph` avoids even that cost — see below. See
[Performance](#performance) for the measured overhead versus a standalone schema.

---

### Multi-schema graphs & cross-schema edges

Three container tiers host several typed `AbyssGraphSchema<ID>` views over one shared
`HazelcastInstance` and one shared node/edge map pair. Pick the tier by how many distinct **shapes**
(`KeyAdapter<ID>` types) coexist:

| Tier | Shapes | Tag | Header byte | Use when |
|---|---|---|---|---|
| `SingleSchemaGraph` | 1 | none | **no** | exactly one schema, no tenant/dictionary split (see [Quick start](#quick-start)) — e.g. an ACL graph: subjects and resources as nodes, permission edges between them, one graph for the whole application |
| `HomogeneousSchemaGraph` | 1, many tags | `SchemaTag` (up to 128 bits) | **no** | many same-shape tenants — e.g. a per-user `Uuid` partitioning a personal app's data, so every user's notes/tasks/whatever live in one shared graph, isolated purely by tag |
| `HeterogeneousSchemaGraph` | many | `SchemaTag` | yes | a handful of genuinely different-shaped schemas registered once at startup — e.g. all of a company's reference dictionaries (departments, cost centers, product categories, currencies) in one graph, each its own shape, cross-linked where the business logic needs it |

Every tag is a `SchemaTag` — a real 128-bit value (`hi`/`lo` longs), not just a `Long`.
`SchemaTag(1L)` covers the common small-integer case; `SchemaTag.of(uuid)` uses a `Uuid` directly as
the tag. `HomogeneousSchemaGraph`/`SingleSchemaGraph` write **no 1.15 header byte** at all — width and
shape are already fixed by the container, so there's nothing left to self-describe, which keeps a
`Uuid` tag + `Uuid` id at a clean 32 bytes instead of 33. `HeterogeneousSchemaGraph` keeps the header
byte because its schemas can differ in shape, so the header's kind nibble is genuinely informative
there. **Because of this, each container tier (and each distinct width/shape within a tier) needs its
own dedicated `HazelcastInstance`** — a headerless container's keys aren't self-describing, so they're
incompatible with any other container's registered Compact adapter sharing the same instance.

#### `HeterogeneousSchemaGraph` — mixed shapes

Since one Hazelcast Compact schema per class can't express heterogeneous native shapes, the
container encodes `EdgeKey`/`ReverseEdgeKey` as a fixed self-describing superset — schema tag + a
kind discriminator + the inner adapter's native fields — via a `MultiSchemaAdapter`. `Long`/`Uuid`
schemas keep native (non-hex) predicates; only `String` schemas pay a string compare, on their own
(short) value. Since the Compact layout must be fixed before the `HazelcastInstance` starts, register
the shared `MultiSchemaAdapter` for the container's `tagWidth`, then `register()` each schema:

Two independent schemas — `City` keyed by `Long`, `Person` keyed by `Uuid` (both defined earlier in
this README):

```kotlin
val module = SerializersModule {
    polymorphic(NodeLike::class) { subclass(City::class); subclass(Person::class) }
    polymorphic(EdgeLike::class) { subclass(Road::class); subclass(Knows::class); subclass(LivesIn::class) }
}

val tagWidth = SchemaTagWidth.BYTE
val hz = Hazelcast.newHazelcastInstance(Config().registerAbyssSerializers(MultiSchemaAdapter(tagWidth), module))

val container = HeterogeneousSchemaGraph(hz, tagWidth, "abyss-nodes", "abyss-edges", allowCrossSchemaEdges = true)
val cities:  AbyssGraphSchema<Long> = container.register(SchemaTag(1L), LongKeyAdapter)
val persons: AbyssGraphSchema<Uuid> = container.register(SchemaTag(2L), UuidKeyAdapter)

val warsaw = City(id = 1L, name = "Warsaw")
val alice  = Person(name = "Alice")
cities.transaction { addNode(warsaw) }
persons.transaction { addNode(alice) }
```

Each `register()` call returns a facade that behaves exactly like a standalone `AbyssGraphSchema` —
hold onto what it returns for `transaction { }`, `from { }`, `outEdges`, etc. (there's no separate
lookup-by-tag method; the container itself keeps only enough bookkeeping to reject a duplicate tag
and validate cross-schema edges). Intra-schema queries never see another schema's nodes or edges.

##### Cross-schema edges

Edges *between* schemas live in the **same** shared edge/reverse maps as intra-schema edges — their
tagged endpoints self-describe (`fromTag != toTag`), so `outgoing<E>()` / `incoming<E>()` reach them
as **ordinary traversal hops**. They're disabled by default — pass `allowCrossSchemaEdges = true` to
the container, otherwise `addCrossEdge` returns `AbyssError.IntegrityError`.

Declare a cross-edge class with real domain types (not `NodeId`) and `@CrossSchemaEdge`, describing
each endpoint's tag/width/adapter — the same `(tag, width, adapter)` triple you passed to that
schema's `register()`/`forTag()`. `addCrossEdge` then resolves both endpoints from the annotation
alone, no manual `NodeId` construction:

```kotlin
@Serializable
@SerialName("lives_in")
@CrossSchemaEdge(
    fromTag = 2L, fromAdapter = UuidKeyAdapter::class, // matches persons' register()
    toTag = 1L, toAdapter = LongKeyAdapter::class,      // matches cities' register()
)
data class LivesIn(
    override val fromId: Uuid,   // a Person's domain id
    override val toId: Long,     // a City's domain id
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = Clock.System.now(),
) : EdgeLike<Uuid, Long>

// Composable with ordinary node/edge ops in the SAME atomic transaction — not a separate commit:
persons.transaction {
    addNode(alice)
    addCrossEdge(LivesIn(alice.id, warsaw.id))
}

// Or as a batch of cross-only ops committed atomically at the container level:
container.transaction { addCrossEdge(LivesIn(alice.id, warsaw.id)) }

// A cross-hop is an ordinary DSL hop; the frontier lands in the target schema. A single expression
// can mix intra- and cross-schema hops, then materialise in whichever schema it ends up in:
persons.from(alice.id) {
    outgoing<Knows>()      // intra-schema (stays in `persons`)
    outgoing<LivesIn>()    // cross-schema (lands in `cities`)
    collectNodes<City>()   // resolves each reached NodeId to its schema
}.getOrNull()?.collect { println(it.name) }

// Ephemeral (TTL) cross edge inside a transaction — same annotation, routes to the ephemeral store:
persons.ephemeral(ttl = 60.seconds) { addCrossEdge(LivesIn(alice.id, warsaw.id)) }
```

`@CrossSchemaEdge`'s `fromTag`/`toTag` are `Long` (covers `BYTE`/`SHORT`/`INT`/`LONG` tag widths). A
genuine 128-bit tag (`SchemaTagWidth.UUID` with a real 128-bit value) can't be expressed in the
annotation — for that rare case, fall back to the raw escape hatch, unchanged: build each endpoint's
tagged `NodeId` by hand with the same `SchemaKeyAdapter` `register()` used, and construct the edge as
`EdgeLike<NodeId, NodeId>` directly (`container.addCrossEdge(LivesIn(fromId = aliceNid, toId = warsawNid))`).
There's no `width` field — a container fixes one `SchemaTagWidth` for its whole lifetime, so it's
reconstructed from the container at resolution time instead of being duplicated on every edge class.

Either way, `addCrossEdge`/`removeCrossEdge` route through the same commit pipeline as ordinary
node/edge ops: they check both endpoints resolve to a registered schema tag and that the node
actually exists before writing, and get `@EdgeConstraint` enforcement for free. Cross-schema edges
persist through the same `persistentStore`/`ephemeralStore` as an ordinary edge — a store-configured
container self-heals a cross edge on cold restart the same way it does for intra-schema edges.
Because cross edges share the intra-schema maps, an untyped whole-node scan (`outEdges(node)` with no
type) now includes them; typed hops are unaffected.

#### `HomogeneousSchemaGraph` — one shape, many (possibly unbounded) tags

When every schema shares the exact same `KeyAdapter<ID>`, there's nothing for a per-schema registry
to track — `HomogeneousSchemaGraph` holds **one** `keyAdapter` for the whole container and builds a
view for a given tag **on the fly**, with no registration step and no stored state. This is the tier
for a personal/multi-tenant app where the tag *is* a per-user `Uuid`: the tag space is unbounded (one
per user), so pre-registering it in a `Set` the way `HeterogeneousSchemaGraph` does isn't viable.

```kotlin
val module = SerializersModule {
    polymorphic(NodeLike::class) { subclass(Note::class) }
    polymorphic(EdgeLike::class) { subclass(References::class) }
}

val tagWidth = SchemaTagWidth.UUID   // the tag itself is a full Uuid
val hz = Hazelcast.newHazelcastInstance(
    Config().registerAbyssSerializers(HeaderlessMultiSchemaAdapter(tagWidth, NodeKeyKind.INT64), module)
)

val notes = HomogeneousSchemaGraph(hz, tagWidth, LongKeyAdapter, "notes-nodes", "notes-edges")

// Each user's data lives in the SAME shared maps, isolated purely by tag — no per-user setup.
val alice = notes.forTag(SchemaTag.of(aliceUserId))   // AbyssGraphSchema<Long>
val bob   = notes.forTag(SchemaTag.of(bobUserId))

alice.transaction { addNode(Note(id = 1L, text = "Alice's note")) }
bob.transaction { addNode(Note(id = 1L, text = "Bob's note")) }   // same numeric id, different tenant
```

`forTag` is a plain one-liner — call it as often as you like; it's not cached, and building a fresh
`AbyssGraphSchema` is cheap (it just wraps the shared worker). Cross-schema edges work the same way as
`HeterogeneousSchemaGraph`'s (`addCrossEdge`/`allowCrossSchemaEdges`), except same-tag edges always
succeed (they're ordinary same-tenant edges) and there's no "was this tag ever used" check — any tag
is implicitly valid, since there's no registry to check it against.

---

### In-memory only (no persistent store)

```kotlin
val config = Config().registerAbyssSerializers(UuidKeyAdapter, module)
val hz = Hazelcast.newHazelcastInstance(config)
val graph = AbyssGraphSchema(UuidKeyAdapter, hz, nodesMapName = "nodes", edgesMapName = "edges")

// write
graph.transaction {
    addNode(Person(name = "Alice"))
    addNode(Person(name = "Bob"))
    addEdge(Knows(fromId = alice.id, toId = bob.id))
}

// read
val alice: Either<AbyssError, Person> = graph.node<Person>(alice.id)

// traverse — frontier nodes only
graph.from(alice.id) {
    outgoing<Knows>()
    nodes<Person>()
    collectNodes<Person>().collect { println(it.name) }             // prints everyone alice knows

    // with predicate — only Bob
    nodes<Person> { it.name == "Bob" }
    collectNodes<Person>().collect { println(it.name) }
}

// traverse — full subgraph (all visited nodes + all traversed edges)
val result: Either<AbyssError, Subgraph> = graph.from(alice.id) {
    outgoing<Knows>()         // hop 1: alice → bob, alice → charlie
    outgoing<Knows>()         // hop 2: bob → dave
    subgraph()  // Person nodes only + all 3 Knows edges
    // subgraph()             // all visited nodes regardless of type + all 3 Knows edges
}
val (persons, edges) = result.getOrNull()!!
persons.forEach { println((it as Person).name) }
edges.forEach { println("${it.fromId} → ${it.toId}") }

// multi-hop with edge + node filters — collect intermediate nodes via subgraph
// "Alice's adult acquaintances (known since 2020+) who like Astronomy"
val astronomers: Either<AbyssError, Subgraph> = graph.from(alice.id) {
    outgoing<Knows> { it.since > Instant.parse("2020-01-01T00:00:00Z") }  // edge filter
    nodes<Person> { it.age > 18 }              // narrow frontier to adults
    outgoing<Likes>()                          // hop to their interests
    nodes<Interest> { it.name == "Astronomy" } // narrow to Astronomy
    subgraph()                   // collect the intermediate Person nodes
}
val astronomyFans = astronomers.getOrNull()!!.nodes.filterIsInstance<Person>()
```

---

### With YugabyteDB persistence

Apply the schema scripts from `abyss-store-yugabyte/src/main/resources/db/` then:

```kotlin
val persistentStore = YugabytePersistentStore.create(
    ysqlUrl      = "jdbc:postgresql://localhost:5433/my_graph",
    ysqlUser     = "abyss",
    ysqlPassword = "abyss",
    module       = module,
)

val ephemeralStore = YugabyteEphemeralStore.create(
    ycqlHost       = "localhost",
    ycqlPort       = 9042,
    ycqlDatacenter = "datacenter1",
    module         = module,
)

val config = Config().registerAbyssSerializers(UuidKeyAdapter, module)
val hz     = Hazelcast.newHazelcastInstance(config)
val graph  = AbyssGraphSchema(UuidKeyAdapter, hz, "nodes", "edges",
    persistentStore = persistentStore,
    ephemeralStore  = ephemeralStore,
)

// durable write (goes to YSQL)
graph.transaction {
    addNode(Person(name = "Alice"))
}

// ephemeral write with TTL — stored in YCQL, expires after 60 s
graph.ephemeral(ttl = 60.seconds) {
    addNode(Person(name = "TemporaryBob"))
}
```

Either store can be omitted. Pass only `persistentStore` for YSQL-only durability (no TTL support),
or only `ephemeralStore` for TTL-only data (nothing survives a YCQL keyspace drop). Pass neither for
pure in-memory (Hazelcast-only) mode.

Cache misses trigger automatic load from whichever store(s) are configured; both are queried in
parallel and the persistent result wins if both return a hit.

#### Multiple graphs in one application

Each graph needs its own YSQL schema and YCQL keyspace. Pass them to each `create()`. This pattern
gives each schema its own independent nodes/edges maps with no cross-graph queries; if the graphs
need to reference each other's nodes directly, use a [multi-schema
container](#multi-schema-graphs--cross-schema-edges) instead.

```kotlin
val socialPersistent = YugabytePersistentStore.create(
    ysqlUrl = "jdbc:postgresql://localhost:5433/mydb",
    ysqlUser = "app", ysqlPassword = "secret",
    module = socialModule, ysqlSchema = "social",
)
val socialEphemeral = YugabyteEphemeralStore.create(
    module = socialModule, ycqlKeyspace = "social_graph",
)

val productPersistent = YugabytePersistentStore.create(
    ysqlUrl = "jdbc:postgresql://localhost:5433/mydb",
    ysqlUser = "app", ysqlPassword = "secret",
    module = productModule, ysqlSchema = "product",
)
val productEphemeral = YugabyteEphemeralStore.create(
    module = productModule, ycqlKeyspace = "product_graph",
)

val socialGraph  = AbyssGraphSchema(UuidKeyAdapter, hz, "social-nodes",  "social-edges",
    persistentStore = socialPersistent, ephemeralStore = socialEphemeral)
val productGraph = AbyssGraphSchema(UuidKeyAdapter, hz, "product-nodes", "product-edges",
    persistentStore = productPersistent, ephemeralStore = productEphemeral)
```

Hazelcast map names must also be distinct (the `nodesMapName` / `edgesMapName` arguments above).

---

### Add / remove nodes and edges

Mutations go through `transaction { }` (persistent, YSQL) or `ephemeral { }` (TTL-bound, YCQL).
The two builders are intentionally separate — they cannot be combined into one atomic operation.

#### Persistent mutations

```kotlin
graph.transaction {
    addNode(Person(name = "Alice"))          // insert or overwrite
    removeNode(alice.id)                    // delete node — cascades to edges (see below)
    addEdge(Knows(fromId = alice.id, toId = bob.id))
    removeEdge<Knows>(fromId = alice.id, toId = bob.id)  // delete by type (compile-time)
    removeEdge(alice.id, bob.id, "knows")                // delete by type string
    removeEdge(edge)                                     // delete by edge instance
}
```

#### Node and edge modification

`modifyNode` and `modifyEdge` are read-modify-write operations: they fetch the current value
internally and pass it to a transform lambda. No pre-fetch required.

```kotlin
graph.transaction {
    // update a node in place — old is null if the node doesn't exist yet
    modifyNode(alice.id) { old ->
        (old as Person).copy(age = old.age + 1)
    }

    // retarget an edge — old is null if the edge doesn't exist
    modifyEdge(alice.id, bob.id, "knows") { old ->
        (old as Knows).copy(toId = charlie.id)
    }
}
```

Both transforms receive `null` when the target doesn't exist, letting the lambda decide whether
to create, throw, or no-op. The same operations are available inside `ephemeral { }`.

#### Ephemeral mutations (TTL-bound)

```kotlin
graph.ephemeral(ttl = 60.seconds) {
    addNode(Person(name = "TempBob"))       // stored in YCQL, expires after 60 s
    addEdge(Knows(fromId = alice.id, toId = bob.id))
}
```

All operations inside `ephemeral { }` share the same TTL. Nodes and edges are stored in
YCQL and disappear automatically when the TTL elapses. The cache entry also expires at the
same time. Ephemeral **edges are outgoing-only** — reachable via `outEdges`/`outgoing` but never
`inEdges`/`incoming` (see [Pluggable storage](#pluggable-storage)).

To disable integrity checks for bulk imports:

```kotlin
graph.ephemeral(ttl = 300.seconds, checkIntegrity = false) {
    nodes.forEach { addNode(it) }
    edges.forEach { addEdge(it) }
}
```

#### Node removal cascades to edges

When `removeNode(id)` is called, **all edges where `fromId == id` or `toId == id` are automatically deleted** in the same transaction. Dangling edges are never left behind.

```kotlin
// alice → bob (Knows), charlie → alice (Knows)
graph.transaction { removeNode(alice.id) }
// both edges are gone; bob and charlie nodes are untouched
```

This cascade is resolved against the current cache state at commit time. Edges added *within the same transaction* as the `removeNode` are not included in the cascade — avoid that combination.

#### Referential integrity

By default, `addEdge` verifies that both `fromId` and `toId` refer to existing nodes and returns
`AbyssError.IntegrityError` if either is missing. Pass `checkIntegrity = false` to skip this
check for bulk operations (e.g. graph import) where node existence is guaranteed by the caller.

#### Schema enforcement

Annotate an edge class with `@EdgeConstraint` to declare which node types each endpoint must be:

```kotlin
@Serializable
@SerialName("knows")
@EdgeConstraint(fromTypes = [Person::class], toTypes = [Person::class])
data class Knows(override val fromId: Uuid, override val toId: Uuid, ...) : EdgeLike<Uuid, Uuid>
```

When `checkIntegrity = true` (the default), `addEdge` checks that the actual node types of both
endpoints match the constraint and returns `AbyssError.SchemaError` on a mismatch.

`@EdgeConstraint` is opt-in — edge types without the annotation are unconstrained. Empty arrays
(`fromTypes = []`) also mean unconstrained, so adding a constraint to an existing edge type is
always a non-breaking change for old data already in storage (only new writes are checked).

For schema evolution: `UnknownNode` endpoints (nodes from a schema version not known to the
current application) are always permitted, so a rolling deployment with mixed schema versions
will not produce false `SchemaError`s.

---

### Edge queries

`outEdges` and `inEdges` are available both as raw flows and via the traversal DSL.

```kotlin
// all outgoing edges from a node
graph.outEdges(alice.id).collect { edge -> println(edge) }
graph.outEdges<Knows>(alice.id).collect { knows -> println(knows) }   // typed + type-filtered

// all incoming edges to a node
graph.inEdges(bob.id).collect { edge -> println(edge) }
graph.inEdges<Knows>(bob.id).collect { knows -> println(knows) }
```

#### Partition layout

Both directions are partition-local:

- **`outEdges`** — `EdgeKey` is `PartitionAware` on `fromId`, so all outgoing edges of a node live on one partition. The query never scatters.
- **`inEdges`** — a mirrored `IMap<ReverseEdgeKey, Unit>` is maintained in sync with the edge map. `ReverseEdgeKey` is `PartitionAware` on `toId`, so the reverse lookup is also single-partition. The reverse map holds only keys; actual edge data is fetched via `IMap.getAll` point-lookups on the primary map.

The reverse map is maintained for **persistent** edges only, kept consistent by every `addEdge` / `removeEdge`. Ephemeral (TTL) edges are [outgoing-only](#pluggable-storage) and write no reverse entry, so `inEdges` never returns them.

#### Node collection

`nodes<T>()` narrows the frontier to nodes of type `T`, loading each one to check its type.
It is a **non-terminal step** — the frontier is updated in place, so subsequent `outgoing` /
`incoming` hops travel only from surviving nodes. Nodes pruned by `nodes` are also removed
from the visited history, so they don't appear in a later `subgraph` call.

`nodes<T> { predicate }` does the same and additionally discards nodes where the predicate
returns false.

`collectNodes<T>()` is the terminal that emits all nodes currently in the frontier as a
`Flow<T>`. Call it after one or more `nodes<T>` steps to materialise the result:

```kotlin
outgoing<Knows>()
nodes<Person> { it.age > 18 }   // non-terminal: narrow frontier
collectNodes<Person>().toList() // terminal: emit survivors
```

`subgraph()` returns a raw `Subgraph(nodes, edges)` containing every node visited across **all hops**
(including the start node) and every edge traversed — heterogeneous `List<NodeLike<*>>` /
`List<EdgeLike<*, *>>`, since a walk may span schemas. Narrow it with `subgraph().resolve<Person>()`
(filters to `Person`), or partition the raw list manually to extract multiple types. Nodes are
resolved in parallel; edges are already in memory from the hop results. Because `nodes<T>` prunes the
visited history, `subgraph().resolve<Person>()` after a multi-hop traversal returns only the Persons
that survived node filters — not every Person ever reached.

#### Connectivity filters

`hasOutgoing<E>(targetId)` keeps only frontier nodes that have an outgoing edge of type `E` to a
specific target node. `hasOutgoing<E, N>()` keeps only those with an outgoing edge of type `E` to
**any** node of type `N`. Both leave the frontier where it is — they filter without advancing it.
`hasIncoming<E>(sourceId)` and `hasIncoming<E, N>()` are the symmetric incoming variants.

Chaining two filters expresses AND — a node must satisfy both to survive:

```kotlin
// "People Alice knows who like Astronomy specifically"
val result = graph.from(alice.id) {
    outgoing<Knows>()
    hasOutgoing<Likes>(astronomyInterestId)    // keep only those with a Likes edge to this node
    collectNodes<Person>()
}

// conjunction — "People Alice knows who like BOTH Astronomy and Jazz"
val both = graph.from(alice.id) {
    outgoing<Knows>()
    hasOutgoing<Likes>(astronomyInterestId)    // AND
    hasOutgoing<Likes>(jazzInterestId)
    collectNodes<Person>()
}
```

`hasTraversal { }` is a generalised form: it runs an arbitrary sub-traversal from each frontier
node and keeps only those where the sub-traversal ends with a non-empty frontier. Unlike
`hasOutgoing`/`hasIncoming` (single-hop), the block can perform multi-hop checks. Direction is
expressed inside the block via `outgoing` / `incoming` calls.

```kotlin
// "People Alice knows who have at least one mutual connection with Bob"
// (i.e. someone alice knows, who also knows someone bob knows)
val mutuals = graph.from(alice.id) {
    outgoing<Knows>()
    hasTraversal {
        outgoing<Knows>()           // hop from each candidate
        hasIncoming<Knows>(bob.id)  // keep only those bob also knows
    }
    collectNodes<Person>()
}
```

#### Cold-restart behaviour

After a Hazelcast restart the maps are empty. On the first `outEdges(nodeId)` or `inEdges(nodeId)` call, Abyss loads the relevant edges from the store (if configured) and warms both `edgesMap` and `reverseEdgesMap` before executing the query. Subsequent calls for the same node are served from the warm cache.

---

## Graph algorithms

All algorithms operate against the Hazelcast in-memory maps. When a store is configured,
individual edge lookups (`outEdges` / `inEdges`) may trigger `MapLoader` reads from the DB on a
cache miss — the same lazy-load behaviour as any other traversal. **`connectedComponents` is the
exception:** it discovers nodes via `allNodeIds()`, which reads only the keys currently present in
the Hazelcast node map. Nodes that have never been loaded (cold after a restart) are not visible
to it — pre-warm the map or use it only in in-memory-only deployments for a full picture.

### `allReachable` — BFS exhaust

Visits every node reachable from the start, following whichever edge types the caller's block
specifies, and returns a `Subgraph` of all visited nodes and all traversed edges. Unlike repeated
`outgoing<E>()` hops, the caller does not need to know the depth in advance.

```kotlin
val subgraph: Either<AbyssError, Subgraph> = graph.from(alice.id) {
    allReachable { outgoing<Knows>() }
}
val (nodes, edges) = subgraph.getOrNull()!!
```

Cycles are handled — already-visited nodes are skipped, so the BFS terminates even on cyclic
graphs.

### `hasCycle` — DFS cycle detection

Returns `true` if any cycle is reachable from the start node via the specified edge types. Uses
DFS with a recursion stack (back-edge detection).

```kotlin
val cyclic: Either<AbyssError, Boolean> = graph.from(alice.id) {
    hasCycle { outgoing<Knows>() }
}
```

Useful for validating that a subgraph forms a DAG before operations that assume acyclicity.

### `connectedComponents` — weakly connected grouping

Partitions **all nodes currently in the Hazelcast map** into weakly connected components — groups
where every node can reach every other when edges are treated as undirected. Returns
`List<Set<ID>>`.

```kotlin
val components: List<Set<Uuid>> = graph.connectedComponents()   // List<Set<ID>> in general
// e.g. [{alice, bob, charlie}, {dave, eve}]
```

Scans `allNodeIds()` and performs a BFS over both `outEdges` and `inEdges` per node. Results are
only complete when the node map is fully warm. Suitable for one-shot analysis; not intended for
hot paths.

### `paths` — Neo4j-style path traversal

Walks the graph from the start node using caller-controlled visitors, emitting a `Path` for each
accepted terminal. Two visitors control the traversal:

- **`edgeVisitor(path, edge) → Boolean`** — return `true` to follow the edge; `false` to skip it.
- **`nodeEvaluator(path, node) → Evaluation`** — decide what to do at each candidate node:
  - `INCLUDE_AND_PRUNE` — accept this node, emit the path, stop recursing from here.
  - `INCLUDE_AND_CONTINUE` — accept this node, keep going deeper; emits if `maxDepth` is reached.
  - `EXCLUDE_AND_CONTINUE` — skip this node (not added to path), but keep recursing from it.
  - `EXCLUDE_AND_PRUNE` — skip this node and stop this branch entirely.

Both visitors receive the current `Path` — the accepted chain so far — so decisions can be
context-aware (e.g. prune if a certain node type already appears in the path).

```kotlin
// Find all permission paths from alice to any Resource via ACL edges (DFS, outgoing only, max 5 hops)
val paths: Either<AbyssError, List<Path>> = graph.from(alice.id) {
    paths(
        strategy = TraversalStrategy.DFS,
        direction = EdgeTraversalDirection.OUT,
        maxDepth = 5,
        edgeVisitor = { _, edge -> edge is AclEdge },
        nodeEvaluator = { _, node -> when (node) {
            is Resource -> Evaluation.INCLUDE_AND_PRUNE    // found target — emit and stop branch
            is Group    -> Evaluation.EXCLUDE_AND_CONTINUE // pass-through intermediate group
            else        -> Evaluation.EXCLUDE_AND_PRUNE    // stop on unexpected types
        }}
    ).toList()
}

// Inspect elements in traversal order
paths.getOrNull()!!.forEach { path ->
    path.toEitherList().forEach { element ->
        element.fold({ edge -> print(" →[${edge::class.simpleName}]") },
                     { node -> print(" ${node::class.simpleName}") })
    }
    println()
}
```

`BFS` strategy emits shortest paths first. Each emitted `Path` is self-contained: `path.nodes`
and `path.edges` are ordered from origin to terminal; `path.toEitherList()` interleaves them as
`List<Either<EdgeLike<*, *>, NodeLike<*>>>` in traversal order.

**Node uniqueness** is path-local: a node cannot appear twice within a single emitted path, but
the same node may appear in multiple independently emitted paths (one per branch that reaches it).
Within a single expansion step, if two edges from the same parent lead to the same neighbour, that
neighbour is visited only once from that parent. This is distinct from global uniqueness (where a
node visited anywhere terminates all future visits to it) — path-local uniqueness means all
distinct routes through a node are still found, just without revisiting the same node within one
route. Cycles are handled as a consequence: a back-edge to an already-visited ancestor is skipped,
so traversal always terminates.

---

## Performance

Measured on a single JVM, pure in-memory mode (no persistent store), 10,000 nodes × 5 edges/node.
Hardware: AMD Ryzen 5 2600 (6-core/12-thread), 32 GB RAM.
All queries run single-threaded; real throughput scales linearly with available cores.

| Adapter | `outEdges` | `inEdges` | 3-hop traversal |
|---|---|---|---|
| `UuidKeyAdapter` | **3,095 ops/sec** | **1,110 ops/sec** | **5.4 ms avg** |
| `LongKeyAdapter` | **3,442 ops/sec** | **1,213 ops/sec** | **4.3 ms avg** |
| `StringKeyAdapter` | **2,635 ops/sec** | **879 ops/sec** | **5.4 ms avg** |

`outEdges`/`inEdges` predicates compare `fromId`/`toId` in each adapter's native encoding (see
[Edge key encoding](#edge-key-encoding)) rather than the hex strings used previously — a fixed
`Int64` (or `Int64` pair for UUID) compare, or a native (non-hex) `String` compare, instead of a
char-by-char hex scan at 2× the byte length. This closed most of the gap between adapters:
`StringKeyAdapter` now trails `LongKeyAdapter` only because its field is still variable-length
(10–50 chars, the ID itself) rather than a fixed 8 bytes, not because of a hex-expansion penalty.

`inEdges` is slower than `outEdges` for all adapters: it resolves edge data via `IMap.getAll`
point-lookups after the reverse-key scan — the reverse map holds only keys, not payloads.

### Multi-schema container overhead

A schema registered *inside* a `HeterogeneousSchemaGraph` container does not match its standalone
throughput. The shared `MultiSchemaAdapter` layout is a fixed superset (`tag`/kind/`hi`/`lo`/`str`),
and `outEdges` evaluates a two-field predicate (`fromIdTag` + `fromIdLo`) over that wider record
instead of a single-field compare. Measured same-JVM against a standalone `LongKeyAdapter` schema
(`MultiSchemaPerformanceTest`): `outEdges` runs at **~0.7×** standalone throughput, while `inEdges`
and 3-hop traversal are **within noise** (the reverse scan and per-node resolution dominate those,
not the key predicate).

The `tag` clause is not optional — two different `ID` shapes can collide on `lo` within a partition,
so it is what keeps a `Long` query from matching a `Uuid` edge whose low 64 bits coincide. This means
`HeterogeneousSchemaGraph` trades the previous uniform-hex encoding for a superset-predicate cost of a
**similar** order on `outEdges`, rather than a clear win — the native encoding's decisive advantage is
on the **standalone** single-schema path (the table above), where the key is a single native field.
`HomogeneousSchemaGraph` doesn't pay this cost: its adapter shape is fixed for the whole container
(one descriptor computed once, not re-derived per key), and its keys carry no header byte at all.

Earlier revisions of this table showed Long/String 3-hop pinned at a flat ~0.7ms versus Uuid's
~3.6ms — a benchmark bug (`TODO.md` 4.6), not a real cost difference: `LongPerformanceTest`/
`StringPerformanceTest` seeded their edges with the wrong type string, so their typed 3-hop hops
silently matched nothing and measured an empty traversal every time. With that fixed, all three
adapters land in the same single-digit-millisecond range for a real 3-hop × 5-fanout traversal at
10k nodes — `LongKeyAdapter` modestly fastest (a fixed 8-byte key, no `Uuid` hashCode/equals
overhead), `UuidKeyAdapter`/`StringKeyAdapter` statistically indistinguishable from each other
run-to-run. `NodeLike`/`EdgeLike` payloads go through a custom JSON `StreamSerializer`
(`NodeLikeHzSerializer`/`EdgeLikeHzSerializer`), not Hazelcast Compact — only `NodeId`/`EdgeKey`/
`ReverseEdgeKey` use real Compact — and isolating that JSON roundtrip entirely
(`SerdeRoundtripPerformanceTest`) still measures a small (~1.8x edge / ~1.2x node) Uuid cost,
consistent with `LongKeyAdapter`'s modest edge. See `TODO.md` 3.5/3.6 for the (now-corrected)
investigation history.

To reproduce:

```
./gradlew :abyss-graph:test --tests "pl.iqtech.abyss.graph.UuidPerformanceTest" -Pperf
./gradlew :abyss-graph:test --tests "pl.iqtech.abyss.graph.LongPerformanceTest" -Pperf
./gradlew :abyss-graph:test --tests "pl.iqtech.abyss.graph.StringPerformanceTest" -Pperf
./gradlew :abyss-graph:test --tests "pl.iqtech.abyss.graph.SerdeRoundtripPerformanceTest" -Pperf
./gradlew :abyss-graph:test --tests "pl.iqtech.abyss.graph.MultiSchemaPerformanceTest" -Pperf
```

### Concurrency scaling

Sweeping concurrent callers (N = 1, 2, 4, 8, 16, 32, each firing 200 ops/caller) against the
2×-enlarged astronomy schema of the Universe fixture, same 6-core/12-thread machine as above
(`AstronomyConcurrencyPerformanceTest`). The Universe fixture is a `HeterogeneousSchemaGraph`
container, so — per [Multi-schema container overhead](#multi-schema-container-overhead) above —
this is the **worst-case tier**: every key pays the superset (`tag`/kind/`hi`/`lo`/`str`) decode and
two-field predicate cost. A standalone schema or a `HomogeneousSchemaGraph` schema, paying neither
that cost nor a header byte, would scale further before hitting the same core-bound flattening
below:

| N callers | `outEdges` | `inEdges` | 3-hop traversal |
|---|---|---|---|
| 1  | 854 ops/sec | 1,360 ops/sec | 900 ops/sec |
| 2  | 2,816 ops/sec | 4,347 ops/sec | 2,259 ops/sec |
| 4  | 7,017 ops/sec | 7,017 ops/sec | 3,940 ops/sec |
| 8  | 10,738 ops/sec | 11,188 ops/sec | 5,000 ops/sec |
| 16 | 14,883 ops/sec | 17,391 ops/sec | 5,765 ops/sec |
| 32 | 16,666 ops/sec | 18,181 ops/sec | 7,795 ops/sec |

Throughput scales close to linearly up to N=4 — roughly this machine's physical core count — then
the curve bends: each doubling past N=8 buys a shrinking fraction more (`outEdges` N=16→32: +12%,
not +100%). The flattening isn't purely server-side saturation, either: this benchmark runs its
callers as coroutines in the *same* JVM as the code being measured, so once N exceeds the hardware
thread count, some of those 12 threads are busy driving the load rather than serving it — the
benchmark's own clients are competing with Abyss for the CPU they're both measured on. A real
deployment with callers on separate hosts from the graph would push this knee out further; this
number is the worst case a single co-located JVM sees, not a hard ceiling.

To reproduce: `./gradlew :abyss-graph:test --tests "pl.iqtech.abyss.graph.AstronomyConcurrencyPerformanceTest" -Pperf`

#### The best case: a standalone schema

Same sweep, same machine, but the architectural opposite tier: a standalone `SingleSchemaGraph`/
`LongKeyAdapter` (no schema tag, no header byte), at `LongPerformanceTest`'s 10,000-node/5-edges-per-
node ring-wrap scale instead of Astronomy's ~28-node fixture (`LongSchemaConcurrencyPerformanceTest`):

| N callers | `outEdges` | `inEdges` | 3-hop traversal |
|---|---|---|---|
| 1  | 1,129 ops/sec | 1,212 ops/sec | 352 ops/sec |
| 2  | 2,649 ops/sec | 2,857 ops/sec | 460 ops/sec |
| 4  | 7,017 ops/sec | 4,678 ops/sec | 754 ops/sec |
| 8  | 11,678 ops/sec | 6,530 ops/sec | 901 ops/sec |
| 16 | 16,080 ops/sec | 9,696 ops/sec | 922 ops/sec |
| 32 | 19,219 ops/sec | 11,721 ops/sec | 921 ops/sec |

**The two fixtures differ in topology, not just scale**, so only `outEdges` is a clean read on the
architecture cost in isolation: it's at or above the Heterogeneous table at every N (and pulls
further ahead at N=32: 19,219 vs 16,666), consistent with paying no tag/header decode. `inEdges` and
3-hop are confounded by fan-out degree, not schema overhead — Long's ring-wrap graph is a uniform
5-fan-out at every hop (a 3-hop traversal touches up to 155 nodes), while Astronomy's `Orbits` chain
is near-linear (degree ~1, moon→planet→star→singularity). That's why 3-hop throughput here is both
lower and flattens almost immediately past N=4 (~920 ops/sec at N=8 through N=32): each traversal is
doing roughly 40x more work than Astronomy's, so a single traversal's own internal coroutine fan-out
already saturates the machine — additional concurrent callers buy almost nothing further, unlike
Astronomy's fan-in-light chain, which keeps climbing to N=32.

To reproduce: `./gradlew :abyss-graph:test --tests "pl.iqtech.abyss.graph.LongSchemaConcurrencyPerformanceTest" -Pperf`

### Edge count per hop

`outgoing<E>()`/`incoming<E>()` fan out one `async { }` coroutine per frontier node in a single wave
(`TraversalBuilder.addHop`), so a hop's cost scales with how many edges it touches. A frontier-size
sweep (K = 1 to 10,000 concurrent per-node lookups, same machine as above) found per-edge cost
essentially **flat**, not degrading, as K grows:

| K (edges in the hop) | Total time | Per-edge cost |
|---|---|---|
| 10 | 2 ms | 218 µs |
| 100 | 10 ms | 109 µs |
| 1,000 | 51 ms | 51 µs |
| 5,000 | 273 ms | 55 µs |
| 10,000 | 513 ms | 51 µs |

**A reasonable ceiling for a single hop is around 1,000 edges** if you want that hop to finish in
tens of milliseconds — comfortably cheap up to ~100 (single-digit ms), noticeable but fine at
500-1,000 (~40-50 ms), and worth a second look past 5,000-10,000 (a quarter to half a second for
that one hop). Scaling past 10,000 wasn't measured, but the near-linear trend suggests extrapolating
linearly is reasonable (e.g. ~50,000 edges ≈ 2.5 s).

The ceiling that actually matters in production isn't a single hop's fan-out, though — it's
**aggregate concurrent load**. Per-edge cost doesn't rise with K in isolation because Hazelcast's
local predicate scan and the coroutine dispatcher have room to spare; the flattening seen in
[Concurrency scaling](#concurrency-scaling) above came from *many simultaneous callers* each doing
their own fan-out, all sharing the same bounded dispatcher/thread pool — not from any one hop
touching a lot of edges. A single supernode-sized hop is cheap; many concurrent traversals each
hitting one is what saturates the machine. See `TODO.md` 2.19 for chunking large per-hop fan-out so
one such traversal can't monopolize the shared pool out from under concurrent callers.

---

## Sizing — Sniper on Oracle Always Free (single Ampere A1)

Hardware: 4 vCPU ARM, 24 GB RAM. Runs YugabyteDB + Ktor app with embedded Hazelcast in Docker Compose.

### Data per user

Sniper domain: rifles (~4), scopes (~4), calibers (~3), loads (~15), components (~16),
sessions (~50/year), DOPE (~200), chrono (~200) = **~500 nodes + ~700 edges = ~1200 elements/user**

### Memory layout (1000 users)

Three Hazelcast maps:

```
nodes map        (IMap<UUID, NodeLike>):          1000 × 500 × 1.5 KB  =  750 MB
edges map        (IMap<EdgeKey, EdgeLike>):        1000 × 700 × 1.5 KB  = 1050 MB
reverse edge map (IMap<ReverseEdgeKey, Unit>):     1000 × 700 × 0.3 KB  =  210 MB  ← keys only
────────────────────────────────────────────────────────────────────────────────
Total graph data in heap                                                 ≈ 2.0 GB
```

The reverse edge map (`inEdges` partition index) holds only `ReverseEdgeKey` objects — no edge payload —
so each entry is ~0.3 KB vs 1.5 KB for a full edge value.

> **Working set vs total users:** the scaling ceilings below are worst-case — all users' data warm
> simultaneously. In practice, LRU eviction (`max-idle-seconds: 86400`, `FREE_HEAP_PERCENTAGE` policy)
> evicts cold users automatically. If 10 % of users are active at any time, only ~200 MB of the
> 2.0 GB/1,000-user figure is resident — the real capacity is roughly 10× the table ceiling on
> each machine.

| Process | RAM |
|---|---|
| YugabyteDB single-node | 8 GB |
| Ktor JVM — app + embedded Hazelcast (`-Xmx7g`) | 7 GB |
| OS | 1.5 GB |
| **Total** | **16.5 GB of 24 GB** |

`-Xmx2g` is sufficient without the graph cache.
With graph cache active: ~500 MB app + 2.0 GB data + GC headroom → **`-Xmx7g`**.

### Scaling ceiling on this machine

| Users | Heap needed | `-Xmx` | Total RAM | Status |
|---|---|---|---|---|
| 1,000 | ~2.7 GB | 7 GB | 16.5 GB | ✓ comfortable |
| 4,500 | ~12 GB | 14 GB | 23.5 GB | ✓ tight |
| 5,000 | ~13.5 GB | 15.5 GB | 25 GB | ✗ RAM exceeded |

Real ceiling: **~4,700 users** before needing more RAM (was ~5,000 before the reverse edge map was added).
CPU stays well under 50% past that point.

---

## Sizing — dedicated server (16 vCPU, 64 GB RAM)

Same data model — only the RAM envelope changes.

| Process | RAM |
|---|---|
| YugabyteDB single-node | 16 GB |
| Ktor JVM — app + embedded Hazelcast | varies |
| OS | 2 GB |
| **JVM budget** | **46 GB** |

### Scaling ceiling on this machine

| Users | Heap needed | `-Xmx` | Total RAM | Status |
|---|---|---|---|---|
| 5,000 | ~11 GB | 22 GB | 40 GB | ✓ comfortable |
| 10,000 | ~21 GB | 42 GB | 60 GB | ✓ tight |
| 11,000 | ~23 GB | 46 GB | 64 GB | ✗ RAM exceeded |

Real ceiling: **~10,500 users**. At that scale (~1,050 concurrent at 3 ms avg across 16 cores) CPU sits at ~20% peak — RAM is the constraint, not CPU.

---

## Sizing — 3-node cluster (3 × 16 vCPU, 64 GB RAM, `backup-count = 0`)

`backup-count = 0` means no partition replicas — each node owns ~1/3 of the total Hazelcast data. A node
failure evicts that third from the cache; `MapLoader` reloads from YugabyteDB on miss. This is a
performance event, not data loss: YugabyteDB RF=3 means all durable data survives one node failure.

YugabyteDB runs as a 3-node RF=3 cluster — each node hosts a tablet server (~12 GB, lower per-node than
single-node because coordinator work is distributed).

| Process | RAM / node |
|---|---|
| YugabyteDB tablet server | 12 GB |
| Ktor JVM — app + embedded Hazelcast | varies |
| OS | 2 GB |
| **JVM budget / node** | **50 GB** |

### Scaling ceiling on this cluster

| Users | Total graph data | Per-node data | Per-node `-Xmx` | RAM / node | Status |
|---|---|---|---|---|---|
| 10,000 | ~20 GB | ~6.7 GB | 16 GB | 30 GB | ✓ comfortable |
| 20,000 | ~40 GB | ~13.3 GB | 28 GB | 42 GB | ✓ comfortable |
| 35,000 | ~70 GB | ~23.3 GB | 48 GB | 62 GB | ✓ tight |
| 37,000 | ~74 GB | ~24.7 GB | 51 GB | 65 GB | ✗ RAM exceeded |

Real ceiling: **~36,000 users** (3 × 25 GB usable live data at 2× GC ratio = 75 GB total / 2 GB per 1,000 users).
At that scale (~3,600 concurrent across 48 cores) CPU peaks at ~22% — RAM is still the constraint.

---

## Building

```
./gradlew build
```

Integration tests (`abyss-store-yugabyte`) require a running YugabyteDB instance.
