package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.right
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.EdgeTraversalDirection
import pl.iqtech.abyss.dsl.Evaluation
import pl.iqtech.abyss.dsl.Path
import pl.iqtech.abyss.dsl.TraversalStrategy
import pl.iqtech.abyss.graph.traversal.TraversalBuilder
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.StoredEdge
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.uuid.Uuid

// Audit: can paths {} silently drop something? Findings are PRINTED ("AUDIT ..."), not asserted — this is a
// probe, not a gate. Part 1: production DFS/BFS vs an oracle written from the KDoc contract (immutable,
// not derived from dfsLoop). Part 2: real Hazelcast under eviction / failing store / cold index.

private class AuditFake(val nodes: Map<NodeId, TestNode>, edges: List<Hop>) : NodeIdEngine {
    override val hopDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    val out = edges.groupBy { it.fromId }
    val `in` = edges.groupBy { it.toId }
    override suspend fun nodeAt(nid: NodeId): NodeLike<*>? = nodes[nid]
    override fun outAt(nid: NodeId, type: String?, needValue: Boolean, includeEphemeral: Boolean): Flow<Hop> = flow { out[nid].orEmpty().forEach { emit(it) } }
    override fun inAt(nid: NodeId, type: String?, needValue: Boolean): Flow<Hop> = flow { `in`[nid].orEmpty().forEach { emit(it) } }
    override suspend fun resolveEdges(hops: List<Hop>): Map<Hop, EdgeLike<*, *>> = hops.associateWith { it.edge!! }
    override fun allNodeIdsRaw(): Flow<NodeId> = emptyFlow()
}

private val INCLUDED = setOf(Evaluation.INCLUDE_AND_CONTINUE, Evaluation.INCLUDE_AND_PRUNE)

// Contract (TraversalBuilderLike.paths KDoc + README "Node uniqueness"), written as plain immutable recursion.
private fun specPaths(
    g: AuditFake, frontier: List<NodeId>, direction: EdgeTraversalDirection, maxDepth: Int,
    visitor: (Path, EdgeLike<*, *>) -> Boolean, evaluator: (Path, NodeLike<*>) -> Evaluation,
): List<Path> {
    fun around(at: NodeId): List<Hop> = when (direction) {
        EdgeTraversalDirection.OUT -> g.out[at].orEmpty()
        EdgeTraversalDirection.IN -> g.`in`[at].orEmpty()
        EdgeTraversalDirection.BOTH -> g.out[at].orEmpty() + g.`in`[at].orEmpty()
    }
    fun walk(path: Path, head: NodeId, at: NodeId, route: Set<NodeId>, depth: Int): List<Path> {
        if (depth >= maxDepth) return emptyList()
        val result = mutableListOf<Path>(); val tried = mutableSetOf<NodeId>()
        for (hop in around(at)) {
            if (!visitor(path, hop.edge!!)) continue
            val next = if (hop.fromId == at) hop.toId else hop.fromId
            if (next in route || !tried.add(next)) continue
            val node = g.nodes[next] ?: continue
            val ev = evaluator(path, node); val d = depth + 1
            val p2 = if (ev in INCLUDED) Path(path.nodes + node, if (at == head) path.edges + hop.edge else path.edges) else path
            val h2 = if (ev in INCLUDED) next else head
            result += when (ev) {
                Evaluation.INCLUDE_AND_PRUNE -> listOf(p2)
                Evaluation.INCLUDE_AND_CONTINUE -> if (d >= maxDepth) listOf(p2) else walk(p2, h2, next, route + next, d).ifEmpty { listOf(p2) }
                Evaluation.EXCLUDE_AND_CONTINUE -> if (d < maxDepth) walk(p2, h2, next, route + next, d) else emptyList()
                Evaluation.EXCLUDE_AND_PRUNE -> emptyList()
            }
        }
        return result
    }
    return frontier.flatMap { o -> g.nodes[o]?.let { walk(Path(listOf(it), emptyList()), o, o, setOf(o), 0) } ?: emptyList() }
}

