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
    suspend fun filterFrontierByNode(nodeType: String, predicate: ((NodeLike) -> Boolean)? = null)
    suspend fun filterFrontierByOutEdgeTo(edgeType: String, toId: Uuid)
    suspend fun filterFrontierByOutEdgeToType(edgeType: String, nodeType: String)
    suspend fun filterFrontierByInEdgeFrom(edgeType: String, fromId: Uuid)
    suspend fun filterFrontierByInEdgeFromType(edgeType: String, nodeType: String)
    suspend fun flushFrontierNodes(): Flow<NodeLike>
    suspend fun collectSubgraph(nodeType: String? = null): Subgraph
    suspend fun checkReaches(targetId: Uuid, block: suspend TraversalBuilderLike.() -> Unit): Boolean
    suspend fun exhaustReachable(block: suspend TraversalBuilderLike.() -> Unit): Subgraph
    suspend fun detectCycle(block: suspend TraversalBuilderLike.() -> Unit): Boolean
}
