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

    override suspend fun addHop(direction: HopDirection, edgeType: String, edgePredicate: ((EdgeLike) -> Boolean)?) {
        frontier = coroutineScope {
            frontier.map { nodeId ->
                async {
                    val edges = when (direction) {
                        HopDirection.OUTGOING -> engine.outEdges(nodeId, edgeType).toList()
                        HopDirection.INCOMING -> engine.inEdges(nodeId, edgeType).toList()
                    }
                    edges
                        .filter { edgePredicate == null || edgePredicate(it) }
                        .map { if (direction == HopDirection.OUTGOING) it.toId else it.fromId }
                }
            }.awaitAll().flatten().toSet()
        }
    }

    override suspend fun addNodeHop(direction: HopDirection, edgeType: String, nodeType: String, nodePredicate: ((NodeLike) -> Boolean)?) {
        frontier = coroutineScope {
            frontier.map { nodeId ->
                async {
                    val edges = when (direction) {
                        HopDirection.OUTGOING -> engine.outEdges(nodeId, edgeType).toList()
                        HopDirection.INCOMING -> engine.inEdges(nodeId, edgeType).toList()
                    }
                    edges.mapNotNull { edge ->
                        val endId = if (direction == HopDirection.OUTGOING) edge.toId else edge.fromId
                        val node = engine.node(endId).getOrNull() ?: return@mapNotNull null
                        if (node::class.findAnnotation<SerialName>()?.value != nodeType) return@mapNotNull null
                        if (nodePredicate != null && !nodePredicate(node)) return@mapNotNull null
                        endId
                    }
                }
            }.awaitAll().flatten().toSet()
        }
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
