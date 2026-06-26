# Abyss Graph

User-agnostic in-memory graph library backed by Hazelcast with pluggable durable storage.

## Modules

| Module | Purpose |
|---|---|
| `abyss-store-api` | `NodeLike` / `EdgeLike` interfaces and `AbyssStoreLike` contract |
| `abyss-dsl` | Engine + traversal interfaces, reified extension functions |
| `abyss-graph` | Hazelcast `IMap` engine — `AbyssGraph` |
| `abyss-store-yugabyte` | YugabyteDB store — YSQL for durable, YCQL for ephemeral/TTL |

## Usage

### Define your types

```kotlin
@Serializable
@SerialName("person")
data class Person(
    override val id: UUID = UUID.randomUUID(),
    val name: String,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Instant.now(),
    override val updatedAt: Instant = Instant.now(),
) : NodeLike

@Serializable
@SerialName("knows")
data class Knows(
    override val fromId: UUID,
    override val toId: UUID,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Instant.now(),
    override val updatedAt: Instant = Instant.now(),
) : EdgeLike
```

Register them in a `SerializersModule`:

```kotlin
val module = SerializersModule {
    polymorphic(NodeLike::class) { subclass(Person::class) }
    polymorphic(EdgeLike::class) { subclass(Knows::class) }
}
```

---

### In-memory only (no persistent store)

```kotlin
val config = Config().registerAbyssSerializers(module)
val hz = Hazelcast.newHazelcastInstance(config)
val graph = AbyssGraph(hz, nodesMapName = "nodes", edgesMapName = "edges")

// write
graph.transaction {
    addNode(Person(name = "Alice"))
    addNode(Person(name = "Bob"))
    addEdge(Knows(fromId = alice.id, toId = bob.id))
}

// read
val alice: Either<AbyssError, Person> = graph.node<Person>(alice.id)

// traverse
graph.from(alice.id) {
    outgoing<Knows>()
    nodes<Person>().collect { println(it.name) }   // prints "Bob"
}
```

---

### With YugabyteDB persistence

Apply the schema scripts from `abyss-store-yugabyte/src/main/resources/db/` then:

```kotlin
val store = YugabyteAbyssStoreLike.create(
    ysqlUrl  = "jdbc:postgresql://localhost:5433/my_graph",
    username = "abyss",
    password = "abyss",
    ycqlHost = "localhost",
    ycqlPort = 9042,
    keyspace = "my_graph",
    module   = module,
)

val config = Config().registerAbyssSerializers(module)
val hz     = Hazelcast.newHazelcastInstance(config)
val graph  = AbyssGraph(hz, "nodes", "edges", store = store)

// durable write (goes to YSQL)
graph.transaction {
    addNode(Person(name = "Alice"))
}

// ephemeral write with TTL — stored in YCQL, expires after 60 s
graph.transaction {
    addNode(Person(name = "TemporaryBob"), ttl = 60.seconds)
}
```

Cache misses trigger automatic load from the store via Hazelcast `MapLoader`.

## Building

```
./gradlew build
```

Integration tests (`abyss-store-yugabyte`) require a running YugabyteDB instance.
