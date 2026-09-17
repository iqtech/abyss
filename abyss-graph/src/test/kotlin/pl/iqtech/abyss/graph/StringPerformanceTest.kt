package pl.iqtech.abyss.graph

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.collectNodes
import pl.iqtech.abyss.dsl.nodes
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.store.api.StringKeyAdapter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

class StringPerformanceTest {

    companion object {
        private val chars = ('a'..'z') + ('0'..'9')

        private fun randomId() = (10 + Random.nextInt(41)).let { len ->
            (1..len).map { chars[Random.nextInt(chars.size)] }.joinToString("")
        }

        private val perfGraph: AbyssGraphSchema<String> by lazy {
            AbyssGraphSchema(StringKeyAdapter, stringTestHz, "perf-str-nodes", "perf-str-edges", module = graphTestModule)
        }

        // Ring seeded through the real write path (PerfRing.kt, TODO 4.14). Distinct ids: a duplicate random id
        // would merge two ring positions and change every expected result size.
        private val nodeIds: List<String> by lazy {
            val ids = generateSequence { randomId() }.distinct().take(RING_NODES).toList()
            runBlocking { perfGraph.seedRing(ids, { StrTestNode(id = it, name = it) }, { f, t -> StrTestEdge(fromId = f, toId = t) }) }
            ids
        }
    }

    private suspend fun outEdges(id: String) = expectSize("outEdges", perfGraph.outEdges(id).toList().size, RING_OUT)
    private suspend fun inEdges(id: String) = expectSize("inEdges", perfGraph.inEdges(id).toList().size, RING_IN)
    private suspend fun threeHop(id: String) = expectSize("3-hop", perfGraph.from(id) {
        outgoing<StrTestEdge>(); outgoing<StrTestEdge>(); outgoing<StrTestEdge>()
        nodes<StrTestNode>(); collectNodes<StrTestNode>().toList()
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
