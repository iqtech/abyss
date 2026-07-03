package pl.iqtech.abyss.graph

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pl.iqtech.abyss.dsl.collectNodes
import pl.iqtech.abyss.dsl.incoming
import pl.iqtech.abyss.dsl.node
import pl.iqtech.abyss.dsl.outEdges
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.store.api.CrossEdgeLike
import pl.iqtech.abyss.store.api.IntKeyAdapter
import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.adapter
import pl.iqtech.abyss.store.api.MultiSchemaAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeKey
import pl.iqtech.abyss.store.api.NodeKeyKind
import pl.iqtech.abyss.store.api.SchemaKeyAdapter
import pl.iqtech.abyss.store.api.SchemaTagWidth
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
) : CrossEdgeLike<NodeId, NodeId>

private const val LONG_TAG = 1L
private const val UUID_TAG = 2L
private const val LONG_TAG_B = 3L

// One HazelcastInstance, tag+native edge encoding, shared maps for all schemas. The registry must
// cover every tag any test registers (two Long schemas exercise fromTag disambiguation).
val multiSchemaHz by lazy {
    System.setProperty("hazelcast.logging.type", "none")
    Hazelcast.newHazelcastInstance(
        Config().setClusterName("graph-test-multi").registerAbyssSerializers(
            MultiSchemaAdapter(SchemaTagWidth.BYTE),
            graphTestModule
        )
    )
}

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

    @Test fun crossEdgeReachableAsOrdinaryHopAcrossSchemas() = runBlocking {
        val g = newContainer(allowCross = true)
        val longS = g.schema<Long>(LONG_TAG)
        val uuidS = g.schema<Uuid>(UUID_TAG)
        val u = Uuid.random()

        longS.transaction { addNode(LongTestNode(1L)) }
        uuidS.transaction { addNode(TestNode(u, name = "target")) }
        assertTrue(g.addCrossEdge(CrossRefEdge(longNodeId(1L), uuidNodeId(u), note = "link")).isRight())

        // outgoing<CrossRefEdge>() is an ordinary hop that lands in the Uuid schema.
        val reached = longS.from(1L) { outgoing<CrossRefEdge>(); collectNodes<TestNode>().toList() }.getOrNull()!!
        assertEquals(setOf("target"), reached.map { it.name }.toSet())

        // incoming<CrossRefEdge>() from the Uuid side reaches back into the Long schema.
        val back = uuidS.from(u) { incoming<CrossRefEdge>(); collectNodes<LongTestNode>().toList() }.getOrNull()!!
        assertEquals(setOf(1L), back.map { it.id }.toSet())

        // The cross-edge (type cross_ref) does not appear in a typed intra query for long_test_edge.
        assertEquals(0, longS.outEdges<LongTestEdge>(1L).toList().size)
    }

    @Test fun intraThenCrossHopInOneExpression() = runBlocking {
        val g = newContainer(allowCross = true)
        val longS = g.schema<Long>(LONG_TAG)
        val uuidS = g.schema<Uuid>(UUID_TAG)
        val a = Uuid.random(); val b = Uuid.random()

        uuidS.transaction { addNode(TestNode(a, name = "a")); addNode(TestNode(b, name = "b")); addEdge(TestEdge(a, b, label = "x")) }
        longS.transaction { addNode(LongTestNode(7L)) }
        g.addCrossEdge(CrossRefEdge(uuidNodeId(b), longNodeId(7L)))

        // One expression: intra Uuid hop (a→b), then cross hop (b→long 7), collect in the Long schema.
        val reached = uuidS.from(a) {
            outgoing<TestEdge>(); outgoing<CrossRefEdge>(); collectNodes<LongTestNode>().toList()
        }.getOrNull()!!
        assertEquals(setOf(7L), reached.map { it.id }.toSet())
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
        // No cross edge was created, so a cross hop yields nothing.
        val reached = g.schema<Long>(LONG_TAG).from(1L) { outgoing<CrossRefEdge>(); collectNodes<TestNode>().toList() }.getOrNull()!!
        assertEquals(emptyList(), reached)
    }

    @Test fun twoLongSchemasDisambiguateByTag() = runBlocking {
        val g = AbyssGraph(multiSchemaHz, SchemaTagWidth.BYTE, "ms-nodes", "ms-edges").also {
            it.register(LONG_TAG, LongKeyAdapter)
            it.register(LONG_TAG_B, LongKeyAdapter)
        }
        val a = g.schema<Long>(LONG_TAG)
        val b = g.schema<Long>(LONG_TAG_B)
        // Identical numeric ids in both schemas — only the schema tag distinguishes their edge keys.
        a.transaction { addNode(LongTestNode(1L)); addNode(LongTestNode(2L)); addEdge(LongTestEdge(1L, 2L)) }
        b.transaction { addNode(LongTestNode(1L)); addNode(LongTestNode(9L)); addEdge(LongTestEdge(1L, 9L)) }

        // The fromTag clause keeps schema A's query from matching schema B's fromId==1 edge.
        assertEquals(setOf(2L), a.outEdges<LongTestEdge>(1L).toList().map { it.toId }.toSet())
        assertEquals(setOf(9L), b.outEdges<LongTestEdge>(1L).toList().map { it.toId }.toSet())
    }

    @Test fun demo() {
        // Tag round-trips through the schema adapter, and the header reads back to its tag.
        val adapter = SchemaKeyAdapter(LONG_TAG, SchemaTagWidth.BYTE, LongKeyAdapter)
        val nid = adapter.toNodeId(7L)
        assert(adapter.fromNodeId(nid) == 7L)
        assert(NodeKey.tag(nid) == LONG_TAG)
        // Wider tag widths preserve the value too.
        val wide = SchemaKeyAdapter(300L, SchemaTagWidth.SHORT, LongKeyAdapter)
        assert(NodeKey.tag(wide.toNodeId(9L)) == 300L)

        // Self-decoding: recover (width, kind, tag, rawId) from the bytes alone — no adapter, no graph.
        assert(NodeKey.width(nid) == SchemaTagWidth.BYTE)
        assert(NodeKey.kind(nid) == NodeKeyKind.INT64)
        assert(LongKeyAdapter.decodeIdBytes(NodeKey.rawId(nid)) == 7L)
        // A bare (untagged) key still carries a NONE-width header and decodes standalone.
        val bare = LongKeyAdapter.toNodeId(42L)
        assert(NodeKey.width(bare) == SchemaTagWidth.NONE)
        assert(NodeKey.kind(bare) == NodeKeyKind.INT64)
        assert(LongKeyAdapter.fromNodeId(bare) == 42L)
    }

    @Test fun kindToAdapterIsTotalAndSelfConsistent() {
        // Every kind maps to a canonical adapter that reports the same kind — the 1:1 map is total.
        for (kind in NodeKeyKind.entries) assert(kind.adapter().nodeKeyKind == kind)

        // INT32 round-trips through its new adapter and self-decodes from the bytes alone.
        val nid = IntKeyAdapter.toNodeId(42)
        assert(IntKeyAdapter.fromNodeId(nid) == 42)
        assert(NodeKey.kind(nid) == NodeKeyKind.INT32)
        assert(NodeKey.width(nid) == SchemaTagWidth.NONE)
    }
}
