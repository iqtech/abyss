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

// Raw (heterogeneous) result types: a walk may cross schemas, so nodes/edges can't commit to one
// domain ID type. Nodes are star-typed NodeLike<*>; callers narrow with resolve<T>() (filterIsInstance).
data class Path(
    val nodes: List<NodeLike<*>>,
    val edges: List<EdgeLike<*, *>>
) {
    val depth: Int get() = nodes.size - 1
    val head: NodeLike<*> get() = nodes.last()

    fun toEitherList(): List<Either<EdgeLike<*, *>, NodeLike<*>>> = buildList {
        nodes.forEachIndexed { i, node ->
            add(Either.Right(node))
            if (i < edges.size) add(Either.Left(edges[i]))
        }
    }
}

data class Subgraph(val nodes: List<NodeLike<*>>, val edges: List<EdgeLike<*, *>>)

// ID is the "home" schema's domain type — used only by the id-valued conveniences (checkReaches,
// filterFrontierByOutEdgeTo, …). The traversal frontier itself is NodeId-based internally, so a
// walk can leave the home schema across a cross-edge.
interface TraversalBuilderLike<ID> {
    suspend fun addHop(direction: HopDirection, edgeType: String, edgePredicate: ((EdgeLike<*, *>) -> Boolean)? = null)
    suspend fun addNodeHop(direction: HopDirection, edgeType: String, nodeType: String, nodePredicate: ((NodeLike<*>) -> Boolean)? = null)
    suspend fun filterFrontierByNode(nodeType: String, predicate: ((NodeLike<*>) -> Boolean)? = null)
    suspend fun filterFrontierByOutEdgeTo(edgeType: String, toId: ID)
    suspend fun filterFrontierByOutEdgeToType(edgeType: String, nodeType: String)
    suspend fun filterFrontierByInEdgeFrom(edgeType: String, fromId: ID)
    suspend fun filterFrontierByInEdgeFromType(edgeType: String, nodeType: String)
    suspend fun filterFrontierByTraversal(block: suspend TraversalBuilderLike<ID>.() -> Unit)
    suspend fun flushFrontierNodes(): Flow<NodeLike<*>>
    /** Terminal: number of distinct nodes in the current frontier (no node materialization). */
    suspend fun count(): Int
    /** Terminal: number of [edgeType] edges from the frontier in [direction] (no edge value or node materialization). */
    suspend fun countEdges(direction: HopDirection, edgeType: String): Int
    suspend fun collectSubgraph(nodeType: String? = null): Subgraph
    suspend fun checkReaches(targetId: ID, block: suspend TraversalBuilderLike<ID>.() -> Unit): Boolean
    suspend fun exhaustReachable(block: suspend TraversalBuilderLike<ID>.() -> Unit): Subgraph
    suspend fun detectCycle(block: suspend TraversalBuilderLike<ID>.() -> Unit): Boolean
    /**
     * Walks the graph emitting one [Path] per maximal accepted path — a path whose included head
     * cannot be extended. Emission fires when that head is:
     * - **pruned** — evaluated `INCLUDE_AND_PRUNE`;
     * - at the **depth cap** — `INCLUDE_AND_CONTINUE` reached at `maxDepth`;
     * - a **natural terminal** — `INCLUDE_AND_CONTINUE` below the cap whose expansion follows no
     *   further edge (edges exhausted, all neighbours visited, edges rejected, or all continuations
     *   dead-end).
     *
     * Intermediate prefixes are never emitted; a lone origin (single-node path) is never emitted.
     */
    fun paths(
        strategy: TraversalStrategy = TraversalStrategy.DFS,
        direction: EdgeTraversalDirection = EdgeTraversalDirection.BOTH,
        maxDepth: Int = Int.MAX_VALUE,
        edgeVisitor: (path: Path, edge: EdgeLike<*, *>) -> Boolean,
        nodeEvaluator: (path: Path, node: NodeLike<*>) -> Evaluation
    ): Flow<Path>
}
