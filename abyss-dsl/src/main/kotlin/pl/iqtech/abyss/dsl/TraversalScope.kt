package pl.iqtech.abyss.dsl

import kotlinx.coroutines.flow.Flow
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike

// Public-facing DSL receiver for every traversal block (from, checkReaches, pathTo,
// exhaustReachable, detectCycle, hasTraversal). Wraps the raw TraversalBuilderLike contract
// TraversalBuilder implements (which must stay public — the implementer lives in a different
// Gradle module, abyss-graph) but keeps `raw` internal so ordinary DSL-block code can't reach
// addHop/addNodeHop/filterFrontierBy* directly — only the typed, reified sugar in Extensions.kt
// (public inline, @PublishedApi-visible, same pattern as AnnotationCache.kt) can. The constructor
// itself must stay public (not internal): TraversalBuilder's overrides construct TraversalScope in
// ordinary non-inline code across that same module boundary, which can't reach an internal
// constructor the way @PublishedApi lets inline bytecode reach an internal member.
//
// flushFrontierNodes/flushHopEdges/countEdges/collectSubgraph are plain pass-throughs, not hidden —
// they're already reasonably typed (or have a nullable-default typed overload) and some callers
// legitimately want the raw untyped result (e.g. a `from` block whose last expression is the cold
// Flow<NodeLike<*>>, collected by the caller after `from` returns).
class TraversalScope<ID>(@PublishedApi internal val raw: TraversalBuilderLike<ID>) {
    suspend fun count(): Int = raw.count()
    suspend fun countEdges(direction: HopDirection, edgeType: String): Int = raw.countEdges(direction, edgeType)
    suspend fun collectSubgraph(nodeType: String? = null, nodeTag: Short? = null): Subgraph = raw.collectSubgraph(nodeType, nodeTag)
    suspend fun flushFrontierNodes(): Flow<NodeLike<*>> = raw.flushFrontierNodes()
    suspend fun flushHopEdges(): Flow<EdgeLike<*, *>> = raw.flushHopEdges()
    suspend fun checkReaches(targetId: ID, block: suspend TraversalScope<ID>.() -> Unit): Boolean = raw.checkReaches(targetId, block)
    suspend fun pathTo(targetId: ID, block: suspend TraversalScope<ID>.() -> Unit): Path? = raw.pathTo(targetId, block)
    suspend fun exhaustReachable(block: suspend TraversalScope<ID>.() -> Unit): Subgraph = raw.exhaustReachable(block)
    suspend fun detectCycle(block: suspend TraversalScope<ID>.() -> Unit): Boolean = raw.detectCycle(block)
    fun paths(
        strategy: TraversalStrategy = TraversalStrategy.DFS,
        direction: EdgeTraversalDirection = EdgeTraversalDirection.BOTH,
        maxDepth: Int = Int.MAX_VALUE,
        edgeVisitor: (path: Path, edge: EdgeLike<*, *>) -> Boolean,
        nodeEvaluator: (path: Path, node: NodeLike<*>) -> Evaluation
    ): Flow<Path> = raw.paths(strategy, direction, maxDepth, edgeVisitor, nodeEvaluator)
}
