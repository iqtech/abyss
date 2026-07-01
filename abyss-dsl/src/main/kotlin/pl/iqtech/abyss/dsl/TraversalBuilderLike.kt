package pl.iqtech.abyss.dsl

import arrow.core.Either
import kotlinx.coroutines.flow.Flow
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike

enum class HopDirection { OUTGOING, INCOMING }

enum class TraversalStrategy { DFS, BFS }

enum class EdgeTraversalDirection { IN, OUT, BOTH }

enum class Evaluation {
    INCLUDE_AND_CONTINUE,
    INCLUDE_AND_PRUNE,
    EXCLUDE_AND_CONTINUE,
    EXCLUDE_AND_PRUNE
}

data class Path<ID>(
    val nodes: List<NodeLike<ID>>,
    val edges: List<EdgeLike<ID>>
) {
    val depth: Int get() = nodes.size - 1
    val head: NodeLike<ID> get() = nodes.last()

    fun toEitherList(): List<Either<EdgeLike<ID>, NodeLike<ID>>> = buildList {
        nodes.forEachIndexed { i, node ->
            add(Either.Right(node))
            if (i < edges.size) add(Either.Left(edges[i]))
        }
    }
}

data class Subgraph<ID>(val nodes: List<NodeLike<ID>>, val edges: List<EdgeLike<ID>>)

interface TraversalBuilderLike<ID> {
    suspend fun addHop(direction: HopDirection, edgeType: String, edgePredicate: ((EdgeLike<ID>) -> Boolean)? = null)
    suspend fun addNodeHop(direction: HopDirection, edgeType: String, nodeType: String, nodePredicate: ((NodeLike<ID>) -> Boolean)? = null)
    suspend fun filterFrontierByNode(nodeType: String, predicate: ((NodeLike<ID>) -> Boolean)? = null)
    suspend fun filterFrontierByOutEdgeTo(edgeType: String, toId: ID)
    suspend fun filterFrontierByOutEdgeToType(edgeType: String, nodeType: String)
    suspend fun filterFrontierByInEdgeFrom(edgeType: String, fromId: ID)
    suspend fun filterFrontierByInEdgeFromType(edgeType: String, nodeType: String)
    suspend fun filterFrontierByTraversal(block: suspend TraversalBuilderLike<ID>.() -> Unit)
    suspend fun flushFrontierNodes(): Flow<NodeLike<ID>>
    suspend fun collectSubgraph(nodeType: String? = null): Subgraph<ID>
    suspend fun checkReaches(targetId: ID, block: suspend TraversalBuilderLike<ID>.() -> Unit): Boolean
    suspend fun exhaustReachable(block: suspend TraversalBuilderLike<ID>.() -> Unit): Subgraph<ID>
    suspend fun detectCycle(block: suspend TraversalBuilderLike<ID>.() -> Unit): Boolean
    fun paths(
        strategy: TraversalStrategy = TraversalStrategy.DFS,
        direction: EdgeTraversalDirection = EdgeTraversalDirection.BOTH,
        maxDepth: Int = Int.MAX_VALUE,
        edgeVisitor: (path: Path<ID>, edge: EdgeLike<ID>) -> Boolean,
        nodeEvaluator: (path: Path<ID>, node: NodeLike<ID>) -> Evaluation
    ): Flow<Path<ID>>
}
