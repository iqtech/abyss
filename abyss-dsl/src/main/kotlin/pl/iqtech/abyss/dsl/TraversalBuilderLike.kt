package pl.iqtech.abyss.dsl

import kotlinx.coroutines.flow.Flow
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import kotlin.uuid.Uuid

enum class HopDirection { OUTGOING, INCOMING }

data class Subgraph(val nodes: List<NodeLike>, val edges: List<EdgeLike>)

interface TraversalBuilderLike {
    suspend fun addHop(direction: HopDirection, edgeType: String, edgePredicate: ((EdgeLike) -> Boolean)? = null)
    suspend fun addNodeHop(direction: HopDirection, edgeType: String, nodeType: String, nodePredicate: ((NodeLike) -> Boolean)? = null)
    suspend fun collectNodes(nodeType: String): Flow<NodeLike>
    suspend fun collectNodes(nodeType: String, filter: (NodeLike) -> Boolean): Flow<NodeLike>
    suspend fun collectSubgraph(nodeType: String? = null): Subgraph
    suspend fun checkReaches(targetId: Uuid, block: suspend TraversalBuilderLike.() -> Unit): Boolean
}
