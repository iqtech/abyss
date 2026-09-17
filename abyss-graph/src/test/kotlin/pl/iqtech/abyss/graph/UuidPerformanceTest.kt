package pl.iqtech.abyss.graph

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.collectNodes
import pl.iqtech.abyss.dsl.nodes
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime
import kotlin.uuid.Uuid

class UuidPerformanceTest {

    companion object {
        private val perfGraph: AbyssGraphSchema<Uuid> by lazy {
            AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "perf-uuid-nodes", "perf-uuid-edges", module = graphTestModule)
        }

        // Ring seeded through the real write path (PerfRing.kt, TODO 4.14).
        private val nodeIds: List<Uuid> by lazy {
            val ids = (1..RING_NODES).map { Uuid.random() }
            runBlocking { perfGraph.seedRing(ids, { TestNode(id = it, name = it.toString()) }, { f, t -> TestEdge(fromId = f, toId = t, label = "") }) }
            ids
        }
    }

    private suspend fun outEdges(id: Uuid) = expectSize("outEdges", perfGraph.outEdges(id).toList().size, RING_OUT)
    private suspend fun inEdges(id: Uuid) = expectSize("inEdges", perfGraph.inEdges(id).toList().size, RING_IN)
    private suspend fun threeHop(id: Uuid) = expectSize("3-hop", perfGraph.from(id) {
        outgoing<TestEdge>(); outgoing<TestEdge>(); outgoing<TestEdge>()
        nodes<TestNode>(); collectNodes<TestNode>().toList()
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
