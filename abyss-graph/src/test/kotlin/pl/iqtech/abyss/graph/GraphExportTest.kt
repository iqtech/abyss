package pl.iqtech.abyss.graph

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pl.iqtech.abyss.dsl.allReachable
import pl.iqtech.abyss.dsl.node
import pl.iqtech.abyss.dsl.outEdges
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.graph.serialization.AbyssJsonLinesCodec
import pl.iqtech.abyss.graph.serialization.GraphLine
import pl.iqtech.abyss.graph.serialization.UnknownNode
import pl.iqtech.abyss.graph.serialization.customJsonSerializer
import pl.iqtech.abyss.store.api.LongKeyAdapter
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

// TODO 2.3: graph export / import (property graph JSON). Reuses LongTestNode/LongTestEdge and
// longTestHz already established in GraphTest.kt/SerializationTest.kt.
class GraphExportTest {

    private val codec = AbyssJsonLinesCodec(graphTestModule)

    @BeforeTest fun clear() {
        listOf("export-src-nodes", "export-src-edges", "export-src-edges-reverse").forEach { longTestHz.getMap<Any, Any>(it).clear() }
        listOf("export-dst-nodes", "export-dst-edges", "export-dst-edges-reverse").forEach { longTestHz.getMap<Any, Any>(it).clear() }
    }

    @Test fun `export then import round-trips a small graph`() = runBlocking {
        val source = SingleSchemaGraph(LongKeyAdapter, longTestHz, "export-src-nodes", "export-src-edges")
        source.transaction {
            addNode(LongTestNode(1L, name = "alice"))
            addNode(LongTestNode(2L, name = "bob"))
            addEdge(LongTestEdge(1L, 2L))
        }

        val lines = source.exportGraphLines(codec).toList()
        assertEquals(3, lines.size)   // 2 nodes + 1 edge

        val dest = SingleSchemaGraph(LongKeyAdapter, longTestHz, "export-dst-nodes", "export-dst-edges")
        val result = dest.importGraphLines(lines.asFlow(), codec)
        assertTrue(result.isRight())

        assertEquals("alice", dest.node<LongTestNode>(1L).getOrNull()?.name)
        assertEquals("bob", dest.node<LongTestNode>(2L).getOrNull()?.name)
        assertEquals(listOf(2L), dest.outEdges<LongTestEdge>(1L).toList().map { it.toId })
    }

    @Test fun `Subgraph exportLines exports only the visited nodes and edges`() = runBlocking {
        val g = SingleSchemaGraph(LongKeyAdapter, longTestHz, "export-src-nodes", "export-src-edges")
        g.transaction {
            addNode(LongTestNode(1L)); addNode(LongTestNode(2L)); addNode(LongTestNode(3L))
            addEdge(LongTestEdge(1L, 2L))
            // 3L is disconnected from 1L — must not appear in the subgraph export
        }

        val subgraph = g.from(1L) { allReachable { outgoing<LongTestEdge>() } }.getOrNull()!!
        val lines = subgraph.exportLines(codec).toList()

        assertEquals(2, lines.count { it.contains("\"kind\":\"node\"") })
        assertEquals(1, lines.count { it.contains("\"kind\":\"relationship\"") })
        assertTrue(lines.none { it.contains("\"id\":3") })
    }

    @Test fun `encoded node line is flat and type-labeled by SerialName`() {
        val line = codec.encodeNode(LongTestNode(1L, name = "alice"))
        val obj = customJsonSerializer.parseToJsonElement(line).jsonObject
        assertEquals("node", obj.getValue("kind").jsonPrimitive.content)
        assertEquals("long_test_node", obj.getValue("node").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("alice", obj.getValue("node").jsonObject.getValue("name").jsonPrimitive.content)
    }

    // UnknownNode's fallback id parsing assumes a Uuid-shaped id (see AbyssSerializer.kt's doc
    // comment), so this uses the Uuid-based TestNode fixture rather than LongTestNode's numeric id.
    @Test fun `unknown node type falls back to UnknownNode instead of throwing`() {
        val line = codec.encodeNode(TestNode(id = Uuid.random(), name = "sniper")).replace("\"test_node\"", "\"future_type\"")
        val decoded = codec.decodeLine(line)
        assertIs<GraphLine.Node>(decoded)
        assertIs<UnknownNode>(decoded.node)
    }
}
