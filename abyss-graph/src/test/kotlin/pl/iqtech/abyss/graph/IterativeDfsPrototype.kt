package pl.iqtech.abyss.graph

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import pl.iqtech.abyss.dsl.EdgeTraversalDirection
import pl.iqtech.abyss.dsl.Evaluation
import pl.iqtech.abyss.dsl.Path
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike

// TODO 1.34 PROTOTYPE — test sources only, not wired into TraversalBuilder. Both functions mirror
// TraversalBuilder.paths(DFS) (origin fetch via nodesAt, edgesFrom routing) so they can be compared with each
// other and with production on the same engine.

private fun edgesFrom(engine: NodeIdEngine, fromNid: NodeId, direction: EdgeTraversalDirection): Flow<Hop> = when (direction) {
    EdgeTraversalDirection.OUT -> engine.outAt(fromNid, null)
    EdgeTraversalDirection.IN -> engine.inAt(fromNid, null)
    EdgeTraversalDirection.BOTH -> flow { emitAll(engine.outAt(fromNid, null)); emitAll(engine.inAt(fromNid, null)) }
}

/**
 * Iterative DFS: one [Frame] per node on the current route, driven by a single `while` loop. Every suspend call
 * (hop read, nodeAt, emit) returns into the loop — no nested collect — so stack depth is constant regardless of
 * route length or whether the engine suspends. Semantics = [recursiveDfsReference] (backtracking, README path-local
 * uniqueness), including emission order: a natural terminal is emitted when its frame is exhausted, before the
 * parent's next hop — the same instant the recursive version returned.
 */
fun iterativeDfsPaths(
    engine: NodeIdEngine,
    frontier: Set<NodeId>,
    direction: EdgeTraversalDirection,
    maxDepth: Int,
    edgeVisitor: (Path, EdgeLike<*, *>) -> Boolean,
    nodeEvaluator: (Path, NodeLike<*>) -> Evaluation,
): Flow<Path> = flow {
    class Frame(
        val fromNid: NodeId, val headNid: NodeId, val depth: Int,
        val hops: Iterator<Hop>,
        val enteredVia: Evaluation?,          // null = origin
        val pushedNode: Boolean, val pushedEdge: Boolean,
    ) {
        val expanded = HashSet<NodeId>()
        var emitted = false
    }

    // Hops read once, at push, WITH values: the worker's resolveEdges does not self-heal evicted edges the way
    // adjacencyHopFlow's flush does, so key-only hops + a later batched value fetch would silently drop them.
    suspend fun hopsOf(nid: NodeId, depth: Int): Iterator<Hop> =
        if (depth >= maxDepth) emptyList<Hop>().iterator() else edgesFrom(engine, nid, direction).toList().iterator()

    val origin = engine.nodesAt(frontier).let { fetched -> frontier.mapNotNull { nid -> fetched[nid]?.let { nid to it } } }
    for ((originNid, originNode) in origin) {
        val nodes = arrayListOf<NodeLike<*>>(originNode)
        val edges = arrayListOf<EdgeLike<*, *>>()
        val onPath = mutableSetOf(originNid)
        fun snapshot() = Path(nodes.toList(), edges.toList())
        fun pop(node: Boolean, edge: Boolean) { if (node) nodes.removeAt(nodes.size - 1); if (edge) edges.removeAt(edges.size - 1) }

        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(originNid, originNid, 0, hopsOf(originNid, 0), null, false, false))
        while (stack.isNotEmpty()) {
            val top = stack.last()
            if (!top.hops.hasNext()) {                                   // subtree done = recursive "return"
                stack.removeLast()
                onPath -= top.fromNid
                val parent = stack.lastOrNull() ?: continue
                when (top.enteredVia) {
                    Evaluation.INCLUDE_AND_CONTINUE -> { if (!top.emitted) emit(snapshot()); parent.emitted = true }
                    Evaluation.EXCLUDE_AND_CONTINUE -> if (top.emitted) parent.emitted = true
                    else -> {}
                }
                pop(top.pushedNode, top.pushedEdge)
                continue
            }
            val hop = top.hops.next()
            val edge = hop.edge!!
            if (!edgeVisitor(snapshot(), edge)) continue
            val next = if (hop.fromId == top.fromNid) hop.toId else hop.fromId
            if (next in onPath || !top.expanded.add(next)) continue
            val node = engine.nodeAt(next) ?: continue
            val eval = nodeEvaluator(snapshot(), node)
            val included = eval == Evaluation.INCLUDE_AND_CONTINUE || eval == Evaluation.INCLUDE_AND_PRUNE
            val pushedEdge = included && top.fromNid == top.headNid
            val nextDepth = top.depth + 1
            if (included) { nodes += node; if (pushedEdge) edges += edge }
            when {
                eval == Evaluation.INCLUDE_AND_PRUNE || (eval == Evaluation.INCLUDE_AND_CONTINUE && nextDepth >= maxDepth) -> {
                    emit(snapshot()); top.emitted = true; pop(true, pushedEdge)
                }
                (eval == Evaluation.INCLUDE_AND_CONTINUE || eval == Evaluation.EXCLUDE_AND_CONTINUE) && nextDepth < maxDepth -> {
                    onPath += next
                    stack.addLast(Frame(next, if (included) next else top.headNid, nextDepth, hopsOf(next, nextDepth), eval, included, pushedEdge))
                }
                else -> {}                                               // EXCLUDE_AND_PRUNE, or EXCLUDE_AND_CONTINUE at cap
            }
        }
    }
}

