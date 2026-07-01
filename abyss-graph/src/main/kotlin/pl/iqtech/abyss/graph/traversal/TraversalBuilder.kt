package pl.iqtech.abyss.graph.traversal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.SerialName
import pl.iqtech.abyss.dsl.AbyssEngineLike
import pl.iqtech.abyss.dsl.Evaluation
import pl.iqtech.abyss.dsl.HopDirection
import pl.iqtech.abyss.dsl.Path
import pl.iqtech.abyss.dsl.Subgraph
import pl.iqtech.abyss.dsl.EdgeTraversalDirection
import pl.iqtech.abyss.dsl.TraversalBuilderLike
import pl.iqtech.abyss.dsl.TraversalStrategy
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import kotlin.reflect.full.findAnnotation

class TraversalBuilder<ID>(
    private val engine: AbyssEngineLike<ID>,
    startFrontier: Set<ID>
) : TraversalBuilderLike<ID> {

    var frontier: Set<ID> = startFrontier
        private set

    private val allVisitedIds: MutableSet<ID> = startFrontier.toMutableSet()
    private val allTraversedEdges: MutableList<EdgeLike<ID>> = mutableListOf()
    internal val traversedEdges: List<EdgeLike<ID>> get() = allTraversedEdges

    override suspend fun addHop(direction: HopDirection, edgeType: String, edgePredicate: ((EdgeLike<ID>) -> Boolean)?) {
        val hopEdges = coroutineScope {
            frontier.map { nodeId ->
                async {
                    val edges = when (direction) {
                        HopDirection.OUTGOING -> engine.outEdges(nodeId, edgeType).toList()
                        HopDirection.INCOMING -> engine.inEdges(nodeId, edgeType).toList()
                    }
                    edges.filter { edgePredicate == null || edgePredicate(it) }
                }
            }.awaitAll().flatten()
        }
        allTraversedEdges += hopEdges
        frontier = hopEdges.map { if (direction == HopDirection.OUTGOING) it.toId else it.fromId }.toSet()
        allVisitedIds += frontier
    }

    override suspend fun addNodeHop(direction: HopDirection, edgeType: String, nodeType: String, nodePredicate: ((NodeLike<ID>) -> Boolean)?) {
        val hopEdges = coroutineScope {
            frontier.map { nodeId ->
                async {
                    val edges = when (direction) {
                        HopDirection.OUTGOING -> engine.outEdges(nodeId, edgeType).toList()
                        HopDirection.INCOMING -> engine.inEdges(nodeId, edgeType).toList()
                    }
                    edges.filter { edge ->
                        val endId = if (direction == HopDirection.OUTGOING) edge.toId else edge.fromId
                        val node = engine.node(endId).getOrNull() ?: return@filter false
                        if (node::class.findAnnotation<SerialName>()?.value != nodeType) return@filter false
                        nodePredicate == null || nodePredicate(node)
                    }
                }
            }.awaitAll().flatten()
        }
        allTraversedEdges += hopEdges
        frontier = hopEdges.map { if (direction == HopDirection.OUTGOING) it.toId else it.fromId }.toSet()
        allVisitedIds += frontier
    }

    override suspend fun filterFrontierByNode(nodeType: String, predicate: ((NodeLike<ID>) -> Boolean)?) {
        val matchingIds = coroutineScope {
            frontier.map { id ->
                async(Dispatchers.IO) {
                    val node = engine.node(id).getOrNull() ?: return@async null
                    if (node::class.findAnnotation<SerialName>()?.value != nodeType) return@async null
                    if (predicate != null && !predicate(node)) return@async null
                    id
                }
            }.awaitAll()
        }.filterNotNull().toSet()
        allVisitedIds -= (frontier - matchingIds)
        frontier = matchingIds
    }

    override suspend fun filterFrontierByOutEdgeTo(edgeType: String, toId: ID) {
        val matching = coroutineScope {
            frontier.map { id ->
                async(Dispatchers.IO) {
                    if (engine.outEdges(id, edgeType).toList().any { it.toId == toId }) id else null
                }
            }.awaitAll()
        }.filterNotNull().toSet()
        allVisitedIds -= (frontier - matching)
        frontier = matching
    }

    override suspend fun filterFrontierByOutEdgeToType(edgeType: String, nodeType: String) {
        val matching = coroutineScope {
            frontier.map { id ->
                async(Dispatchers.IO) {
                    val hasMatch = engine.outEdges(id, edgeType).toList().any { edge ->
                        engine.node(edge.toId).getOrNull()
                            ?.let { it::class.findAnnotation<SerialName>()?.value == nodeType } == true
                    }
                    if (hasMatch) id else null
                }
            }.awaitAll()
        }.filterNotNull().toSet()
        allVisitedIds -= (frontier - matching)
        frontier = matching
    }

    override suspend fun filterFrontierByInEdgeFrom(edgeType: String, fromId: ID) {
        val matching = coroutineScope {
            frontier.map { id ->
                async(Dispatchers.IO) {
                    if (engine.inEdges(id, edgeType).toList().any { it.fromId == fromId }) id else null
                }
            }.awaitAll()
        }.filterNotNull().toSet()
        allVisitedIds -= (frontier - matching)
        frontier = matching
    }

    override suspend fun filterFrontierByInEdgeFromType(edgeType: String, nodeType: String) {
        val matching = coroutineScope {
            frontier.map { id ->
                async(Dispatchers.IO) {
                    val hasMatch = engine.inEdges(id, edgeType).toList().any { edge ->
                        engine.node(edge.fromId).getOrNull()
                            ?.let { it::class.findAnnotation<SerialName>()?.value == nodeType } == true
                    }
                    if (hasMatch) id else null
                }
            }.awaitAll()
        }.filterNotNull().toSet()
        allVisitedIds -= (frontier - matching)
        frontier = matching
    }

    override suspend fun filterFrontierByTraversal(block: suspend TraversalBuilderLike<ID>.() -> Unit) {
        val matching = coroutineScope {
            frontier.map { id ->
                async {
                    val sub = TraversalBuilder(engine, setOf(id))
                    sub.block()
                    if (sub.frontier.isNotEmpty()) id else null
                }
            }.awaitAll()
        }.filterNotNull().toSet()
        allVisitedIds -= (frontier - matching)
        frontier = matching
    }

    override suspend fun flushFrontierNodes(): Flow<NodeLike<ID>> = flow {
        for (id in frontier) engine.node(id).getOrNull()?.let { emit(it) }
    }

    @Suppress("unused")
    private suspend fun collectNodes(nodeType: String): Flow<NodeLike<ID>> {
        val nodes = coroutineScope {
            frontier.map { id -> async(Dispatchers.IO) { engine.node(id).getOrNull() } }.awaitAll()
        }
        return flow {
            for (node in nodes) {
                if (node == null) continue
                if (node::class.findAnnotation<SerialName>()?.value != nodeType) continue
                emit(node)
            }
        }
    }

    override suspend fun collectSubgraph(nodeType: String?): Subgraph<ID> {
        val nodes = coroutineScope {
            allVisitedIds.map { id -> async(Dispatchers.IO) { engine.node(id).getOrNull() } }.awaitAll()
        }.filterNotNull().let { all ->
            if (nodeType == null) all
            else all.filter { it::class.findAnnotation<SerialName>()?.value == nodeType }
        }
        return Subgraph(nodes, allTraversedEdges.toList())
    }

    override suspend fun exhaustReachable(block: suspend TraversalBuilderLike<ID>.() -> Unit): Subgraph<ID> {
        val visited = mutableSetOf<ID>(); visited += frontier
        val allEdges = mutableListOf<EdgeLike<ID>>()
        var current = frontier.toSet()
        while (current.isNotEmpty()) {
            val sub = TraversalBuilder(engine, current)
            sub.block()
            allEdges += sub.traversedEdges
            val next = sub.frontier - visited
            visited += next
            current = next
        }
        val nodes = coroutineScope {
            visited.map { id -> async(Dispatchers.IO) { engine.node(id).getOrNull() } }.awaitAll()
        }.filterNotNull()
        return Subgraph(nodes, allEdges)
    }

    override suspend fun detectCycle(block: suspend TraversalBuilderLike<ID>.() -> Unit): Boolean {
        val visited = mutableSetOf<ID>()
        for (start in frontier) {
            if (start !in visited && dfsCycle(start, visited, mutableSetOf(), block)) return true
        }
        return false
    }

    private suspend fun dfsCycle(
        nodeId: ID,
        visited: MutableSet<ID>,
        inStack: MutableSet<ID>,
        block: suspend TraversalBuilderLike<ID>.() -> Unit
    ): Boolean {
        visited += nodeId; inStack += nodeId
        val sub = TraversalBuilder(engine, setOf(nodeId))
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
        edgeVisitor: (Path<ID>, EdgeLike<ID>) -> Boolean,
        nodeEvaluator: (Path<ID>, NodeLike<ID>) -> Evaluation
    ): Flow<Path<ID>> = flow {
        val origin = frontier.mapNotNull { engine.node(it).getOrNull() }
        when (strategy) {
            TraversalStrategy.DFS -> for (node in origin)
                dfsLoop(Path(listOf(node), emptyList()), node.id, setOf(node.id), direction, maxDepth, 0, edgeVisitor, nodeEvaluator)
            TraversalStrategy.BFS -> bfsLoop(origin, direction, maxDepth, edgeVisitor, nodeEvaluator)
        }
    }

    private suspend fun edgesFrom(fromId: ID, direction: EdgeTraversalDirection): List<EdgeLike<ID>> = when (direction) {
        EdgeTraversalDirection.OUT  -> engine.outEdges(fromId).toList()
        EdgeTraversalDirection.IN   -> engine.inEdges(fromId).toList()
        EdgeTraversalDirection.BOTH -> engine.outEdges(fromId).toList() + engine.inEdges(fromId).toList()
    }

    // fromId: physical traversal position (may differ from currentPath.head when a node was EXCLUDE_AND_CONTINUE)
    // depth:  hop count (not path depth) — counts all hops including through excluded nodes
    // seen:   mutable within each call level to prevent re-processing the same neighbour via different edges
    private suspend fun FlowCollector<Path<ID>>.dfsLoop(
        currentPath: Path<ID>,
        fromId: ID,
        visited: Set<ID>,
        direction: EdgeTraversalDirection,
        maxDepth: Int,
        depth: Int,
        edgeVisitor: (Path<ID>, EdgeLike<ID>) -> Boolean,
        nodeEvaluator: (Path<ID>, NodeLike<ID>) -> Evaluation
    ) {
        if (depth >= maxDepth) return
        val edges = edgesFrom(fromId, direction)
        val seen = visited.toMutableSet()
        for (edge in edges) {
            if (!edgeVisitor(currentPath, edge)) continue
            val nextId = if (edge.fromId == fromId) edge.toId else edge.fromId
            if (nextId in seen) continue
            seen += nextId  // mark before eval to skip if the same node appears via another edge
            val nextNode = engine.node(nextId).getOrNull() ?: continue
            val eval = nodeEvaluator(currentPath, nextNode)
            val included = eval == Evaluation.INCLUDE_AND_CONTINUE || eval == Evaluation.INCLUDE_AND_PRUNE
            val extendedPath = if (included) {
                // only add edge when we're travelling directly from the last accepted node
                val edgePart = if (fromId == currentPath.head.id) listOf(edge) else emptyList()
                Path(currentPath.nodes + nextNode, currentPath.edges + edgePart)
            } else currentPath
            val nextDepth = depth + 1
            if (eval == Evaluation.INCLUDE_AND_PRUNE ||
                (eval == Evaluation.INCLUDE_AND_CONTINUE && nextDepth >= maxDepth)) emit(extendedPath)
            if (nextDepth < maxDepth && (eval == Evaluation.INCLUDE_AND_CONTINUE || eval == Evaluation.EXCLUDE_AND_CONTINUE))
                dfsLoop(extendedPath, nextId, seen.toSet(), direction, maxDepth, nextDepth, edgeVisitor, nodeEvaluator)
        }
    }

    // BFS queue carries (path, fromId, visited, depth) per entry so paths remain independent
    private suspend fun FlowCollector<Path<ID>>.bfsLoop(
        origin: List<NodeLike<ID>>,
        direction: EdgeTraversalDirection,
        maxDepth: Int,
        edgeVisitor: (Path<ID>, EdgeLike<ID>) -> Boolean,
        nodeEvaluator: (Path<ID>, NodeLike<ID>) -> Evaluation
    ) {
        data class Entry(val path: Path<ID>, val fromId: ID, val visited: Set<ID>, val depth: Int)
        val queue = ArrayDeque<Entry>()
        for (node in origin) queue += Entry(Path(listOf(node), emptyList()), node.id, setOf(node.id), 0)
        while (queue.isNotEmpty()) {
            val (currentPath, fromId, visited, depth) = queue.removeFirst()
            if (depth >= maxDepth) continue
            val edges = edgesFrom(fromId, direction)
            for (edge in edges) {
                if (!edgeVisitor(currentPath, edge)) continue
                val nextId = if (edge.fromId == fromId) edge.toId else edge.fromId
                if (nextId in visited) continue  // only skip nodes already on this path (prevents cycles)
                val nextNode = engine.node(nextId).getOrNull() ?: continue
                val eval = nodeEvaluator(currentPath, nextNode)
                val included = eval == Evaluation.INCLUDE_AND_CONTINUE || eval == Evaluation.INCLUDE_AND_PRUNE
                val extendedPath = if (included) {
                    val edgePart = if (fromId == currentPath.head.id) listOf(edge) else emptyList()
                    Path(currentPath.nodes + nextNode, currentPath.edges + edgePart)
                } else currentPath
                val nextDepth = depth + 1
                if (eval == Evaluation.INCLUDE_AND_PRUNE ||
                    (eval == Evaluation.INCLUDE_AND_CONTINUE && nextDepth >= maxDepth)) emit(extendedPath)
                if (eval == Evaluation.INCLUDE_AND_CONTINUE || eval == Evaluation.EXCLUDE_AND_CONTINUE)
                    queue += Entry(extendedPath, nextId, visited + nextId, nextDepth)
            }
        }
    }

    override suspend fun checkReaches(targetId: ID, block: suspend TraversalBuilderLike<ID>.() -> Unit): Boolean {
        val visited = mutableSetOf<ID>()
        visited += frontier
        var current = frontier.toSet()
        while (current.isNotEmpty()) {
            val sub = TraversalBuilder(engine, current)
            sub.block()
            val next = sub.frontier - visited
            if (targetId in next) return true
            visited += next
            current = next
        }
        return false
    }
}
