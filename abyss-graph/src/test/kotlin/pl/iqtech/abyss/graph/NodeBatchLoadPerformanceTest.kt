package pl.iqtech.abyss.graph

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.graph.traversal.TraversalBuilder
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.measureTime
import kotlin.uuid.Uuid

// TODO 2.30 baseline/after harness. Two axes are measured, deliberately:
//
//  - **wall-clock ms** — what a `delay()`-per-call fake charges. Catches the *sequential* sites
//    (flushFrontierNodes' `for (nid in frontier)`), useless for the already-fanned-out ones.
//  - **engine round trips** — what a parallel fan-out does NOT fix. 300 concurrent point reads
//    finish in one latency, but they are still 300 Hazelcast operations and 300 JDBC round trips
//    against a pool; at a million events a minute that is the number that decides whether the
//    cluster keeps up. Batching is the only thing that moves it.
//
// nodesAt charges ONE delay and ONE round trip per call, which is exactly what `IMap.getAll` and
// one `WHERE id = ANY(?)` cost (measured on the live YB container: 1 storage read request for 128
// ids). Same modelling resolveEdges already gets in PathToPerformanceTest.
private class CountingFakeEngine(
    private val children: Map<NodeId, List<NodeId>>,
    private val latencyMs: Long,
) : NodeIdEngine {
    override val hopDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(512)

    val roundTrips = AtomicInteger(0)

    override suspend fun nodeAt(nid: NodeId): NodeLike<*> {
        roundTrips.incrementAndGet()
        delay(latencyMs)
        return TestNode(id = Uuid.random(), name = "n")
    }

    override suspend fun nodesAt(ids: Collection<NodeId>): Map<NodeId, NodeLike<*>> {
        if (ids.isEmpty()) return emptyMap()
        roundTrips.incrementAndGet()
        delay(latencyMs)
        return ids.associateWith { TestNode(id = Uuid.random(), name = "n") }
    }

    override fun outAt(nid: NodeId, type: String?, needValue: Boolean, includeEphemeral: Boolean): Flow<Hop> = flow {
        roundTrips.incrementAndGet()
        delay(latencyMs)
        children[nid].orEmpty().forEach { emit(Hop(nid, it, "test_edge", null)) }
    }

    override fun inAt(nid: NodeId, type: String?, needValue: Boolean): Flow<Hop> = emptyFlow()

    override suspend fun resolveEdges(hops: List<Hop>): Map<Hop, EdgeLike<*, *>> {
        if (hops.isEmpty()) return emptyMap()
        roundTrips.incrementAndGet()
        delay(latencyMs)
        return hops.associateWith { TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "") }
    }

    override fun allNodeIdsRaw(): Flow<NodeId> = emptyFlow()
}

class NodeBatchLoadPerformanceTest {

    companion object {
        private const val WIDTH = 300
        private const val LATENCY_MS = 5L

        private fun ids(n: Int) = List(n) { UuidKeyAdapter.toNodeId(Uuid.random()) }

        // root -> hub -> `width` leaves: the hub is the sole entry at its level, so pathTo's
        // entry-level fan-out has nothing to spread across and the per-neighbour node resolution is
        // what's left to measure. Same shape PathToPerformanceTest uses for the same reason.
        private fun star(width: Int): Pair<Map<NodeId, List<NodeId>>, NodeId> {
            val root = UuidKeyAdapter.toNodeId(Uuid.random())
            val hub = UuidKeyAdapter.toNodeId(Uuid.random())
            return mapOf(root to listOf(hub), hub to ids(width)) to root
        }

        private fun report(label: String, roundTrips: Int, elapsedMs: Long, runs: Int) {
            println("\n$label: ${"%.0f".format(elapsedMs.toDouble() / runs)}ms avg, " +
                    "${roundTrips / runs} engine round trips ($runs runs, ${elapsedMs}ms total)")
        }
    }

    @Test fun `flushFrontierNodes over a wide frontier`() {
        if (System.getProperty("perf") == null) return
        val frontier = ids(WIDTH).toSet()
        val engine = CountingFakeEngine(emptyMap(), LATENCY_MS)

        fun run() = runBlocking {
            TraversalBuilder(engine, frontier, UuidKeyAdapter).flushFrontierNodes().toList()
        }

        assertEquals(WIDTH, run().size)

        val n = 3
        engine.roundTrips.set(0)
        val elapsed = measureTime { repeat(n) { run() } }
        report("flushFrontierNodes (frontier=$WIDTH, latency=${LATENCY_MS}ms)", engine.roundTrips.get(), elapsed.inWholeMilliseconds, n)
    }

    @Test fun `collectSubgraph over a wide visited set`() {
        if (System.getProperty("perf") == null) return
        val frontier = ids(WIDTH).toSet()
        val engine = CountingFakeEngine(emptyMap(), LATENCY_MS)

        fun run() = runBlocking {
            TraversalBuilder(engine, frontier, UuidKeyAdapter).collectSubgraph(null, null)
        }

        assertEquals(WIDTH, run().nodes.size)

        val n = 3
        engine.roundTrips.set(0)
        val elapsed = measureTime { repeat(n) { run() } }
        report("collectSubgraph (visited=$WIDTH, latency=${LATENCY_MS}ms)", engine.roundTrips.get(), elapsed.inWholeMilliseconds, n)
    }

    @Test fun `pathTo neighbour resolution over a single supernode`() {
        if (System.getProperty("perf") == null) return
        val (children, root) = star(WIDTH)
        val engine = CountingFakeEngine(children, LATENCY_MS)
        val target = Uuid.random() // not present among the star's node ids

        fun run() = runBlocking {
            TraversalBuilder(engine, setOf(root), UuidKeyAdapter).pathTo(target) { outgoing<TestEdge>() }
        }

        assertNull(run()) // sanity: the walk really is exhaustive, no early exit hides the cost

        val n = 3
        engine.roundTrips.set(0)
        val elapsed = measureTime { repeat(n) { run() } }
        report("pathTo (supernode width=$WIDTH, latency=${LATENCY_MS}ms)", engine.roundTrips.get(), elapsed.inWholeMilliseconds, n)
    }
}
