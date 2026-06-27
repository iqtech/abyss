package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import pl.iqtech.abyss.store.api.EdgeConstraint
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.edge
import pl.iqtech.abyss.dsl.edgeExists
import pl.iqtech.abyss.dsl.inEdges
import pl.iqtech.abyss.dsl.node
import pl.iqtech.abyss.dsl.outEdges
import pl.iqtech.abyss.dsl.removeEdge
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

@Serializable @SerialName("other_node")
data class OtherNode(
    override val id: Uuid,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
) : NodeLike

@Serializable @SerialName("typed_edge")
@EdgeConstraint(fromTypes = [TestNode::class], toTypes = [TestNode::class])
data class TypedEdge(
    override val fromId: Uuid,
    override val toId: Uuid,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
) : EdgeLike

val graphTestModule = SerializersModule {
    polymorphic(NodeLike::class) { subclass(TestNode::class); subclass(OtherNode::class) }
    polymorphic(EdgeLike::class) { subclass(TestEdge::class); subclass(TypedEdge::class) }
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
        graphTestHz.getMap<Any, Any>("g-edges-reverse").clear()
    }

    // ── node ─────────────────────────────────────────────────────────────────

    @Test fun `node() returns Right when present`() {
        runBlocking {
            val node = TestNode(id = Uuid.random(), name = "rifle")
            graphTestHz.getMap<java.util.UUID, NodeLike>("g-nodes")[node.id.toJavaUuid()] = node
            val result = graphTest.node(node.id)
            assertIs<Either.Right<NodeLike>>(result)
            assertEquals(node, result.value)
        }
    }

    @Test fun `node() returns NodeNotFound when absent`() {
        runBlocking {
            val result = graphTest.node(Uuid.random())
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.NodeNotFound>(result.value)
        }
    }

    @Test fun `reified node() casts to concrete type`() {
        runBlocking {
            val node = TestNode(id = Uuid.random(), name = "scope")
            graphTestHz.getMap<java.util.UUID, NodeLike>("g-nodes")[node.id.toJavaUuid()] = node
            val result = graphTest.node<TestNode>(node.id)
            assertIs<Either.Right<TestNode>>(result)
            assertEquals("scope", result.value.name)
        }
    }

    // ── nodeExists ────────────────────────────────────────────────────────────

    @Test fun `nodeExists() returns true when present`() {
        runBlocking {
            val node = TestNode(id = Uuid.random(), name = "ammo")
            graphTestHz.getMap<java.util.UUID, NodeLike>("g-nodes")[node.id.toJavaUuid()] = node
            assertEquals(Either.Right(true), graphTest.nodeExists(node.id))
        }
    }

    @Test fun `nodeExists() returns false when absent`() {
        runBlocking {
            assertEquals(Either.Right(false), graphTest.nodeExists(Uuid.random()))
        }
    }

    // ── edge ─────────────────────────────────────────────────────────────────

    @Test fun `edge() returns Right when present`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "has_rifle")
            graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(edge.fromId, edge.toId, "test_edge")] = edge
            val result = graphTest.edge(edge.fromId, edge.toId, "test_edge")
            assertIs<Either.Right<EdgeLike>>(result)
            assertEquals(edge, result.value)
        }
    }

    @Test fun `edge() returns EdgeNotFound when absent`() {
        runBlocking {
            val result = graphTest.edge(Uuid.random(), Uuid.random(), "test_edge")
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.EdgeNotFound>(result.value)
        }
    }

    @Test fun `reified edge() resolves type from SerialName`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "owns")
            graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(edge.fromId, edge.toId, "test_edge")] = edge
            val result = graphTest.edge<TestEdge>(edge.fromId, edge.toId)
            assertIs<Either.Right<TestEdge>>(result)
            assertEquals("owns", result.value.label)
        }
    }

    // ── edgeExists ────────────────────────────────────────────────────────────

    @Test fun `edgeExists() returns true when present`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "link")
            graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(edge.fromId, edge.toId, "test_edge")] = edge
            assertEquals(Either.Right(true), graphTest.edgeExists<TestEdge>(edge.fromId, edge.toId))
        }
    }

    @Test fun `edgeExists() returns false when absent`() {
        runBlocking {
            assertEquals(Either.Right(false), graphTest.edgeExists<TestEdge>(Uuid.random(), Uuid.random()))
        }
    }

    // ── outEdges ──────────────────────────────────────────────────────────────

    @Test fun `outEdges() returns all edges from a node`() {
        runBlocking {
            val from = Uuid.random()
            val edges = (1..3).map { TestEdge(fromId = from, toId = Uuid.random(), label = "e$it") }
            edges.forEach { graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(it.fromId, it.toId, "test_edge")] = it }

            val result = graphTest.outEdges(from).toList()
            assertEquals(3, result.size)
        }
    }

    @Test fun `outEdges() with type filters correctly`() {
        runBlocking {
            val from = Uuid.random()
            val edges = (1..3).map { TestEdge(fromId = from, toId = Uuid.random(), label = "e$it") }
            edges.forEach { graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(it.fromId, it.toId, "test_edge")] = it }
            // decoy with a different key type
            graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(from, Uuid.random(), "other_type")] =
                edges[0].copy(toId = Uuid.random())

            val result = graphTest.outEdges(from, "test_edge").toList()
            assertEquals(3, result.size)
        }
    }

    @Test fun `reified outEdges() emits typed edges`() {
        runBlocking {
            val from = Uuid.random()
            val edges = (1..2).map { TestEdge(fromId = from, toId = Uuid.random(), label = "e$it") }
            edges.forEach { graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(it.fromId, it.toId, "test_edge")] = it }

            val result = graphTest.outEdges<TestEdge>(from).toList()
            assertEquals(2, result.size)
            result.forEach { assertIs<TestEdge>(it) }
        }
    }

    @Test fun `outEdges() pages correctly`() {
        runBlocking {
            val from = Uuid.random()
            val edges = (1..5).map { TestEdge(fromId = from, toId = Uuid.random(), label = "e$it") }
            edges.forEach { graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(it.fromId, it.toId, "test_edge")] = it }

            val result = graphTest.outEdges(from, pageSize = 2).toList()
            assertEquals(5, result.size)
        }
    }

    // ── inEdges ───────────────────────────────────────────────────────────────

    @Test fun `inEdges() returns all edges to a node`() {
        runBlocking {
            val to = Uuid.random()
            val edges = (1..3).map { TestEdge(fromId = Uuid.random(), toId = to, label = "e$it") }
            edges.forEach { graphTest.transaction(checkIntegrity = false) { addEdge(it) } }

            val result = graphTest.inEdges(to).toList()
            assertEquals(3, result.size)
        }
    }

    @Test fun `reified inEdges() emits typed edges`() {
        runBlocking {
            val to = Uuid.random()
            val edges = (1..2).map { TestEdge(fromId = Uuid.random(), toId = to, label = "e$it") }
            edges.forEach { graphTest.transaction(checkIntegrity = false) { addEdge(it) } }

            val result = graphTest.inEdges<TestEdge>(to).toList()
            assertEquals(2, result.size)
            result.forEach { assertIs<TestEdge>(it) }
        }
    }

    // ── transaction ───────────────────────────────────────────────────────────

    @Test fun `transaction addNode makes node retrievable`() {
        runBlocking {
            val node = TestNode(id = Uuid.random(), name = "tx-node")
            graphTest.transaction { addNode(node) }
            assertIs<Either.Right<NodeLike>>(graphTest.node(node.id))
        }
    }

    @Test fun `transaction addEdge makes edge retrievable`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "tx-edge")
            graphTest.transaction(checkIntegrity = false) { addEdge(edge) }
            assertIs<Either.Right<EdgeLike>>(graphTest.edge(edge.fromId, edge.toId, "test_edge"))
        }
    }

    @Test fun `transaction modifyEdge retargets toId`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")
            val c = TestNode(id = Uuid.random(), name = "c")
            val ab = TestEdge(fromId = a.id, toId = b.id, label = "ab")
            val ac = ab.copy(toId = c.id, label = "ac")
            graphTest.transaction { addNode(a); addNode(b); addNode(c); addEdge(ab) }
            graphTest.transaction { modifyEdge(ab, ac) }
            assertIs<Either.Right<EdgeLike>>(graphTest.edge(a.id, c.id, "test_edge"))
            assertIs<Either.Left<AbyssError>>(graphTest.edge(a.id, b.id, "test_edge"))
            assertEquals(1, graphTest.inEdges(c.id).toList().size)
            assertEquals(0, graphTest.inEdges(b.id).toList().size)
        }
    }

    @Test fun `transaction modifyEdge retargets fromId`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")
            val c = TestNode(id = Uuid.random(), name = "c")
            val ac = TestEdge(fromId = a.id, toId = c.id, label = "ac")
            val bc = ac.copy(fromId = b.id, label = "bc")
            graphTest.transaction { addNode(a); addNode(b); addNode(c); addEdge(ac) }
            graphTest.transaction { modifyEdge(ac, bc) }
            assertIs<Either.Right<EdgeLike>>(graphTest.edge(b.id, c.id, "test_edge"))
            assertIs<Either.Left<AbyssError>>(graphTest.edge(a.id, c.id, "test_edge"))
        }
    }

    @Test fun `transaction modifyEdge returns IntegrityError when new endpoint absent`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")
            val ab = TestEdge(fromId = a.id, toId = b.id, label = "ab")
            graphTest.transaction { addNode(a); addNode(b); addEdge(ab) }
            val result = graphTest.transaction { modifyEdge(ab, ab.copy(toId = Uuid.random())) }
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.IntegrityError>(result.value)
        }
    }

    @Test fun `transaction modifyEdge old endpoint not integrity-checked`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")
            val ghost = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "ghost")
            graphTest.transaction(checkIntegrity = false) { addNode(a); addNode(b); addEdge(ghost) }
            val result = graphTest.transaction { modifyEdge(ghost, TestEdge(fromId = a.id, toId = b.id, label = "real")) }
            assertIs<Either.Right<Unit>>(result)
        }
    }

    @Test fun `transaction addEdge returns IntegrityError when fromId node absent`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "dangling")
            val result = graphTest.transaction { addEdge(edge) }
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.IntegrityError>(result.value)
        }
    }

    @Test fun `transaction addEdge returns IntegrityError when toId node absent`() {
        runBlocking {
            val from = TestNode(id = Uuid.random(), name = "from")
            graphTest.transaction(checkIntegrity = false) { addNode(from) }
            val edge = TestEdge(fromId = from.id, toId = Uuid.random(), label = "dangling")
            val result = graphTest.transaction { addEdge(edge) }
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.IntegrityError>(result.value)
        }
    }

    @Test fun `transaction addEdge with checkIntegrity=false skips node existence check`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "bulk")
            val result = graphTest.transaction(checkIntegrity = false) { addEdge(edge) }
            assertIs<Either.Right<Unit>>(result)
        }
    }

    @Test fun `transaction addNode overwrites existing node`() {
        runBlocking {
            val node = TestNode(id = Uuid.random(), name = "before")
            graphTest.transaction { addNode(node) }
            graphTest.transaction { addNode(node.copy(name = "after")) }
            val result = graphTest.node<TestNode>(node.id)
            assertEquals("after", (result as Either.Right).value.name)
        }
    }

    @Test fun `transaction removeNode removes from cache`() {
        runBlocking {
            val node = TestNode(id = Uuid.random(), name = "ephemeral")
            graphTest.transaction { addNode(node) }
            graphTest.transaction { removeNode(node.id) }
            assertIs<Either.Left<AbyssError>>(graphTest.node(node.id))
        }
    }

    @Test fun `transaction removeEdge removes from cache`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "bye")
            graphTest.transaction(checkIntegrity = false) { addEdge(edge) }
            graphTest.transaction { removeEdge(edge.fromId, edge.toId, "test_edge") }
            assertIs<Either.Left<AbyssError>>(graphTest.edge(edge.fromId, edge.toId, "test_edge"))
        }
    }

    @Test fun `transaction removeEdge with edge instance removes edge`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "bye")
            graphTest.transaction(checkIntegrity = false) { addEdge(edge) }
            graphTest.transaction { removeEdge(edge) }
            assertIs<Either.Left<AbyssError>>(graphTest.edge(edge.fromId, edge.toId, "test_edge"))
        }
    }

    @Test fun `transaction removeNode cascades to connected edges`() {
        runBlocking {
            val node  = TestNode(id = Uuid.random(), name = "hub")
            val other = TestNode(id = Uuid.random(), name = "other")
            val out = TestEdge(fromId = node.id,  toId = other.id, label = "out")
            val inc = TestEdge(fromId = other.id, toId = node.id,  label = "in")
            graphTest.transaction { addNode(node); addNode(other); addEdge(out); addEdge(inc) }

            graphTest.transaction { removeNode(node.id) }

            assertIs<Either.Left<AbyssError>>(graphTest.node(node.id))
            assertIs<Either.Left<AbyssError>>(graphTest.edge(node.id,  other.id, "test_edge"))
            assertIs<Either.Left<AbyssError>>(graphTest.edge(other.id, node.id,  "test_edge"))
        }
    }

    // ── ephemeral ─────────────────────────────────────────────────────────────

    @Test fun `ephemeral addNode makes node retrievable`() {
        runBlocking {
            val node = TestNode(id = Uuid.random(), name = "eph-node")
            graphTest.ephemeral(60.seconds) { addNode(node) }
            assertIs<Either.Right<NodeLike>>(graphTest.node(node.id))
        }
    }

    @Test fun `ephemeral addEdge makes edge retrievable`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "eph-edge")
            graphTest.ephemeral(60.seconds, checkIntegrity = false) { addEdge(edge) }
            assertIs<Either.Right<EdgeLike>>(graphTest.edge(edge.fromId, edge.toId, "test_edge"))
        }
    }

    @Test fun `ephemeral addEdge returns IntegrityError when fromId node absent`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "eph-dangling")
            val result = graphTest.ephemeral(60.seconds) { addEdge(edge) }
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.IntegrityError>(result.value)
        }
    }

    @Test fun `ephemeral addEdge with checkIntegrity=false skips node existence check`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "eph-bulk")
            val result = graphTest.ephemeral(60.seconds, checkIntegrity = false) { addEdge(edge) }
            assertIs<Either.Right<Unit>>(result)
        }
    }

    @Test fun `ephemeral with store commits to store with ttl`() {
        runBlocking {
            val fake = FakeStore()
            val storeGraph = AbyssGraph(graphTestHz, "eph-s-nodes", "eph-s-edges", fake)
            val node = TestNode(id = Uuid.random(), name = "eph-stored")

            storeGraph.ephemeral(60.seconds) { addNode(node) }

            assertTrue(fake.saveNodeCalls.contains(node.id))
            assertIs<Either.Right<NodeLike>>(storeGraph.node(node.id))

            graphTestHz.getMap<Any, Any>("eph-s-nodes").clear()
            graphTestHz.getMap<Any, Any>("eph-s-edges").clear()
        }
    }

    // ── schema enforcement ────────────────────────────────────────────────────

    @Test fun `transaction addEdge with constrained edge and correct node types succeeds`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")
            graphTest.transaction { addNode(a); addNode(b) }
            val result = graphTest.transaction { addEdge(TypedEdge(fromId = a.id, toId = b.id)) }
            assertIs<Either.Right<Unit>>(result)
        }
    }

    @Test fun `transaction addEdge returns SchemaError when fromId has wrong node type`() {
        runBlocking {
            val bad  = OtherNode(id = Uuid.random())
            val good = TestNode(id = Uuid.random(), name = "good")
            graphTest.transaction(checkIntegrity = false) { addNode(bad); addNode(good) }
            val result = graphTest.transaction { addEdge(TypedEdge(fromId = bad.id, toId = good.id)) }
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.SchemaError>(result.value)
        }
    }

    @Test fun `transaction addEdge returns SchemaError when toId has wrong node type`() {
        runBlocking {
            val good = TestNode(id = Uuid.random(), name = "good")
            val bad  = OtherNode(id = Uuid.random())
            graphTest.transaction(checkIntegrity = false) { addNode(good); addNode(bad) }
            val result = graphTest.transaction { addEdge(TypedEdge(fromId = good.id, toId = bad.id)) }
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.SchemaError>(result.value)
        }
    }

    @Test fun `transaction addEdge with constrained edge and in-tx nodes passes schema check`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "inline-a")
            val b = TestNode(id = Uuid.random(), name = "inline-b")
            val result = graphTest.transaction {
                addNode(a)
                addNode(b)
                addEdge(TypedEdge(fromId = a.id, toId = b.id))
            }
            assertIs<Either.Right<Unit>>(result)
        }
    }

    @Test fun `transaction addEdge with checkIntegrity=false bypasses schema check`() {
        runBlocking {
            val bad  = OtherNode(id = Uuid.random())
            val good = TestNode(id = Uuid.random(), name = "good")
            graphTest.transaction(checkIntegrity = false) { addNode(bad); addNode(good) }
            val result = graphTest.transaction(checkIntegrity = false) {
                addEdge(TypedEdge(fromId = bad.id, toId = good.id))
            }
            assertIs<Either.Right<Unit>>(result)
        }
    }

    @Test fun `outEdges warms cold cache from store`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "warm-out")
            val fake = WarmingFakeStore(outEdges = listOf(edge))
            val g = AbyssGraph(graphTestHz, "w-out-nodes", "w-out-edges", fake)
            val result = g.outEdges(edge.fromId).toList()
            assertEquals(1, result.size)
            graphTestHz.getMap<Any, Any>("w-out-nodes").clear()
            graphTestHz.getMap<Any, Any>("w-out-edges").clear()
        }
    }

    @Test fun `inEdges warms cold cache from store`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "warm-in")
            val fake = WarmingFakeStore(inEdges = listOf(edge))
            val g = AbyssGraph(graphTestHz, "w-in-nodes", "w-in-edges", fake)
            val result = g.inEdges(edge.toId).toList()
            assertEquals(1, result.size)
            graphTestHz.getMap<Any, Any>("w-in-nodes").clear()
            graphTestHz.getMap<Any, Any>("w-in-edges").clear()
            graphTestHz.getMap<Any, Any>("w-in-edges-reverse").clear()
        }
    }

    @Test fun `transaction with store commits to store before cache`() {
        runBlocking {
            val fake = FakeStore()
            val storeGraph = AbyssGraph(graphTestHz, "s-nodes", "s-edges", fake)
            val node = TestNode(id = Uuid.random(), name = "stored")

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
            val result = storeGraph.transaction { addNode(TestNode(id = Uuid.random(), name = "x")) }
            assertIs<Either.Left<AbyssError>>(result)
        }
    }
}

