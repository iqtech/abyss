package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.right
import com.hazelcast.map.IMap
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.collectNodes
import pl.iqtech.abyss.dsl.edge
import pl.iqtech.abyss.dsl.nodes
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.Path
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.StoredEdge
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.uuid.Uuid

// Same shape as IndexAlwaysAliveTest's SelfHealStore (private there): a store that really holds edges.
private class EvictionTestStore : AbyssStoreLike {
    val edges = java.util.concurrent.CopyOnWriteArrayList<StoredEdge>()
    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> = Either.Right(null to null)
    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> =
        Either.Right(edges.find { it.fromId == fromId && it.toId == toId }?.let { it.edge to null } ?: (null to null))
    override suspend fun loadEdges(fromId: NodeId): Either<AbyssError, List<StoredEdge>> = Either.Right(edges.filter { it.fromId == fromId })
    override suspend fun loadInEdges(toId: NodeId): Either<AbyssError, List<StoredEdge>> = Either.Right(edges.filter { it.toId == toId })
    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        block(object : AbyssStoreTransactionLike {
            override fun saveNode(id: NodeId, node: NodeLike<*>, tags: Set<String>) {}
            override fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, tags: Set<String>) { edges += StoredEdge(fromId, toId, edge, null) }
            override fun deleteNode(id: NodeId) {}
            override fun deleteEdge(fromId: NodeId, toId: NodeId, type: String) { edges.removeAll { it.fromId == fromId && it.toId == toId } }
        })
        return Unit.right()
    }
}

// TODO 4.14: outEdges must return the full set when edgesMap (an evictable cache, TODO 1.27) lost values
// the authoritative adjacency index still lists.
class OutEdgesEvictionTest {

