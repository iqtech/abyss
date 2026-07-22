package pl.iqtech.abyss.graph

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import pl.iqtech.abyss.dsl.nodes
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.graph.traversal.TraversalBuilder
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime
import kotlin.uuid.Uuid

// Wall-clock cost of `nodes<N>()` over a hub frontier. Models a throughput-bounded store: each nodeAt
// holds one of `pool` permits for `latencyMs` (a connection-pool + per-query-latency stand-in, like
// HikariCP fronting YSQL). Compares a tag-carrying frontier (in-memory Short compare, no fetch)
// against a null-tag frontier (the pre-change behaviour: one nodeAt per frontier node). See
// TypedTraversalTagFilterPlan.md.
private class LatencyEngine(
    private val latencyMs: Long,
    poolSize: Int,
    private val nodes: Map<NodeId, NodeLike<*>>,
    private val outHops: Map<NodeId, List<Hop>>,
) : NodeIdEngine {
    val fetches = AtomicInteger(0)
    private val pool = Semaphore(poolSize)

    override suspend fun nodeAt(nid: NodeId): NodeLike<*>? {
        pool.withPermit { delay(latencyMs) }          // bounded-throughput store round trip
        fetches.incrementAndGet()
        return nodes[nid]
    }
    override fun outAt(nid: NodeId, type: String?, needValue: Boolean): Flow<Hop> =
        outHops[nid].orEmpty().filter { type == null || it.type == type }.asFlow()
    override fun inAt(nid: NodeId, type: String?, needValue: Boolean): Flow<Hop> = emptyFlow()
    override suspend fun resolveEdges(hops: List<Hop>): Map<Hop, EdgeLike<*, *>> = emptyMap()
    override fun allNodeIdsRaw(): Flow<NodeId> = emptyFlow()
    override val hopDispatcher = Dispatchers.IO
}

class TypedNodeFilterPerformanceTest {

    companion object {
        private const val FANOUT = 4000   // TestNode neighbours of the hub
        private const val LATENCY_MS = 2L // per-node store round trip
        private const val POOL = 32       // concurrent store slots (connection-pool stand-in)
    }

    // origin --test_edge--> FANOUT TestNode neighbours; hops tagged (nodeTypeTag=1) or null.
    private fun engine(tagged: Boolean): Pair<LatencyEngine, NodeId> {
        val origin = huid.toNodeId(Uuid.random())
        val nodesMap = mutableMapOf<NodeId, NodeLike<*>>()
        val hops = ArrayList<Hop>(FANOUT)
        repeat(FANOUT) {
            val nid = huid.toNodeId(Uuid.random())
            nodesMap[nid] = TestNode(id = Uuid.random(), name = "n")
            hops += Hop(origin, nid, "test_edge", null, if (tagged) 1 else null)
        }
        return LatencyEngine(LATENCY_MS, POOL, nodesMap, mapOf(origin to hops)) to origin
    }

    private suspend fun filterOnce(engine: LatencyEngine, origin: NodeId): Int =
        TraversalBuilder(engine, setOf(origin), huid).run { outgoing<TestEdge>(); nodes<TestNode>(); frontier.size }

    @Test fun `nodes filter- tag compare vs per-node fetch wall-clock`() {
        if (System.getProperty("perf") == null) return

        val (tagged, tagOrigin) = engine(tagged = true)
        val (untagged, unOrigin) = engine(tagged = false)
        runBlocking { filterOnce(engine(true).first, engine(true).second) } // JIT warm-up (throwaway)

        var keptTag = 0; var keptFetch = 0
        val tTag = measureTime { runBlocking { keptTag = filterOnce(tagged, tagOrigin) } }
        val tFetch = measureTime { runBlocking { keptFetch = filterOnce(untagged, unOrigin) } }

        println("\nnodes<N>() over $FANOUT-node frontier (store: ${LATENCY_MS}ms/fetch, pool=$POOL):")
        println("  tag compare : ${tTag.inWholeMilliseconds}ms, ${tagged.fetches.get()} fetch(es), kept=$keptTag")
        println("  per-node fetch (pre-change): ${tFetch.inWholeMilliseconds}ms, ${untagged.fetches.get()} fetch(es), kept=$keptFetch")
        println("  speedup: ${"%.1f".format(tFetch.inWholeMilliseconds.toDouble() / maxOf(1, tTag.inWholeMilliseconds))}x")

        assertEquals(FANOUT, keptTag); assertEquals(FANOUT, keptFetch)   // same result either way
        assertEquals(0, tagged.fetches.get(), "tag path fetches nothing")
        assertEquals(FANOUT, untagged.fetches.get(), "null-tag path fetches every frontier node")
        assertTrue(tTag < tFetch, "tag compare must beat per-node fetch")
    }
}
