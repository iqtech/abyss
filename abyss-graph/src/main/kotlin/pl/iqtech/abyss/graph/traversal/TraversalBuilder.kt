package pl.iqtech.abyss.graph.traversal

import kotlinx.coroutines.Dispatchers
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
import java.util.UUID
import kotlin.reflect.full.findAnnotation

class TraversalBuilder(
    private val engine: AbyssEngineLike,
    startFrontier: Set<UUID>
) : TraversalBuilderLike {

    var frontier: Set<UUID> = startFrontier
        private set

    override suspend fun addHop(direction: HopDirection, edgeType: String, edgePredicate: ((EdgeLike) -> Boolean)?) {
        val next = mutableSetOf<UUID>()
        for (nodeId in frontier) {
            val edges = when (direction) {
                HopDirection.OUTGOING -> engine.outEdges(nodeId, edgeType).toList()
                HopDirection.INCOMING -> engine.inEdges(nodeId, edgeType).toList()
            }
            for (edge in edges) {
                if (edgePredicate != null && !edgePredicate(edge)) continue
                next += when (direction) {
                    HopDirection.OUTGOING -> edge.toId
                    HopDirection.INCOMING -> edge.fromId
                }
            }
        }
        frontier = next
    }

    override suspend fun addNodeHop(direction: HopDirection, edgeType: String, nodeType: String, nodePredicate: ((NodeLike) -> Boolean)?) {
        val next = mutableSetOf<UUID>()
        for (nodeId in frontier) {
            val edges = when (direction) {
                HopDirection.OUTGOING -> engine.outEdges(nodeId, edgeType).toList()
                HopDirection.INCOMING -> engine.inEdges(nodeId, edgeType).toList()
            }
            for (edge in edges) {
                val endId = when (direction) {
                    HopDirection.OUTGOING -> edge.toId
                    HopDirection.INCOMING -> edge.fromId
                }
                val node = engine.node(endId).getOrNull() ?: continue
                if (node::class.findAnnotation<SerialName>()?.value != nodeType) continue
                if (nodePredicate != null && !nodePredicate(node)) continue
                next += endId
            }
        }
        frontier = next
    }

    override suspend fun collectNodes(nodeType: String, filter: ((NodeLike) -> Boolean)?): Flow<NodeLike> {
        val snapshot = frontier.toSet()
        return flow {
            for (id in snapshot) {
                val node = withContext(Dispatchers.IO) { engine.node(id).getOrNull() } ?: continue
                if (node::class.findAnnotation<SerialName>()?.value != nodeType) continue
                if (filter != null && !filter(node)) continue
                emit(node)
            }
        }
    }

    override suspend fun checkReaches(targetId: UUID, block: suspend TraversalBuilderLike.() -> Unit): Boolean {
        val visited = mutableSetOf<UUID>()
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
