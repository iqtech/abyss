package pl.iqtech.abyss.graph

import arrow.core.Either
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import pl.iqtech.abyss.dsl.collectNodes
import pl.iqtech.abyss.dsl.incomingAny
import pl.iqtech.abyss.dsl.nodes
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.dsl.outgoingAny
import pl.iqtech.abyss.dsl.removeEdge
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.HeaderlessKeyAdapter
import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.StoredEdge
import pl.iqtech.abyss.store.api.TypeTag
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

// TODO 2.21: sharded adjacency index. Exercises the untyped/mixed-hop DSL sugar (outgoingAny/
// incomingAny), exact-type edge removal, the fast-path/index-path split in outAt, EntryProcessor
// write atomicity under concurrency, and cold-cache/restart-equivalent tag resolution.
class MixedTraversalTest {

    @BeforeTest fun clear() {
        listOf("g-nodes", "g-edges", "g-edges-adjacency", "mx-nodes", "mx-edges", "mx-edges-adjacency",
            "rst-nodes", "rst-edges", "rst-edges-adjacency").forEach { graphTestHz.getMap<Any, Any>(it).clear() }
    }

    @Test fun `outgoingAny unions neighbors across edge types`() = runBlocking {
        val a = TestNode(id = Uuid.random(), name = "a")
        val b = TestNode(id = Uuid.random(), name = "b")
        val c = TestNode(id = Uuid.random(), name = "c")
        graphTest.transaction {
            addNode(a); addNode(b); addNode(c)
            addEdge(TestEdge(fromId = a.id, toId = b.id, label = "x"))
            addEdge(TypedEdge(fromId = a.id, toId = c.id))
        }
        val result = graphTest.from(a.id) { outgoingAny(); nodes<TestNode>(); collectNodes<TestNode>().toList() }
        assertIs<Either.Right<List<TestNode>>>(result)
        assertEquals(setOf(b, c), result.value.toSet())
    }

    @Test fun `incomingAny unions neighbors across edge types`() = runBlocking {
        val a = TestNode(id = Uuid.random(), name = "a")
        val b = TestNode(id = Uuid.random(), name = "b")
        val hub = TestNode(id = Uuid.random(), name = "hub")
        graphTest.transaction {
            addNode(a); addNode(b); addNode(hub)
            addEdge(TestEdge(fromId = a.id, toId = hub.id, label = "x"))
            addEdge(TypedEdge(fromId = b.id, toId = hub.id))
        }
        val result = graphTest.from(hub.id) { incomingAny(); nodes<TestNode>(); collectNodes<TestNode>().toList() }
        assertIs<Either.Right<List<TestNode>>>(result)
        assertEquals(setOf(a, b), result.value.toSet())
    }

    @Test fun `removing one of two differently-typed edges leaves the neighbor visible via the surviving type`() = runBlocking {
        val a = TestNode(id = Uuid.random(), name = "a")
        val b = TestNode(id = Uuid.random(), name = "b")
        graphTest.transaction {
            addNode(a); addNode(b)
            addEdge(TestEdge(fromId = a.id, toId = b.id, label = "x"))
            addEdge(TypedEdge(fromId = a.id, toId = b.id))
        }
        graphTest.transaction { removeEdge<Uuid, TestEdge>(a.id, b.id) }

        assertEquals(Either.Right(1), graphTest.from(a.id) { outgoing<TypedEdge>(); count() })
        assertEquals(Either.Right(0), graphTest.from(a.id) { outgoing<TestEdge>(); count() })
    }

    @Test fun `removing the last edge between a pair evicts the neighbor entirely`() = runBlocking {
        val a = TestNode(id = Uuid.random(), name = "a")
        val b = TestNode(id = Uuid.random(), name = "b")
        graphTest.transaction { addNode(a); addNode(b); addEdge(TestEdge(fromId = a.id, toId = b.id, label = "x")) }
        graphTest.transaction { removeEdge<Uuid, TestEdge>(a.id, b.id) }

        assertEquals(Either.Right(0), graphTest.from(a.id) { outgoingAny(); count() })
    }