private fun key(p: Path) = p.nodes.joinToString(">") { (it as TestNode).name } + "|" + p.edges.joinToString(",") { (it as TestEdge).label }
private fun <T> List<T>.minusMultiset(other: List<T>): List<T> { val rest = other.toMutableList(); return filter { !rest.remove(it) } }

// Store with a kill switch: `failing` makes every read return Left, like a YB outage/timeout.
private class FlakyStore : AbyssStoreLike {
    val nodes = mutableMapOf<NodeId, NodeLike<*>>()
    val edges = mutableListOf<StoredEdge>()
    @Volatile var failing = false
    private fun boom() = Either.Left(AbyssError.Unexpected(RuntimeException("store down")))
    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> =
        if (failing) boom() else Either.Right(nodes[id] to null)
    override suspend fun loadNodes(ids: Collection<NodeId>): Either<AbyssError, Map<NodeId, Pair<NodeLike<*>?, Duration?>>> =
        if (failing) boom() else Either.Right(ids.mapNotNull { id -> nodes[id]?.let { id to (it to null) } }.toMap())
    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> =
        if (failing) boom() else Either.Right(edges.find { it.fromId == fromId && it.toId == toId }?.let { it.edge to null } ?: (null to null))
    override suspend fun loadEdges(fromId: NodeId): Either<AbyssError, List<StoredEdge>> = if (failing) boom() else Either.Right(edges.filter { it.fromId == fromId })
    override suspend fun loadInEdges(toId: NodeId): Either<AbyssError, List<StoredEdge>> = if (failing) boom() else Either.Right(edges.filter { it.toId == toId })
    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        block(object : AbyssStoreTransactionLike {
            override fun saveNode(id: NodeId, node: NodeLike<*>, tags: Set<String>) { nodes[id] = node }
            override fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, tags: Set<String>) { edges += StoredEdge(fromId, toId, edge, null, "test_node") }
            override fun deleteNode(id: NodeId) { nodes.remove(id) }
            override fun deleteEdge(fromId: NodeId, toId: NodeId, type: String) { edges.removeAll { it.fromId == fromId && it.toId == toId } }
        })
        return Unit.right()
    }
}

class PathsSilentDropAuditTest {

    // ── Part 1: differential vs the contract ──────────────────────────────────────────────────────────────
    @Test fun `part 1 - production DFS and BFS vs contract oracle`() = runBlocking {
        val evals = Evaluation.entries
        var cases = 0; var paths = 0
        val drops = mutableMapOf<TraversalStrategy, Int>(); val extras = mutableMapOf<TraversalStrategy, Int>(); var dfsOrderDiff = 0
        val firstExample = mutableMapOf<String, String>()
        for (seed in 0 until 2_000) {
            val rnd = Random(seed)
            val n = rnd.nextInt(2, 9)
            val names = List(n) { "n$it" }
            val ns = names.mapIndexed { i, nm -> TestNode(id = Uuid.fromLongs(seed.toLong(), i.toLong() + 1), name = nm) }
            val ids = ns.map { UuidKeyAdapter.toNodeId(it.id) }
            // parallel edges of different types, self-loops, cycles; an occasional dangling endpoint (node absent)
            val hops = List(rnd.nextInt(1, n * 3)) { i ->
                val f = rnd.nextInt(n); val t = rnd.nextInt(n)
                Hop(ids[f], ids[t], "t${i % 2}", TestEdge(fromId = ns[f].id, toId = ns[t].id, label = "e$i"))
            }
            val dangling = if (seed % 7 == 0) setOf(ids.last()) else emptySet()
            val g = AuditFake(ids.zip(ns).filter { it.first !in dangling }.toMap(), hops)
            val frontier = if (seed % 3 == 0) listOf(ids[0], ids[1]) else listOf(ids[0])
            val visitor = { p: Path, e: EdgeLike<*, *> -> Math.floorMod(seed * 7 + (e as TestEdge).label.hashCode() + p.nodes.size, 6) != 0 }
            val evaluator = { p: Path, node: NodeLike<*> -> evals[Math.floorMod(seed * 31 + (node as TestNode).name.hashCode() * 17 + p.nodes.size * 3 + p.edges.size, evals.size)] }
            for (direction in EdgeTraversalDirection.entries) for (maxDepth in listOf(0, 1, 2, 3, 4, Int.MAX_VALUE)) {
                val expected = specPaths(g, frontier, direction, maxDepth, visitor, evaluator).map(::key)
                cases++; paths += expected.size
                for (strategy in TraversalStrategy.entries) {
                    val actual = TraversalBuilder(g, frontier.toSet(), UuidKeyAdapter).paths(strategy, direction, maxDepth, visitor, evaluator).toList().map(::key)
                    val missing = expected.minusMultiset(actual); val extra = actual.minusMultiset(expected)
                    val ctx = "seed=$seed dir=$direction maxDepth=$maxDepth"
                    if (missing.isNotEmpty()) { drops.merge(strategy, 1, Int::plus); firstExample.putIfAbsent("$strategy DROP", "$ctx missing=$missing expected=$expected actual=$actual") }
                    if (extra.isNotEmpty()) { extras.merge(strategy, 1, Int::plus); firstExample.putIfAbsent("$strategy EXTRA", "$ctx extra=$extra expected=$expected actual=$actual") }
                    if (strategy == TraversalStrategy.DFS && missing.isEmpty() && extra.isEmpty() && actual != expected) dfsOrderDiff++
                }
            }
        }
        println("AUDIT part1 cases=$cases oraclePaths=$paths")
        for (s in TraversalStrategy.entries) println("AUDIT part1 $s casesWithDrops=${drops[s] ?: 0} casesWithExtras=${extras[s] ?: 0}")
        println("AUDIT part1 DFS same-multiset-different-order=$dfsOrderDiff")
        firstExample.forEach { (k, v) -> println("AUDIT part1 example $k: $v") }
    }

