package pl.iqtech.abyss.graph

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pl.iqtech.abyss.dsl.HopDirection
import pl.iqtech.abyss.dsl.node
import pl.iqtech.abyss.dsl.outEdges
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.SchemaKeyAdapter
import pl.iqtech.abyss.store.api.SchemaTagWidth
import pl.iqtech.abyss.store.api.UniformHexAdapter
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable @SerialName("cross_ref")
data class CrossRefEdge(
    override val fromId: NodeId,
    override val toId: NodeId,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
    val note: String = "",
) : EdgeLike<NodeId>

// One HazelcastInstance, uniform-hex edge encoding, shared maps for all schemas.
val multiSchemaHz by lazy {
    System.setProperty("hazelcast.logging.type", "none")
    Hazelcast.newHazelcastInstance(
        Config().setClusterName("graph-test-multi").registerAbyssSerializers(UniformHexAdapter, graphTestModule)
    )
}

private const val LONG_TAG = 1L
private const val UUID_TAG = 2L

class MultiSchemaTest {

    private fun newContainer(allowCross: Boolean = false) =
        AbyssGraph(multiSchemaHz, SchemaTagWidth.BYTE, "ms-nodes", "ms-edges", allowCrossSchemaEdges = allowCross).also {
            it.register(LONG_TAG, LongKeyAdapter)
            it.register(UUID_TAG, UuidKeyAdapter)
        }

    private fun longNodeId(id: Long) = SchemaKeyAdapter(LONG_TAG, SchemaTagWidth.BYTE, LongKeyAdapter).toNodeId(id)
    private fun uuidNodeId(id: Uuid) = SchemaKeyAdapter(UUID_TAG, SchemaTagWidth.BYTE, UuidKeyAdapter).toNodeId(id)

    @BeforeTest fun clear() {
        listOf("ms-nodes", "ms-edges", "ms-edges-reverse", "ms-edges-cross", "ms-edges-cross-reverse")
            .forEach { multiSchemaHz.getMap<Any, Any>(it).clear() }
    }

    @Test fun twoSchemasCoexistInSharedMaps() = runBlocking {
        val g = newContainer()
        val longS = g.schema<Long>(LONG_TAG)
        val uuidS = g.schema<Uuid>(UUID_TAG)
        val u1 = Uuid.random()

        longS.transaction { addNode(LongTestNode(1L, name = "long-one")) }
        uuidS.transaction { addNode(TestNode(u1, name = "uuid-one")) }

        assertEquals("long-one", longS.node<LongTestNode>(1L).getOrNull()?.name)
        assertEquals("uuid-one", uuidS.node<TestNode>(u1).getOrNull()?.name)

        // Both live in the same shared nodes map, distinguished only by their tag prefix.
        assertEquals(2, multiSchemaHz.getMap<Any, Any>("ms-nodes").size)
    }

    @Test fun intraSchemaEdgesStayScoped() = runBlocking {
        val g = newContainer()
        val longS = g.schema<Long>(LONG_TAG)
        val uuidS = g.schema<Uuid>(UUID_TAG)
        val ua = Uuid.random(); val ub = Uuid.random()

        longS.transaction {
            addNode(LongTestNode(1L)); addNode(LongTestNode(2L))
            addEdge(LongTestEdge(1L, 2L))
        }
        uuidS.transaction {
            addNode(TestNode(ua, name = "a")); addNode(TestNode(ub, name = "b"))
            addEdge(TestEdge(ua, ub, label = "x"))
        }

        val longOut = longS.outEdges<LongTestEdge>(1L).toList()
        val uuidOut = uuidS.outEdges<TestEdge>(ua).toList()
        assertEquals(1, longOut.size)
        assertEquals(2L, longOut.single().toId)
        assertEquals(1, uuidOut.size)
        assertEquals(ub, uuidOut.single().toId)
    }

    @Test fun resolveSchemaRoutesByTagPrefix() {
        val g = newContainer()
        val longNid = SchemaKeyAdapter(LONG_TAG, SchemaTagWidth.BYTE, LongKeyAdapter).toNodeId(42L)
        val uuidNid = SchemaKeyAdapter(UUID_TAG, SchemaTagWidth.BYTE, UuidKeyAdapter).toNodeId(Uuid.random())
        assertTrue(g.resolveSchema(longNid) === g.schema<Long>(LONG_TAG))
        assertTrue(g.resolveSchema(uuidNid) === g.schema<Uuid>(UUID_TAG))
    }

