package pl.iqtech.abyss.graph

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.outEdges
import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.SchemaKeyAdapter
import pl.iqtech.abyss.store.api.SchemaTagWidth
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

private const val HOMOG_TAG_A = 1L
private const val HOMOG_TAG_B = 2L

// HomogeneousSchemaGraph (TODO 1.19): same query behavior as HeterogeneousSchemaGraph
// (MultiSchemaTest.twoLongSchemasDisambiguateByTag) but with one descriptor computed once instead
// of re-derived per key.
class HomogeneousSchemaTest {

    @BeforeTest fun clear() {
        listOf("hg-nodes", "hg-edges", "hg-edges-reverse").forEach { multiSchemaHz.getMap<Any, Any>(it).clear() }
    }

    @Test fun twoLongSchemasDisambiguateByTag() = runBlocking {
        val g = HomogeneousSchemaGraph(multiSchemaHz, SchemaTagWidth.BYTE, "hg-nodes", "hg-edges")
        val a = g.register(HOMOG_TAG_A, LongKeyAdapter)
        val b = g.register(HOMOG_TAG_B, LongKeyAdapter)
        // Identical numeric ids in both schemas — only the schema tag distinguishes their edge keys.
        a.transaction { addNode(LongTestNode(1L)); addNode(LongTestNode(2L)); addEdge(LongTestEdge(1L, 2L)) }
        b.transaction { addNode(LongTestNode(1L)); addNode(LongTestNode(9L)); addEdge(LongTestEdge(1L, 9L)) }

        assertEquals(setOf(2L), a.outEdges<LongTestEdge>(1L).toList().map { it.toId }.toSet())
        assertEquals(setOf(9L), b.outEdges<LongTestEdge>(1L).toList().map { it.toId }.toSet())
    }

    @Test fun homogeneousResolutionIsConstantHeterogeneousIsPerKey() {
        val nid = SchemaKeyAdapter(HOMOG_TAG_A, SchemaTagWidth.BYTE, LongKeyAdapter).toNodeId(7L)
        val homogeneous = HomogeneousSchemaResolution(SchemaTagWidth.BYTE)
        val heterogeneous = HeterogeneousSchemaResolution

        // Homogeneous: same stored instance on every call, even for the same key.
        assertSame(homogeneous.edgeAdapterOf(nid), homogeneous.edgeAdapterOf(nid))
        // Heterogeneous: fresh MultiSchemaAdapter every call — today's unchanged per-key derivation.
        assertNotSame(heterogeneous.edgeAdapterOf(nid), heterogeneous.edgeAdapterOf(nid))
    }
}
