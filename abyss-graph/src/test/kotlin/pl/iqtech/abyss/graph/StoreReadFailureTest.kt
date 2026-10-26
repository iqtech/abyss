package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.right
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.EdgeTraversalDirection
import pl.iqtech.abyss.dsl.Evaluation
import pl.iqtech.abyss.dsl.TraversalStrategy
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreLike
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreTransactionLike
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.StoredEdge
import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.SchemaKeyAdapter
import pl.iqtech.abyss.store.api.SchemaTag
import pl.iqtech.abyss.store.api.SchemaTagWidth
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.uuid.Uuid

// TODO 1.38: a store read that returns Left must fail the read with StoreUnavailable — never read as
// "absent". Reads fail, writes succeed: the partial outage where silently reading around the store is
// most dangerous (a delete cascade that sees no edges still commits).
private val DOWN = AbyssError.Unexpected(RuntimeException("store down"))

private class ReadFailingStore : AbyssStoreLike {
    val nodes = mutableMapOf<NodeId, NodeLike<*>>()
    val edges = mutableListOf<StoredEdge>()
    @Volatile var failing = false
    private fun boom() = Either.Left(DOWN)
    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> =
        if (failing) boom() else Either.Right(nodes[id] to null)
    override suspend fun loadNodes(ids: Collection<NodeId>): Either<AbyssError, Map<NodeId, Pair<NodeLike<*>?, Duration?>>> =
        if (failing) boom() else Either.Right(ids.mapNotNull { id -> nodes[id]?.let { id to (it to null) } }.toMap())
    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> =
        if (failing) boom() else Either.Right(edges.find { it.fromId == fromId && it.toId == toId }?.let { it.edge to null } ?: (null to null))
    override suspend fun loadEdges(fromId: NodeId): Either<AbyssError, List<StoredEdge>> = if (failing) boom() else Either.Right(edges.filter { it.fromId == fromId })
    override suspend fun loadInEdges(toId: NodeId): Either<AbyssError, List<StoredEdge>> = if (failing) boom() else Either.Right(edges.filter { it.toId == toId })
    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        block(object : AbyssStoreTransactionLike {
            override fun saveNode(id: NodeId, node: NodeLike<*>, tags: Set<String>) { nodes[id] = node }
            override fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, tags: Set<String>) { edges += StoredEdge(fromId, toId, edge, null, "test_node") }
            override fun deleteNode(id: NodeId) { nodes.remove(id) }
            override fun deleteEdge(fromId: NodeId, toId: NodeId, type: String) { edges.removeAll { it.fromId == fromId && it.toId == toId } }
        })
        return Unit.right()
    }
}

// Empty ephemeral side whose reads can fail independently of the persistent store.
private class ReadFailingEphemeralStore : AbyssEphemeralStoreLike {
    @Volatile var failing = false
    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> =
        if (failing) Either.Left(DOWN) else Either.Right(null to null)
    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> =
        if (failing) Either.Left(DOWN) else Either.Right(null to null)
    override suspend fun loadEdges(fromId: NodeId): Either<AbyssError, List<StoredEdge>> =
        if (failing) Either.Left(DOWN) else Either.Right(emptyList())
    override suspend fun transaction(block: suspend AbyssEphemeralStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> = Unit.right()
}

class StoreReadFailureTest {
    private val maps = listOf("srf-nodes", "srf-edges", "srf-edges-adjacency")
    private val store = ReadFailingStore()
    private val eph = ReadFailingEphemeralStore()
    private val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "srf-nodes", "srf-edges",
        persistentStore = store, ephemeralStore = eph, module = graphTestModule)
    private val nodesMap = graphTestHz.getMap<NodeId, Any>("srf-nodes")
    private val edgesMap = graphTestHz.getMap<Any, Any>("srf-edges")
    private val adjMap = graphTestHz.getMap<Any, Any>("srf-edges-adjacency")

    // Five nodes, chain a>b>c plus d, e unconnected. Odd count, asymmetric degrees.
    private val n = listOf("a", "b", "c", "d", "e").map { TestNode(id = Uuid.random(), name = it) }
    private val a = n[0]; private val b = n[1]; private val c = n[2]
    private fun nid(x: TestNode) = huid.toNodeId(x.id)
    private fun assertUnavailable(r: Either<AbyssError, *>) =
        assertEquals(AbyssError.StoreUnavailable(DOWN), r.leftOrNull(), "expected Left(StoreUnavailable), got $r")

