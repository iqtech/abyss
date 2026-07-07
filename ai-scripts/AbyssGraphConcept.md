# Abyss

A user-agnostic, in-memory graph library backed by Hazelcast with pluggable durable storage.
No domain assumptions, no user/tenant concept. Published as three modules: `abyss-dsl`, `abyss-graph`, and storage backends (e.g. `abyss-store-yugabyte`).

> **Deployment note:** Hazelcast is an **embedded library** — it runs inside the application JVM, not as
> a separate server. All graph data lives in the same heap as the application. On single-node deployments
> all Hazelcast operations are direct heap access with zero network overhead.

---

## Core interfaces

```kotlin
@Polymorphic
interface NodeLike {
    val id: UUID            // carried in the value — needed for predicate query results
    val tags: List<String>  // managed by the library; GIN-indexed in DB
    val createdAt: Instant  // set on first write, never updated
    val updatedAt: Instant  // updated on every write; drives cache invalidation
}

@Polymorphic
interface EdgeLike {
    val fromId: UUID        // stamped by the library at addEdge time
    val toId: UUID          // stamped by the library at addEdge time
    val tags: List<String>
    val createdAt: Instant
    val updatedAt: Instant
    // type identity comes from @SerialName on the concrete class
}
```

> **Not sealed:** `NodeLike` and `EdgeLike` are open interfaces — consuming projects define their own
> subtypes in separate modules. `sealed` would prohibit cross-module implementations and break the
> library's core contract. Open polymorphism is handled via `PolymorphicSerializer` and a
> `SerializersModule` that consuming projects extend with their own types.

`fromId` / `toId` / `createdAt` / `updatedAt` / `tags` are library-managed — stamped by the library on
`addNode` / `addEdge`, not by the consuming project. Concrete types carry only domain payload:

```kotlin
@Serializable @SerialName("user")   data class UserNode(override val id: UUID, ...) : NodeLike
@Serializable @SerialName("rifle")  data class RifleNode(override val id: UUID, ...) : NodeLike

@Serializable @SerialName("has_rifle") data class HasRifleEdge(...) : EdgeLike
```

`@SerialName` is the type discriminator — no explicit `type: String` field needed.
The DB `type` column mirrors the discriminator value for SQL-level filtering without JSONB parsing.

---

## Hazelcast maps

### Nodes

```
IMap<UUID, NodeLike>
```

Key is the node's UUID. Value is any `NodeLike` implementation.

### Edges

```
IMap<EdgeKey, EdgeLike>
```

#### EdgeKey

Compound key `(fromId, toId, type)`. Including `type` allows multiple edges of different types between
the same node pair. Serialized with Hazelcast 5 **Compact Serialization**.

```kotlin
data class EdgeKey(val fromId: UUID, val toId: UUID, val type: String)

class EdgeKeySerializer : CompactSerializer<EdgeKey> {
    override fun getTypeName() = "EdgeKey"
    override fun getCompactClass() = EdgeKey::class.java

    override fun write(writer: CompactWriter, obj: EdgeKey) {
        writer.writeString("fromId", obj.fromId.toString())
        writer.writeString("toId",   obj.toId.toString())
        writer.writeString("type",   obj.type)
    }

    override fun read(reader: CompactReader) = EdgeKey(
        UUID.fromString(reader.readString("fromId")),
        UUID.fromString(reader.readString("toId")),
        reader.readString("type")!!
    )
}
```

Compact field names (`"fromId"`, `"toId"`, `"type"`) are what predicate attribute paths resolve against.

> **Type coupling:** `EdgeKey.type` must match the value's `@SerialName` discriminator. The library
> derives `type` from the value at write time — callers never set it manually:
> ```kotlin
> val type = edge::class.findAnnotation<SerialName>()!!.value
> edgesMap.put(EdgeKey(fromId, toId, type), edge)
> ```
> One edge per type per node pair remains the constraint. Parallel same-type edges (e.g. two
> `physical_link` edges between the same nodes) are not supported — use a UUID surrogate key if needed.

#### Indexes

Both key attributes are indexed so either direction of traversal is O(1) predicate lookup:

```yaml
hazelcast:
  map:
    abyss-edges:
      indexes:
        - type: HASH
          attributes: ["__key.fromId"]
        - type: HASH
          attributes: ["__key.toId"]
```

#### Traversal

```kotlin
edgesMap.values(Predicates.equal("__key.fromId", nodeId))  // outgoing edges
edgesMap.values(Predicates.equal("__key.toId",   nodeId))  // incoming edges
```

#### Partition co-location

Implement `PartitionAware<UUID>` on `EdgeKey` returning `fromId` as the partition key.
All outgoing edges from a node land on the same partition — outgoing traversal becomes local (no network hop).
Incoming queries remain scatter-gather regardless.

#### Pagination — `PartitionPredicate` + `PagingPredicate`

Raw predicate queries return all matching entries in one result set — OOM risk for high fan-out nodes.
With `PartitionAware` in place, `PartitionPredicate` targets a single partition and `PagingPredicate`
adds stable pagination:

```kotlin
val paged = Predicates.pagingPredicate<EdgeKey, EdgeLike>(
    Predicates.partitionPredicate(
        fromId,
        Predicates.equal("__key.fromId", fromId)
    ),
    edgeComparator,   // stable ordering required — createdAt or toId are natural choices
    pageSize = 100
)
val page1 = edgesMap.values(paged)
paged.nextPage()
val page2 = edgesMap.values(paged)
```

