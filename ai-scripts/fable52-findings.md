# Fable 5.2 audit findings (2026-11-14)

Correctness audit of all main sources (5,350 lines, 46 files) at `973a3e8` on `dev`, version 0.37.1.
Fix plan: `ai-scripts/fable52-fix.md`.

- **Scope:** `abyss-graph`, `abyss-dsl`, `abyss-store-api`, `abyss-store-yugabyte` main sources and the
  two DDL files. Tests were read only as far as needed to write probes.
- **Excluded as already known:** everything in `TODO.md`, `ai/flow/`, `ConsistencyAuditFindings.md`,
  `TransactionDurabilitySafetyAudit.md` and `paths-problem-ISSUE.md`, including `paths {}` and ephemerals,
  the cross-layer `modifyNode`/`modifyEdge` gap, the YSQL scan autocommit (1.37) and the preload-vs-remove
  index race (1.36).
- **Evidence labels:** "executed" means a probe test with a real assertion failed on today's code, on real
  embedded Hazelcast. "By reading" means source only, no test yet.
- **Limit:** the stores in the probes are in-memory fakes. Nothing here ran against live YugabyteDB.
- **Probes:** `AuditProbeTest`, 10 tests, 10 red, each on its own assertion and none on setup. The file is
  not in the test tree, so `dev` stays green. Its source is the appendix of this document.

## Summary

| # | Finding | Severity | Evidence |
|---|---|---|---|
| F1 | A cold read racing a `removeNode` puts the removed node back in the cache | High | executed (G) |
| F2 | The adjacency index holds the same edge twice | High | executed (A1, A2) |
| F3 | `removeEdge` with an unregistered type throws after the store commit | High | executed (C1, C2) |
| F4 | A tag wider than `tagWidth` is silently truncated | High | executed (B) |
| F5 | The integrity check ignores removals in the same transaction | Medium | executed (F1, F2) |
| F6 | `toSubgraph` drops a node when two schemas share a domain id | Medium | executed (E) |
| F7 | `UnknownNode` throws on a non-UUID id | Medium | executed (D) |
| R1 | Unknown types throw inside the worker | Medium | by reading |
| R2 | `CancellationException` is turned into `Left(Unexpected)` | Low | by reading |
| R3 | `Either` APIs throw raw exceptions from the worker | Medium | by reading (F3 is one instance) |
| R4 | `exportGraphLines` hides failed reads | Low | by reading |
| R5 | Scan `parallelism` is not validated | Low | by reading |
| R6 | `SingleSchemaGraph()` cannot run on a Hazelcast client | Low | by reading |
| R7 | Edge writes read endpoints one at a time | Unmeasured | by reading |
| R8 | Two stale comments | Trivial | by reading |

## Executed findings

### F1. A cold read racing a `removeNode` puts the removed node back in the cache

- **Where:** `AbyssSchemaWorker.loadAndCacheNode` (`AbyssSchemaWorker.kt:672-685`).
- **Mechanism:** it reads the store, then calls `nodesMap.set(nid, node)` unconditionally. A `removeNode`
  that commits between the two steps deletes the store row and the cache entry, and the pending `set` then
  writes the old value back. The entry has no TTL.
- **Probe G:** the store's `loadNode` takes its snapshot and waits on a gate; `removeNode` commits; the gate
  opens. Afterwards the store is empty and `node(x)` returns `Right`.
- **Consequences:**
  - Reads return a deleted node until the entry is evicted or overwritten.
  - `integrityError` reads through the same cache, so `addEdge` to the deleted node passes and a dangling
    edge reaches the store.
- **Same shape, by reading:**
  - `loadAndCacheEdge` (`:713-728`) and `loadAndCacheNodes` (`:692-711`) write back the same way.
  - A concurrent update instead of a remove leaves the older value in the cache.
  - `preloadOut`/`preloadIn` use `putIfAbsent` for edge values, which is safe against an update but not
    against a remove.
