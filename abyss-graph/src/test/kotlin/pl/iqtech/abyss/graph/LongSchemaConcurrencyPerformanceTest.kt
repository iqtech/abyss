package pl.iqtech.abyss.graph

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.collectNodes
import pl.iqtech.abyss.dsl.nodes
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.store.api.HeaderlessKeyAdapter
import pl.iqtech.abyss.store.api.LongKeyAdapter
import kotlin.test.Test
import kotlin.time.measureTime

// The best-case counterpart to AstronomyConcurrencyPerformanceTest (TODO 4.7): a standalone
// SingleSchemaGraph pays neither a schema tag nor a header byte (see README's "Multi-schema
// container overhead"), so this is the ceiling the HeterogeneousSchemaGraph numbers are measured
// against — same N=1..32 caller sweep, same op budget, but a LongKeyAdapter single schema at the
// same 10k-node/5-edge-per-node scale as LongPerformanceTest (not Astronomy's ~28-node fixture),
// for a fair side-by-side comparison.
class LongSchemaConcurrencyPerformanceTest {

    companion object {
        private val CONCURRENCY_LEVELS = listOf(1, 2, 4, 8, 16, 32)
        private const val OPS_PER_COROUTINE = 200

        private val perfHz by lazy {
            System.setProperty("hazelcast.logging.type", "none")
            Hazelcast.newHazelcastInstance(
                Config().setClusterName("graph-test-long-concurrency")
                    .registerAbyssSerializers(HeaderlessKeyAdapter(LongKeyAdapter), graphTestModule)
            )
        }
        private val perfGraph: AbyssGraphSchema<Long> by lazy {
            AbyssGraphSchema(LongKeyAdapter, perfHz, "perf-longc-nodes", "perf-longc-edges", module = graphTestModule)
        }

        // Same ring as LongPerformanceTest, seeded through the real write path (PerfRing.kt, TODO 4.14).
        private val nodeIds: List<Long> by lazy {
            val ids = (1L..RING_NODES.toLong()).toList()
            runBlocking { perfGraph.seedRing(ids, { LongTestNode(id = it, name = it.toString()) }, { f, t -> LongTestEdge(fromId = f, toId = t) }) }
            ids
        }
    }

    private fun concurrentBench(name: String, body: suspend () -> Unit) {
        println("\n$name:")
        CONCURRENCY_LEVELS.forEach { n ->
            val elapsed = measureTime {
                runBlocking { coroutineScope { repeat(n) { launch { repeat(OPS_PER_COROUTINE) { body() } } } } }
            }
            val totalOps = n * OPS_PER_COROUTINE
            val opsPerSec = totalOps * 1000.0 / elapsed.inWholeMilliseconds
            val avgMs = elapsed.inWholeMilliseconds.toDouble() / totalOps
            println("  N=$n: ${opsPerSec.toInt()} ops/sec, ${"%.3f".format(avgMs)}ms avg/op ($totalOps ops, ${elapsed.inWholeMilliseconds}ms)")
        }
    }

    // Seeding writes, it doesn't read: without an explicit warm-up N=1 would pay a cold-JIT tax on the read
    // paths that Astronomy's N=1 doesn't, skewing the comparison. Matches LongPerformanceTest's own warm-up.
    private suspend fun warmUp(body: suspend () -> Unit) = repeat(200) { body() }

    @Test fun `outEdges concurrency sweep`() {
        if (System.getProperty("perf") == null) return
        val ids = nodeIds
        val body: suspend () -> Unit = { expectSize("outEdges", perfGraph.outEdges(ids.random()).toList().size, RING_OUT) }
        runBlocking { warmUp(body) }
        concurrentBench("outEdges", body)
    }

    @Test fun `inEdges concurrency sweep`() {
        if (System.getProperty("perf") == null) return
        val ids = nodeIds
        val body: suspend () -> Unit = { expectSize("inEdges", perfGraph.inEdges(ids.random()).toList().size, RING_IN) }
        runBlocking { warmUp(body) }
        concurrentBench("inEdges", body)
    }

    @Test fun `3-hop traversal concurrency sweep`() {
        if (System.getProperty("perf") == null) return
        val ids = nodeIds
        val body: suspend () -> Unit = {
            expectSize("3-hop", perfGraph.from(ids.random()) {
                outgoing<LongTestEdge>(); outgoing<LongTestEdge>(); outgoing<LongTestEdge>()
                nodes<LongTestNode>(); collectNodes<LongTestNode>().toList()
            }.fold({ error("3-hop failed: $it") }, { it.size }), RING_3HOP)
        }
        runBlocking { warmUp(body) }
        concurrentBench("3-hop traversal", body)
    }
}