#### Streaming via SSE

`PagingPredicate` is stateful — an SSE connection is a natural cursor holder. Ktor SSE runs in a
coroutine: when the client's TCP buffer fills, `send()` suspends and Hazelcast holds the next page fetch.
**Backpressure is free**, no extra machinery:

```kotlin
get("/graph/edges/{nodeId}") {
    val nodeId = UUID.fromString(call.parameters["nodeId"]!!)
    call.respondSse {
        val paged = Predicates.pagingPredicate<EdgeKey, EdgeLike>(
            Predicates.partitionPredicate(nodeId, Predicates.equal("__key.fromId", nodeId)),
            edgeComparator,
            100
        )
        do {
            val page = edgesMap.values(paged)
            page.forEach { send(ServerSentEvent(data = json.encodeToString(it))) }
            paged.nextPage()
        } while (page.isNotEmpty())
    }
}
```

**Resumability:** if the SSE connection drops, `PagingPredicate` restarts from page 1 — fast-forwarding
is O(n). For large streams, swap to a **range predicate cursor** instead: client sends `Last-Event-ID`
with the last seen `createdAt`, server resumes in O(1):

```kotlin
Predicates.and(
    Predicates.partitionPredicate(fromId, Predicates.equal("__key.fromId", fromId)),
    Predicates.greaterThan("createdAt", lastSeenCreatedAt)
)
```

Requires `createdAt` as an indexed Compact Serialization field on edge values.
For Sniper (low fan-out, short streams): `PagingPredicate` is sufficient. For IoT at scale: range cursor.

### Eviction policies

