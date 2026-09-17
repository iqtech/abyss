package pl.iqtech.abyss.graph

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.collectNodes
import pl.iqtech.abyss.dsl.nodes
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.store.api.LongKeyAdapter
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

class LongPerformanceTest {

    companion object {
        private val perfGraph: AbyssGraphSchema<Long> by lazy {
            AbyssGraphSchema(LongKeyAdapter, longTestHz, "perf-long-nodes", "perf-long-edges", module = graphTestModule)
        }

        // Ring seeded through the real write path (PerfRing.kt, TODO 4.14).
        private val nodeIds: List<Long> by lazy {
            val ids = (1L..RING_NODES.toLong()).toList()
            runBlocking { perfGraph.seedRing(ids, { LongTestNode(id = it, name = it.toString()) }, { f, t -> LongTestEdge(fromId = f, toId = t) }) }
            ids
        }
    }

    private suspend fun outEdges(id: Long) = expectSize("outEdges", perfGraph.outEdges(id).toList().size, RING_OUT)
    private suspend fun inEdges(id: Long) = expectSize("inEdges", perfGraph.inEdges(id).toList().size, RING_IN)
    private suspend fun threeHop(id: Long) = expectSize("3-hop", perfGraph.from(id) {
        outgoing<LongTestEdge>(); outgoing<LongTestEdge>(); outgoing<LongTestEdge>()
        nodes<LongTestNode>(); collectNodes<LongTestNode>().toList()
    }.fold({ error("3-hop failed: $it") }, { it.size }), RING_3HOP)

    @Test fun `outEdges throughput`() {
        if (System.getProperty("perf") == null) return
        val ids = nodeIds
        runBlocking { repeat(200) { outEdges(ids.random()) } }

        val n = 2_000
        val elapsed = measureTime { runBlocking { repeat(n) { outEdges(ids.random()) } } }
        val opsPerSec = n * 1000.0 / elapsed.inWholeMilliseconds
        println("\noutEdges: ${opsPerSec.toInt()} ops/sec  ($n queries, ${elapsed.inWholeMilliseconds}ms)")
        assertTrue(opsPerSec > 500)
    }

    @Test fun `inEdges throughput`() {
        if (System.getProperty("perf") == null) return
        val ids = nodeIds
        runBlocking { repeat(200) { inEdges(ids.random()) } }

        val n = 2_000
        val elapsed = measureTime { runBlocking { repeat(n) { inEdges(ids.random()) } } }
        val opsPerSec = n * 1000.0 / elapsed.inWholeMilliseconds
        println("\ninEdges: ${opsPerSec.toInt()} ops/sec  ($n queries, ${elapsed.inWholeMilliseconds}ms)")
        assertTrue(opsPerSec > 500)
    }

    @Test fun `3-hop traversal throughput`() {
        if (System.getProperty("perf") == null) return
        val ids = nodeIds
        runBlocking { repeat(20) { threeHop(ids.random()) } }

        val n = 200
        val elapsed = measureTime { runBlocking { repeat(n) { threeHop(ids.random()) } } }
        val msEach = elapsed.inWholeMilliseconds.toDouble() / n
        println("\n3-hop traversal: ${"%.1f".format(msEach)}ms avg  ($n traversals, ${elapsed.inWholeMilliseconds}ms)")
        assertTrue(msEach < 500)
    }
}