- **Relation to 1.36:** 1.36 is the same race on the adjacency index. This is the value-map side.

### F2. The adjacency index holds the same edge twice

- **Where:** `AdjacencyMutationProcessor.process` (`AdjacencyMutationProcessor.kt:18`).
- **Mechanism:** `Add` does `current + entry` on a `Set<AdjacencyEntry>`, and the data class equality
  includes `nodeTypeTag`. `Remove` matches on `(neighborId, edgeTypeTag)` only. So the same edge added once
  with a null tag and once with a resolved tag is two entries. The comment in `AdjacencyKey.kt` says set
  uniqueness is "effectively (neighborId, edgeTypeTag)"; that holds only while the tag always resolves the
  same way.
- **Probe A1 (cache-only):** edge a→b written with `checkIntegrity = false` while b is missing, b added,
  the edge added again. `countEdges` returns 2 for one edge.
- **Probe A2 (store):** edge written before its target node, nodes added, then `outEdges(a)` read cold. The
  write path stored a null tag, the preload resolved the tag from the store's neighbour type. Result:
  `[only, only]`.
- **Triggers:**
  - executed: an edge written before its target node, the supported `checkIntegrity = false` import order;
  - by reading: a persistent edge to an ephemeral node, where the YSQL `LEFT JOIN nodes` returns null and
    the write path resolves the tag through `readNode`;
  - by reading: a neighbour type missing from this process's module, where `nodeTagOf` returns null.
- **Consequences:**
  - duplicate edges from `outEdges`/`inEdges` and from `collectEdges`/`subgraph`;
  - wrong `countEdges`;
  - with a store, `cachedOutScan` compares the scan size with the index count, they never match again, and
    that node stays on the slower heal path;
  - a `Remove` clears both entries, so the duplicate lasts until the edge is removed or the cluster
    restarts.

### F3. `removeEdge` with an unregistered type throws after the store commit

- **Where:** `AbyssSchemaWorker.applyToCacheAsync` (`AbyssSchemaWorker.kt:663`), called from
  `populateCache` (`:611-620`).
- **Mechanism:** `tagRegistry.edgeTagOf(op.type)` calls `error()` for an unknown type string. It runs
  after the store commit and outside any `Either.catch`. `ops.flatMap { applyToCacheAsync(...) }` stops at
  the throwing op, so the ops after it never reach the cache.
- **Probe C1:** `transaction { removeEdge(a, b, "no_such_type") }` throws `IllegalStateException: Edge type
  'no_such_type' has no registered @TypeTag` out of an `Either`-returning API.
- **Probe C2:** `addNode(n1); removeEdge(.., "no_such_type"); addNode(n2)` with a store. The store holds
  n2, the cache does not.
- **Trigger:** a typo in the string-typed `removeEdge`, or a type this process's module does not know
  (rolling deploy).
- **Cache-only mode, by reading:** the cache is the only copy, so the ops after the bad one are lost.

### F4. A tag wider than `tagWidth` is silently truncated

- **Where:** `HeaderlessSchemaKeyAdapter` (`KeyAdapter.kt:314`), reached through
  `HomogeneousSchemaGraph.forTag` (`HomogeneousSchemaGraph.kt:75`).
- **Mechanism:** `SchemaKeyAdapter` rejects a tag that does not fit (`KeyAdapter.kt:282-289`).
  `HeaderlessSchemaKeyAdapter` has no such check, and `NodeKey.composeHeaderlessTag` copies only the last
  `width.bytes` bytes of the tag.
- **Probe B:** tag 1 and tag 257 at `BYTE` width both produce NodeId `010000000000000005` for id 5.
- **Consequences:**
  - two tenants whose tags agree in the low bytes share one key space, for reads and writes;
  - by reading: `ownsNodeId` compares the truncated tag with the full one, so it is false for the wide
    tag's own nodes, and `allNodeIds`, `scanNodeIds` and `scanPersistentNodes` return nothing for it.