    @BeforeTest fun seed() = runBlocking {
        maps.forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        g.transaction {
            n.forEach { addNode(it) }
            addEdge(TestEdge(fromId = a.id, toId = b.id, label = "a-b"))
            addEdge(TestEdge(fromId = b.id, toId = c.id, label = "b-c"))
        }.let { assertTrue(it.isRight(), "seed: $it") }
    }

    @AfterTest fun clear() = maps.forEach { graphTestHz.getMap<Any, Any>(it).clear() }

    // Today: Left(NodeNotFound) — an error, but it claims the node doesn't exist.
    @Test fun `node on cache miss with store down is StoreUnavailable`() = runBlocking {
        nodesMap.evict(nid(b)); store.failing = true
        assertUnavailable(g.node(b.id))
    }

    // Today: Right(false).
    @Test fun `nodeExists on cache miss with store down is StoreUnavailable`() = runBlocking {
        nodesMap.evict(nid(b)); store.failing = true
        assertUnavailable(g.nodeExists(b.id))
    }

    // Today: Left(EdgeNotFound).
    @Test fun `edge on cache miss with store down is StoreUnavailable`() = runBlocking {
        edgesMap.evictAll(); store.failing = true
        assertUnavailable(g.edge(a.id, b.id, "test_edge"))
    }

    // Today: Right of the 3 still-cached ids. Evicts 2 of 5, non-adjacent.
    @Test fun `existingNodeIds with a partial miss and store down is StoreUnavailable`() = runBlocking {
        nodesMap.evict(nid(n[1])); nodesMap.evict(nid(n[3])); store.failing = true
        assertUnavailable(g.existingNodeIds(n.map { it.id }))
    }

    // Today: completes empty. A cold index (restart) forces the preload.
    @Test fun `outEdges on a cold node with store down throws StoreUnavailableException`() = runBlocking {
        adjMap.clear(); edgesMap.clear(); store.failing = true
        val ex = assertFailsWith<StoreUnavailableException> { g.outEdges(b.id).toList() }
        assertEquals(DOWN, ex.error.error)
    }

    // Today: Right with a truncated path set.
    @Test fun `traversal with an evicted mid node and store down is StoreUnavailable`() = runBlocking {
        nodesMap.evict(nid(b)); store.failing = true
        for (s in TraversalStrategy.entries) {
            assertUnavailable(g.from(a.id) {
                paths(s, EdgeTraversalDirection.OUT, edgeVisitor = { _, _ -> true }, nodeEvaluator = { _, _ -> Evaluation.INCLUDE_AND_CONTINUE }).toList()
            })
        }
    }

    // Today: Right — the cascade reads an empty cold index, deletes node b only, and both b's edges stay in
    // the store dangling. Must fail with nothing committed.
    @Test fun `removeNode with a cold index and store reads down fails and commits nothing`() = runBlocking {
        adjMap.clear(); store.failing = true
        assertUnavailable(g.transaction { removeNode(b.id) })
        assertTrue(nid(b) in store.nodes, "node b must not be deleted")
        assertEquals(2, store.edges.size, "both edges must remain")
    }

    // Today: Left(IntegrityError "not found").
    @Test fun `addEdge integrity check on a cold endpoint with store down is StoreUnavailable`() = runBlocking {
        nodesMap.evict(nid(n[4])); store.failing = true
        assertUnavailable(g.transaction { addEdge(TestEdge(fromId = a.id, toId = n[4].id, label = "a-e")) })
    }

    // Any Left fails the read: the persistent side has the node, the ephemeral side errors. Today: Right(b).
    @Test fun `ephemeral read failure fails the read even when persistent answered`() = runBlocking {
        nodesMap.evict(nid(b)); eph.failing = true
        assertUnavailable(g.node(b.id))
    }

    // Read-modify-write: today the miss reads as null, transform(null) builds a fresh node, and the commit
    // OVERWRITES the stored one. Must fail with the stored node untouched.
    @Test fun `modifyNode on an evicted node with store down fails and leaves the stored node intact`() = runBlocking {
        nodesMap.evict(nid(b)); store.failing = true
        assertUnavailable(g.transaction { modifyNode(b.id) { (it as TestNode?)?.copy(name = "b2") ?: TestNode(id = b.id, name = "fresh") } })
        assertEquals(b, store.nodes[nid(b)])
    }

