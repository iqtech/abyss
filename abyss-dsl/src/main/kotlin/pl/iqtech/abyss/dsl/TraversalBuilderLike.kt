package pl.iqtech.abyss.dsl

import kotlinx.coroutines.flow.Flow
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import java.util.UUID

enum class HopDirection { OUTGOING, INCOMING }

interface TraversalBuilderLike {
    fun addHop(direction: HopDirection, edgeType: String, edgePredicate: ((EdgeLike) -> Boolean)? = null)
    fun addNodeHop(direction: HopDirection, edgeType: String, nodeType: String, nodePredicate: ((NodeLike) -> Boolean)? = null)
    fun collectNodes(nodeType: String, filter: ((NodeLike) -> Boolean)? = null): Flow<NodeLike>
    fun checkReaches(targetId: UUID, block: TraversalBuilderLike.() -> Unit): Boolean
}
