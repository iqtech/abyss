package pl.iqtech.abyss.graph

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import pl.iqtech.abyss.dsl.EdgeTraversalDirection
import pl.iqtech.abyss.dsl.Evaluation
import pl.iqtech.abyss.dsl.Path
import pl.iqtech.abyss.dsl.TraversalStrategy
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.Uuid

// Deterministic, NON-suspending graph fake: fixed node values, edges in insertion order (parallel edges and
// self-loops allowed). Never suspends — the harshest case for stack depth.
private class GraphFake(val nodes: Map<NodeId, TestNode>, val edges: List<Hop>) : NodeIdEngine {
    override val hopDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    val out = edges.groupBy { it.fromId }
    val `in` = edges.groupBy { it.toId }
    override suspend fun nodeAt(nid: NodeId): NodeLike<*>? = nodes[nid]
    override suspend fun nodesAt(ids: Collection<NodeId>): Map<NodeId, NodeLike<*>> = ids.mapNotNull { id -> nodes[id]?.let { id to it } }.toMap()
    override fun outAt(nid: NodeId, type: String?, needValue: Boolean, includeEphemeral: Boolean): Flow<Hop> = flow { out[nid].orEmpty().forEach { emit(it) } }
    override fun inAt(nid: NodeId, type: String?, needValue: Boolean): Flow<Hop> = flow { `in`[nid].orEmpty().forEach { emit(it) } }
    override suspend fun resolveEdges(hops: List<Hop>): Map<Hop, EdgeLike<*, *>> = hops.associateWith { it.edge!! }
    override fun allNodeIdsRaw(): Flow<NodeId> = emptyFlow()
}

private fun fakeGraph(names: List<String>, links: List<Triple<Int, Int, String>>): Pair<GraphFake, List<NodeId>> {
    val ns = names.map { TestNode(id = Uuid.random(), name = it) }
    val ids = ns.map { UuidKeyAdapter.toNodeId(it.id) }
    val hops = links.map { (f, t, label) -> Hop(ids[f], ids[t], "test_edge", TestEdge(fromId = ns[f].id, toId = ns[t].id, label = label)) }
    return GraphFake(ids.zip(ns).toMap(), hops) to ids
}

class IterativeDfsPrototypeTest {

    // ── Gate 1: identical emission sequence to the recursive backtracking oracle ─────────────────────────────
    // Random small graphs (cycles, parallel edges, self-loops), all directions, several maxDepths, 1–2 origins.
    // Visitor/evaluator decisions are pure functions of (seed, label | name, path size), so both runs see the same
    // decisions — and path size makes them depend on the route, exercising EXCLUDE pass-throughs and head tracking.
    @Test fun `gate 1 - iterative matches recursive oracle on random graphs`() = runBlocking {
        val evals = listOf(Evaluation.INCLUDE_AND_CONTINUE, Evaluation.INCLUDE_AND_CONTINUE, Evaluation.INCLUDE_AND_PRUNE,
            Evaluation.EXCLUDE_AND_CONTINUE, Evaluation.EXCLUDE_AND_PRUNE)
        var cases = 0; var totalPaths = 0; var maxPathLen = 0; var withExcludedGap = 0
        for (seed in 0 until 300) {
            val rnd = Random(seed)
            val n = rnd.nextInt(2, 9)
            val links = List(rnd.nextInt(1, n * 3)) { i -> Triple(rnd.nextInt(n), rnd.nextInt(n), "e$i") }
            val (engine, ids) = fakeGraph(List(n) { "n$it" }, links)
            val frontier = if (seed % 3 == 0 && n > 1) setOf(ids[0], ids[1]) else setOf(ids[0])
            val visitor = { _: Path, e: EdgeLike<*, *> -> Math.floorMod(seed * 7 + (e as TestEdge).label.hashCode(), 5) != 0 }
            val evaluator = { p: Path, node: NodeLike<*> -> evals[Math.floorMod(seed * 31 + (node as TestNode).name.hashCode() * 17 + p.nodes.size, evals.size)] }
            for (direction in EdgeTraversalDirection.entries) for (maxDepth in listOf(0, 1, 2, 3, Int.MAX_VALUE)) {
                val expected = recursiveDfsReference(engine, frontier, direction, maxDepth, visitor, evaluator).toList()
                val actual = iterativeDfsPaths(engine, frontier, direction, maxDepth, visitor, evaluator).toList()
                assertEquals(expected, actual, "seed=$seed direction=$direction maxDepth=$maxDepth")
                cases++; totalPaths += expected.size
                maxPathLen = maxOf(maxPathLen, expected.maxOfOrNull { it.nodes.size } ?: 0)
                withExcludedGap += expected.count { it.edges.size < it.nodes.size - 1 }
            }
        }
        println("GATE1 cases=$cases paths=$totalPaths maxPathNodes=$maxPathLen pathsWithExcludedGap=$withExcludedGap")
        assertTrue(totalPaths > 1_000 && maxPathLen >= 5 && withExcludedGap > 0, "differential must not be vacuous")
    }