    @Test fun crossEdgeAddedQueriedAndStaysOutOfIntraQueries() = runBlocking {
        val g = newContainer(allowCross = true)
        val longS = g.schema<Long>(LONG_TAG)
        val uuidS = g.schema<Uuid>(UUID_TAG)
        val u = Uuid.random()

        longS.transaction { addNode(LongTestNode(1L)) }
        uuidS.transaction { addNode(TestNode(u, name = "target")) }

        val from = longNodeId(1L); val to = uuidNodeId(u)
        assertTrue(g.addCrossEdge(CrossRefEdge(from, to, note = "link")).isRight())

        val out = g.crossOutEdges(from).toList()
        assertEquals(1, out.size)
        assertEquals(to, out.single().toId)
        assertEquals("link", (out.single() as CrossRefEdge).note)

        val inc = g.crossInEdges(to).toList()
        assertEquals(1, inc.size)
        assertEquals(from, inc.single().fromId)

        // The cross-edge must not leak into the intra-schema view of the source node.
        assertEquals(0, longS.outEdges<LongTestEdge>(1L).toList().size)
    }

    @Test fun integrityRejectsMissingEndpoint() = runBlocking {
        val g = newContainer(allowCross = true)
        g.schema<Long>(LONG_TAG).transaction { addNode(LongTestNode(1L)) }
        // toId points at a Uuid node that was never created.
        val result = g.addCrossEdge(CrossRefEdge(longNodeId(1L), uuidNodeId(Uuid.random())))
        assertTrue(result.isLeft())
    }

    @Test fun crossEdgesDisabledByDefault() = runBlocking {
        val g = newContainer() // allowCrossSchemaEdges = false
        g.schema<Long>(LONG_TAG).transaction { addNode(LongTestNode(1L)) }
        g.schema<Uuid>(UUID_TAG).transaction { addNode(TestNode(Uuid.random(), name = "t")) }
        val result = g.addCrossEdge(CrossRefEdge(longNodeId(1L), uuidNodeId(Uuid.random())))
        assertTrue(result.isLeft())
        assertEquals(0, g.crossOutEdges(longNodeId(1L)).toList().size)
    }

    @Test fun crossHopWalksIntoTargetSchema() = runBlocking {
        val g = newContainer(allowCross = true)
        val longS = g.schema<Long>(LONG_TAG)
        val uuidS = g.schema<Uuid>(UUID_TAG)
        val u = Uuid.random()

        longS.transaction { addNode(LongTestNode(1L)) }
        uuidS.transaction { addNode(TestNode(u, name = "reached")) }
        g.addCrossEdge(CrossRefEdge(longNodeId(1L), uuidNodeId(u), note = "link"))

        // Hop from the Long frontier across the cross-edge into the Uuid schema.
        val reached = g.crossHop(setOf(longNodeId(1L)), HopDirection.OUTGOING)
        assertEquals(setOf(uuidNodeId(u)), reached)

        // The reached NodeId self-describes its schema; resolve and continue typed there.
        val target = reached.single()
        assertTrue(g.resolveSchema(target) === uuidS)
        val targetUuid = SchemaKeyAdapter(UUID_TAG, SchemaTagWidth.BYTE, UuidKeyAdapter).fromNodeId(target)
        assertEquals("reached", uuidS.node<TestNode>(targetUuid).getOrNull()?.name)

        // Reverse direction from the Uuid side reaches back to the Long node.
        assertEquals(setOf(longNodeId(1L)), g.crossHop(setOf(uuidNodeId(u)), HopDirection.INCOMING))
    }

    @Test fun crossHopEmptyWhenDisabled() = runBlocking {
        val g = newContainer() // disabled
        assertEquals(emptySet(), g.crossHop(setOf(longNodeId(1L)), HopDirection.OUTGOING))
    }

    @Test fun demo() {
        // Tag round-trips through the schema adapter, and the prefix reads back to its tag.
        val adapter = SchemaKeyAdapter(LONG_TAG, SchemaTagWidth.BYTE, LongKeyAdapter)
        val nid = adapter.toNodeId(7L)
        assert(adapter.fromNodeId(nid) == 7L)
        assert(SchemaKeyAdapter.readTag(nid, SchemaTagWidth.BYTE) == LONG_TAG)
        // Wider tag widths preserve the value too.
        val wide = SchemaKeyAdapter(300L, SchemaTagWidth.SHORT, LongKeyAdapter)
        assert(SchemaKeyAdapter.readTag(wide.toNodeId(9L), SchemaTagWidth.SHORT) == 300L)
    }
}