    // Documented contract edges that still lose information — shown, not judged.
    @Test fun `part 1b - documented collapses and the excluded-gap Path shape`() = runBlocking {
        val ns = listOf("a", "x", "b", "c").map { TestNode(id = Uuid.random(), name = it) }
        val ids = ns.map { UuidKeyAdapter.toNodeId(it.id) }
        fun hop(f: Int, t: Int, type: String, label: String) = Hop(ids[f], ids[t], type, TestEdge(fromId = ns[f].id, toId = ns[t].id, label = label))
        // parallel edges a->b of two types, and a<-b back-edge
        val multi = AuditFake(ids.zip(ns).toMap(), listOf(hop(0, 2, "owns", "a-owns-b"), hop(0, 2, "likes", "a-likes-b"), hop(2, 0, "owns", "b-owns-a")))
        for (dir in listOf(EdgeTraversalDirection.OUT, EdgeTraversalDirection.BOTH)) {
            val r = TraversalBuilder(multi, setOf(ids[0]), UuidKeyAdapter).paths(TraversalStrategy.DFS, dir, Int.MAX_VALUE, { _, _ -> true }, { _, _ -> Evaluation.INCLUDE_AND_CONTINUE }).toList()
            println("AUDIT part1b parallel edges a->b (owns, likes) + b->a, dir=$dir: ${r.map(::key)}")
        }
        // a -> x(EXCLUDE_AND_CONTINUE) -> b -> c
        val gap = AuditFake(ids.zip(ns).toMap(), listOf(hop(0, 1, "t", "a-x"), hop(1, 2, "t", "x-b"), hop(2, 3, "t", "b-c")))
        val r = TraversalBuilder(gap, setOf(ids[0]), UuidKeyAdapter).paths(TraversalStrategy.DFS, EdgeTraversalDirection.OUT, Int.MAX_VALUE, { _, _ -> true },
            { _, n -> if ((n as TestNode).name == "x") Evaluation.EXCLUDE_AND_CONTINUE else Evaluation.INCLUDE_AND_CONTINUE }).toList()
        val either = r.single().toEitherList().joinToString(" ") { it.fold({ e -> "[${(e as TestEdge).label}]" }, { n -> (n as TestNode).name }) }
        println("AUDIT part1b excluded gap: path=${key(r.single())} toEitherList=$either")
        // BFS minimal: a -> b(IC); b -> x1, x2 (EXCLUDE_AND_CONTINUE, dead ends); b -> c (INCLUDE_AND_PRUNE), then without c
        val m = listOf("a", "b", "x1", "x2", "c").map { TestNode(id = Uuid.random(), name = it) }
        val mid = m.map { UuidKeyAdapter.toNodeId(it.id) }
        fun mh(f: Int, t: Int) = Hop(mid[f], mid[t], "t", TestEdge(fromId = m[f].id, toId = m[t].id, label = "${m[f].name}-${m[t].name}"))
        val ev = { _: Path, n: NodeLike<*> -> when ((n as TestNode).name) { "x1", "x2" -> Evaluation.EXCLUDE_AND_CONTINUE; "c" -> Evaluation.INCLUDE_AND_PRUNE; else -> Evaluation.INCLUDE_AND_CONTINUE } }
        for ((label, links) in listOf("with c" to listOf(mh(0, 1), mh(1, 2), mh(1, 4)), "x1+x2, no c" to listOf(mh(0, 1), mh(1, 2), mh(1, 3)))) {
            val fg = AuditFake(mid.zip(m).toMap(), links)
            for (s in TraversalStrategy.entries)
                println("AUDIT part1b BFS-min $label $s: ${TraversalBuilder(fg, setOf(mid[0]), UuidKeyAdapter).paths(s, EdgeTraversalDirection.OUT, Int.MAX_VALUE, { _, _ -> true }, ev).toList().map(::key)}")
        }
    }

