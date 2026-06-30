package pl.iqtech.abyss.graph.traversal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.SerialName
import pl.iqtech.abyss.dsl.AbyssEngineLike
import pl.iqtech.abyss.dsl.HopDirection
import pl.iqtech.abyss.dsl.Subgraph
import pl.iqtech.abyss.dsl.TraversalBuilderLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import kotlin.reflect.full.findAnnotation
import kotlin.uuid.Uuid

class TraversalBuilder(
    private val engine: AbyssEngineLike,
    startFrontier: Set<Uuid>
) : TraversalBuilderLike {

    var frontier: Set<Uuid> = startFrontier
        private set

    private val allVisitedIds: MutableSet<Uuid> = startFrontier.toMutableSet()
    private val allTraversedEdges: MutableList<EdgeLike> = mutableListOf()
    internal val traversedEdges: List<EdgeLike> get() = allTraversedEdges

    override suspend fun addHop(direction: HopDirection, edgeType: String, edgePredicate: ((EdgeLike) -> Boolean)?) {
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

    override suspend fun addNodeHop(direction: HopDirection, edgeType: String, nodeType: String, nodePredicate: ((NodeLike) -> Boolean)?) {
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

    override suspend fun filterFrontierByNode(nodeType: String, predicate: ((NodeLike) -> Boolean)?) {
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

    override suspend fun filterFrontierByOutEdgeTo(edgeType: String, toId: Uuid) {
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

    override suspend fun filterFrontierByInEdgeFrom(edgeType: String, fromId: Uuid) {
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

    override suspend fun flushFrontierNodes(): Flow<NodeLike> = flow {
        for (id in frontier) engine.node(id).getOrNull()?.let { emit(it) }
    }

    private suspend fun collectNodes(nodeType: String): Flow<NodeLike> {
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

    private suspend fun collectNodes(nodeType: String, filter: (NodeLike) -> Boolean): Flow<NodeLike> =
        collectNodes(nodeType).filter { filter(it) }

    override suspend fun collectSubgraph(nodeType: String?): Subgraph {
        val nodes = coroutineScope {
            allVisitedIds.map { id -> async(Dispatchers.IO) { engine.node(id).getOrNull() } }.awaitAll()
        }.filterNotNull().let { all ->
            if (nodeType == null) all
            else all.filter { it::class.findAnnotation<SerialName>()?.value == nodeType }
        }
        return Subgraph(nodes, allTraversedEdges.toList())
    }

    override suspend fun exhaustReachable(block: suspend TraversalBuilderLike.() -> Unit): Subgraph {
        val visited = mutableSetOf<Uuid>(); visited += frontier
        val allEdges = mutableListOf<EdgeLike>()
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

    override suspend fun detectCycle(block: suspend TraversalBuilderLike.() -> Unit): Boolean {
        val visited = mutableSetOf<Uuid>()
        for (start in frontier) {
            if (start !in visited && dfsCycle(start, visited, mutableSetOf(), block)) return true
        }
        return false
    }

    private suspend fun dfsCycle(
        nodeId: Uuid,
        visited: MutableSet<Uuid>,
        inStack: MutableSet<Uuid>,
        block: suspend TraversalBuilderLike.() -> Unit
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

    override suspend fun checkReaches(targetId: Uuid, block: suspend TraversalBuilderLike.() -> Unit): Boolean {
        val visited = mutableSetOf<Uuid>()
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
