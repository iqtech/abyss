package pl.iqtech.abyss.graph

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.collectNodes
import pl.iqtech.abyss.dsl.inEdges
import pl.iqtech.abyss.dsl.outEdges
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.store.api.MultiSchemaAdapter
import pl.iqtech.abyss.store.api.SchemaTagWidth
import kotlin.test.Test
import kotlin.time.measureTime

// TODO 3.4: concurrent query benchmark. Spins up N coroutines firing queries continuously against
// the (2x enlarged, see UniverseFixture.kt) astronomy schema of the Universe fixture, at
// N = 1, 2, 4, 8, 16, 32, printing throughput + avg per-query latency at each level so the knee of
// the curve (where latency starts climbing as CPU saturates) can be read off the report — the exact
// knee position is machine-dependent (TODO 3.4 cites ~4 callers on Oracle Ampere A1), so this test
// reports rather than asserts it.
class AstronomyConcurrencyPerformanceTest {

    companion object {
        private val CONCURRENCY_LEVELS = listOf(1, 2, 4, 8, 16, 32)
        private const val OPS_PER_COROUTINE = 200

        private val perfHz by lazy {
            System.setProperty("hazelcast.logging.type", "none")
            Hazelcast.newHazelcastInstance(
                Config().setClusterName("graph-test-astro-concurrency")
                    .registerAbyssSerializers(MultiSchemaAdapter(SchemaTagWidth.BYTE), universeModule)
            )
        }
        private val universe: UniverseGraph by lazy { UniverseGraph(perfHz, "perf-astro-c-nodes", "perf-astro-c-edges") }
        private val data: UniverseData by lazy { runBlocking { universe.build() } }

        // All astronomy node ids — any node works as an outEdges/inEdges target.
        private val astroIds by lazy { data.astroByName.values.map { it.id } }
        // Only moons reliably reach a Singularity in exactly 3 outgoing Orbits hops (the fixture
        // deliberately includes star systems with no singularity in range, e.g. TRAPPIST-1/Gliese 581).
        private val moonIds by lazy { data.astroByName.values.filterIsInstance<Moon>().map { it.id } }
    }

    private fun concurrentBench(name: String, body: suspend () -> Unit) {
        println("\n$name:")
        CONCURRENCY_LEVELS.forEach { n ->
            val elapsed = measureTime {
                runBlocking {
                    coroutineScope { repeat(n) { launch { repeat(OPS_PER_COROUTINE) { body() } } } }
                }
            }
            val totalOps = n * OPS_PER_COROUTINE
            val opsPerSec = totalOps * 1000.0 / elapsed.inWholeMilliseconds
            val avgMs = elapsed.inWholeMilliseconds.toDouble() / totalOps
            println("  N=$n: ${opsPerSec.toInt()} ops/sec, ${"%.3f".format(avgMs)}ms avg/op ($totalOps ops, ${elapsed.inWholeMilliseconds}ms)")
        }
    }

    @Test fun `outEdges concurrency sweep`() {
        if (System.getProperty("perf") == null) return
        check(astroIds.isNotEmpty())
        concurrentBench("outEdges") { universe.astronomy.outEdges<Orbits>(astroIds.random()).toList() }
    }

    @Test fun `inEdges concurrency sweep`() {
        if (System.getProperty("perf") == null) return
        check(astroIds.isNotEmpty())
        concurrentBench("inEdges") { universe.astronomy.inEdges<Orbits>(astroIds.random()).toList() }
    }

    @Test fun `3-hop traversal concurrency sweep`() {
        if (System.getProperty("perf") == null) return
        check(moonIds.isNotEmpty())
        concurrentBench("3-hop traversal (moon -> planet -> star -> singularity)") {
            universe.astronomy.from(moonIds.random()) {
                outgoing<Orbits>(); outgoing<Orbits>(); outgoing<Orbits>(); collectNodes<Singularity>().toList()
            }
        }
    }
}
