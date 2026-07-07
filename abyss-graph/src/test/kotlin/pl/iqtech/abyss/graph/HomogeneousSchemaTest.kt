package pl.iqtech.abyss.graph

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.outEdges
import pl.iqtech.abyss.store.api.HeaderlessMultiSchemaAdapter
import pl.iqtech.abyss.store.api.HeaderlessSchemaKeyAdapter
import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.NodeKeyKind
import pl.iqtech.abyss.store.api.SchemaKeyAdapter
import pl.iqtech.abyss.store.api.SchemaTag
import pl.iqtech.abyss.store.api.SchemaTagWidth
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.uuid.Uuid

private val HOMOG_TAG_A = SchemaTag(1L)
private val HOMOG_TAG_B = SchemaTag(2L)

// HomogeneousSchemaGraph's NodeIds carry no header byte at all (unlike HeterogeneousSchemaGraph):
// width and kind are both fixed at construction, so nothing is left to self-describe. That means its
// registered Compact adapter shape is incompatible with any other container's — every distinct
// (width, kind) combination needs its own dedicated HazelcastInstance, same reasoning as
// LongPerformanceTest/StringPerformanceTest getting their own instances in GraphTest.kt.
private val homogeneousLongTagHz by lazy {
    System.setProperty("hazelcast.logging.type", "none")
    Hazelcast.newHazelcastInstance(
        Config().setClusterName("graph-test-homog-long-tag").registerAbyssSerializers(
            HeaderlessMultiSchemaAdapter(SchemaTagWidth.BYTE, NodeKeyKind.INT64),
            graphTestModule,
        )
    )
}

private val homogeneousUuidTagHz by lazy {
    System.setProperty("hazelcast.logging.type", "none")
    Hazelcast.newHazelcastInstance(
        Config().setClusterName("graph-test-homog-uuid-tag").registerAbyssSerializers(
            HeaderlessMultiSchemaAdapter(SchemaTagWidth.UUID, NodeKeyKind.INT64),
            graphTestModule,
        )
    )
}

// HomogeneousSchemaGraph (TODO 1.19): registry-free — forTag(tag) builds an AbyssGraphSchema<ID>
// view on the fly from one shared adapter, with the same query behavior as HeterogeneousSchemaGraph
// (MultiSchemaTest.twoLongSchemasDisambiguateByTag) but with one descriptor computed once instead
// of re-derived per key, and (TODO 1.19 follow-up) no header byte on any key.
class HomogeneousSchemaTest {

    @BeforeTest fun clear() {
        listOf("hg-nodes", "hg-edges", "hg-edges-adjacency").forEach { homogeneousLongTagHz.getMap<Any, Any>(it).clear() }
        listOf("hg-uuid-nodes", "hg-uuid-edges", "hg-uuid-edges-adjacency").forEach { homogeneousUuidTagHz.getMap<Any, Any>(it).clear() }
    }

    @Test fun twoLongSchemasDisambiguateByTag() = runBlocking {
        val g = HomogeneousSchemaGraph(homogeneousLongTagHz, SchemaTagWidth.BYTE, LongKeyAdapter, "hg-nodes", "hg-edges", module = graphTestModule)
        val a = g.forTag(HOMOG_TAG_A)
        val b = g.forTag(HOMOG_TAG_B)
        // Identical numeric ids in both schemas — only the schema tag distinguishes their edge keys.
        a.transaction { addNode(LongTestNode(1L)); addNode(LongTestNode(2L)); addEdge(LongTestEdge(1L, 2L)) }
        b.transaction { addNode(LongTestNode(1L)); addNode(LongTestNode(9L)); addEdge(LongTestEdge(1L, 9L)) }

        assertEquals(setOf(2L), a.outEdges<LongTestEdge>(1L).toList().map { it.toId }.toSet())
        assertEquals(setOf(9L), b.outEdges<LongTestEdge>(1L).toList().map { it.toId }.toSet())
    }

    // The motivating scenario: a per-user Uuid used directly as the schema tag (a personal-app-style
    // container where every user's data lives in the same shared maps, isolated purely by tag), with
    // SchemaTagWidth.UUID actually round-tripping a full 128-bit tag for the first time.
    @Test fun uuidTagsIsolateTwoUsersWithNoRegistry() = runBlocking {
        val g = HomogeneousSchemaGraph(homogeneousUuidTagHz, SchemaTagWidth.UUID, LongKeyAdapter, "hg-uuid-nodes", "hg-uuid-edges", module = graphTestModule)
        val userA = g.forTag(SchemaTag.of(Uuid.random()))
        val userB = g.forTag(SchemaTag.of(Uuid.random()))

        userA.transaction { addNode(LongTestNode(1L)); addNode(LongTestNode(2L)); addEdge(LongTestEdge(1L, 2L)) }
        userB.transaction { addNode(LongTestNode(1L)); addNode(LongTestNode(9L)); addEdge(LongTestEdge(1L, 9L)) }

        assertEquals(setOf(2L), userA.outEdges<LongTestEdge>(1L).toList().map { it.toId }.toSet())
        assertEquals(setOf(9L), userB.outEdges<LongTestEdge>(1L).toList().map { it.toId }.toSet())
    }

    // Byte-layout regression (mirrors SingleSchemaTest.headerlessNodeIdHasNoHeaderByte): no header
    // byte anywhere, so a Uuid tag + Uuid id is exactly 32 bytes (16+16) — a clean alignment a headered
    // key (33 bytes) would break — and a Long tag + Long id is exactly 16 bytes (8+8), not 17.
    @Test fun homogeneousNodeIdHasNoHeaderByte() {
        val uuidTagUuidId = HeaderlessSchemaKeyAdapter(SchemaTag.of(Uuid.random()), SchemaTagWidth.UUID, UuidKeyAdapter)
        assertEquals(32, uuidTagUuidId.toNodeId(Uuid.random()).bytes.size)

        val longTagLongId = HeaderlessSchemaKeyAdapter(SchemaTag(1L), SchemaTagWidth.LONG, LongKeyAdapter)
        assertEquals(16, longTagLongId.toNodeId(7L).bytes.size)

        val byteTagLongId = HeaderlessSchemaKeyAdapter(HOMOG_TAG_A, SchemaTagWidth.BYTE, LongKeyAdapter)
        assertEquals(9, byteTagLongId.toNodeId(7L).bytes.size)
    }

    @Test fun homogeneousResolutionIsConstantHeterogeneousIsPerKey() {
        val nid = SchemaKeyAdapter(HOMOG_TAG_A, SchemaTagWidth.BYTE, LongKeyAdapter).toNodeId(7L)
        val homogeneous = HomogeneousSchemaResolution(SchemaTagWidth.BYTE, NodeKeyKind.INT64)
        val heterogeneous = HeterogeneousSchemaResolution

        // Homogeneous: same stored instance on every call, even for the same key.
        assertSame(homogeneous.edgeAdapterOf(nid), homogeneous.edgeAdapterOf(nid))
        // Heterogeneous: fresh MultiSchemaAdapter every call — today's unchanged per-key derivation.
        assertNotSame(heterogeneous.edgeAdapterOf(nid), heterogeneous.edgeAdapterOf(nid))
    }
}