- **Existing tests run truncated:** `MultiSchemaTest.kt:515-590` uses `forTag(SchemaTag(501L))` and
  `SchemaTag(502L)` at `SchemaTagWidth.BYTE`. They pass because 0xF5 and 0xF6 happen to differ.
- **Not at risk:** `SchemaTagWidth.UUID` with `SchemaTag.of(uuid)`, the per-user case in
  `HomogeneousSchemaTest`.

### F5. The integrity check ignores removals in the same transaction

- **Where:** `integrityError` (`AbyssSchemaWorker.kt:561-575`) and `expandCascades` (`:550-557`).
- **Mechanism:** the check resolves endpoints from `addedInTx` or `readNode`, with no removed set. The
  cascade for a `RemoveNode` is computed from the index before the transaction's own `AddEdge` exists.
- **Probes F1, F2:** `removeNode(b); addEdge(a→b)` and `addEdge(a→b); removeNode(b)` both return
  `Right(Unit)`. Afterwards the edge exists and node b does not.
- **Weight:** the transaction contradicts itself, so this is a caller error. `checkIntegrity = true`
  promises to catch it.

### F6. `toSubgraph` drops a node when two schemas share a domain id

- **Where:** `Flow<Path>.toSubgraph` (`Extensions.kt:163-172`).
- **Mechanism:** nodes are keyed by `it.id`, edges by `(fromId, toId, type)`, all domain ids. A path can
  cross schemas, and two schemas can use the same domain id.
- **Probe E:** a path holding `LongTestNode(5)` and a planet with id 5 gives a subgraph with one node.
- **By reading:** in a `HomogeneousSchemaGraph` the same class with the same id exists per tenant, so a
  cross-tenant path collapses nodes and edges the same way.

### F7. `UnknownNode` throws on a non-UUID id

- **Where:** `UnknownNode` (`AbyssSerializer.kt:28`).
- **Mechanism:** the comment says a non-UUID graph gets a zero-UUID fallback. The code calls `Uuid.parse`
  on whatever the `id` field holds, and `.jsonPrimitive` on it, which also throws for a structured id.
- **Probe D:** `{"type":"future_type","id":42}` throws `IllegalArgumentException`.
- **Consequence:** on a Long or String graph, a node of a type this member does not know fails to
  deserialize from the Hazelcast map. That is the rolling-deploy case the fallback exists for.
  `SerializationTest` covers UUID ids only.

## By reading, no test yet

- **R1. Unknown types throw inside the worker.**
  - `resolveEdgeEndpointTags` (`AbyssSchemaWorker.kt:631`) calls `typeTag()` on the endpoint node, which
    throws for `UnknownNode`. `schemaCheck` (`:732-734`) explicitly tolerates `UnknownNode`.
  - `preloadOut`/`preloadIn` call `serialName()` and `typeTag()` on each stored edge, so one `UnknownEdge`
    fails the whole node's preload.
  - `hopOf` calls `edgeNameOf(tag)`, which throws for a tag this member does not know.
- **R2. Cancellation is swallowed.** `catch (e: Throwable)` around `buffer.block()` turns
  `CancellationException` into `Left(Unexpected)`: `AbyssGraphSchema.kt:196`, `:225`, `:241`, and
  `transaction` in both containers. `modifyNode`/`modifyEdge` suspend inside that block.
- **R3. Raw exceptions from `Either` APIs.** `worker.transaction`, `batchTransaction` and `ephemeral` are
  returned unwrapped by the facade. Only `StoreUnavailableException` is mapped to `Left`. The read side
  (`node`, `edge`, `from`) wraps with `Either.catch`.
- **R4. Export hides failed reads.** `exportGraphLines` uses `node(id).getOrNull()` (`GraphExport.kt:40`):
  a failed read is a missing node in the export. Same class as TODO 1.38.