One `nodes` map, one `edges` map — separate maps per storage tier break traversal (a `to` UUID from an
edge is opaque without type context; the raw `node(id)` lookup can't know which map to probe).

Durable and ephemeral entries coexist in the same map:
- **Durable entries** — inserted without TTL; `max-idle-seconds` evicts cold entries; `MapLoader` reloads from YSQL on miss
- **Ephemeral entries** — inserted with per-entry TTL via `map.put(key, value, ttl, TimeUnit)`; Hazelcast TTL ≤ YCQL TTL per the ordering constraint

`MapLoader` on a cold miss: fires YSQL and YCQL queries in parallel via `CompletableFuture`, joins both, returns whichever is non-null. Total latency = `max(YSQL, YCQL)`. Any given UUID lives in exactly one store so exactly one query returns a result.

Eviction policy: **LRU** — graph traversal is temporal; active sessions touch their nodes repeatedly, cold sessions don't.
Max-size: `FREE_HEAP_PERCENTAGE` — responds to actual memory pressure, not entry counts.

```yaml
hazelcast:
  map:
    abyss-nodes:
      max-idle-seconds: 86400        # 24h idle → evict durable entries; ephemeral entries expire via per-entry TTL
      eviction:
        eviction-policy: LRU
        max-size-policy: FREE_HEAP_PERCENTAGE
        size: 20                     # evict when less than 20% heap free
    abyss-edges:
      max-idle-seconds: 86400
      eviction:
        eviction-policy: LRU
        max-size-policy: FREE_HEAP_PERCENTAGE
        size: 20
```

---

## Polymorphic serialization

`NodeLike` and `EdgeLike` are open interfaces — subtypes can be in any module.
kotlinx-serialization handles them via open polymorphism: consuming projects register their
types in a `SerializersModule`, which they pass to `AbyssGraph` at construction time.

`createPolymorphicJsonSerializer` wraps the base serializer with a fallback for unknown types
instead of throwing — safe when newer node/edge types arrive from a newer server version:

```kotlin
val nodeSerializer = createPolymorphicJsonSerializer<NodeLike> { json -> UnknownNode(json) }
val edgeSerializer = createPolymorphicJsonSerializer<EdgeLike> { json -> UnknownEdge(json) }
```

Adding a new node or edge type requires a new `@Serializable @SerialName("slug") data class`
plus a `subclass(MyNode::class)` entry in the consuming project's `SerializersModule`.

---

## Durability and write ordering

> **Critical:** Hazelcast Community Edition has no durable map persistence. Maps are volatile —
> a JVM crash loses all in-memory state. **Data loss.**

### YSQL is the source of truth

Responsibilities are split strictly:

| Layer | Role |
|-------|------|
| YSQL | Source of truth — durable, transactional writes |
| Hazelcast | Speed layer — in-memory reads, cache only |

**Write path — YSQL first, Hazelcast second:**
```
BEGIN YSQL TX
  INSERT/UPDATE graph.nodes ...
  INSERT/UPDATE graph.edges ...
COMMIT YSQL TX
→ nodesMap.put(...), edgesMap.put(...)   // cache population, best-effort
```

If the Hazelcast put fails — next read is a cache miss, the library fetches from YSQL, cache is
repopulated. No data loss.

**Read path — Hazelcast first, YSQL on miss:**
```
nodesMap.get(id)
  → hit:  return from Hazelcast
  → miss: load from YSQL → populate cache → return
```

`MapLoader` is used for **reading only** — `load()` on cache miss. No write-through.

### `graph.transaction {}` maps to a YSQL transaction

Multi-node/edge operations are wrapped in a single YSQL transaction. Hazelcast is populated after commit.
No `partitionId` — nodes are keyed by their own `id`, edges by `fromId`, matching Hazelcast's partitioning
strategy. Uniform distribution, no hot partitions, no external key provider needed.

```kotlin
graph.transaction {
    addNode(userNode)
    addNode(rifleNode)
    addEdge(HasRifleEdge(fromId = userId, toId = rifleId, ...))
}
// → single YSQL TX commits atomically, then bulk Hazelcast puts
```

### DB schema

Partitioning mirrors Hazelcast: nodes distributed by `hash(id)`, edges by `hash(from_id)` — a node and
its outgoing edges land on the same tablet without any explicit partition column.

```sql
CREATE TABLE graph.nodes (
    id           uuid        PRIMARY KEY,               -- hash(id) → tablet; uniform distribution
    type         text        NOT NULL,                  -- mirrors @SerialName value
    tags         text[]      NOT NULL DEFAULT '{}',
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    data         jsonb       NOT NULL
);
CREATE INDEX ON graph.nodes USING GIN (tags);

CREATE TABLE graph.edges (
    from_id      uuid        NOT NULL,                  -- leading PK; hash(from_id) co-locates with source node
    to_id        uuid        NOT NULL,
    type         text        NOT NULL,                  -- mirrors @SerialName value; allows multiple edge types per node pair
    tags         text[]      NOT NULL DEFAULT '{}',
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    data         jsonb       NOT NULL,
    PRIMARY KEY (from_id, to_id, type)
);
CREATE INDEX ON graph.edges(to_id, type);              -- for incoming edge queries
CREATE INDEX ON graph.edges USING GIN (tags);
```

---

## Ephemeral nodes and edges

Nodes and edges can be given a TTL — they expire automatically in both layers with no cleanup job.

### Why YCQL over YSQL for ephemeral backing

YSQL TTL requires a scheduled DELETE or `pg_partman`. YCQL TTL is native — set on insert, garbage-collected
by compaction. Write-heavy, short-lived data also fits YCQL's LSM model better than YSQL's MVCC.
Already the established pattern in this codebase (refresh tokens).

### TTL ordering constraint

Hazelcast TTL must be **≤** YCQL TTL:
- Hazelcast evicts first → cache miss → library loads from YCQL (still alive) ✓
- YCQL expires first while Hazelcast holds the entry → stale entry survives until Hazelcast TTL fires ✗

Set them equal, or Hazelcast slightly shorter for tighter expiry.

### API

```kotlin
graph.transaction {
    addNode(SessionNode(...), ttl = 24.hours)                                              // → YCQL-backed
    addEdge(TempAclEdge(fromId = userId, toId = resourceId, ...), ttl = 1.hours)          // → YCQL-backed
}

graph.transaction {
    addNode(RifleNode(...))   // registered as durable → YSQL-backed
}
```

### YCQL schema

```cql
CREATE TABLE graph.ephemeral_nodes (
    id   uuid PRIMARY KEY,
    type text,
    tags list<text>,
    data text
) WITH default_time_to_live = 0;    -- TTL set per row on insert

CREATE TABLE graph.ephemeral_edges (
    from_id uuid,
    to_id   uuid,
    type    text,
    tags    list<text>,
    data    text,
    PRIMARY KEY (from_id, to_id, type)   -- type allows multiple edge types per node pair, consistent with YSQL schema
) WITH default_time_to_live = 0;
```

### Use cases

- Temporary ACL grants: `User -[temp_acl_read, TTL=1h]-> Resource`
- Session/presence nodes that expire when the session ends
- Rate-limiting edges: `User -[rate_limit_hit]-> Endpoint` with a short TTL window
- Cached computed edges (derived shortest paths) — expire and recompute on next traversal

---

## Reading data

Four distinct layers, each with different trade-offs.

**1. Hazelcast — direct key lookup**
`nodesMap.get(uuid)` / `edgesMap.get(EdgeKey(fromId, toId, type))` — pure in-memory, sub-millisecond, no DB involved.

**2. Hazelcast — predicate queries**
Index-backed scans on `__key.fromId`, `__key.toId`, and `type` (if edge values use Compact Serialization).
Outgoing/incoming edge traversal, BFS/DFS, ACL reachability — all in-memory.

**3. YSQL — structured queries**
`type` is a plain btree-indexed column, so type filtering is cheap before touching JSONB:
```sql
SELECT * FROM graph.nodes WHERE type = 'rifle';
SELECT * FROM graph.edges WHERE from_id = ? AND type = 'acl_read';
```

**4. YSQL — JSONB payload queries**
Drill into the `data` column with PostgreSQL operators. GIN index on `data` makes containment queries fast:
```sql
WHERE data->>'caliber' = '308'
WHERE data @> '{"manufacturer": "Tikka"}'
WHERE (data->>'weight_kg')::float > 4
```
```sql
CREATE INDEX ON graph.nodes USING GIN (data);
```

**5. YSQL — full-text search across multiple JSONB fields**
Use a generated `tsvector` column so the expression is computed once on write, not on every query:
```sql
ALTER TABLE graph.nodes ADD COLUMN fts tsvector
    GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(data->>'name', '')),         'A') ||
        setweight(to_tsvector('english', coalesce(data->>'description', '')), 'B') ||
        setweight(to_tsvector('english', coalesce(data->>'manufacturer', '')), 'B')
    ) STORED;

CREATE INDEX ON graph.nodes USING GIN (fts);
```
```sql
WHERE fts @@ to_tsquery('tikka & 308')
```
Fields being indexed are fixed per node `type` — use a partial index (`WHERE type = 'rifle'`) if
different node types have different searchable fields.

**6. YSQL — geo search (PostGIS)**
Store `lat`/`lon` in the JSONB payload, expose them via a generated geometry column:
```sql
ALTER TABLE graph.nodes ADD COLUMN geo geometry(Point, 4326)
    GENERATED ALWAYS AS (
        ST_SetSRID(ST_MakePoint(
            (data->>'lon')::float,
            (data->>'lat')::float
        ), 4326)
    ) STORED;

CREATE INDEX ON graph.nodes USING GIST (geo);
```
```sql
-- nodes within 5 km of a point
WHERE ST_DWithin(geo::geography, ST_MakePoint(:lon, :lat)::geography, 5000)

-- nearest N nodes
ORDER BY geo <-> ST_MakePoint(:lon, :lat)::geometry LIMIT 10

-- combine with type filter
WHERE type = 'range' AND ST_DWithin(geo::geography, ST_MakePoint(:lon, :lat)::geography, 50000)
```
YSQL supports PostGIS — zero new infrastructure beyond enabling the extension.

**7. YSQL — JOIN nodes + edges**
Cross-entity queries in one round-trip — impossible in pure Hazelcast:
```sql
SELECT n.data FROM graph.nodes n
JOIN graph.edges e ON e.to_id = n.id
WHERE e.from_id = ? AND e.type = 'has_rifle'
```

**8. YSQL — recursive CTE**
Full subgraph traversal in a single query — replaces application-level BFS for non-latency-sensitive reads
(e.g. ACL checks that don't need Hazelcast's sub-millisecond response):
```sql
WITH RECURSIVE subgraph AS (
    SELECT to_id FROM graph.edges WHERE from_id = :userId
    UNION ALL
    SELECT e.to_id FROM graph.edges e
    JOIN subgraph s ON e.from_id = s.to_id
)
SELECT * FROM graph.nodes WHERE id IN (SELECT to_id FROM subgraph);
```

**Rule of thumb:** Hazelcast for hot-path single-entity and traversal reads; YSQL for analytical,
cross-entity, or cold reads.

---

## Usage pattern in consuming projects

The library is graph-only — no user concept. Consuming projects anchor their data with a root node:

1. On first login, create a `UserNode` with the user's own UUID as its `id`.
2. On every request, look up the `UserNode` directly by `userId` — no mapping required.
3. Traverse from `UserNode` to reach any domain data.

```
UserNode
  ├─[has_rifle]──▶ RifleNode ──[has_scope]──▶ ScopeNode
  ├─[has_session]──▶ SessionNode ──[used_load]──▶ AmmunitionNode
  └─[has_load]──▶ AmmunitionNode ──[uses_bullet]──▶ BulletNode
                                  └─[uses_powder]──▶ PowderNode
```

Ownership is topology, not a column.

---

## API Design

### Error model

```kotlin
sealed interface AbyssError {
    data class NodeNotFound(val id: UUID) : AbyssError
    data class EdgeNotFound(val fromId: UUID, val toId: UUID, val type: String) : AbyssError
    data class Unexpected(val cause: Throwable) : AbyssError
}
```

### CRUD — `AbyssEngineLike`

All reads return `Either<AbyssError, T>` — non-nullable right side, explicit not-found on the left.
Writes are always transactional; `AbyssTransactionLike` accumulates operations and commits them atomically —
YSQL for durable entries, YCQL for ephemeral — followed by bulk Hazelcast puts.

```kotlin
interface AbyssEngineLike {

    suspend fun node(id: UUID): Either<AbyssError, NodeLike>
    suspend fun edge(fromId: UUID, toId: UUID, type: String): Either<AbyssError, EdgeLike>
    suspend fun nodeExists(id: UUID): Either<AbyssError, Boolean>
    suspend fun edgeExists(fromId: UUID, toId: UUID, type: String): Either<AbyssError, Boolean>

    // __key.fromId HASH index + PartitionPredicate → single partition, local on outgoing node
    fun outEdges(nodeId: UUID, pageSize: Int = 100): Flow<EdgeLike>
    fun outEdges(nodeId: UUID, type: String, pageSize: Int = 100): Flow<EdgeLike>
    // __key.toId HASH index, scatter-gather across partitions
    fun inEdges(nodeId: UUID, pageSize: Int = 100): Flow<EdgeLike>
    fun inEdges(nodeId: UUID, type: String, pageSize: Int = 100): Flow<EdgeLike>

    suspend fun transaction(
        block: suspend AbyssTransactionLike.() -> Unit
    ): Either<AbyssError, Unit>
}

interface AbyssTransactionLike {
    fun addNode(node: NodeLike, ttl: Duration? = null)
    fun updateNode(node: NodeLike)                              // full replace
    fun removeNode(id: UUID)

    fun addEdge(edge: EdgeLike, ttl: Duration? = null)         // fromId/toId carried on EdgeLike
    fun updateEdge(edge: EdgeLike)                             // full replace
    fun removeEdge(fromId: UUID, toId: UUID, type: String)
}
```

Reified extensions keep generics off the interfaces and resolve `@SerialName` at call sites:

```kotlin
suspend inline fun <reified N : NodeLike> AbyssEngineLike.node(id: UUID): Either<AbyssError, N> =
    node(id).flatMap { (it as? N)?.right() ?: AbyssError.NodeNotFound(id).left() }

suspend inline fun <reified E : EdgeLike> AbyssEngineLike.edge(fromId: UUID, toId: UUID): Either<AbyssError, E> {
    val type = E::class.findAnnotation<SerialName>()!!.value
    return edge(fromId, toId, type).flatMap { (it as? E)?.right() ?: AbyssError.EdgeNotFound(fromId, toId, type).left() }
}

suspend inline fun <reified E : EdgeLike> AbyssEngineLike.edgeExists(fromId: UUID, toId: UUID): Either<AbyssError, Boolean> =
    edgeExists(fromId, toId, E::class.findAnnotation<SerialName>()!!.value)

inline fun <reified E : EdgeLike> AbyssTransactionLike.removeEdge(fromId: UUID, toId: UUID) =
    removeEdge(fromId, toId, E::class.findAnnotation<SerialName>()!!.value)

inline fun <reified E : EdgeLike> AbyssEngineLike.outEdges(nodeId: UUID): Flow<E> =
    outEdges(nodeId, E::class.findAnnotation<SerialName>()!!.value).filterIsInstance<E>()

inline fun <reified E : EdgeLike> AbyssEngineLike.inEdges(nodeId: UUID): Flow<E> =
    inEdges(nodeId, E::class.findAnnotation<SerialName>()!!.value).filterIsInstance<E>()
```

Call site is exhaustive — `when` on `AbyssError` is checked by the compiler:

```kotlin
graph.node<RifleNode>(rifleId).fold(
    ifLeft = { error ->
        when (error) {
            is AbyssError.NodeNotFound -> respondNotFound()
            is AbyssError.Unexpected   -> respondInternalError(error.cause)
            else -> {}
        }
    },
    ifRight = { rifle -> respond(rifle) }
)
```

### Traversal DSL

Kotlin receiver-based builders make the traversal API read like topology, not predicate boilerplate.
Reified edge/node types resolve `@SerialName` discriminators at compile time — no strings at call sites.

`from` lives on `AbyssEngineLike` and returns `Either` consistent with the rest of the client:

```kotlin
// on AbyssEngineLike
suspend fun <T> from(nodeId: UUID, block: suspend TraversalBuilderLike.() -> T): Either<AbyssError, T>

interface TraversalBuilderLike  // all operations are reified extensions

// outgoing hop — __key.fromId + __key.type; cursor advances to toId; single partition if PartitionAware
inline fun <reified E : EdgeLike> TraversalBuilderLike.outgoing()
// incoming hop — __key.toId + __key.type; cursor advances to fromId; scatter-gather across partitions
inline fun <reified E : EdgeLike> TraversalBuilderLike.incoming()
// predicate variants — app-side filter on edge payload
// skip: non-matching edges are silently skipped (default)
// stop: predicate failure aborts the entire traversal, returning empty / false
inline fun <reified E : EdgeLike> TraversalBuilderLike.outgoing(noinline predicate: (E) -> Boolean, stop: Boolean = false)
inline fun <reified E : EdgeLike> TraversalBuilderLike.incoming(noinline predicate: (E) -> Boolean, stop: Boolean = false)
// node predicate variants — filter on the target node; one extra node map lookup per candidate edge
inline fun <reified E : EdgeLike, reified N : NodeLike> TraversalBuilderLike.outgoing(noinline predicate: (N) -> Boolean, stop: Boolean = false)
inline fun <reified E : EdgeLike, reified N : NodeLike> TraversalBuilderLike.incoming(noinline predicate: (N) -> Boolean, stop: Boolean = false)

// terminal — stream destination nodes; optional in-memory filter
inline fun <reified N : NodeLike> TraversalBuilderLike.nodes(noinline filter: ((N) -> Boolean)? = null): Flow<N>

// BFS — traverse {} holds traversal-only edges; reaches {} holds the terminal edge
// reaches returns Boolean — sufficient for ACL checks; path inspection (audit/debug) is not modelled here
fun TraversalBuilderLike.traverse(block: TraversalBuilderLike.() -> Unit)
fun TraversalBuilderLike.reaches(targetId: UUID, block: TraversalBuilderLike.() -> Unit): Boolean
```

**Basic traversal:**
```kotlin
val rifles: Flow<RifleNode> = graph.from(userId) {
    outgoing<HasRifleEdge>()
    nodes<RifleNode>()
}
```

**Multi-hop:**
```kotlin
val scopes: Flow<ScopeNode> = graph.from(userId) {
    outgoing<HasRifleEdge>()
    outgoing<HasScopeEdge>()
    nodes<ScopeNode>()
}
```

**ACL check:**
```kotlin
val hasAccess: Boolean = graph.from(userId) {
    traverse {
        outgoing<InGroupEdge>()       // traversal-only edges — repeated until target found or exhausted
    }
    reaches(resourceId) {
        outgoing<AclReadEdge>()       // terminal edge — must be the last hop
    }
}
```

**Node predicate — "rifles chambered in .308":**
```kotlin
val rifles308: Flow<RifleNode> = graph.from(userId) {
    outgoing<HasRifleEdge, RifleNode> { it.caliber == ".308" }
    nodes<RifleNode>()
}
```

**Incoming traversal — "which loads use this bullet?":**
```kotlin
val loadsUsingBullet: Flow<AmmunitionNode> = graph.from(bulletId) {
    incoming<UsesBulletEdge>()
    nodes<AmmunitionNode>()
}
```

**Geo-scoped traversal:**
```kotlin
val nearbyRanges: Flow<RangeNode> = graph.from(userId) {
    outgoing<HasSessionEdge>()
    nodes<RangeNode> {
        geo within 50.km of here
    }
}
```

**Writing — always inside a transaction:**
```kotlin
graph.transaction {
    addNode(RifleNode(id = UUID.random(), ...))
    addEdge(HasRifleEdge(fromId = userId, toId = rifleId, ...))
    removeEdge<HasRifleEdge>(fromId = userId, toId = rifleId)
}
// YSQL commits atomically, then Hazelcast cache is populated
```

Single-operation writes are still wrapped in a transaction internally — the API never exposes
a raw write that bypasses YSQL.

The DSL shape drives the internal structure: `outgoing<E>()` / `incoming<E>()` compile to predicates on
`__key.fromId` / `__key.toId` + edge type discriminator; `nodes<N>()` filters results by node discriminator;
`traverse {}` + `reaches {}` is the BFS loop with split traversal/terminal edge sets.

---

## Library modules

Three published artifacts with clean dependency boundaries:

| Module | Contents | Depends on |
|---|---|---|
| `abyss-store-api` | `NodeLike`, `EdgeLike`, `AbyssStoreLike`, `AbyssStoreTransactionLike`, `AbyssError` | Arrow, kotlinx-serialization |
| `abyss-dsl` | `AbyssEngineLike`, `AbyssTransactionLike`, `TraversalBuilderLike`, `EdgeKey`, reified extensions | `abyss-store-api` |
| `abyss-graph` | Hazelcast `IMap` impl of `AbyssEngineLike` — speed layer, predicate indexes, pagination, write ordering | `abyss-dsl` + Hazelcast |
| `abyss-store-yugabyte` | `AbyssStoreLike` impl — YSQL (durable) + YCQL (ephemeral), TTL routing between the two | `abyss-store-api` + YugabyteDB drivers |

Store implementations depend only on `abyss-store-api` — no DSL or Hazelcast types pulled in.
`abyss-graph` owns write ordering (store first, cache second) and depends on the full `abyss-dsl`.
Consumers who want only DB reads can implement `AbyssEngineLike` directly against `AbyssStoreLike`, no Hazelcast required.

### `AbyssStoreLike` — defined in `abyss-store-api`

TTL is first-class — `null` means durable, non-null means ephemeral. Backends decide the mechanism
(YCQL native TTL, `expires_at` column + cleanup job, Redis TTL, etc.).

```kotlin
interface AbyssStoreLike {
    suspend fun loadNode(id: UUID): Either<AbyssError, NodeLike?>
    suspend fun loadEdge(fromId: UUID, toId: UUID, type: String): Either<AbyssError, EdgeLike?>

    suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit>
}

interface AbyssStoreTransactionLike {
    fun saveNode(node: NodeLike, ttl: Duration? = null)
    fun saveEdge(edge: EdgeLike, ttl: Duration? = null)        // fromId/toId carried on EdgeLike
    fun deleteNode(id: UUID)
    fun deleteEdge(fromId: UUID, toId: UUID, type: String)
}
```

`abyss-store-yugabyte` maintains two connections (HikariCP for YSQL, YugabyteDB Java driver `com.yugabyte:java-driver-core:4.19.0-yb-1` for YCQL) and routes on TTL: `null` → YSQL, non-null → YCQL.
A PostgreSQL impl would write to a single table with an `expires_at` column and a background cleanup job.

### Instantiation

Each `AbyssGraph` instance binds to its own Hazelcast map names and DB table names. A single application
can run multiple independent graph instances sharing the same Hazelcast cluster and DB connections —
isolated by configuration, not by process.

```kotlin
// abyss-graph — depends on abyss-dsl
class AbyssGraph(
    val hazelcast: HazelcastInstance,
    val nodesMapName: String,
    val edgesMapName: String,
    val store: AbyssStoreLike? = null    // null = in-memory only, no persistence
) : AbyssEngineLike

// abyss-store-yugabyte — depends on abyss-store-api only
class YugabyteAbyssStoreLike(
    val ysql: DataSource,
    val ycql: CqlSession,
    val durableNodesTable: String,
    val durableEdgesTable: String,
    val ephemeralNodesTable: String,
    val ephemeralEdgesTable: String
) : AbyssStoreLike
```

Two graph instances in one application — domain data and ACL restrictions fully isolated:

```kotlin
val domainGraph = AbyssGraph(
    hazelcast = hz,
    nodesMapName = "domain-nodes",
    edgesMapName = "domain-edges",
    store = YugabyteAbyssStoreLike(ysql, ycql,
        durableNodesTable    = "graph.nodes",
        durableEdgesTable    = "graph.edges",
        ephemeralNodesTable  = "graph.ephemeral_nodes",
        ephemeralEdgesTable  = "graph.ephemeral_edges"
    )
)

val aclGraph = AbyssGraph(
    hazelcast = hz,
    nodesMapName = "acl-nodes",
    edgesMapName = "acl-edges",
    store = YugabyteAbyssStoreLike(ysql, ycql,
        durableNodesTable    = "acl.nodes",
        durableEdgesTable    = "acl.edges",
        ephemeralNodesTable  = "acl.ephemeral_nodes",
        ephemeralEdgesTable  = "acl.ephemeral_edges"
    )
)
```

Hazelcast map names must be unique across instances within the same cluster.

---

## Implementation details

### Dependency graph

```
abyss-store-api          NodeLike, EdgeLike, AbyssStoreLike, AbyssStoreTransactionLike, AbyssError
      │
      ├── abyss-dsl      AbyssEngineLike, AbyssTransactionLike, TraversalBuilderLike, EdgeKey
      │         │
      │         └── abyss-graph          Hazelcast IMap impl of AbyssEngineLike
      │
      └── abyss-store-yugabyte           YugabyteDB impl of AbyssStoreLike (YSQL + YCQL)
          abyss-store-postgres           (future) PostgreSQL impl of AbyssStoreLike
          abyss-store-*                  (future) any other backend
```

Application code depends on `abyss-dsl` + one store impl. Store impls never see `TraversalBuilderLike` or
`EdgeKey`. `abyss-graph` is optional — consumers who want only DB reads implement `AbyssEngineLike`
directly against `AbyssStoreLike`:

```kotlin
class StoreOnlyEngine(private val store: AbyssStoreLike) : AbyssEngineLike {

    override suspend fun node(id: UUID) =
        store.loadNode(id).flatMap { it?.right() ?: AbyssError.NodeNotFound(id).left() }

    override suspend fun edge(fromId: UUID, toId: UUID, type: String) =
        store.loadEdge(fromId, toId, type).flatMap { it?.right() ?: AbyssError.EdgeNotFound(fromId, toId, type).left() }

    override suspend fun nodeExists(id: UUID) = store.loadNode(id).map { it != null }
    override suspend fun edgeExists(fromId: UUID, toId: UUID, type: String) = store.loadEdge(fromId, toId, type).map { it != null }

    override suspend fun transaction(block: suspend AbyssTransactionLike.() -> Unit) =
        store.transaction { block() }

    // outEdges / inEdges / from — query store directly, no Hazelcast predicate indexes
}
```

### Project structure

Single multimodule Gradle project, shared versioning across all modules.

Rationale: the four modules are not independently useful — `abyss-store-api` defines the contract,
`abyss-dsl` builds on it, `abyss-graph` and store impls implement it. They move together. Independent
versioning adds release overhead with no practical benefit; a consumer pinning `abyss-store-api 1.2.0`
against `abyss-graph 1.0.0` would get a broken combination.

```
abyss/
  build.gradle.kts              # shared version, common deps, Kotlin/Arrow/serialization versions
  libs.versions.toml            # version catalog
  abyss-store-api/
  abyss-dsl/
  abyss-graph/
  abyss-store-yugabyte/
```

One `version = "x.y.z"` in the root; all subprojects inherit it. Published together as a single release.

### Package naming

Root package: `pl.iqtech.abyss`. Each module appends its own suffix:

| Module | Package |
|---|---|
| `abyss-store-api` | `pl.iqtech.abyss.store.api` |
| `abyss-dsl` | `pl.iqtech.abyss.dsl` |
| `abyss-graph` | `pl.iqtech.abyss.graph` |
| `abyss-store-yugabyte` | `pl.iqtech.abyss.store.yugabyte` |

---

## Sizing — Sniper on Oracle Always Free (single Ampere A1)

Hardware: 4 vCPU ARM, 24GB RAM. Runs YugabyteDB + Ktor app with embedded Hazelcast in Docker Compose.

### Data per user

Sniper domain: rifles (~4), scopes (~4), calibers (~3), loads (~15), components (~16),
sessions (~50/year), DOPE (~200), chrono (~200) = **~500 nodes + ~700 edges = ~1200 elements/user**

### Memory layout (1000 users)

```
1000 users × 1200 elements × 1.5KB (JVM object overhead) = 1.8GB graph data in heap
```

| Process | RAM |
|---|---|
| YugabyteDB single-node | 8GB |
| Ktor JVM — app + embedded Hazelcast (`-Xmx6g`) | 6GB |
| OS | 1.5GB |
| **Total** | **15.5GB of 24GB** |

`-Xmx2g` (current CLAUDE.md default) is sufficient for Sniper without the graph cache.
Bump to `-Xmx6g` when graph cache is active: ~500MB app + 1.8GB data + GC headroom.

### DB size (1000 users)

```
1200 elements × 500 bytes compressed × 1000 users ≈ 600MB raw
+ indexes (×2) ≈ 1.2GB total
```

YugabyteDB is over-provisioned for this load — its 8GB footprint is runtime overhead, not data.

### CPU at peak

Global user base (EU + US + AU) distributes load across time zones naturally.
Worst-case overlap: ~150 concurrent users, each request 3ms avg (Hazelcast hit <1ms, YSQL write 2–5ms).

```
150 requests × 3ms = 450ms CPU/second across 4 cores ≈ 11% peak load
```

### Scaling ceiling on this machine

| Users | Heap needed | `-Xmx` | Total RAM | Status |
|---|---|---|---|---|
| 1,000 | ~2.5GB | 6GB | 15.5GB | ✓ comfortable |
| 5,000 | ~10GB | 14GB | 23.5GB | ✓ tight |
| 5,500 | ~12GB | 16GB | 25.5GB | ✗ RAM exceeded |

Real ceiling: **~5,000 users** before needing more RAM. CPU stays well under 50% past that point.

### Economics

```
Revenue:         1,000 × $25/year = $25,000/year
Infrastructure:  Oracle Free Tier + Cloudflare R2 + domain ≈ $15/year
```

---

## Future development

- **Graph algorithms** — BFS/DFS traversal, cycle detection, connected components.
  Hazelcast's in-memory maps make these fast without round-tripping to the DB.

- **ACL / permission model** — permissions as a directed graph, access as BFS reachability with edge-type filtering.

  Example: `User -[ingroup]-> Group -[acl_read]-> Resource` — if a path exists from the user to the resource
  traversing only allowed edge types, access is granted. The terminal edge type encodes the permission level
  (`acl_read`, `acl_write`, etc.); `ingroup` is a traversal-only edge. Nested groups work naturally via the
  visited set in BFS.

  The algorithm is two parameters: `(startId, targetId, terminalEdgeType)`. Result should be cached in a flat
  `IMap<Pair<UUID, UUID>, Boolean>` with short TTL — ACL checks are read-heavy, invalidation only on group or
  permission changes.

  For high fan-out nodes, add a `type` index on the value side (requires Compact Serialization on edge values)
  to avoid in-memory filtering of all outgoing edges. Without it, all outgoing edges for a node are fetched and
  filtered in application code — acceptable for low fan-out.

  This is the same pattern used by Google Zanzibar / SpiceDB / OpenFGA at scale. If rule complexity grows
  (union/intersection of permissions, negative grants, wildcard subjects), those are known prior art.

- **Monitoring** — three tiers, most already wired by the underlying stack.

  **Hazelcast** — free via `LocalMapStats`; Hz 5 auto-registers to Micrometer if it is on the classpath:
  - Per-map: hit count, miss count, get/put/remove count, owned entry count, heap cost
  - Cache hit ratio (`hitCount / getOperationCount`) — primary signal for cache effectiveness
  - Partition and cluster member stats

  **YugabyteDB** — free via Prometheus endpoint (`:9000/prometheus-metrics`) and `pg_stat_*`:
  - `pg_stat_statements` — query count, mean/total latency, rows — shows which graph queries are slow
  - `pg_stat_user_tables` — seq scans vs index scans on `graph.nodes` / `graph.edges` — catches missing indexes
  - `pg_stat_user_indexes` — confirms GIN/GIST indexes are actually used
  - Tablet server metrics: read/write latency histograms, storage cache hit ratios

  **Library-level** (needs explicit instrumentation via Micrometer):
  - Traversal count + average BFS depth
  - Traversal latency (app-level loop, separate from raw Hazelcast get latency)
  - Transaction count + success/failure rate
  - ACL check count + ACL result cache hit ratio
  - Node/edge count per type — in-memory vs DB (divergence = cache warm-up incomplete)

---

## Logging

SLF4J with no forced backend — consumers bring their own (Logback, Log4j2, etc.).

### Logger hierarchy

```
com.abyss                     root — controls the whole library in one line
  com.abyss.graph             Hazelcast IMap operations
  com.abyss.store.yugabyte    YSQL / YCQL operations
```

### What gets logged at each level

| Level | Events |
|-------|--------|
| `ERROR` | Transaction commit failed (includes exception); store load threw unexpectedly; serialization failure on a cache put |
| `WARN` | Hazelcast cache put failed after successful store write (best-effort, non-fatal — next read will reload); `MapLoader` returned null for both YSQL and YCQL on the same id (id genuinely absent, but worth surfacing) |
| `INFO` | `AbyssGraph` initialized — map names, whether a store is wired; `AbyssGraph` closed |
| `DEBUG` | Cache miss → store load (includes node/edge id and type); transaction summary on commit (node/edge counts, durable vs ephemeral); traversal completed (hop count, result count) |
| `TRACE` | Individual predicate queries (edge type, direction, page number); each `MapLoader.load()` call; per-entry Hazelcast put inside a bulk post-commit flush |

### Representative log lines

```
INFO  com.abyss.graph - AbyssGraph initialized [nodes=abyss-nodes, edges=abyss-edges, store=YugabyteAbyssStoreLike]
DEBUG com.abyss.graph - cache miss [id=3fa85f64, type=rifle] → loading from store
WARN  com.abyss.graph - cache put failed after commit [id=3fa85f64] — next read will reload
DEBUG com.abyss.graph - transaction committed [durable_nodes=1, durable_edges=1, ephemeral_nodes=0, ephemeral_edges=0]
DEBUG com.abyss.graph - traversal completed [hops=2, results=4]
ERROR com.abyss.graph - transaction failed [cause=...]
TRACE com.abyss.store.yugabyte - MapLoader.load [id=3fa85f64]
TRACE com.abyss.graph - predicate query [direction=outgoing, type=has_rifle, page=1]
```

### Recommended production config

```xml
<!-- Logback snippet — silence noisy Hazelcast internals, keep abyss at INFO -->
<logger name="com.hazelcast" level="WARN"/>
<logger name="com.abyss"     level="INFO"/>
```

Flip `com.abyss.graph` to `DEBUG` to diagnose cache miss storms.
Flip `com.abyss.store.yugabyte` to `DEBUG` to diagnose slow store loads without enabling Hazelcast noise.

---

- **Recursive CTE depth cap** — YSQL recursive CTEs have no built-in depth limit or memory cap.
  A deep/wide DAG traversal (millions of devices, 6+ hops) can exhaust YugabyteDB memory silently.
  The library should enforce a configurable `maxDepth` and `maxNodes` on every recursive query it
  generates. Deferred — not a concern at Sniper scale, becomes critical for IoT use cases.
