package pl.iqtech.abyss.graph

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.EdgeTraversalDirection
import pl.iqtech.abyss.dsl.Evaluation
import pl.iqtech.abyss.dsl.TraversalScope
import pl.iqtech.abyss.dsl.TraversalStrategy
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

// TODO 2.30 / 4.12 baseline/after harness. Two axes are measured, deliberately:
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
//
// valuedHops: paths()' edgesFrom dereferences hop.edge, so the paths scenario needs hops that carry a
// value. Opt-in, so the 2.30 scenarios (which resolve edges via resolveEdges) keep measuring exactly
// what they measured before.
//
// TODO 2.32 gap 1: outAt/inAt honour `type` and inAt mirrors outAt over `children` inverted, so a typed
// walk can't score the same as an untyped one and an IN walk can't end silently at link 0. Deliberately
// NOT a per-route cost model — both still charge one round trip per call; only real Hazelcast
// (MapOpCounter) prices routes. edgeTypeOf defaults every edge to "test_edge" (TestEdge's @SerialName),
// so the pre-existing scenarios — which pass null or "test_edge" — see exactly the hops they saw before.
private class CountingFakeEngine(
    private val children: Map<NodeId, List<NodeId>>,
    private val latencyMs: Long,
    private val valuedHops: Boolean = false,
    private val edgeTypeOf: (from: NodeId, to: NodeId) -> String = { _, _ -> "test_edge" },
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

    private val parents: Map<NodeId, List<NodeId>> =
        children.flatMap { (from, tos) -> tos.map { it to from } }.groupBy({ it.first }, { it.second })

    override fun outAt(nid: NodeId, type: String?, needValue: Boolean, includeEphemeral: Boolean): Flow<Hop> =
        hops(children[nid].orEmpty().map { nid to it }, type)

    override fun inAt(nid: NodeId, type: String?, needValue: Boolean): Flow<Hop> =
        hops(parents[nid].orEmpty().map { it to nid }, type)

    private fun hops(pairs: List<Pair<NodeId, NodeId>>, type: String?): Flow<Hop> = flow {
        roundTrips.incrementAndGet()
        delay(latencyMs)
        for ((from, to) in pairs) {
            val edgeType = edgeTypeOf(from, to)
            if (type != null && type != edgeType) continue
            val edge = if (valuedHops) TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "") else null
            emit(Hop(from, to, edgeType, edge))
        }
    }

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
        private const val CHAIN_LENGTH = 10_000

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

    // TODO 4.12: a typed node hop out of one supernode. Fake hops carry no nodeTypeTag, so every
    // neighbour needs a fetch — the public outgoing<E, N>(predicate) sugar always supplies a predicate
    // anyway, which makes this the common case, not a cold-cache corner.
    @Test fun `addNodeHop over a single supernode`() {
        if (System.getProperty("perf") == null) return
        val (children, root) = star(WIDTH)
        val hub = children.getValue(root).single()
        val engine = CountingFakeEngine(children, LATENCY_MS)

        fun run() = runBlocking {
            val builder = TraversalBuilder(engine, setOf(hub), UuidKeyAdapter)
            TraversalScope(builder).outgoing<Uuid, TestEdge, TestNode> { true }
            builder.frontier.size
        }

        assertEquals(WIDTH, run())

        val n = 3
        engine.roundTrips.set(0)
        val elapsed = measureTime { repeat(n) { run() } }
        report("addNodeHop (supernode width=$WIDTH, latency=${LATENCY_MS}ms)", engine.roundTrips.get(), elapsed.inWholeMilliseconds, n)
    }

    // TODO 4.12: BFS paths out of one supernode — one entry whose hop loop resolves every neighbour.
    @Test fun `paths BFS over a single supernode`() {
        if (System.getProperty("perf") == null) return
        val (children, root) = star(WIDTH)
        val hub = children.getValue(root).single()
        val engine = CountingFakeEngine(children, LATENCY_MS, valuedHops = true)

        fun run() = runBlocking {
            TraversalBuilder(engine, setOf(hub), UuidKeyAdapter).paths(
                TraversalStrategy.BFS, EdgeTraversalDirection.OUT,
                edgeVisitor = { _, _ -> true },
                nodeEvaluator = { _, _ -> Evaluation.INCLUDE_AND_PRUNE },
            ).toList().size
        }

        assertEquals(WIDTH, run())

        val n = 3
        engine.roundTrips.set(0)
        val elapsed = measureTime { repeat(n) { run() } }
        report("paths BFS (supernode width=$WIDTH, latency=${LATENCY_MS}ms)", engine.roundTrips.get(), elapsed.inWholeMilliseconds, n)
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

    // TODO 2.32 Phase 0 (shape): today's paths() walking a degree-1 chain end to end, OUT from the head
    // and IN from the tail. latencyMs = 0, so wall-clock is the loops themselves (DFS recursion depth,
    // BFS's per-link visited/Path copies) rather than delay(); round trips count the engine calls. A
    // StackOverflowError is a result, not a harness failure — it is recorded as DNF.
    @Test fun `chain walk baseline - paths over a 10k chain`() {
        if (System.getProperty("perf") == null) return
        val chain = ids(CHAIN_LENGTH)
        val children = chain.zipWithNext().associate { (a, b) -> a to listOf(b) }
        for (strategy in TraversalStrategy.entries) for (direction in listOf(EdgeTraversalDirection.OUT, EdgeTraversalDirection.IN)) {
            val engine = CountingFakeEngine(children, latencyMs = 0, valuedHops = true)
            val origin = if (direction == EdgeTraversalDirection.OUT) chain.first() else chain.last()
            var outcome = ""
            val elapsed = measureTime {
                outcome = try {
                    val paths = runBlocking {
                        TraversalBuilder(engine, setOf(origin), UuidKeyAdapter).paths(
                            strategy, direction,
                            edgeVisitor = { _, _ -> true },
                            nodeEvaluator = { _, _ -> Evaluation.INCLUDE_AND_CONTINUE },
                        ).toList()
                    }
                    "${paths.size} path(s), ${paths.map { it.nodes.size }} nodes"
                } catch (e: StackOverflowError) { "DNF: StackOverflowError" }
            }
            println("\nchain paths $strategy $direction (length=$CHAIN_LENGTH, latency=0): ${elapsed.inWholeMilliseconds}ms, " +
                    "${engine.roundTrips.get()} engine round trips, $outcome")
        }
    }
}

// Pins gap 1 itself — the five scenarios above never pass a non-"test_edge" type or call inAt.
class CountingFakeEngineTest {
    @Test fun `outAt and inAt filter by type and inAt mirrors outAt`() = runBlocking {
        val (a, b, c, x) = List(4) { UuidKeyAdapter.toNodeId(Uuid.random()) }
        val engine = CountingFakeEngine(mapOf(a to listOf(b), b to listOf(c, x)), latencyMs = 0) { _, to -> if (to == x) "other" else "has_next" }

        assertEquals(listOf(c), engine.outAt(b, "has_next").toList().map { it.toId })
        assertEquals(setOf(c, x), engine.outAt(b, null).toList().map { it.toId }.toSet())
        assertEquals(listOf(b), engine.inAt(c, "has_next").toList().map { it.fromId })
        assertEquals(listOf("other"), engine.inAt(x, null).toList().map { it.type })
        assertEquals(emptyList(), engine.inAt(x, "has_next").toList())
        assertEquals(emptyList(), engine.inAt(a, null).toList())
        assertEquals(6, engine.roundTrips.get())   // one per call, whatever the route or result
    }
}
