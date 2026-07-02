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
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime
import kotlin.uuid.Uuid

class UuidPerformanceTest {

    companion object {
        private const val NODE_COUNT = 10_000
        private const val EDGES_PER_NODE = 5

        private val perfGraph: AbyssGraphSchema<Uuid> by lazy {
            AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "perf-uuid-nodes", "perf-uuid-edges")
        }

        private val nodeIds: List<Uuid> by lazy {
            val ids = (1..NODE_COUNT).map { Uuid.random() }
            val nodesMap = graphTestHz.getMap<NodeId, NodeLike<*>>("perf-uuid-nodes")
            val edgesMap = graphTestHz.getMap<EdgeKey, EdgeLike<*>>("perf-uuid-edges")
            val reverseMap = graphTestHz.getMap<ReverseEdgeKey, Unit>("perf-uuid-edges-reverse")
            ids.forEach { id ->
                nodesMap[UuidKeyAdapter.toNodeId(id)] = TestNode(id = id, name = id.toString())
            }
            ids.forEachIndexed { i, fromId ->
                repeat(EDGES_PER_NODE) { j ->
                    val toId = ids[(i + j + 1) % NODE_COUNT]
                    val fromNid = UuidKeyAdapter.toNodeId(fromId)
                    val toNid   = UuidKeyAdapter.toNodeId(toId)
                    edgesMap[EdgeKey(fromNid, toNid, "test_edge", UuidKeyAdapter.partitionKey(fromNid))] =
                        TestEdge(fromId = fromId, toId = toId, label = "")
                    reverseMap[ReverseEdgeKey(toNid, fromNid, "test_edge", UuidKeyAdapter.partitionKey(toNid))] = Unit
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
                    outgoing<TestEdge>(); outgoing<TestEdge>(); outgoing<TestEdge>()
                    nodes<TestNode>(); collectNodes<TestNode>().toList()
                }
            }
        }

        val n = 200
        val elapsed = measureTime {
            runBlocking {
                repeat(n) {
                    perfGraph.from(ids.random()) {
                        outgoing<TestEdge>(); outgoing<TestEdge>(); outgoing<TestEdge>()
                        nodes<TestNode>(); collectNodes<TestNode>().toList()
                    }
                }
            }
        }
        val msEach = elapsed.inWholeMilliseconds.toDouble() / n
        println("\n3-hop traversal: ${"%.1f".format(msEach)}ms avg  ($n traversals, ${elapsed.inWholeMilliseconds}ms)")
        assertTrue(msEach < 500)
    }
}