    @Test fun `typed outgoing value-needed fast path and existence-only index path agree`() = runBlocking {
        val a = TestNode(id = Uuid.random(), name = "a")
        val b = TestNode(id = Uuid.random(), name = "b")
        graphTest.transaction { addNode(a); addNode(b); addEdge(TestEdge(fromId = a.id, toId = b.id, label = "x")) }

        val valueNeeded = graphTest.from(a.id) { outgoing<TestEdge> { it.label == "x" }; nodes<TestNode>(); collectNodes<TestNode>().toList() }
        assertEquals(Either.Right(listOf(b)), valueNeeded)

        val existenceOnly = graphTest.from(a.id) { outgoing<TestEdge>(); nodes<TestNode>(); collectNodes<TestNode>().toList() }
        assertEquals(Either.Right(listOf(b)), existenceOnly)
    }

    @Test fun `concurrent writers into the same hub shard produce the exact expected neighbor count`() = runBlocking {
        val hub = TestNode(id = Uuid.random(), name = "hub")
        graphTest.transaction { addNode(hub) }
        val targets = List(200) { TestNode(id = Uuid.random(), name = "t$it") }
        coroutineScope {
            targets.map { t ->
                async { graphTest.transaction { addNode(t); addEdge(TestEdge(fromId = hub.id, toId = t.id, label = "x")) } }
            }.awaitAll()
        }
        assertEquals(Either.Right(200), graphTest.from(hub.id) { outgoingAny(); count() })
    }

    @Test fun `cold-cache mixed hop self-heals the adjacency index from the store`() = runBlocking {
        val a = TestNode(id = Uuid.random(), name = "a")
        val b = TestNode(id = Uuid.random(), name = "b")
        val fake = ColdStoreFixture(nodes = listOf(a, b), outEdges = listOf(TestEdge(fromId = a.id, toId = b.id, label = "x")))
        val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "mx-nodes", "mx-edges", fake, module = graphTestModule)

        val result = g.from(a.id) { outgoingAny(); nodes<TestNode>(); collectNodes<TestNode>().toList() }
        assertEquals(Either.Right(listOf(b)), result)
    }

    @Test fun `fresh worker resolves edge type tags with no prior write in this process`() = runBlocking {
        val a = TestNode(id = Uuid.random(), name = "a")
        val b = TestNode(id = Uuid.random(), name = "b")
        val fake = ColdStoreFixture(nodes = listOf(a, b), outEdges = listOf(TestEdge(fromId = a.id, toId = b.id, label = "x")))
        // Never called addEdge in this process for "test_edge" — only the pre-seeded store knows about
        // it. Resolving it (String -> Short via TypeTagRegistry.edgeTagOf) must not depend on any prior
        // in-process registration: the registry is built once, eagerly, from @TypeTag alone.
        val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "rst-nodes", "rst-edges", fake, module = graphTestModule)
        val removed = g.transaction { removeEdge<Uuid, TestEdge>(a.id, b.id) }
        assertTrue(removed.isRight())
    }

    @Test fun `TypeTag collision within the node namespace fails registration`() {
        val module = SerializersModule { polymorphic(NodeLike::class) { subclass(DupNodeA::class); subclass(DupNodeB::class) } }
        assertFails { TypeTagRegistry.of(module) }
    }

    @Test fun `TypeTag collision within the edge namespace fails registration`() {
        val module = SerializersModule { polymorphic(EdgeLike::class) { subclass(DupEdgeA::class); subclass(DupEdgeB::class) } }
        assertFails { TypeTagRegistry.of(module) }
    }

    @Test fun `a node and an edge sharing the same TypeTag value do not conflict`() {
        val module = SerializersModule {
            polymorphic(NodeLike::class) { subclass(CrossNsNode::class) }
            polymorphic(EdgeLike::class) { subclass(CrossNsEdge::class) }
        }
        TypeTagRegistry.of(module) // must not throw
    }

    @Test fun `shardIndexOf is deterministic and does not skew heavily for sequential Long ids`() {
        val adapter = HeaderlessKeyAdapter(LongKeyAdapter)
        val nid = adapter.toNodeId(42L)
        assertEquals(shardIndexOf(nid, 16), shardIndexOf(nid, 16))

        val shardCount = 16
        val sampleSize = 1600
        val counts = IntArray(shardCount)
        for (i in 1..sampleSize.toLong()) counts[shardIndexOf(adapter.toNodeId(i), shardCount)]++
        val mean = sampleSize.toDouble() / shardCount
        assertTrue(counts.all { it < mean * 3 }, "shard distribution too skewed: ${counts.toList()}")
    }
}

