package pl.iqtech.abyss.graph

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pl.iqtech.abyss.dsl.node
import pl.iqtech.abyss.dsl.outEdges
import pl.iqtech.abyss.graph.serialization.AbyssJsonLinesCodec
import pl.iqtech.abyss.graph.serialization.customJsonSerializer
import pl.iqtech.abyss.store.api.HeaderlessMultiSchemaAdapter
import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.MultiSchemaAdapter
import pl.iqtech.abyss.store.api.NodeKeyKind
import pl.iqtech.abyss.store.api.SchemaTag
import pl.iqtech.abyss.store.api.SchemaTagWidth
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Regression coverage for the allNodeIds() tag-scoping fix (TODO 2.3 follow-up): exportGraphLines/
// importGraphLines must only ever touch one schema's own nodes/edges in a HomogeneousSchemaGraph/
// HeterogeneousSchemaGraph container, never a sibling schema's data sharing the same nodesMap.
// GraphExportTest.kt already covers the single-schema case; this file is the multi-schema one.
class GraphExportMultiSchemaTest {

    // ── Heterogeneous: UniverseGraph's three registered schemas share one nodesMap ────────────

    private val universeHz by lazy {
        System.setProperty("hazelcast.logging.type", "none")
        Hazelcast.newHazelcastInstance(
            Config().setClusterName("graph-export-multischema-universe")
                .registerAbyssSerializers(MultiSchemaAdapter(SchemaTagWidth.BYTE), universeModule)
        )
    }
    private val universeCodec = AbyssJsonLinesCodec(universeModule)

    @BeforeTest fun clearUniverse() {
        clearUniverseMaps(universeHz)
        listOf("dst-nodes", "dst-edges", "dst-edges-adjacency").forEach { universeHz.getMap<Any, Any>(it).clear() }
    }

    @Test fun `exporting one heterogeneous schema only touches its own nodes and edges`() = runBlocking {
        val universe = UniverseGraph(universeHz)
        val data = universe.build()

        val astronomyLines = universe.astronomy.exportGraphLines(universeCodec).toList()
        val astronomyTypes = setOf("uni_star", "uni_planet", "uni_moon", "uni_singularity", "uni_orbits")
        astronomyLines.forEach { line ->
            val obj = customJsonSerializer.parseToJsonElement(line).jsonObject
            val isNode = obj.getValue("kind").jsonPrimitive.content == "node"
            val payload = obj.getValue(if (isNode) "node" else "edge").jsonObject
            assertTrue(payload.getValue("type").jsonPrimitive.content in astronomyTypes)
        }
        assertEquals(data.astroByName.size, astronomyLines.count { it.contains("\"kind\":\"node\"") })

        // users also owns the "from" side of the cross-schema InterestedIn/LivesOn edges, so those
        // legitimately appear here too (exportGraphLines walks each node's own outEdges) — only the
        // node lines are expected to be uni_user.
        val usersLines = universe.users.exportGraphLines(universeCodec).toList()
        assertEquals(data.users.size, usersLines.count { it.contains("\"kind\":\"node\"") })
        usersLines.filter { it.contains("\"kind\":\"node\"") }.forEach { line -> assertTrue(line.contains("\"uni_user\"")) }
    }

    @Test fun `heterogeneous schema round-trips through export then import without touching a sibling schema`() = runBlocking {
        val universe = UniverseGraph(universeHz)
        val data = universe.build()

        val lines = universe.astronomy.exportGraphLines(universeCodec).toList()

        val dstContainer = HeterogeneousSchemaGraph(universeHz, SchemaTagWidth.BYTE, "dst-nodes", "dst-edges", module = universeModule)
        val dstAstronomy = dstContainer.register(UniverseTags.ASTRONOMY, LongKeyAdapter)
        val result = dstAstronomy.importGraphLines(lines.asFlow(), universeCodec)
        assertTrue(result.isRight())

        val earth = data.astroByName.getValue("Earth")
        assertEquals("Earth", dstAstronomy.node<Planet>(earth.id).getOrNull()?.name)

        // Importing astronomy into a fresh container must not have touched the source's users schema.
        assertEquals(data.users.size, universe.users.exportGraphLines(universeCodec).toList().count { it.contains("\"kind\":\"node\"") })
    }

    // ── Homogeneous: two forTag() facades share one nodesMap, no header byte at all ────────────

    private val homogeneousHz by lazy {
        System.setProperty("hazelcast.logging.type", "none")
        Hazelcast.newHazelcastInstance(
            Config().setClusterName("graph-export-multischema-homogeneous")
                .registerAbyssSerializers(HeaderlessMultiSchemaAdapter(SchemaTagWidth.BYTE, NodeKeyKind.INT64), graphTestModule)
        )
    }
    private val homogeneousCodec = AbyssJsonLinesCodec(graphTestModule)

    @BeforeTest fun clearHomogeneous() {
        listOf("hgx-nodes", "hgx-edges", "hgx-edges-adjacency").forEach { homogeneousHz.getMap<Any, Any>(it).clear() }
    }

    @Test fun `exporting one homogeneous schema tag leaves the sibling tag's export untouched`() = runBlocking {
        val g = HomogeneousSchemaGraph(homogeneousHz, SchemaTagWidth.BYTE, LongKeyAdapter, "hgx-nodes", "hgx-edges", module = graphTestModule)
        val a = g.forTag(SchemaTag(1L))
        val b = g.forTag(SchemaTag(2L))
        // Identical numeric ids in both schemas: the sharpest case for a decode collision.
        a.transaction { addNode(LongTestNode(1L, name = "a1")); addNode(LongTestNode(2L, name = "a2")); addEdge(LongTestEdge(1L, 2L)) }
        b.transaction { addNode(LongTestNode(1L, name = "b1")); addNode(LongTestNode(9L, name = "b9")); addEdge(LongTestEdge(1L, 9L)) }

        val aLines = a.exportGraphLines(homogeneousCodec).toList()
        assertEquals(2, aLines.count { it.contains("\"kind\":\"node\"") })
        assertTrue(aLines.any { it.contains("\"a1\"") } && aLines.any { it.contains("\"a2\"") })
        assertTrue(aLines.none { it.contains("\"b1\"") || it.contains("\"b9\"") })

        val bLines = b.exportGraphLines(homogeneousCodec).toList()
        assertEquals(2, bLines.count { it.contains("\"kind\":\"node\"") })
        assertTrue(bLines.any { it.contains("\"b1\"") } && bLines.any { it.contains("\"b9\"") })

        // Round-trip a's export into a fresh tag on the same container; b's data stays untouched.
        val c = g.forTag(SchemaTag(3L))
        val result = c.importGraphLines(aLines.asFlow(), homogeneousCodec)
        assertTrue(result.isRight())
        assertEquals("a1", c.node<LongTestNode>(1L).getOrNull()?.name)
        assertEquals(setOf(2L), c.outEdges<LongTestEdge>(1L).toList().map { it.toId }.toSet())
        assertEquals(2, b.exportGraphLines(homogeneousCodec).toList().count { it.contains("\"kind\":\"node\"") })
    }
}
