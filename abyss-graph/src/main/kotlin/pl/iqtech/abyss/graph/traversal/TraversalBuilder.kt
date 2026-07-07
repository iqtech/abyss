package pl.iqtech.abyss.graph.traversal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import pl.iqtech.abyss.dsl.Evaluation
import pl.iqtech.abyss.dsl.HopDirection
import pl.iqtech.abyss.dsl.Path
import pl.iqtech.abyss.dsl.Subgraph
import pl.iqtech.abyss.dsl.EdgeTraversalDirection
import pl.iqtech.abyss.dsl.TraversalBuilderLike
import pl.iqtech.abyss.dsl.TraversalStrategy
import pl.iqtech.abyss.dsl.cachedAnnotation
import pl.iqtech.abyss.graph.Hop
import pl.iqtech.abyss.graph.NodeIdEngine
import pl.iqtech.abyss.store.api.KeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.EdgeLike

// The frontier is NodeId-based so a walk can span schemas; homeAdapter converts the home schema's
// domain ids for the id-valued conveniences (checkReaches, filterFrontierByOutEdgeTo, …).
class TraversalBuilder<ID>(
    private val engine: NodeIdEngine,
    startFrontier: Set<NodeId>,
    private val homeAdapter: KeyAdapter<ID>,
) : TraversalBuilderLike<ID> {

    companion object {
        // ponytail: shared JVM-wide bound, not per-call — caps how many of Dispatchers.IO's slots
        // one supernode-heavy hop can occupy at once, so concurrent traversals interleave instead
        // of queuing behind one unbounded burst. Bump if profiling shows it's the wrong number.
        private const val HOP_FANOUT_PARALLELISM = 256
        private val hopDispatcher = Dispatchers.IO.limitedParallelism(HOP_FANOUT_PARALLELISM)
    }

    var frontier: Set<NodeId> = startFrontier
        private set

    private val allVisitedIds: MutableSet<NodeId> = startFrontier.toMutableSet()
    private val allTraversedHops: MutableList<Hop> = mutableListOf()
    internal val traversedHops: List<Hop> get() = allTraversedHops

    private suspend fun hops(nid: NodeId, direction: HopDirection, type: String?, needValue: Boolean): List<Hop> =
        if (direction == HopDirection.OUTGOING) engine.outAt(nid, type, needValue) else engine.inAt(nid, type, needValue)

    // Fills in edge values for key-only hops (predicate-free hops skip the fetch) in one batched call,
    // preserving input order — the single point where Subgraph.edges is materialized.
    private suspend fun resolveHopEdges(hops: List<Hop>): List<EdgeLike<*, *>> {
        val unresolved = hops.filter { it.edge == null }
        val resolved = if (unresolved.isEmpty()) emptyMap() else engine.resolveEdges(unresolved)
        return hops.mapNotNull { it.edge ?: resolved[it] }
    }

    private fun Hop.target(direction: HopDirection): NodeId =
        if (direction == HopDirection.OUTGOING) toId else fromId

    private fun NodeLike<*>.typeName(): String? = this::class.cachedAnnotation<SerialName>()?.value

    override suspend fun addHop(direction: HopDirection, edgeType: String?, edgePredicate: ((EdgeLike<*, *>) -> Boolean)?) {
        val needValue = edgePredicate != null
        val hopEdges = coroutineScope {
            frontier.map { nid ->
                async(hopDispatcher) { hops(nid, direction, edgeType, needValue).filter { edgePredicate == null || edgePredicate(it.edge!!) } }
            }.awaitAll().flatten()
        }
        allTraversedHops += hopEdges
        frontier = hopEdges.map { it.target(direction) }.toSet()
        allVisitedIds += frontier
    }

    override suspend fun addNodeHop(direction: HopDirection, edgeType: String, nodeType: String, nodePredicate: ((NodeLike<*>) -> Boolean)?) {
        val hopEdges = coroutineScope {
            frontier.map { nid ->
                async(hopDispatcher) {
                    hops(nid, direction, edgeType, needValue = false).filter { hop ->
                        val node = engine.nodeAt(hop.target(direction)) ?: return@filter false
                        if (node.typeName() != nodeType) return@filter false
                        nodePredicate == null || nodePredicate(node)
                    }
                }
            }.awaitAll().flatten()
        }
        allTraversedHops += hopEdges
        frontier = hopEdges.map { it.target(direction) }.toSet()
        allVisitedIds += frontier
    }

    override suspend fun filterFrontierByNode(nodeType: String, predicate: ((NodeLike<*>) -> Boolean)?) {
        val matchingIds = coroutineScope {
            frontier.map { nid ->
                async(hopDispatcher) {
                    val node = engine.nodeAt(nid) ?: return@async null
                    if (node.typeName() != nodeType) return@async null
                    if (predicate != null && !predicate(node)) return@async null
                    nid
                }
            }.awaitAll()
        }.filterNotNull().toSet()
        allVisitedIds -= (frontier - matchingIds)
        frontier = matchingIds
    }

    private suspend fun filterFrontierByEdge(direction: HopDirection, edgeType: String, endpoint: NodeId) {
        val matching = coroutineScope {
            frontier.map { nid ->
                async(hopDispatcher) { if (hops(nid, direction, edgeType, needValue = false).any { it.target(direction) == endpoint }) nid else null }
            }.awaitAll()
        }.filterNotNull().toSet()
        allVisitedIds -= (frontier - matching)
        frontier = matching
    }

    private suspend fun filterFrontierByEdgeType(direction: HopDirection, edgeType: String, nodeType: String) {
        val matching = coroutineScope {
            frontier.map { nid ->
                async(hopDispatcher) {
                    val has = hops(nid, direction, edgeType, needValue = false).any { engine.nodeAt(it.target(direction))?.typeName() == nodeType }
                    if (has) nid else null
                }
            }.awaitAll()
        }.filterNotNull().toSet()
        allVisitedIds -= (frontier - matching)
        frontier = matching
    }

    override suspend fun filterFrontierByOutEdgeTo(edgeType: String, toId: ID) =
        filterFrontierByEdge(HopDirection.OUTGOING, edgeType, homeAdapter.toNodeId(toId))

    override suspend fun filterFrontierByOutEdgeToType(edgeType: String, nodeType: String) =
        filterFrontierByEdgeType(HopDirection.OUTGOING, edgeType, nodeType)

    override suspend fun filterFrontierByInEdgeFrom(edgeType: String, fromId: ID) =
        filterFrontierByEdge(HopDirection.INCOMING, edgeType, homeAdapter.toNodeId(fromId))

    override suspend fun filterFrontierByInEdgeFromType(edgeType: String, nodeType: String) =
        filterFrontierByEdgeType(HopDirection.INCOMING, edgeType, nodeType)

    override suspend fun filterFrontierByTraversal(block: suspend TraversalBuilderLike<ID>.() -> Unit) {
        val matching = coroutineScope {
            frontier.map { nid ->
                async(hopDispatcher) {
                    val sub = TraversalBuilder(engine, setOf(nid), homeAdapter)
                    sub.block()
                    if (sub.frontier.isNotEmpty()) nid else null
                }
            }.awaitAll()
        }.filterNotNull().toSet()
        allVisitedIds -= (frontier - matching)
        frontier = matching
    }

    override suspend fun flushFrontierNodes(): Flow<NodeLike<*>> = flow {
        for (nid in frontier) engine.nodeAt(nid)?.let { emit(it) }
    }

    override suspend fun count(): Int = frontier.size

    override suspend fun countEdges(direction: HopDirection, edgeType: String): Int = coroutineScope {
        frontier.map { nid -> async(hopDispatcher) { hops(nid, direction, edgeType, needValue = false).size } }.awaitAll()
    }.sum()

    override suspend fun collectSubgraph(nodeType: String?): Subgraph {
        val nodes = coroutineScope {
            allVisitedIds.map { nid -> async(hopDispatcher) { engine.nodeAt(nid) } }.awaitAll()
        }.filterNotNull().let { all ->
            if (nodeType == null) all else all.filter { it.typeName() == nodeType }
        }
        return Subgraph(nodes, resolveHopEdges(allTraversedHops))
    }

    override suspend fun exhaustReachable(block: suspend TraversalBuilderLike<ID>.() -> Unit): Subgraph {
        val visited = mutableSetOf<NodeId>(); visited += frontier
        val allHops = mutableListOf<Hop>()
        var current = frontier.toSet()
        while (current.isNotEmpty()) {
            val sub = TraversalBuilder(engine, current, homeAdapter)
            sub.block()
            allHops += sub.traversedHops
            val next = sub.frontier - visited
            visited += next
            current = next
        }
        val nodes = coroutineScope {
            visited.map { nid -> async(hopDispatcher) { engine.nodeAt(nid) } }.awaitAll()
        }.filterNotNull()
        return Subgraph(nodes, resolveHopEdges(allHops))
    }

    override suspend fun detectCycle(block: suspend TraversalBuilderLike<ID>.() -> Unit): Boolean {
        val visited = mutableSetOf<NodeId>()
        for (start in frontier) {
            if (start !in visited && dfsCycle(start, visited, mutableSetOf(), block)) return true
        }
        return false
    }

    private suspend fun dfsCycle(
        nodeId: NodeId,
        visited: MutableSet<NodeId>,
        inStack: MutableSet<NodeId>,
        block: suspend TraversalBuilderLike<ID>.() -> Unit
    ): Boolean {
        visited += nodeId; inStack += nodeId
        val sub = TraversalBuilder(engine, setOf(nodeId), homeAdapter)
        sub.block()
        for (neighbor in sub.frontier) {
            if (neighbor in inStack) return true
            if (neighbor !in visited && dfsCycle(neighbor, visited, inStack, block)) return true
        }
        inStack -= nodeId
        return false
    }

    override fun paths(
        strategy: TraversalStrategy,
        direction: EdgeTraversalDirection,
        maxDepth: Int,
        edgeVisitor: (Path, EdgeLike<*, *>) -> Boolean,
        nodeEvaluator: (Path, NodeLike<*>) -> Evaluation
    ): Flow<Path> = flow {
        val origin = frontier.mapNotNull { nid -> engine.nodeAt(nid)?.let { nid to it } }
        when (strategy) {
            TraversalStrategy.DFS -> for ((nid, node) in origin)
                dfsLoop(Path(listOf(node), emptyList()), nid, nid, setOf(nid), direction, maxDepth, 0, edgeVisitor, nodeEvaluator)
            TraversalStrategy.BFS -> bfsLoop(origin, direction, maxDepth, edgeVisitor, nodeEvaluator)
        }
    }

    private suspend fun edgesFrom(fromNid: NodeId, direction: EdgeTraversalDirection): List<Hop> = when (direction) {
        EdgeTraversalDirection.OUT  -> engine.outAt(fromNid, null)
        EdgeTraversalDirection.IN   -> engine.inAt(fromNid, null)
        EdgeTraversalDirection.BOTH -> engine.outAt(fromNid, null) + engine.inAt(fromNid, null)
    }

    // headNid: NodeId of the last accepted node (currentPath.head); fromNid: physical position (may
    // differ when a node was EXCLUDE_AND_CONTINUE). depth counts hops. seen prevents re-processing a
    // neighbour via different edges within a level. Returns whether this subtree emitted any path —
    // the caller uses it to decide if an INCLUDE_AND_CONTINUE head is itself a natural terminal.
    private suspend fun FlowCollector<Path>.dfsLoop(
        currentPath: Path,
        headNid: NodeId,
        fromNid: NodeId,
        visited: Set<NodeId>,
        direction: EdgeTraversalDirection,
        maxDepth: Int,
        depth: Int,
        edgeVisitor: (Path, EdgeLike<*, *>) -> Boolean,
        nodeEvaluator: (Path, NodeLike<*>) -> Evaluation
    ): Boolean {
        if (depth >= maxDepth) return false
        val edges = edgesFrom(fromNid, direction)
        val seen = visited.toMutableSet()
        var emitted = false
        for (hop in edges) {
            if (!edgeVisitor(currentPath, hop.edge!!)) continue
            val nextNid = if (hop.fromId == fromNid) hop.toId else hop.fromId
            if (nextNid in seen) continue
            seen += nextNid
            val nextNode = engine.nodeAt(nextNid) ?: continue
            val eval = nodeEvaluator(currentPath, nextNode)
            val included = eval == Evaluation.INCLUDE_AND_CONTINUE || eval == Evaluation.INCLUDE_AND_PRUNE
            val extendedPath = if (included) {
                val edgePart = if (fromNid == headNid) listOf(hop.edge) else emptyList()
                Path(currentPath.nodes + nextNode, currentPath.edges + edgePart)
            } else currentPath
            val nextHeadNid = if (included) nextNid else headNid
            val nextDepth = depth + 1
            when {
                eval == Evaluation.INCLUDE_AND_PRUNE -> { emit(extendedPath); emitted = true }
                eval == Evaluation.INCLUDE_AND_CONTINUE && nextDepth >= maxDepth -> { emit(extendedPath); emitted = true }
                eval == Evaluation.INCLUDE_AND_CONTINUE -> {
                    val childEmitted = dfsLoop(extendedPath, nextHeadNid, nextNid, seen.toSet(), direction, maxDepth, nextDepth, edgeVisitor, nodeEvaluator)
                    if (!childEmitted) emit(extendedPath) // natural terminal: included head with no emitting expansion
                    emitted = true
                }
                eval == Evaluation.EXCLUDE_AND_CONTINUE && nextDepth < maxDepth ->
                    if (dfsLoop(extendedPath, nextHeadNid, nextNid, seen.toSet(), direction, maxDepth, nextDepth, edgeVisitor, nodeEvaluator)) emitted = true
                // else: EXCLUDE_AND_PRUNE, or EXCLUDE_AND_CONTINUE at cap → nothing
            }
        }
        return emitted
    }

    private suspend fun FlowCollector<Path>.bfsLoop(
        origin: List<Pair<NodeId, NodeLike<*>>>,
        direction: EdgeTraversalDirection,
        maxDepth: Int,
        edgeVisitor: (Path, EdgeLike<*, *>) -> Boolean,
        nodeEvaluator: (Path, NodeLike<*>) -> Evaluation
    ) {
        data class Entry(val path: Path, val headNid: NodeId, val fromNid: NodeId, val visited: Set<NodeId>, val depth: Int)
        val queue = ArrayDeque<Entry>()
        for ((nid, node) in origin) queue += Entry(Path(listOf(node), emptyList()), nid, nid, setOf(nid), 0)
        while (queue.isNotEmpty()) {
            val (currentPath, headNid, fromNid, visited, depth) = queue.removeFirst()
            if (depth >= maxDepth) continue // safety guard; with the enqueue guard below only fires for maxDepth == 0
            val edges = edgesFrom(fromNid, direction)
            var produced = false // this entry emitted a path or enqueued a continuation
            for (hop in edges) {
                if (!edgeVisitor(currentPath, hop.edge!!)) continue
                val nextNid = if (hop.fromId == fromNid) hop.toId else hop.fromId
                if (nextNid in visited) continue
                val nextNode = engine.nodeAt(nextNid) ?: continue
                val eval = nodeEvaluator(currentPath, nextNode)
                val included = eval == Evaluation.INCLUDE_AND_CONTINUE || eval == Evaluation.INCLUDE_AND_PRUNE
                val extendedPath = if (included) {
                    val edgePart = if (fromNid == headNid) listOf(hop.edge) else emptyList()
                    Path(currentPath.nodes + nextNode, currentPath.edges + edgePart)
                } else currentPath
                val nextHeadNid = if (included) nextNid else headNid
                val nextDepth = depth + 1
                if (eval == Evaluation.INCLUDE_AND_PRUNE ||
                    (eval == Evaluation.INCLUDE_AND_CONTINUE && nextDepth >= maxDepth)) { emit(extendedPath); produced = true }
                if (nextDepth < maxDepth && (eval == Evaluation.INCLUDE_AND_CONTINUE || eval == Evaluation.EXCLUDE_AND_CONTINUE)) {
                    queue += Entry(extendedPath, nextHeadNid, nextNid, visited + nextNid, nextDepth)
                    produced = true
                }
            }
            if (!produced && currentPath.nodes.size > 1) emit(currentPath) // natural terminal (excludes lone origin)
        }
    }

    override suspend fun checkReaches(targetId: ID, block: suspend TraversalBuilderLike<ID>.() -> Unit): Boolean {
        val target = homeAdapter.toNodeId(targetId)
        val visited = mutableSetOf<NodeId>()
        visited += frontier
        var current = frontier.toSet()
        while (current.isNotEmpty()) {
            val sub = TraversalBuilder(engine, current, homeAdapter)
            sub.block()
            val next = sub.frontier - visited
            if (target in next) return true
            visited += next
            current = next
        }
        return false
    }
}