- **R5. Scan `parallelism` is not validated.** `hashCodeRanges(0)` divides by zero; a negative value gives
  no ranges, so the scan is empty and reads as "no rows" (`YugabytePersistentStore.kt:297-300`).
- **R6. `SingleSchemaGraph()` on a client.** The factory (`SingleSchemaGraph.kt`) does not pass
  `evictionVerifiedExternally`, so the startup guard always refuses a Hazelcast client instance.
- **R7. Edge writes read endpoints one at a time.** `integrityError` and `resolveEdgeEndpointTags` call
  `readNode` per endpoint, sequentially; `nodesAt` already batches. Unmeasured, so it is a lead and not a
  claim.
- **R8. Stale comments.**
  - `HazelcastEphemeralStore.kt:108-111` says `YugabyteEphemeralStore` commits as "one CQL logged batch";
    it runs one `execute` per op (durability audit, finding 3).
  - `AbyssSchemaWorker.kt:105-116` holds the same six-line comment twice.

## Read and found clean

No new defect found by reading in: the `LOCK_ORDER` sort and retry in `commitYsql`; chunking, statement
grouping and `committedOps` accounting in `commitYsqlBatched`; the history upsert and delete statements;
`NodeKey` compose/parse and the Compact key encodings including sign extension of tagged `Int32`;
`ShardedAdjacencyIndex` windows and the loaded flag; the iterative DFS; `pathTo`; the eviction guards; the
sub-second TTL guard in `ephemeral()`.

## Appendix: probe source

`abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/AuditProbeTest.kt`, as run. Run with
`./gradlew :abyss-graph:test --tests '*AuditProbeTest*'`.

