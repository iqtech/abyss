package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.right
import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.edge
import pl.iqtech.abyss.dsl.edgeExists
import pl.iqtech.abyss.dsl.inEdges
import pl.iqtech.abyss.dsl.node
import pl.iqtech.abyss.dsl.outEdges
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

val graphTestModule = SerializersModule {
    polymorphic(NodeLike::class) { subclass(TestNode::class) }
    polymorphic(EdgeLike::class) { subclass(TestEdge::class) }
}

val graphTestHz by lazy {
    System.setProperty("hazelcast.logging.type", "none")
    Hazelcast.newHazelcastInstance(Config().registerAbyssSerializers(graphTestModule))
}

val graphTest by lazy { AbyssGraph(graphTestHz, "g-nodes", "g-edges") }

class GraphTest {

    @BeforeTest fun clear() {
        graphTestHz.getMap<Any, Any>("g-nodes").clear()
        graphTestHz.getMap<Any, Any>("g-edges").clear()
    }

    // ── node ─────────────────────────────────────────────────────────────────

    @Test fun `node() returns Right when present`() {
        runBlocking {
            val node = TestNode(id = UUID.randomUUID(), name = "rifle")
            graphTestHz.getMap<UUID, NodeLike>("g-nodes")[node.id] = node
            val result = graphTest.node(node.id)
            assertIs<Either.Right<NodeLike>>(result)
            assertEquals(node, result.value)
        }
    }

    @Test fun `node() returns NodeNotFound when absent`() {
        runBlocking {
            val result = graphTest.node(UUID.randomUUID())
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.NodeNotFound>(result.value)
        }
    }

    @Test fun `reified node() casts to concrete type`() {
        runBlocking {
            val node = TestNode(id = UUID.randomUUID(), name = "scope")
            graphTestHz.getMap<UUID, NodeLike>("g-nodes")[node.id] = node
            val result = graphTest.node<TestNode>(node.id)
            assertIs<Either.Right<TestNode>>(result)
            assertEquals("scope", result.value.name)
        }
    }

    // ── nodeExists ────────────────────────────────────────────────────────────

    @Test fun `nodeExists() returns true when present`() {
        runBlocking {
            val node = TestNode(id = UUID.randomUUID(), name = "ammo")
            graphTestHz.getMap<UUID, NodeLike>("g-nodes")[node.id] = node
            assertEquals(Either.Right(true), graphTest.nodeExists(node.id))
        }
    }

    @Test fun `nodeExists() returns false when absent`() {
        runBlocking {
            assertEquals(Either.Right(false), graphTest.nodeExists(UUID.randomUUID()))
        }
    }

    // ── edge ─────────────────────────────────────────────────────────────────