    // ephemeralStoreHops: today a failing ephemeral loadEdges is skipped and only the persistent edges emit.
    @Test fun `outEdges including ephemeral with the ephemeral store down throws StoreUnavailableException`() = runBlocking {
        eph.failing = true
        val ex = assertFailsWith<StoreUnavailableException> { g.outEdges(b.id, includeEphemeral = true).toList() }
        assertEquals(DOWN, ex.error.error)
    }

    // Cache population resolves endpoint type tags by reading the node. That read must happen BEFORE the store
    // commit: failing after it would report Left for a committed edge. checkIntegrity = false so nothing else
    // reads e first. Today: Right, edge committed (the tag silently degrades to null).
    @Test fun `addEdge whose endpoint tag read fails commits nothing`() = runBlocking {
        val e = n[4]
        nodesMap.evict(nid(e)); store.failing = true
        assertUnavailable(g.transaction(checkIntegrity = false) { addEdge(TestEdge(fromId = a.id, toId = e.id, label = "a-e")) })
        assertEquals(2, store.edges.size, "a-e must not be committed")
    }

    // One container catch site (Heterogeneous; Homogeneous has the identical body). Today: Right(false).
    @Test fun `container crossEdgeExists with store down is StoreUnavailable`() = runBlocking {
        val cs = ReadFailingStore()
        listOf("srfx-nodes", "srfx-edges", "srfx-edges-adjacency").forEach { multiSchemaHz.getMap<Any, Any>(it).clear() }
        val c = HeterogeneousSchemaGraph(multiSchemaHz, SchemaTagWidth.BYTE, "srfx-nodes", "srfx-edges",
            allowCrossSchemaEdges = true, persistentStore = cs, module = graphTestModule)
        val (t1, t2) = SchemaTag(41L) to SchemaTag(42L)
        c.register(t1, LongKeyAdapter); c.register(t2, UuidKeyAdapter)
        val from = SchemaKeyAdapter(t1, SchemaTagWidth.BYTE, LongKeyAdapter).toNodeId(7L)
        val to = SchemaKeyAdapter(t2, SchemaTagWidth.BYTE, UuidKeyAdapter).toNodeId(Uuid.random())
        cs.failing = true
        assertUnavailable(c.crossEdgeExists(from, to, "test_edge"))
    }

    // preloadIn: b has one in-edge (a-b); a cold index forces the IN preload. Today: completes empty.
    @Test fun `inEdges on a cold node with store down throws StoreUnavailableException`() = runBlocking {
        adjMap.clear(); edgesMap.clear(); store.failing = true
        val ex = assertFailsWith<StoreUnavailableException> { g.inEdges(b.id).toList() }
        assertEquals(DOWN, ex.error.error)
    }

    // Batched node read, ephemeral side failing while persistent answers. Today: Right of all five.
    @Test fun `existingNodeIds with the ephemeral store down is StoreUnavailable`() = runBlocking {
        nodesMap.evict(nid(n[1])); nodesMap.evict(nid(n[3])); eph.failing = true
        assertUnavailable(g.existingNodeIds(n.map { it.id }))
    }

    // Edge point read, ephemeral side failing while persistent answers. Today: Right(edge).
    @Test fun `edge with the ephemeral store down is StoreUnavailable`() = runBlocking {
        edgesMap.evictAll(); eph.failing = true
        assertUnavailable(g.edge(a.id, b.id, "test_edge"))
    }

    // Ephemeral store-api default loadNodes (no override): one failing id of three. Today: Right of the other two.
    @Test fun `default ephemeral loadNodes propagates a per-id Left`() = runBlocking {
        val ids = n.take(3).map(::nid)
        val bad = ids[2]
        val s = object : AbyssEphemeralStoreLike {
            override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> =
                if (id == bad) Either.Left(DOWN) else Either.Right(null to null)
            override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> = Either.Right(null to null)
            override suspend fun transaction(block: suspend AbyssEphemeralStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> = Unit.right()
        }
        assertEquals(DOWN, s.loadNodes(ids).leftOrNull())
    }

    // Store-api default loadNodes (no override): one failing id of three. Today: Right of the other two.
    @Test fun `default loadNodes propagates a per-id Left`() = runBlocking {
        val ids = n.take(3).map(::nid)
        val bad = ids[1]
        val s = object : AbyssStoreLike {
            override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> =
                if (id == bad) Either.Left(DOWN) else Either.Right(null to null)
            override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> = Either.Right(null to null)
            override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> = Unit.right()
        }
        assertIs<Either.Left<AbyssError>>(s.loadNodes(ids))
        assertEquals(DOWN, s.loadNodes(ids).leftOrNull())
    }
}