private class FakeStore(private val failTx: Boolean = false) : AbyssStoreLike {
    val saveNodeCalls = mutableSetOf<Uuid>()

    override suspend fun loadNode(id: Uuid): Either<AbyssError, NodeLike?> = Either.Right(null)
    override suspend fun loadEdge(fromId: Uuid, toId: Uuid, type: String): Either<AbyssError, EdgeLike?> = Either.Right(null)

    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        if (failTx) return AbyssError.Unexpected(RuntimeException("store down")).left()
        val tx = object : AbyssStoreTransactionLike {
            override fun saveNode(node: NodeLike, ttl: Duration?) { saveNodeCalls += node.id }
            override fun saveEdge(edge: EdgeLike, ttl: Duration?) {}
            override fun deleteNode(id: Uuid) { saveNodeCalls -= id }
            override fun deleteEdge(fromId: Uuid, toId: Uuid, type: String) {}
        }
        tx.block()
        return Unit.right()
    }
}

private class WarmingFakeStore(
    private val outEdges: List<EdgeLike> = emptyList(),
    private val inEdges: List<EdgeLike> = emptyList(),
) : AbyssStoreLike {
    override suspend fun loadNode(id: Uuid): Either<AbyssError, NodeLike?> = Either.Right(null)
    override suspend fun loadEdge(fromId: Uuid, toId: Uuid, type: String): Either<AbyssError, EdgeLike?> = Either.Right(null)
    override suspend fun loadEdges(fromId: Uuid): Either<AbyssError, List<EdgeLike>> = Either.Right(outEdges.filter { it.fromId == fromId })
    override suspend fun loadInEdges(toId: Uuid): Either<AbyssError, List<EdgeLike>> = Either.Right(inEdges.filter { it.toId == toId })
    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> = Unit.right()
}

private fun AbyssError.left(): Either<AbyssError, Nothing> = Either.Left(this)
