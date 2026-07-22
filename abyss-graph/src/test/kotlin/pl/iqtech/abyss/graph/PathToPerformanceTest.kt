package pl.iqtech.abyss.graph

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.graph.traversal.TraversalBuilder
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.time.measureTime
import kotlin.uuid.Uuid

// Isolates pathTo's per-level fan-out cost from real store latency: a hand-rolled NodeIdEngine that
// charges a fixed delay() per call (no Hazelcast round trip is slow enough in-process to expose the
// serial-vs-parallel gap — see UuidPerformanceTest for the in-memory-engine baseline instead).
private class DelayedFakeEngine(
    private val children: Map<NodeId, List<NodeId>>,
    private val latencyMs: Long,
) : NodeIdEngine {
    override val hopDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(512)

    override suspend fun nodeAt(nid: NodeId): NodeLike<*> {
        delay(latencyMs)
        return TestNode(id = Uuid.random(), name = "n")
    }

    override fun outAt(nid: NodeId, type: String?, needValue: Boolean): Flow<Hop> = flow {
        delay(latencyMs)
        children[nid].orEmpty().forEach { emit(Hop(nid, it, "test_edge", null)) }
    }

    override fun inAt(nid: NodeId, type: String?, needValue: Boolean): Flow<Hop> = emptyFlow()

    override suspend fun resolveEdges(hops: List<Hop>): Map<Hop, EdgeLike<*, *>> {
        delay(latencyMs)
        return hops.associateWith { TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "") }
    }

    override fun allNodeIdsRaw(): Flow<NodeId> = emptyFlow()
}

class PathToPerformanceTest {

    companion object {
        private const val BRANCHING = 2
        private const val DEPTH = 7          // full binary tree: 255 nodes, widest level = 128
        private const val LATENCY_MS = 5L

        // Unreachable target forces pathTo to walk every level of the tree with no early exit, so the
        // whole width of the deepest ("current") level is always paid for — worst case, not best case.
        private fun buildTree(): Pair<Map<NodeId, List<NodeId>>, NodeId> {
            val total = (1 shl (DEPTH + 1)) - 1
            val ids = List(total) { UuidKeyAdapter.toNodeId(Uuid.random()) }
            val children = buildMap {
                for (i in 0 until total) {
                    val kids = (1..BRANCHING).mapNotNull { k -> ((BRANCHING * i) + k).takeIf { it < total } }.map { ids[it] }
                    if (kids.isNotEmpty()) put(ids[i], kids)
                }
            }
            return children to ids[0]
        }

        // root -> hub -> `width` leaves. Only the hub is ever the sole entry in `current` at its
        // level, so entry-level fan-out (already parallel) has nothing to parallelize across — this
        // isolates the per-hop nodeAt resolution loop *within* one entry's own sub-traversal.
        private fun buildStar(width: Int): Pair<Map<NodeId, List<NodeId>>, NodeId> {
            val root = UuidKeyAdapter.toNodeId(Uuid.random())
            val hub = UuidKeyAdapter.toNodeId(Uuid.random())
            val leaves = List(width) { UuidKeyAdapter.toNodeId(Uuid.random()) }
            return mapOf(root to listOf(hub), hub to leaves) to root
        }
    }

    @Test fun `pathTo throughput over a single supernode`() {
        if (System.getProperty("perf") == null) return
        val width = 300
        val (children, root) = buildStar(width)
        val engine = DelayedFakeEngine(children, LATENCY_MS)
        val target = Uuid.random() // not present among the star's node ids

        fun run() = runBlocking {
            TraversalBuilder(engine, setOf(root), UuidKeyAdapter).pathTo(target) { outgoing<TestEdge>() }
        }

        assertNull(run())

        val n = 3
        val elapsed = measureTime { repeat(n) { run() } }
        val msEach = elapsed.inWholeMilliseconds.toDouble() / n
        println("\npathTo (single supernode width=$width, latency=${LATENCY_MS}ms): ${"%.0f".format(msEach)}ms avg ($n runs, ${elapsed.inWholeMilliseconds}ms total)")
    }

    @Test fun `pathTo throughput over a wide unreachable-target tree`() {
        if (System.getProperty("perf") == null) return
        val (children, root) = buildTree()
        val engine = DelayedFakeEngine(children, LATENCY_MS)
        val target = Uuid.random() // not present among the tree's node ids

        fun run() = runBlocking {
            TraversalBuilder(engine, setOf(root), UuidKeyAdapter).pathTo(target) { outgoing<TestEdge>() }
        }

        assertNull(run()) // sanity: target really is unreachable, this is measuring the full walk

        val n = 3
        val elapsed = measureTime { repeat(n) { run() } }
        val msEach = elapsed.inWholeMilliseconds.toDouble() / n
        println("\npathTo (branching=$BRANCHING, depth=$DEPTH, latency=${LATENCY_MS}ms): ${"%.0f".format(msEach)}ms avg ($n runs, ${elapsed.inWholeMilliseconds}ms total)")
    }
}
