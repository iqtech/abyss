# Abyss Graph

User-agnostic in-memory graph library backed by Hazelcast with pluggable durable storage.

## Modules

| Module | Purpose |
|---|---|
| `abyss-store-api` | `NodeLike` / `EdgeLike` interfaces, `AbyssStoreLike` (persistent) and `AbyssEphemeralStoreLike` (TTL) contracts |
| `abyss-dsl` | Engine + traversal interfaces, reified extension functions |
| `abyss-graph` | Hazelcast `IMap` engine — `AbyssGraph` |
| `abyss-store-yugabyte` | YugabyteDB stores — `YugabytePersistentStore` (YSQL) and `YugabyteEphemeralStore` (YCQL) |

## Pluggable storage

`AbyssGraph` accepts two independently nullable stores:

- **`persistentStore: AbyssStoreLike?`** — durable, no-TTL writes. Any `AbyssStoreLike` works here; the
  reference implementation is `YugabytePersistentStore` (YSQL/PostgreSQL-compatible).
- **`ephemeralStore: AbyssEphemeralStoreLike?`** — TTL-bound writes. Any `AbyssEphemeralStoreLike` works;
  the reference implementation is `YugabyteEphemeralStore` (YCQL/Cassandra-compatible).

Either store can be `null`. Passing neither gives a pure in-memory (Hazelcast-only) mode.

The store split affects consistency guarantees on bidirectional edge access:

- **Transactional store (e.g. PostgreSQL / `YugabytePersistentStore`):** a single `edges` table with an
  index on `to_id` covers both traversal directions in one atomic write. Forward and reverse access are
  always in sync.
- **YCQL (`YugabyteEphemeralStore`):** YCQL has no multi-statement transaction support. Efficient reverse
  lookups on ephemeral data require a denormalized reverse table — and those two writes are best-effort.
  A failure between them leaves the maps temporarily inconsistent until the next write or eviction.

Delete operations issued from either DSL builder (`transaction { }` or `ephemeral { }`) are fanned out
to **both** stores, so a node removed via `transaction { removeNode(id) }` is also deleted from the
ephemeral store (best-effort; fanout failure is logged but not propagated).

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
) : NodeLike

@Serializable
@SerialName("interest")
data class Interest(
    override val id: Uuid = Uuid.random(),
    val name: String,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = Clock.System.now(),
) : NodeLike

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
) : EdgeLike

@Serializable
@SerialName("likes")
data class Likes(
    override val fromId: Uuid,
    override val toId: Uuid,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = Clock.System.now(),
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
    subgraph<Person>()        // Person nodes only + all 3 Knows edges
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
    subgraph<Person>()                         // collect the intermediate Person nodes
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

val config = Config().registerAbyssSerializers(module)
val hz     = Hazelcast.newHazelcastInstance(config)
val graph  = AbyssGraph(hz, "nodes", "edges",
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

Each graph needs its own YSQL schema and YCQL keyspace. Pass them to each `create()`:

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

val socialGraph  = AbyssGraph(hz, "social-nodes",  "social-edges",
    persistentStore = socialPersistent, ephemeralStore = socialEphemeral)
val productGraph = AbyssGraph(hz, "product-nodes", "product-edges",
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

#### Ephemeral mutations (TTL-bound)

```kotlin
graph.ephemeral(ttl = 60.seconds) {
    addNode(Person(name = "TempBob"))       // stored in YCQL, expires after 60 s
    addEdge(Knows(fromId = alice.id, toId = bob.id))
}
```

All operations inside `ephemeral { }` share the same TTL. Nodes and edges are stored in
YCQL and disappear automatically when the TTL elapses. The cache entry also expires at the
same time.

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
data class Knows(override val fromId: Uuid, override val toId: Uuid, ...) : EdgeLike
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

Both maps are kept consistent by every `addEdge` / `removeEdge` transaction, including TTL expiry (same TTL is applied to both entries).

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

`subgraph<T>()` returns a `Subgraph(nodes, edges)` containing every node visited across **all hops**
(including the start node) and every edge traversed. Nodes are filtered to type `T`; use the
no-arg `subgraph()` to get all visited nodes regardless of type, or partition the result manually
to extract multiple types. Nodes are resolved in parallel; edges are already in memory from the
hop results. Because `nodes<T>` prunes the visited history, `subgraph<Person>()` after a
multi-hop traversal returns only the Persons that survived node filters — not every Person
ever reached.

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
`List<Set<Uuid>>`.

```kotlin
val components: List<Set<Uuid>> = graph.connectedComponents()
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
`List<Either<EdgeLike, NodeLike>>` in traversal order.

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

| Operation | Result |
|---|---|
| `outEdges` throughput | **2,512 ops/sec** (2,000 queries) |
| `inEdges` throughput | **1,417 ops/sec** (2,000 queries) |
| 3-hop traversal | **4.0 ms avg** (200 traversals) |

`inEdges` is slower than `outEdges` because it resolves edge data via `IMap.getAll` point-lookups after
the reverse-key scan — the reverse map holds only keys, not payloads.

To reproduce:

```
./gradlew :abyss-graph:test --tests "pl.iqtech.abyss.graph.PerformanceTest" -Pperf
```

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