    @Test fun `edge() returns Right when present`() {
        runBlocking {
            val edge = TestEdge(fromId = UUID.randomUUID(), toId = UUID.randomUUID(), label = "has_rifle")
            graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(edge.fromId, edge.toId, "test_edge")] = edge
            val result = graphTest.edge(edge.fromId, edge.toId, "test_edge")
            assertIs<Either.Right<EdgeLike>>(result)
            assertEquals(edge, result.value)
        }
    }

    @Test fun `edge() returns EdgeNotFound when absent`() {
        runBlocking {
            val result = graphTest.edge(UUID.randomUUID(), UUID.randomUUID(), "test_edge")
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.EdgeNotFound>(result.value)
        }
    }

    @Test fun `reified edge() resolves type from SerialName`() {
        runBlocking {
            val edge = TestEdge(fromId = UUID.randomUUID(), toId = UUID.randomUUID(), label = "owns")
            graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(edge.fromId, edge.toId, "test_edge")] = edge
            val result = graphTest.edge<TestEdge>(edge.fromId, edge.toId)
            assertIs<Either.Right<TestEdge>>(result)
            assertEquals("owns", result.value.label)
        }
    }

    // ── edgeExists ────────────────────────────────────────────────────────────

    @Test fun `edgeExists() returns true when present`() {
        runBlocking {
            val edge = TestEdge(fromId = UUID.randomUUID(), toId = UUID.randomUUID(), label = "link")
            graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(edge.fromId, edge.toId, "test_edge")] = edge
            assertEquals(Either.Right(true), graphTest.edgeExists<TestEdge>(edge.fromId, edge.toId))
        }
    }

    @Test fun `edgeExists() returns false when absent`() {
        runBlocking {
            assertEquals(Either.Right(false), graphTest.edgeExists<TestEdge>(UUID.randomUUID(), UUID.randomUUID()))
        }
    }

    // ── outEdges ──────────────────────────────────────────────────────────────

    @Test fun `outEdges() returns all edges from a node`() {
        runBlocking {
            val from = UUID.randomUUID()
            val edges = (1..3).map { TestEdge(fromId = from, toId = UUID.randomUUID(), label = "e$it") }
            edges.forEach { graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(it.fromId, it.toId, "test_edge")] = it }

            val result = graphTest.outEdges(from).toList()
            assertEquals(3, result.size)
        }
    }

    @Test fun `outEdges() with type filters correctly`() {
        runBlocking {
            val from = UUID.randomUUID()
            val edges = (1..3).map { TestEdge(fromId = from, toId = UUID.randomUUID(), label = "e$it") }
            edges.forEach { graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(it.fromId, it.toId, "test_edge")] = it }
            // decoy with a different key type
            graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(from, UUID.randomUUID(), "other_type")] =
                edges[0].copy(toId = UUID.randomUUID())

            val result = graphTest.outEdges(from, "test_edge").toList()
            assertEquals(3, result.size)
        }
    }

    @Test fun `reified outEdges() emits typed edges`() {
        runBlocking {
            val from = UUID.randomUUID()
            val edges = (1..2).map { TestEdge(fromId = from, toId = UUID.randomUUID(), label = "e$it") }
            edges.forEach { graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(it.fromId, it.toId, "test_edge")] = it }

            val result = graphTest.outEdges<TestEdge>(from).toList()
            assertEquals(2, result.size)
            result.forEach { assertIs<TestEdge>(it) }
        }
    }

    @Test fun `outEdges() pages correctly`() {
        runBlocking {
            val from = UUID.randomUUID()
            val edges = (1..5).map { TestEdge(fromId = from, toId = UUID.randomUUID(), label = "e$it") }
            edges.forEach { graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(it.fromId, it.toId, "test_edge")] = it }

            val result = graphTest.outEdges(from, pageSize = 2).toList()
            assertEquals(5, result.size)
        }
    }

    // ── inEdges ───────────────────────────────────────────────────────────────

    @Test fun `inEdges() returns all edges to a node`() {
        runBlocking {
            val to = UUID.randomUUID()
            val edges = (1..3).map { TestEdge(fromId = UUID.randomUUID(), toId = to, label = "e$it") }
            edges.forEach { graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(it.fromId, it.toId, "test_edge")] = it }

            val result = graphTest.inEdges(to).toList()
            assertEquals(3, result.size)
        }
    }

    @Test fun `reified inEdges() emits typed edges`() {
        runBlocking {
            val to = UUID.randomUUID()
            val edges = (1..2).map { TestEdge(fromId = UUID.randomUUID(), toId = to, label = "e$it") }
            edges.forEach { graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(it.fromId, it.toId, "test_edge")] = it }

            val result = graphTest.inEdges<TestEdge>(to).toList()
            assertEquals(2, result.size)
            result.forEach { assertIs<TestEdge>(it) }
        }
    }

    // ── transaction ───────────────────────────────────────────────────────────

    @Test fun `transaction addNode makes node retrievable`() {
        runBlocking {
            val node = TestNode(id = UUID.randomUUID(), name = "tx-node")
            graphTest.transaction { addNode(node) }
            assertIs<Either.Right<NodeLike>>(graphTest.node(node.id))
        }
    }

    @Test fun `transaction addEdge makes edge retrievable`() {
        runBlocking {
            val edge = TestEdge(fromId = UUID.randomUUID(), toId = UUID.randomUUID(), label = "tx-edge")
            graphTest.transaction { addEdge(edge) }
            assertIs<Either.Right<EdgeLike>>(graphTest.edge(edge.fromId, edge.toId, "test_edge"))
        }
    }

    @Test fun `transaction updateNode updates cached value`() {
        runBlocking {
            val node = TestNode(id = UUID.randomUUID(), name = "before")
            graphTest.transaction { addNode(node) }
            graphTest.transaction { updateNode(node.copy(name = "after")) }
            val result = graphTest.node<TestNode>(node.id)
            assertEquals("after", (result as Either.Right).value.name)
        }
    }

    @Test fun `transaction removeNode removes from cache`() {
        runBlocking {
            val node = TestNode(id = UUID.randomUUID(), name = "ephemeral")
            graphTest.transaction { addNode(node) }
            graphTest.transaction { removeNode(node.id) }
            assertIs<Either.Left<AbyssError>>(graphTest.node(node.id))
        }
    }

    @Test fun `transaction removeEdge removes from cache`() {
        runBlocking {
            val edge = TestEdge(fromId = UUID.randomUUID(), toId = UUID.randomUUID(), label = "bye")
            graphTest.transaction { addEdge(edge) }
            graphTest.transaction { removeEdge(edge.fromId, edge.toId, "test_edge") }
            assertIs<Either.Left<AbyssError>>(graphTest.edge(edge.fromId, edge.toId, "test_edge"))
        }
    }

    @Test fun `transaction with store commits to store before cache`() {
        runBlocking {
            val fake = FakeStore()
            val storeGraph = AbyssGraph(graphTestHz, "s-nodes", "s-edges", fake)
            val node = TestNode(id = UUID.randomUUID(), name = "stored")

            storeGraph.transaction { addNode(node) }

            assertTrue(fake.saveNodeCalls.contains(node.id))
            assertIs<Either.Right<NodeLike>>(storeGraph.node(node.id))

            graphTestHz.getMap<Any, Any>("s-nodes").clear()
            graphTestHz.getMap<Any, Any>("s-edges").clear()
        }
    }

    @Test fun `transaction with store returns Left when store fails`() {
        runBlocking {
            val fake = FakeStore(failTx = true)
            val storeGraph = AbyssGraph(graphTestHz, "f-nodes", "f-edges", fake)
            val result = storeGraph.transaction { addNode(TestNode(id = UUID.randomUUID(), name = "x")) }
            assertIs<Either.Left<AbyssError>>(result)
        }
    }
}

private class FakeStore(private val failTx: Boolean = false) : AbyssStoreLike {
    val saveNodeCalls = mutableSetOf<UUID>()

    override suspend fun loadNode(id: UUID): Either<AbyssError, NodeLike?> = Either.Right(null)
    override suspend fun loadEdge(fromId: UUID, toId: UUID, type: String): Either<AbyssError, EdgeLike?> = Either.Right(null)

    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        if (failTx) return AbyssError.Unexpected(RuntimeException("store down")).left()
        val tx = object : AbyssStoreTransactionLike {
            override fun saveNode(node: NodeLike, ttl: Duration?) { saveNodeCalls += node.id }
            override fun saveEdge(edge: EdgeLike, ttl: Duration?) {}
            override fun deleteNode(id: UUID) { saveNodeCalls -= id }
            override fun deleteEdge(fromId: UUID, toId: UUID, type: String) {}
        }
        tx.block()
        return Unit.right()
    }
}

private fun AbyssError.left(): Either<AbyssError, Nothing> = Either.Left(this)
