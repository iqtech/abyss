package pl.iqtech.abyss.graph

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.collectNodes
import pl.iqtech.abyss.dsl.nodes
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.StringKeyAdapter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

class StringPerformanceTest {

    companion object {
        private const val NODE_COUNT = 10_000
        private const val EDGES_PER_NODE = 5
        private val chars = ('a'..'z') + ('0'..'9')

        private fun randomId() = (10 + Random.nextInt(41)).let { len ->
            (1..len).map { chars[Random.nextInt(chars.size)] }.joinToString("")
        }

        private val perfGraph: AbyssGraphSchema<String> by lazy {
            AbyssGraphSchema(StringKeyAdapter, stringTestHz, "perf-str-nodes", "perf-str-edges")
        }

        private val nodeIds: List<String> by lazy {
            val ids = (1..NODE_COUNT).map { randomId() }
            val nodesMap = stringTestHz.getMap<NodeId, NodeLike<*>>("perf-str-nodes")
            val edgesMap = stringTestHz.getMap<EdgeKey, EdgeLike<*>>("perf-str-edges")
            val reverseMap = stringTestHz.getMap<ReverseEdgeKey, Unit>("perf-str-edges-reverse")
            ids.forEach { id ->
                nodesMap[StringKeyAdapter.toNodeId(id)] = StrTestNode(id = id, name = id)
            }
            ids.forEachIndexed { i, fromId ->
                repeat(EDGES_PER_NODE) { j ->
                    val toId = ids[(i + j + 1) % NODE_COUNT]
                    val fromNid = StringKeyAdapter.toNodeId(fromId)
                    val toNid   = StringKeyAdapter.toNodeId(toId)
                    edgesMap[EdgeKey(fromNid, toNid, "test_edge", StringKeyAdapter.partitionKey(fromNid))] =
                        StrTestEdge(fromId = fromId, toId = toId)
                    reverseMap[ReverseEdgeKey(toNid, fromNid, "test_edge", StringKeyAdapter.partitionKey(toNid))] = Unit
                }
            }
            ids
        }
    }

    @Test fun `outEdges throughput`() {
        if (System.getProperty("perf") == null) return
        val ids = nodeIds
        runBlocking { repeat(200) { perfGraph.outEdges(ids.random()).toList() } }

        val n = 2_000
        val elapsed = measureTime { runBlocking { repeat(n) { perfGraph.outEdges(ids.random()).toList() } } }
        val opsPerSec = n * 1000.0 / elapsed.inWholeMilliseconds
        println("\noutEdges: ${opsPerSec.toInt()} ops/sec  ($n queries, ${elapsed.inWholeMilliseconds}ms)")
        assertTrue(opsPerSec > 500)
    }

    @Test fun `inEdges throughput`() {
        if (System.getProperty("perf") == null) return
        val ids = nodeIds
        runBlocking { repeat(200) { perfGraph.inEdges(ids.random()).toList() } }

        val n = 2_000
        val elapsed = measureTime { runBlocking { repeat(n) { perfGraph.inEdges(ids.random()).toList() } } }
        val opsPerSec = n * 1000.0 / elapsed.inWholeMilliseconds
        println("\ninEdges: ${opsPerSec.toInt()} ops/sec  ($n queries, ${elapsed.inWholeMilliseconds}ms)")
        assertTrue(opsPerSec > 500)
    }

    @Test fun `3-hop traversal throughput`() {
        if (System.getProperty("perf") == null) return
        val ids = nodeIds
        runBlocking {
            repeat(20) {
                perfGraph.from(ids.random()) {
                    outgoing<StrTestEdge>(); outgoing<StrTestEdge>(); outgoing<StrTestEdge>()
                    nodes<StrTestNode>(); collectNodes<StrTestNode>().toList()
                }
            }
        }

        val n = 200
        val elapsed = measureTime {
            runBlocking {
                repeat(n) {
                    perfGraph.from(ids.random()) {
                        outgoing<StrTestEdge>(); outgoing<StrTestEdge>(); outgoing<StrTestEdge>()
                        nodes<StrTestNode>(); collectNodes<StrTestNode>().toList()
                    }
                }
            }
        }
        val msEach = elapsed.inWholeMilliseconds.toDouble() / n
        println("\n3-hop traversal: ${"%.1f".format(msEach)}ms avg  ($n traversals, ${elapsed.inWholeMilliseconds}ms)")
        assertTrue(msEach < 500)
    }
}