    // ── Gate 2: stack independence on a non-suspending engine ──────────────────────────────────────────────
    @Test fun `gate 2 - iterative survives deep chains on a non-suspending engine`() = runBlocking {
        for (length in listOf(10_000, 40_000)) {
            val (engine, ids) = fakeGraph(List(length) { "c$it" }, (0 until length - 1).map { Triple(it, it + 1, "next") })
            val started = System.nanoTime()
            val paths = iterativeDfsPaths(engine, setOf(ids.first()), EdgeTraversalDirection.OUT, Int.MAX_VALUE,
                { _, _ -> true }, { _, _ -> Evaluation.INCLUDE_AND_CONTINUE }).toList()
            println("GATE2 iterative length=$length: ${paths.map { it.nodes.size }} nodes in ${(System.nanoTime() - started) / 1_000_000}ms")
            assertEquals(listOf(length), paths.map { it.nodes.size })
        }
        // The gate must discriminate: the recursive oracle on the same engine overflows.
        val (engine, ids) = fakeGraph(List(10_000) { "c$it" }, (0 until 9_999).map { Triple(it, it + 1, "next") })
        val oracle = try {
            recursiveDfsReference(engine, setOf(ids.first()), EdgeTraversalDirection.OUT, Int.MAX_VALUE, { _, _ -> true }, { _, _ -> Evaluation.INCLUDE_AND_CONTINUE }).toList(); "completed"
        } catch (e: StackOverflowError) { "StackOverflowError" }
        println("GATE2 recursive oracle length=10000: $oracle")
        assertEquals("StackOverflowError", oracle, "control: recursion must fail here, else gate 2 proves nothing")
    }

    // ── Gate 3: depth on real Hazelcast, three collecting dispatchers, default 512 MB heap ───────────────────
    @Test fun `gate 3 - iterative depth on real Hazelcast`() {
        if (System.getProperty("perf") == null) return
        val rt = Runtime.getRuntime()
        println("GATE3 maxHeap=${rt.maxMemory() / 1_048_576}MB")
        val dispatchers = listOf<Pair<String, CoroutineDispatcher?>>("event loop" to null, "IO" to Dispatchers.IO, "Default" to Dispatchers.Default)
        for (length in listOf(2_000, 10_000, 20_000, 40_000)) {
            val chain = seedChain(graphTestHz, "idfs-nodes", "idfs-edges", length, others = 0)
            val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "idfs-nodes", "idfs-edges", module = graphTestModule)
            val origin = setOf(huid.toNodeId(chain.first()))
            for ((label, dispatcher) in dispatchers) {
                System.gc()
                val hops = AtomicInteger()
                var peakMb = 0L
                val sampler = thread(isDaemon = true) {
                    try { while (true) { peakMb = maxOf(peakMb, (rt.totalMemory() - rt.freeMemory()) / 1_048_576); Thread.sleep(200) } } catch (_: InterruptedException) {}
                }
                val exec = Executors.newSingleThreadExecutor()
                val started = System.nanoTime()
                val future = exec.submit<String> {
                    try {
                        runBlocking {
                            suspend fun walk() = iterativeDfsPaths(g.traversalEngine, origin, EdgeTraversalDirection.OUT, Int.MAX_VALUE,
                                { _, e -> hops.incrementAndGet(); e is TestEdge }, { _, _ -> Evaluation.INCLUDE_AND_CONTINUE }).toList()
                            val paths = if (dispatcher == null) walk() else withContext(dispatcher) { walk() }
                            "ok ${paths.map { it.nodes.size }}"
                        }
                    } catch (t: Throwable) { "THREW ${t::class.simpleName}: ${t.message?.take(100)}" }
                }
                val outcome = try { future.get(180, TimeUnit.SECONDS) } catch (_: TimeoutException) {
                    val h1 = hops.get(); Thread.sleep(5_000); "TIMEOUT (180s) hops $h1 -> ${hops.get()} over 5s"
                }
                sampler.interrupt(); exec.shutdownNow()
                println("GATE3 length=$length on $label: $outcome in ${(System.nanoTime() - started) / 1_000_000}ms, peakHeap~${peakMb}MB")
                if (outcome != "ok [$length]") fail("gate 3 failed at length=$length on $label: $outcome")
            }
        }
    }

    // ── Gate 4: same Hazelcast map ops as production DFS on the same warm chain ────────────────────────────
    // 1,000 links: production DFS still completes there (its unwind overflows ~3k).
    @Test fun `gate 4 - iterative map ops equal production DFS`() {
        if (System.getProperty("perf") == null) return
        val length = 1_000
        for (others in listOf(0, 16)) {
            val chain = seedChain(graphTestHz, "idfs4-nodes", "idfs4-edges", length, others)
            fun measure(run: suspend (AbyssGraphSchema<Uuid>) -> List<Path>): Map<String, Map<String, Long>> {
                val counter = MapOpCounter(graphTestHz)
                val g = AbyssGraphSchema(UuidKeyAdapter, counter.hz, "idfs4-nodes", "idfs4-edges", module = graphTestModule)
                counter.reset()
                val paths = runBlocking { run(g) }
                assertEquals(listOf(length), paths.map { it.nodes.size })
                return counter.snapshot()
            }
            val visitor = { _: Path, e: EdgeLike<*, *> -> e is TestEdge }
            val evaluator = { _: Path, _: NodeLike<*> -> Evaluation.INCLUDE_AND_CONTINUE }
            val production = measure { g -> g.from(chain.first()) { paths(TraversalStrategy.DFS, EdgeTraversalDirection.OUT, edgeVisitor = visitor, nodeEvaluator = evaluator) }.getOrNull()!!.toList() }
            val prototype = measure { g -> iterativeDfsPaths(g.traversalEngine, setOf(huid.toNodeId(chain.first())), EdgeTraversalDirection.OUT, Int.MAX_VALUE, visitor, evaluator).toList() }
            println("GATE4 others=$others production=$production")
            println("GATE4 others=$others prototype =$prototype")
            assertEquals(production, prototype, "others=$others")
        }
    }
}