```kotlin
package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.right
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import pl.iqtech.abyss.dsl.Path
import pl.iqtech.abyss.dsl.collectEdges
import pl.iqtech.abyss.dsl.countEdges
import pl.iqtech.abyss.dsl.edgeExists
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.dsl.serialName
import pl.iqtech.abyss.dsl.toSubgraph
import pl.iqtech.abyss.graph.serialization.UnknownNode
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.HeaderlessSchemaKeyAdapter
import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.SchemaTag
import pl.iqtech.abyss.store.api.SchemaTagWidth
import pl.iqtech.abyss.store.api.StoredEdge
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

// A store that really holds nodes and edges. loadEdges/loadInEdges return the neighbour's type the way
// the YSQL LEFT JOIN does: null while the neighbour row is missing, its @SerialName once it exists.
private class ProbeStore : AbyssStoreLike {
    val nodes = ConcurrentHashMap<NodeId, NodeLike<*>>()
    val edges = CopyOnWriteArrayList<StoredEdge>()
    @Volatile var loadNodeGate: CompletableDeferred<Unit>? = null
    val loadNodeEntered = CompletableDeferred<Unit>()

    private fun typeOf(id: NodeId) = nodes[id]?.let { it::class.serialName() }
    private fun same(e: StoredEdge, f: NodeId, t: NodeId, type: String) = e.fromId == f && e.toId == t && e.edge::class.serialName() == type

    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> {
        val snapshot = nodes[id]                       // the row as the query saw it
        loadNodeGate?.let { gate -> loadNodeGate = null; loadNodeEntered.complete(Unit); gate.await() }
        return Either.Right(snapshot to null)
    }
    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> =
        Either.Right(edges.find { same(it, fromId, toId, type) }?.let { it.edge to null } ?: (null to null))
    override suspend fun loadEdges(fromId: NodeId): Either<AbyssError, List<StoredEdge>> =
        Either.Right(edges.filter { it.fromId == fromId }.map { it.copy(neighborType = typeOf(it.toId)) })
    override suspend fun loadInEdges(toId: NodeId): Either<AbyssError, List<StoredEdge>> =
        Either.Right(edges.filter { it.toId == toId }.map { it.copy(neighborType = typeOf(it.fromId)) })
    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        block(object : AbyssStoreTransactionLike {
            override fun saveNode(id: NodeId, node: NodeLike<*>, tags: Set<String>) { nodes[id] = node }
            override fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, tags: Set<String>) {
                edges.removeAll { same(it, fromId, toId, edge::class.serialName()) }
                edges += StoredEdge(fromId, toId, edge, null)
            }
            override fun deleteNode(id: NodeId) { nodes.remove(id) }
            override fun deleteEdge(fromId: NodeId, toId: NodeId, type: String) { edges.removeAll { same(it, fromId, toId, type) } }
        })
        return Unit.right()
    }
}

private data class ProbePlanet(
    override val id: Long,
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
) : NodeLike<Long>

class AuditProbeTest {

    private fun graph(prefix: String, store: AbyssStoreLike? = null): AbyssGraphSchema<Uuid> {
        listOf("$prefix-nodes", "$prefix-edges", "$prefix-edges-adjacency").forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        return AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "$prefix-nodes", "$prefix-edges", persistentStore = store, module = graphTestModule)
    }

    // ── A: adjacency entry identity ──────────────────────────────────────────

    @Test fun `A1 cache-only - re-adding an edge after its target node appears must not duplicate the hop`() = runBlocking {
        val g = graph("au-a1")
        val a = Uuid.random(); val b = Uuid.random()
        check(g.transaction { addNode(TestNode(id = a, name = "a")) }.isRight())
        check(g.transaction(checkIntegrity = false) { addEdge(TestEdge(fromId = a, toId = b, label = "v1")) }.isRight())
        check(g.transaction { addNode(TestNode(id = b, name = "b")) }.isRight())
        check(g.transaction { addEdge(TestEdge(fromId = a, toId = b, label = "v2")) }.isRight())

        assertEquals(1, g.from(a) { countEdges<TestEdge>() }.getOrNull(), "one edge a->b, counted")
        assertEquals(listOf("v2"), g.from(a) { outgoing<TestEdge>(); collectEdges<TestEdge>().toList() }.getOrNull()!!.map { it.label })
    }

    @Test fun `A2 store - edge written before its target node, then read cold, must come back once`() = runBlocking {
        val g = graph("au-a2", ProbeStore())
        val a = Uuid.random(); val b = Uuid.random()
        check(g.transaction(checkIntegrity = false) { addEdge(TestEdge(fromId = a, toId = b, label = "only")) }.isRight())
        check(g.transaction { addNode(TestNode(id = a, name = "a")); addNode(TestNode(id = b, name = "b")) }.isRight())

        assertEquals(listOf("only"), g.outEdges(a).toList().map { (it as TestEdge).label })
    }

    // ── B: headerless tag does not fit its width ─────────────────────────────

    @Test fun `B tag wider than tagWidth must be rejected or stay distinct`() {
        val built = runCatching { HeaderlessSchemaKeyAdapter(SchemaTag(257L), SchemaTagWidth.BYTE, LongKeyAdapter) }
        if (built.isFailure) return
        val tenant1 = HeaderlessSchemaKeyAdapter(SchemaTag(1L), SchemaTagWidth.BYTE, LongKeyAdapter)
        assertNotEquals(tenant1.toNodeId(5L), built.getOrThrow().toNodeId(5L), "tenant 1 and tenant 257 share a key")
    }

    // ── C: unregistered edge type in a remove ────────────────────────────────

    @Test fun `C1 removeEdge with an unregistered type returns an Either`() = runBlocking {
        val g = graph("au-c1")
        val r = runCatching { g.transaction { removeEdge(Uuid.random(), Uuid.random(), "no_such_type") } }
        assertTrue(r.isSuccess, "transaction threw instead of returning Either: ${r.exceptionOrNull()}")
    }

    @Test fun `C2 removeEdge with an unregistered type leaves store and cache in agreement`() = runBlocking {
        val store = ProbeStore()
        val g = graph("au-c2", store)
        val n2 = TestNode(id = Uuid.random(), name = "after")
        runCatching {
            g.transaction {
                addNode(TestNode(id = Uuid.random(), name = "before"))
                removeEdge(Uuid.random(), Uuid.random(), "no_such_type")
                addNode(n2)
            }
        }
        val nid = huid.toNodeId(n2.id)
        assertEquals(store.nodes.containsKey(nid), graphTestHz.getMap<NodeId, NodeLike<*>>("au-c2-nodes").containsKey(nid), "store committed vs cache populated")
    }

    // ── D: UnknownNode on a non-UUID graph ───────────────────────────────────

    @Test fun `D UnknownNode accepts a Long id`() {
        val r = runCatching { UnknownNode(Json.parseToJsonElement("""{"type":"future_type","id":42}""")) }
        assertTrue(r.isSuccess, "UnknownNode threw on a non-UUID id: ${r.exceptionOrNull()}")
    }

    // ── E: toSubgraph across schemas ─────────────────────────────────────────

    @Test fun `E toSubgraph keeps two nodes of different schemas that share a domain id`() = runBlocking {
        val path = Path(listOf(LongTestNode(id = 5L, name = "user"), ProbePlanet(id = 5L)), emptyList())
        assertEquals(2, flowOf(path).toSubgraph().nodes.size)
    }

    // ── F: integrity check vs a removal in the same transaction ──────────────

    @Test fun `F1 removeNode then addEdge to it in one transaction leaves no dangling edge`() = runBlocking {
        val g = graph("au-f1")
        val a = Uuid.random(); val b = Uuid.random()
        check(g.transaction { addNode(TestNode(id = a, name = "a")); addNode(TestNode(id = b, name = "b")) }.isRight())
        val r = g.transaction { removeNode(b); addEdge(TestEdge(fromId = a, toId = b, label = "x")) }
        val dangling = g.edgeExists<TestEdge>(a, b).getOrNull() == true && g.node(b).isLeft()
        assertTrue(!dangling, "result=$r, edge a->b exists while node b is gone")
    }

    @Test fun `F2 addEdge then removeNode of its target in one transaction leaves no dangling edge`() = runBlocking {
        val g = graph("au-f2")
        val a = Uuid.random(); val b = Uuid.random()
        check(g.transaction { addNode(TestNode(id = a, name = "a")); addNode(TestNode(id = b, name = "b")) }.isRight())
        val r = g.transaction { addEdge(TestEdge(fromId = a, toId = b, label = "x")); removeNode(b) }
        val dangling = g.edgeExists<TestEdge>(a, b).getOrNull() == true && g.node(b).isLeft()
        assertTrue(!dangling, "result=$r, edge a->b exists while node b is gone")
    }

    // ── G: cold read write-back vs a concurrent remove ───────────────────────

    @Test fun `G a cold read racing a removeNode must not put the removed node back in the cache`() = runBlocking {
        val store = ProbeStore()
        val g = graph("au-g", store)
        val x = Uuid.random()
        check(g.transaction { addNode(TestNode(id = x, name = "x")) }.isRight())
        graphTestHz.getMap<Any, Any>("au-g-nodes").clear()          // cold: evicted, still in the store

        val gate = CompletableDeferred<Unit>()
        store.loadNodeGate = gate
        val reader = async(Dispatchers.Default) { g.node(x) }        // store read done, write-back pending
        store.loadNodeEntered.await()
        check(g.transaction { removeNode(x) }.isRight())             // commits: store row and cache entry gone
        gate.complete(Unit)
        reader.await()

        assertTrue(store.nodes.isEmpty(), "store no longer holds x")
        assertIs<Either.Left<AbyssError>>(g.node(x), "x was removed, a later read must not find it")
        Unit
    }
}
```