private class ColdStoreFixture(
    private val nodes: List<NodeLike<Uuid>> = emptyList(),
    private val outEdges: List<EdgeLike<Uuid, Uuid>> = emptyList(),
) : AbyssStoreLike {
    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> =
        Either.Right(nodes.find { huid.toNodeId(it.id) == id } to null)
    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> = Either.Right(null to null)
    override suspend fun loadEdges(fromId: NodeId): Either<AbyssError, List<StoredEdge>> =
        Either.Right(outEdges.filter { huid.toNodeId(it.fromId) == fromId }.map { StoredEdge(fromId, huid.toNodeId(it.toId), it, null) })
    override suspend fun loadInEdges(toId: NodeId): Either<AbyssError, List<StoredEdge>> = Either.Right(emptyList())

    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        val tx = object : AbyssStoreTransactionLike {
            override fun saveNode(id: NodeId, node: NodeLike<*>) {}
            override fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>) {}
            override fun deleteNode(id: NodeId) {}
            override fun deleteEdge(fromId: NodeId, toId: NodeId, type: String) {}
        }
        tx.block()
        return Either.Right(Unit)
    }
}

// Collision-guard fixtures — never registered in graphTestModule, only in small local modules built
// inline per-test above, so their tags don't need to be collision-free with the shared fixtures.
@Serializable @SerialName("dup_node_a") @TypeTag(90)
private data class DupNodeA(override val id: Uuid, override val tags: List<String> = emptyList(), override val createdAt: Instant = Instant.fromEpochSeconds(0), override val updatedAt: Instant = Instant.fromEpochSeconds(0)) : NodeLike<Uuid>

@Serializable @SerialName("dup_node_b") @TypeTag(90)
private data class DupNodeB(override val id: Uuid, override val tags: List<String> = emptyList(), override val createdAt: Instant = Instant.fromEpochSeconds(0), override val updatedAt: Instant = Instant.fromEpochSeconds(0)) : NodeLike<Uuid>

@Serializable @SerialName("dup_edge_a") @TypeTag(91)
private data class DupEdgeA(override val fromId: Uuid, override val toId: Uuid, override val tags: List<String> = emptyList(), override val createdAt: Instant = Instant.fromEpochSeconds(0), override val updatedAt: Instant = Instant.fromEpochSeconds(0)) : EdgeLike<Uuid, Uuid>

@Serializable @SerialName("dup_edge_b") @TypeTag(91)
private data class DupEdgeB(override val fromId: Uuid, override val toId: Uuid, override val tags: List<String> = emptyList(), override val createdAt: Instant = Instant.fromEpochSeconds(0), override val updatedAt: Instant = Instant.fromEpochSeconds(0)) : EdgeLike<Uuid, Uuid>

@Serializable @SerialName("cross_ns_node") @TypeTag(92)
private data class CrossNsNode(override val id: Uuid, override val tags: List<String> = emptyList(), override val createdAt: Instant = Instant.fromEpochSeconds(0), override val updatedAt: Instant = Instant.fromEpochSeconds(0)) : NodeLike<Uuid>

@Serializable @SerialName("cross_ns_edge") @TypeTag(92)
private data class CrossNsEdge(override val fromId: Uuid, override val toId: Uuid, override val tags: List<String> = emptyList(), override val createdAt: Instant = Instant.fromEpochSeconds(0), override val updatedAt: Instant = Instant.fromEpochSeconds(0)) : EdgeLike<Uuid, Uuid>