    // ── Part 2: real Hazelcast + store ────────────────────────────────────────────────────────────────────
    @Test fun `part 2 - eviction, failing store, cold index`() = runBlocking {
        val maps = listOf("psd-nodes", "psd-edges", "psd-edges-adjacency")
        maps.forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        val store = FlakyStore()
        val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "psd-nodes", "psd-edges", persistentStore = store, module = graphTestModule)
        val (a, b, c) = listOf("a", "b", "c").map { TestNode(id = Uuid.random(), name = it) }
        g.transaction { listOf(a, b, c).forEach { addNode(it) }; addEdge(TestEdge(fromId = a.id, toId = b.id, label = "a-b")); addEdge(TestEdge(fromId = b.id, toId = c.id, label = "b-c")) }

        suspend fun run(label: String) {
            for (strategy in TraversalStrategy.entries) {
                val r = try {
                    g.from(a.id) { paths(strategy, EdgeTraversalDirection.OUT, edgeVisitor = { _, _ -> true }, nodeEvaluator = { _, _ -> Evaluation.INCLUDE_AND_CONTINUE }).toList() }
                        .fold({ "Left($it)" }, { ps -> "Right(${ps.map(::key)})" })
                } catch (t: Throwable) { "THREW ${t::class.simpleName}: ${t.message}" }
                println("AUDIT part2 %-52s %s -> %s".format(label, strategy, r))
            }
        }
        val nodesMap = graphTestHz.getMap<NodeId, Any>("psd-nodes")
        val edgesMap = graphTestHz.getMap<Any, Any>("psd-edges")
        val adjMap = graphTestHz.getMap<Any, Any>("psd-edges-adjacency")

        run("S0 warm (expect a>b>c)")
        edgesMap.evictAll(); run("S1 edge values evicted, store up")
        edgesMap.evictAll(); store.failing = true; run("S2 edge values evicted, store DOWN"); store.failing = false
        nodesMap.evict(huid.toNodeId(b.id)); store.failing = true; run("S3 node b evicted, store DOWN"); store.failing = false
        run("S5 store back up")
        nodesMap.evict(huid.toNodeId(c.id)); store.failing = true; run("S4 only node c (leaf) evicted, store DOWN"); store.failing = false
        run("S5' store back up")
        adjMap.clear(); edgesMap.clear(); store.failing = true; run("S6 cold index (restart), store DOWN"); store.failing = false
        run("S7 cold index, store up")
        maps.forEach { graphTestHz.getMap<Any, Any>(it).clear() }
    }
}
