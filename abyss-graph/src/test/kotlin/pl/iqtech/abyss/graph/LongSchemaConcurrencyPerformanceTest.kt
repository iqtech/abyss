package pl.iqtech.abyss.graph

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.collectNodes
import pl.iqtech.abyss.dsl.inEdges
import pl.iqtech.abyss.dsl.nodes
import pl.iqtech.abyss.dsl.outEdges
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.HeaderlessKeyAdapter
import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
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
        private const val NODE_COUNT = 10_000
        private const val EDGES_PER_NODE = 5

        private val perfHz by lazy {
            System.setProperty("hazelcast.logging.type", "none")
            Hazelcast.newHazelcastInstance(
                Config().setClusterName("graph-test-long-concurrency")
                    .registerAbyssSerializers(HeaderlessKeyAdapter(LongKeyAdapter), graphTestModule)
            )
        }
        private val hlong = HeaderlessKeyAdapter(LongKeyAdapter)
        private val perfGraph: AbyssGraphSchema<Long> by lazy {
            AbyssGraphSchema(LongKeyAdapter, perfHz, "perf-longc-nodes", "perf-longc-edges")
        }

        // Same deterministic ring-wrap seed as LongPerformanceTest, pre-populated directly into the
        // maps (bypassing transaction{}) purely for fast setup at this scale.
        private val nodeIds: List<Long> by lazy {
            val ids = (1L..NODE_COUNT.toLong()).toList()
            val nodesMap = perfHz.getMap<NodeId, NodeLike<*>>("perf-longc-nodes")
            val edgesMap = perfHz.getMap<EdgeKey, EdgeLike<*, *>>("perf-longc-edges")
            val reverseMap = perfHz.getMap<ReverseEdgeKey, Unit>("perf-longc-edges-reverse")
            ids.forEach { id -> nodesMap[hlong.toNodeId(id)] = LongTestNode(id = id, name = id.toString()) }
            ids.forEachIndexed { i, fromId ->
                repeat(EDGES_PER_NODE) { j ->
                    val toId = ids[(i + j + 1) % NODE_COUNT]
                    val fromNid = hlong.toNodeId(fromId)
                    val toNid = hlong.toNodeId(toId)
                    edgesMap[EdgeKey(fromNid, toNid, "long_test_edge", hlong.partitionKey(fromNid))] =
                        LongTestEdge(fromId = fromId, toId = toId)
                    reverseMap[ReverseEdgeKey(toNid, fromNid, "long_test_edge", hlong.partitionKey(toNid))] = Unit
                }
            }
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

    // Astronomy's fixture is seeded via transaction { addNode/addEdge }, which incidentally JIT-warms
    // the outEdges/outgoing code paths before its sweep starts. This fixture is seeded via direct map
    // puts instead (needed for fast setup at 10k-node scale — see nodeIds above), so without an
    // explicit warm-up here N=1 would pay a cold-JIT tax Astronomy's N=1 doesn't, skewing the
    // comparison. Matches LongPerformanceTest's own warm-up convention for the same query.
    private suspend fun warmUp(body: suspend () -> Unit) = repeat(200) { body() }

    @Test fun `outEdges concurrency sweep`() {
        if (System.getProperty("perf") == null) return
        val ids = nodeIds
        val body: suspend () -> Unit = { perfGraph.outEdges(ids.random()).toList() }
        runBlocking { warmUp(body) }
        concurrentBench("outEdges", body)
    }

    @Test fun `inEdges concurrency sweep`() {
        if (System.getProperty("perf") == null) return
        val ids = nodeIds
        val body: suspend () -> Unit = { perfGraph.inEdges(ids.random()).toList() }
        runBlocking { warmUp(body) }
        concurrentBench("inEdges", body)
    }

    @Test fun `3-hop traversal concurrency sweep`() {
        if (System.getProperty("perf") == null) return
        val ids = nodeIds
        val body: suspend () -> Unit = {
            perfGraph.from(ids.random()) {
                outgoing<LongTestEdge>(); outgoing<LongTestEdge>(); outgoing<LongTestEdge>()
                nodes<LongTestNode>(); collectNodes<LongTestNode>().toList()
            }
        }
        runBlocking { warmUp(body) }
        concurrentBench("3-hop traversal", body)
    }
}