    private fun setup(prefix: String): Triple<AbyssGraphSchema<Uuid>, Uuid, List<TestEdge>> = runBlocking {
        listOf("$prefix-nodes", "$prefix-edges", "$prefix-edges-adjacency").forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "$prefix-nodes", "$prefix-edges", persistentStore = EvictionTestStore(), module = graphTestModule)
        val hub = Uuid.random()
        val edges = (1..10).map { TestEdge(fromId = hub, toId = Uuid.random(), label = "e$it") }
        check(g.transaction(checkIntegrity = false) { edges.forEach { addEdge(it) } }.isRight())
        Triple(g, hub, edges)
    }

    @Test fun `partial eviction - outEdges still returns every edge`() = runBlocking {
        val (g, hub, edges) = setup("oev1")
        val map: IMap<EdgeKey, EdgeLike<*, *>> = graphTestHz.getMap("oev1-edges")
        assertEquals(10, map.size)
        // Evict by predicate (runs on every partition): a key read back from the map has no partition key
        // (EdgeKey.readBack, TODO 4.14), so it can't be routed to evict(key). No MapStore here, so remove == eviction.
        val victims = edges.take(3).map { it.label }.toSet()
        map.removeAll(com.hazelcast.query.Predicate<EdgeKey, EdgeLike<*, *>> { (it.value as TestEdge).label in victims })
        assertEquals(7, map.size, "3 values evicted from the cache")

        assertEquals(edges.map { it.label }.toSet(), g.outEdges(hub).toList().map { (it as TestEdge).label }.toSet())
    }

    @Test fun `cold node - cache and index empty, store holds the edges`() = runBlocking {
        val (g, hub, edges) = setup("oev2")
        graphTestHz.getMap<Any, Any>("oev2-edges").clear()
        graphTestHz.getMap<Any, Any>("oev2-edges-adjacency").clear()
        assertEquals(edges.map { it.label }.toSet(), g.outEdges(hub).toList().map { (it as TestEdge).label }.toSet())
    }

    // Why the 1-op cache-only scan (TODO 4.14) is NOT taken with a store, even with edgesMap eviction off: a point
    // read (edge()/edgeExists) caches ONE edge of a cold node, so the cache holds 1 of 10 values with nothing evicted.
    @Test fun `store, nothing evicted - a point read caches one edge of a cold node, outEdges still returns every edge`() = runBlocking {
        val (g, hub, edges) = setup("oev7")
        graphTestHz.getMap<Any, Any>("oev7-edges").clear()
        graphTestHz.getMap<Any, Any>("oev7-edges-adjacency").clear()
        assertTrue(g.edge<TestEdge>(hub, edges.first().toId).isRight())
        assertEquals(1, graphTestHz.getMap<Any, Any>("oev7-edges").size, "the point read cached exactly one edge")

        assertEquals(edges.map { it.label }.toSet(), g.outEdges(hub).toList().map { (it as TestEdge).label }.toSet())
    }

    @Test fun `steady state - nothing evicted`() = runBlocking {
        val (g, hub, edges) = setup("oev3")
        assertEquals(edges.map { it.label }.toSet(), g.outEdges(hub).toList().map { (it as TestEdge).label }.toSet())
    }

    // A key read back from a map has no partition key: routing with it must fail loudly, not hit the hex-default
    // partition while the worker wrote with the adapter pk (UUID here) — TODO 4.14.
    @Test fun `keys read back from a map refuse to route`() = runBlocking {
        val (_, _, _) = setup("oev6")
        val edgeKey = graphTestHz.getMap<EdgeKey, EdgeLike<*, *>>("oev6-edges").entries.first().key
        val adjKey = graphTestHz.getMap<AdjacencyKey, AdjacencyValue>("oev6-edges-adjacency").entries.first().key
        assertFailsWith<IllegalStateException> { edgeKey.partitionKey }
        assertFailsWith<IllegalStateException> { adjKey.partitionKey }
        // Through the map, Hazelcast wraps it (HazelcastSerializationException while computing the partition hash).
        val routed = assertFails { graphTestHz.getMap<EdgeKey, EdgeLike<*, *>>("oev6-edges")[edgeKey] }
        assertTrue(generateSequence(routed) { it.cause }.any { it is IllegalStateException && "read back" in (it.message ?: "") }, "cause chain: $routed")
    }

    // Node delete cascades its edges from the key set it can see. Store deleteNode removes only the node row
    // (edges go by explicit DeleteEdge), so an outgoing edge the cascade misses stays in the store AND in the
    // index — every later read heals it back as an edge of a deleted node.
    @Test fun `partial eviction - deleting a node cascades every outgoing edge`() = runBlocking {
        listOf("oev5-nodes", "oev5-edges", "oev5-edges-adjacency").forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        val store = EvictionTestStore()
        val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "oev5-nodes", "oev5-edges", persistentStore = store, module = graphTestModule)
        val hub = TestNode(id = Uuid.random(), name = "hub")
        val targets = (1..10).map { TestNode(id = Uuid.random(), name = "t$it") }
        val edges = targets.mapIndexed { i, t -> TestEdge(fromId = hub.id, toId = t.id, label = "e${i + 1}") }
        check(g.transaction(checkIntegrity = false) { addNode(hub); targets.forEach { addNode(it) }; edges.forEach { addEdge(it) } }.isRight())
        val map: IMap<EdgeKey, EdgeLike<*, *>> = graphTestHz.getMap("oev5-edges")
        val victims = edges.take(3).map { it.label }.toSet()
        map.removeAll(com.hazelcast.query.Predicate<EdgeKey, EdgeLike<*, *>> { (it.value as TestEdge).label in victims })
        assertEquals(7, map.size)

        check(g.transaction { removeNode(hub.id) }.isRight())

        assertEquals(emptyList(), store.edges.map { (it.edge as TestEdge).label }, "no edge rows left in the store")
        assertEquals(emptyList(), targets.flatMap { g.inEdges(it.id).toList() }.map { (it as TestEdge).label }, "no target still sees an incoming edge")
        assertEquals(emptyList(), g.outEdges(hub.id).toList().map { (it as TestEdge).label }, "deleted hub has no outgoing edges")
    }

    // outAtPersistent's typed value fast path (type != null && needValue, i.e. outgoing<E> { predicate }) was an
    // unchecked partition scan behind a warm probe before TODO 4.14. Existence-only outgoing<E>() rides the index: control.
    @Test fun `partial eviction - typed traversal with edge predicate still reaches every target`() = runBlocking {
        listOf("oev4-nodes", "oev4-edges", "oev4-edges-adjacency").forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "oev4-nodes", "oev4-edges", persistentStore = EvictionTestStore(), module = graphTestModule)
        val hub = TestNode(id = Uuid.random(), name = "hub")
        val targets = (1..10).map { TestNode(id = Uuid.random(), name = "t$it") }
        val edges = targets.mapIndexed { i, t -> TestEdge(fromId = hub.id, toId = t.id, label = "e${i + 1}") }
        check(g.transaction(checkIntegrity = false) { addNode(hub); targets.forEach { addNode(it) }; edges.forEach { addEdge(it) } }.isRight())
        val map: IMap<EdgeKey, EdgeLike<*, *>> = graphTestHz.getMap("oev4-edges")
        val victims = edges.take(3).map { it.label }.toSet()
        map.removeAll(com.hazelcast.query.Predicate<EdgeKey, EdgeLike<*, *>> { (it.value as TestEdge).label in victims })
        assertEquals(7, map.size)

        val existenceOnly = g.from(hub.id) { outgoing<TestEdge>(); nodes<TestNode>(); collectNodes<TestNode>().toList() }
        val withPredicate = g.from(hub.id) { outgoing<TestEdge> { true }; nodes<TestNode>(); collectNodes<TestNode>().toList() }
        assertEquals(10, existenceOnly.getOrNull()?.size, "control: index path")
        assertEquals(10, withPredicate.getOrNull()?.size, "typed value fast path under partial eviction")
    }

    // TODO 1.34 note 2: key-only hops (no edge predicate) get their values from resolveEdges — one getAll,
    // misses dropped — while adjacencyHopFlow's flush heals the same misses from the store.
    private fun evictThree(prefix: String, edges: List<TestEdge>) {
        val map: IMap<EdgeKey, EdgeLike<*, *>> = graphTestHz.getMap("$prefix-edges")
        val victims = edges.take(3).map { it.label }.toSet()
        map.removeAll(com.hazelcast.query.Predicate<EdgeKey, EdgeLike<*, *>> { (it.value as TestEdge).label in victims })
        assertEquals(7, map.size, "3 values evicted from the cache")
    }

    @Test fun `partial eviction - flushHopEdges after a key-only hop returns every edge`() = runBlocking {
        val (g, hub, edges) = setup("oev8")
        evictThree("oev8", edges)
        val got = g.from(hub) { outgoing<TestEdge>(); flushHopEdges().toList() }.getOrNull()!!
        assertEquals(edges.map { it.label }.toSet(), got.map { (it as TestEdge).label }.toSet())
    }

    @Test fun `partial eviction - collectSubgraph after a key-only hop returns every edge`() = runBlocking {
        val (g, hub, edges) = setup("oev9")
        evictThree("oev9", edges)
        val got = g.from(hub) { outgoing<TestEdge>(); collectSubgraph() }.getOrNull()!!
        assertEquals(edges.map { it.label }.toSet(), got.edges.map { (it as TestEdge).label }.toSet())
    }

    @Test fun `partial eviction - exhaustReachable after a key-only hop returns every edge`() = runBlocking {
        val (g, hub, edges) = setup("oev10")
        evictThree("oev10", edges)
        val got = g.from(hub) { exhaustReachable { outgoing<TestEdge>() } }.getOrNull()!!
        assertEquals(edges.map { it.label }.toSet(), got.edges.map { (it as TestEdge).label }.toSet())
    }

    // Removed ≠ evicted: gone from cache AND store while the index still lists it (a remove racing the read).
    // Healing must not resurrect it — the store says null, so the hop is dropped, as before.
    @Test fun `removed edge the index still lists is dropped, evicted ones are healed`() = runBlocking {
        listOf("oev11-nodes", "oev11-edges", "oev11-edges-adjacency").forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        val store = EvictionTestStore()
        val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "oev11-nodes", "oev11-edges", persistentStore = store, module = graphTestModule)
        val hub = Uuid.random()
        val edges = (1..10).map { TestEdge(fromId = hub, toId = Uuid.random(), label = "e$it") }
        check(g.transaction(checkIntegrity = false) { edges.forEach { addEdge(it) } }.isRight())
        evictThree("oev11", edges)                                   // e1..e3: evicted, still stored
        val removed = edges[3]                                       // e4: gone from cache and store
        graphTestHz.getMap<EdgeKey, EdgeLike<*, *>>("oev11-edges")
            .removeAll(com.hazelcast.query.Predicate<EdgeKey, EdgeLike<*, *>> { (it.value as TestEdge).label == removed.label })
        store.edges.removeAll { (it.edge as TestEdge).label == removed.label }

        val got = g.from(hub) { outgoing<TestEdge>(); flushHopEdges().toList() }.getOrNull()!!
        assertEquals((edges - removed).map { it.label }.toSet(), got.map { (it as TestEdge).label }.toSet())
    }

    // pathTo: A→B→T is the shortest route, A→C→D→T the detour. Its search must run on the index; an edge's
    // value only matters for the returned Path.
    private class PathGraph(val g: AbyssGraphSchema<Uuid>, val store: EvictionTestStore, val a: Uuid, val t: Uuid)

    private fun pathGraph(prefix: String, withDetour: Boolean): PathGraph = runBlocking {
        listOf("$prefix-nodes", "$prefix-edges", "$prefix-edges-adjacency").forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        val store = EvictionTestStore()
        val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "$prefix-nodes", "$prefix-edges", persistentStore = store, module = graphTestModule)
        val (a, b, c, d, t) = List(5) { Uuid.random() }
        check(g.transaction {
            listOf(a to "A", b to "B", c to "C", d to "D", t to "T").forEach { (id, n) -> addNode(TestNode(id, name = n)) }
            addEdge(TestEdge(fromId = a, toId = b, label = "ab")); addEdge(TestEdge(fromId = b, toId = t, label = "bt"))
            if (withDetour) {
                addEdge(TestEdge(fromId = a, toId = c, label = "ac")); addEdge(TestEdge(fromId = c, toId = d, label = "cd"))
                addEdge(TestEdge(fromId = d, toId = t, label = "dt"))
            }
        }.isRight())
        PathGraph(g, store, a, t)
    }

    private fun evictLabel(prefix: String, label: String) {
        val map: IMap<EdgeKey, EdgeLike<*, *>> = graphTestHz.getMap("$prefix-edges")
        map.removeAll(com.hazelcast.query.Predicate<EdgeKey, EdgeLike<*, *>> { (it.value as TestEdge).label == label })
        check(map.values.none { (it as TestEdge).label == label })
    }

    private fun Path.labels() = edges.map { (it as TestEdge).label }
    private fun Path.names() = nodes.map { (it as TestNode).name }

    @Test fun `pathTo - evicted edge on the shortest route still yields the shortest path`() = runBlocking {
        val p = pathGraph("oev12", withDetour = true)
        evictLabel("oev12", "ab")
        val path = p.g.from(p.a) { pathTo(p.t) { outgoing<TestEdge>() } }.getOrNull()
        assertEquals(listOf("A", "B", "T"), path?.names())
        assertEquals(listOf("ab", "bt"), path?.labels(), "edge values, the evicted one healed from the store")
    }

    @Test fun `pathTo - evicted edge on the only route still finds it`() = runBlocking {
        val p = pathGraph("oev13", withDetour = false)
        evictLabel("oev13", "ab")
        val path = p.g.from(p.a) { pathTo(p.t) { outgoing<TestEdge>() } }.getOrNull()
        assertEquals(listOf("ab", "bt"), path?.labels())
    }

    @Test fun `pathTo - removed edge the index still lists is routed around, never returned`() = runBlocking {
        val p = pathGraph("oev14", withDetour = true)
        evictLabel("oev14", "ab")
        p.store.edges.removeAll { (it.edge as TestEdge).label == "ab" }
        val path = p.g.from(p.a) { pathTo(p.t) { outgoing<TestEdge>() } }.getOrNull()
        assertEquals(listOf("A", "C", "D", "T"), path?.names())
        assertEquals(listOf("ac", "cd", "dt"), path?.labels())
    }

    @Test fun `pathTo - removed edge on the only route means no path`() = runBlocking {
        val p = pathGraph("oev15", withDetour = false)
        evictLabel("oev15", "ab")
        p.store.edges.removeAll { (it.edge as TestEdge).label == "ab" }
        assertEquals(null, p.g.from(p.a) { pathTo(p.t) { outgoing<TestEdge>() } }.getOrNull())
    }

    @Test fun `pathTo - steady state, nothing evicted`() = runBlocking {
        val p = pathGraph("oev16", withDetour = true)
        val path = p.g.from(p.a) { pathTo(p.t) { outgoing<TestEdge>() } }.getOrNull()
        assertEquals(listOf("A", "B", "T"), path?.names())
        assertEquals(listOf("ab", "bt"), path?.labels())
    }
}