/**
 * Oracle: the recursive backtracking fix (one route set + one path stack, no per-level copies). Correct semantics,
 * but its unwind recurses on the thread stack (StackOverflowError ~3k links on Hazelcast) — only for small graphs.
 */
fun recursiveDfsReference(
    engine: NodeIdEngine,
    frontier: Set<NodeId>,
    direction: EdgeTraversalDirection,
    maxDepth: Int,
    edgeVisitor: (Path, EdgeLike<*, *>) -> Boolean,
    nodeEvaluator: (Path, NodeLike<*>) -> Evaluation,
): Flow<Path> = flow {
    suspend fun FlowCollector<Path>.loop(
        nodes: ArrayList<NodeLike<*>>, edges: ArrayList<EdgeLike<*, *>>,
        headNid: NodeId, fromNid: NodeId, onPath: MutableSet<NodeId>, depth: Int,
    ): Boolean {
        if (depth >= maxDepth) return false
        val expanded = HashSet<NodeId>()
        var emitted = false
        edgesFrom(engine, fromNid, direction).collect { hop ->
            val edge = hop.edge!!
            if (!edgeVisitor(Path(nodes.toList(), edges.toList()), edge)) return@collect
            val nextNid = if (hop.fromId == fromNid) hop.toId else hop.fromId
            if (nextNid in onPath || !expanded.add(nextNid)) return@collect
            val nextNode = engine.nodeAt(nextNid) ?: return@collect
            val eval = nodeEvaluator(Path(nodes.toList(), edges.toList()), nextNode)
            val included = eval == Evaluation.INCLUDE_AND_CONTINUE || eval == Evaluation.INCLUDE_AND_PRUNE
            val pushedEdge = included && fromNid == headNid
            if (included) { nodes += nextNode; if (pushedEdge) edges += edge }
            try {
                val nextHeadNid = if (included) nextNid else headNid
                val nextDepth = depth + 1
                suspend fun descend(): Boolean {
                    onPath += nextNid
                    try { return loop(nodes, edges, nextHeadNid, nextNid, onPath, nextDepth) } finally { onPath -= nextNid }
                }
                when {
                    eval == Evaluation.INCLUDE_AND_PRUNE -> { emit(Path(nodes.toList(), edges.toList())); emitted = true }
                    eval == Evaluation.INCLUDE_AND_CONTINUE && nextDepth >= maxDepth -> { emit(Path(nodes.toList(), edges.toList())); emitted = true }
                    eval == Evaluation.INCLUDE_AND_CONTINUE -> { if (!descend()) emit(Path(nodes.toList(), edges.toList())); emitted = true }
                    eval == Evaluation.EXCLUDE_AND_CONTINUE && nextDepth < maxDepth -> if (descend()) emitted = true
                }
            } finally {
                if (included) { nodes.removeAt(nodes.size - 1); if (pushedEdge) edges.removeAt(edges.size - 1) }
            }
        }
        return emitted
    }
    val origin = engine.nodesAt(frontier).let { fetched -> frontier.mapNotNull { nid -> fetched[nid]?.let { nid to it } } }
    for ((nid, node) in origin) loop(arrayListOf(node), arrayListOf(), nid, nid, mutableSetOf(nid), 0)
}
