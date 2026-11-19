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
//
// This is the raw, impl-facing contract TraversalBuilder implements — it must stay public because
// the implementer lives in a different Gradle module (abyss-graph). Callers never see this type
// directly: every nested-block parameter below takes a TraversalScope<ID> receiver instead, which
// hides the raw addHop/addNodeHop/filterFrontierBy*/flush*/countEdges/collectSubgraph primitives
// behind the typed sugar in Extensions.kt. See TraversalScope.kt.
interface TraversalBuilderLike<ID> {
    // includeEphemeral applies to OUTGOING hops only (ephemeral edges are outgoing-only, store-only —
    // TODO 1.27); when set, the hop also includes ephemeral out-edges read from the ephemeral store.
    suspend fun addHop(direction: HopDirection, edgeType: String?, edgePredicate: ((EdgeLike<*, *>) -> Boolean)? = null, includeEphemeral: Boolean = false)
    // nodeTag is the wanted node type's @TypeTag — lets the impl match against the tag carried on the
    // adjacency index / frontier without fetching each node; null falls back to a @SerialName fetch.
    suspend fun addNodeHop(direction: HopDirection, edgeType: String, nodeType: String, nodeTag: Short? = null, nodePredicate: ((NodeLike<*>) -> Boolean)? = null)
    suspend fun filterFrontierByNode(nodeType: String, nodeTag: Short? = null, predicate: ((NodeLike<*>) -> Boolean)? = null)
    suspend fun filterFrontierByOutEdgeTo(edgeType: String, toId: ID)
    suspend fun filterFrontierByOutEdgeToType(edgeType: String, nodeType: String, nodeTag: Short? = null)
    suspend fun filterFrontierByInEdgeFrom(edgeType: String, fromId: ID)
    suspend fun filterFrontierByInEdgeFromType(edgeType: String, nodeType: String, nodeTag: Short? = null)
    suspend fun filterFrontierByTraversal(block: suspend TraversalScope<ID>.() -> Unit)
    suspend fun flushFrontierNodes(): Flow<NodeLike<*>>
    /** Terminal: edges of the most recent hop whose target survived any later frontier filter. */
    suspend fun flushHopEdges(): Flow<EdgeLike<*, *>>
    /** Terminal: number of distinct nodes in the current frontier (no node materialization). */
    suspend fun count(): Int
    /** Terminal: number of [edgeType] edges from the frontier in [direction] (no edge value or node materialization). */
    suspend fun countEdges(direction: HopDirection, edgeType: String): Int
    suspend fun collectSubgraph(nodeType: String? = null, nodeTag: Short? = null): Subgraph
    suspend fun checkReaches(targetId: ID, block: suspend TraversalScope<ID>.() -> Unit): Boolean
    /**
     * BFS to [targetId] following [block]'s hops, returning the first (fewest-hops, not
     * weighted-shortest — this model has no edge weights) path found, or `null` if unreachable.
     * Mirrors [checkReaches]'s contract: a [targetId] already in the starting frontier does not
     * count as reached (only a hop-away match does).
     */
    suspend fun pathTo(targetId: ID, block: suspend TraversalScope<ID>.() -> Unit): Path?
    suspend fun exhaustReachable(block: suspend TraversalScope<ID>.() -> Unit): Subgraph
    suspend fun detectCycle(block: suspend TraversalScope<ID>.() -> Unit): Boolean
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
    /**
     * Streams the chain of [edgeType] edges from the single origin in [direction], one node per link,
     * in walk order (INCOMING walks emit in reverse chain order). The origin itself is not emitted.
     * Lazy: a consumer that stops early stops the walk.
     *
     * - Ends cleanly where no further [edgeType] edge exists, or after [maxLength] links are followed.
     * - More than one [edgeType] edge in [direction] from a node (a fork), or a link back to an already
     *   walked node (a cycle), throws [IllegalStateException] — a chain that forks or loops is corrupt data.
     *   Every node up to and including the forking node (or the cycle's last new node) is emitted first.
     * - A link to an absent node (broken link) is followed but not emitted, so emissions can be fewer
     *   than links; [maxLength] counts links.
     * - Exactly one origin, else [IllegalArgumentException].
     * - [includeEphemeral] (OUTGOING only, ignored for INCOMING, as in [addHop]) also follows ephemeral
     *   links, at one ephemeral-store read per link. Ephemeral edges are outgoing-only (TODO 1.13/1.27),
     *   so an INCOMING walk never sees them and ends early at an ephemeral link, without error.
     *
     * The flow is cold and usually collected after `from {}` returns, so its errors (fork, cycle,
     * [IllegalArgumentException], a store outage) reach the collector as exceptions, not as `Either.Left`.
     */
    fun walkChain(edgeType: String, direction: HopDirection, maxLength: Int = Int.MAX_VALUE, includeEphemeral: Boolean = false): Flow<NodeLike<*>>
}
