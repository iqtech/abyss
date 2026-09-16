package pl.iqtech.abyss.graph

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.EdgeTraversalDirection
import pl.iqtech.abyss.dsl.Evaluation
import pl.iqtech.abyss.dsl.TraversalStrategy
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test

// Repro for the 10k-link DFS paths() hang seen in TODO 2.32 Phase 0. Own Hazelcast instance with logging
// ON (graphTestHz silences it) plus a default uncaught handler, so whatever kills the continuation is
// printed instead of swallowed. Depths ascend and stop at the first failure — after an OOM the JVM is
// not trustworthy. Each walk runs on its own thread under a hard timeout so a lost continuation can't
// park the test worker for ever.
class DfsDepthHangReproTest {
    @Test fun `DFS paths depth ladder on real Hazelcast`() {
        if (System.getProperty("perf") == null) return
        System.setProperty("hazelcast.logging.type", "jdk")
        Thread.setDefaultUncaughtExceptionHandler { t, e -> println("UNCAUGHT on ${t.name}: $e") }
        val hz = Hazelcast.newHazelcastInstance(Config().setClusterName("dfs-hang-repro").registerAbyssSerializers(huid, graphTestModule))
        val rt = Runtime.getRuntime()
        println("maxHeap=${rt.maxMemory() / 1_048_576}MB")
        try {
            for (length in listOf(2_000, 3_000, 4_000, 5_000, 10_000)) {
                val chain = seedChain(hz, "dfsr-nodes", "dfsr-edges", length, others = 0)
                val g = AbyssGraphSchema(UuidKeyAdapter, hz, "dfsr-nodes", "dfsr-edges", module = graphTestModule)
                System.gc()
                val gcBefore = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime }
                var peakMb = 0L
                val sampler = thread(isDaemon = true) {
                    try { while (true) { peakMb = maxOf(peakMb, (rt.totalMemory() - rt.freeMemory()) / 1_048_576); Thread.sleep(200) } }
                    catch (_: InterruptedException) {}
                }
                val hops = AtomicInteger()
                val exec = Executors.newSingleThreadExecutor()
                val started = System.nanoTime()
                val future = exec.submit<String> {
                    try {
                        val n = runBlocking {
                            g.from(chain.first()) {
                                paths(TraversalStrategy.DFS, EdgeTraversalDirection.OUT,
                                    edgeVisitor = { _, e -> hops.incrementAndGet(); e is TestEdge },
                                    nodeEvaluator = { _, _ -> Evaluation.INCLUDE_AND_CONTINUE })
                            }.getOrNull()!!.count()
                        }
                        "ok ($n path)"
                    } catch (t: Throwable) { "THREW ${t::class.simpleName}: ${t.message?.take(120)}" }
                }
                val outcome = try { future.get(60, TimeUnit.SECONDS) } catch (_: TimeoutException) {
                    val h1 = hops.get(); Thread.sleep(5_000); val h2 = hops.get()
                    println("HANG at length=$length: hops visited $h1 -> $h2 over 5s")
                    for ((t, st) in Thread.getAllStackTraces()) {
                        val interesting = st.any { f -> f.className.startsWith("pl.iqtech") || f.className.startsWith("kotlinx.coroutines") || f.className.contains("hazelcast.spi.impl.operation") }
                        if (!interesting) continue
                        println("--- ${t.name} state=${t.state} depth=${st.size}")
                        st.take(25).forEach { println("      at $it") }
                    }
                    "TIMEOUT (60s, no result)"
                }
                sampler.interrupt()
                val gcMs = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime } - gcBefore
                println("DFS length=$length: $outcome in ${(System.nanoTime() - started) / 1_000_000}ms, peakHeap~${peakMb}MB, gc=${gcMs}ms")
                exec.shutdownNow()
                if (!outcome.startsWith("ok")) break
            }
        } finally { hz.shutdown() }
    }
}
