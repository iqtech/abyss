package pl.iqtech.abyss.dsl

import kotlinx.coroutines.flow.Flow
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import java.util.UUID

enum class HopDirection { OUTGOING, INCOMING }

interface TraversalBuilderLike {
    suspend fun addHop(direction: HopDirection, edgeType: String, edgePredicate: ((EdgeLike) -> Boolean)? = null)
    suspend fun addNodeHop(direction: HopDirection, edgeType: String, nodeType: String, nodePredicate: ((NodeLike) -> Boolean)? = null)
    suspend fun collectNodes(nodeType: String, filter: ((NodeLike) -> Boolean)? = null): Flow<NodeLike>
    suspend fun checkReaches(targetId: UUID, block: suspend TraversalBuilderLike.() -> Unit): Boolean
}
