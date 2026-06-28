package pl.iqtech.abyss.graph.traversal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
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

    override suspend fun collectNodes(nodeType: String): Flow<NodeLike> {
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

    override suspend fun collectNodes(nodeType: String, filter: (NodeLike) -> Boolean): Flow<NodeLike> = flow {
        for (id in frontier) {
            val node = withContext(Dispatchers.IO) { engine.node(id).getOrNull() } ?: continue
            if (node::class.findAnnotation<SerialName>()?.value != nodeType) continue
            if (!filter(node)) continue
            emit(node)
        }
    }

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
