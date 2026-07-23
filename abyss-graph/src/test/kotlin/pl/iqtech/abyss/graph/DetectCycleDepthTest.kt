package pl.iqtech.abyss.graph

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.outgoingAny
import pl.iqtech.abyss.graph.traversal.TraversalBuilder
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.HeaderlessKeyAdapter
import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.measureTime

// TODO 3.12 (fable.md 2.5): dfsCycle recursed one call per node. Every recursive call routes
// through sub.block() -> addHop's async(engine.hopDispatcher).awaitAll(), a genuine suspension
// point, so this isn't a native StackOverflowError — Kotlin's CPS transform unwinds the JVM stack
// at each suspension and instead chains one heap-allocated Continuation per recursion depth. A
// linear chain long enough exercises that unbounded chain. No Hazelcast needed: the risk is purely
// in TraversalBuilder's own recursion, so a minimal fake NodeIdEngine (same shape as
// TypedNodeFilterPerformanceTest's LatencyEngine) isolates it and builds far faster than seeding
// real Hazelcast maps at this node count.
private class ChainEngine(private val chainLength: Int, private val closesCycle: Boolean) : NodeIdEngine {
    private val adapter = HeaderlessKeyAdapter(LongKeyAdapter)
    fun nodeId(i: Long): NodeId = adapter.toNodeId(i)

    override suspend fun nodeAt(nid: NodeId): NodeLike<*>? = null // detectCycle never calls this
    override fun outAt(nid: NodeId, type: String?, needValue: Boolean, includeEphemeral: Boolean): Flow<Hop> = flow {
        val i = adapter.fromNodeId(nid)
        val next = when {
            i < chainLength - 1 -> i + 1
            closesCycle -> 0L
            else -> return@flow
        }
        emit(Hop(nid, nodeId(next), "chain", null, null))
    }
    override fun inAt(nid: NodeId, type: String?, needValue: Boolean): Flow<Hop> = emptyFlow()
    override suspend fun resolveEdges(hops: List<Hop>): Map<Hop, EdgeLike<*, *>> = emptyMap()
    override fun allNodeIdsRaw(): Flow<NodeId> = emptyFlow()
    // A real dispatcher, not Dispatchers.Unconfined: the risk lives specifically in the suspension
    // boundary async(engine.hopDispatcher) creates. An inline/unconfined dispatcher would mask it.
    override val hopDispatcher: CoroutineDispatcher = Dispatchers.IO
}

class DetectCycleDepthTest {

    companion object {
        // Empirical, not derived (TODO 3.12 baseline): the pre-fix recursive dfsCycle reliably threw
        // OutOfMemoryError (or crashed the JVM under GC pressure) on a chain this long against the
        // default 512m test heap, while the post-fix iterative version completes cleanly in ~30s —
        // the per-depth cost dropped from a heap-allocated Continuation chain to a plain
        // ArrayDeque entry. Kept at the size that actually demonstrated the difference.
        private const val CHAIN_LENGTH = 1_000_000
    }

    private fun chain(closesCycle: Boolean): Pair<TraversalBuilder<Long>, ChainEngine> {
        val engine = ChainEngine(CHAIN_LENGTH, closesCycle)
        val adapter = HeaderlessKeyAdapter(LongKeyAdapter)
        return TraversalBuilder(engine, setOf(engine.nodeId(0L)), adapter) to engine
    }

    @Test fun `detectCycle handles a long linear chain without unbounded recursion`() {
        if (System.getProperty("perf") == null) return
        val (builder, _) = chain(closesCycle = false)
        var result = false
        val elapsed = measureTime { result = runBlocking { builder.detectCycle { outgoingAny() } } }
        println("\ndetectCycle over $CHAIN_LENGTH-node chain (no cycle): ${elapsed.inWholeMilliseconds}ms")
        assertFalse(result)
    }

    @Test fun `detectCycle finds a cycle closing a long chain`() {
        if (System.getProperty("perf") == null) return
        val (builder, _) = chain(closesCycle = true)
        var result = false
        val elapsed = measureTime { result = runBlocking { builder.detectCycle { outgoingAny() } } }
        println("\ndetectCycle over $CHAIN_LENGTH-node chain (closed cycle): ${elapsed.inWholeMilliseconds}ms")
        assertTrue(result)
    }
}
