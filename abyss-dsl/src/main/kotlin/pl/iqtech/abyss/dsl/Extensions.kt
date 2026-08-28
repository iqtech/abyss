package pl.iqtech.abyss.dsl

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike

// AbyssEngineLike — typed lookups use star-projection receiver so member functions don't shadow them.
// id/fromId/toId are Any? to avoid a signature clash with the typed members.

@Suppress("UNCHECKED_CAST")
suspend inline fun <reified N : NodeLike<*>> AbyssEngineLike<*>.node(id: Any?): Either<AbyssError, N> =
    (this as AbyssEngineLike<Any?>).node(id as Any?)
        .flatMap { (it as? N)?.right() ?: AbyssError.NodeNotFound(id as Any).left() }

@Suppress("UNCHECKED_CAST")
suspend inline fun <reified E : EdgeLike<*, *>> AbyssEngineLike<*>.edge(fromId: Any?, toId: Any?): Either<AbyssError, E> {
    val type = E::class.serialName()
    return (this as AbyssEngineLike<Any?>).edge(fromId as Any?, toId as Any?, type)
        .flatMap { (it as? E)?.right() ?: AbyssError.EdgeNotFound(fromId as Any, toId as Any, type).left() }
}

@Suppress("UNCHECKED_CAST")
suspend inline fun <reified E : EdgeLike<*, *>> AbyssEngineLike<*>.edgeExists(fromId: Any?, toId: Any?): Either<AbyssError, Boolean> =
    (this as AbyssEngineLike<Any?>).edgeExists(fromId as Any?, toId as Any?, E::class.serialName())

@Suppress("UNCHECKED_CAST")
inline fun <reified E : EdgeLike<*, *>> AbyssEngineLike<*>.outEdges(nodeId: Any?, pageSize: Int = 100, includeEphemeral: Boolean = false): Flow<E> =
    (this as AbyssEngineLike<Any?>).outEdges(nodeId as Any?, E::class.serialName(), pageSize, includeEphemeral).filterIsInstance<E>()

@Suppress("UNCHECKED_CAST")
inline fun <reified E : EdgeLike<*, *>> AbyssEngineLike<*>.inEdges(nodeId: Any?, pageSize: Int = 100): Flow<E> =
    (this as AbyssEngineLike<Any?>).inEdges(nodeId as Any?, E::class.serialName(), pageSize).filterIsInstance<E>()

// AbyssTransactionLike

inline fun <ID, reified E : EdgeLike<ID, ID>> AbyssTransactionLike<ID>.removeEdge(fromId: ID, toId: ID) =
    removeEdge(fromId, toId, E::class.serialName())

fun <ID> AbyssTransactionLike<ID>.removeEdge(edge: EdgeLike<ID, ID>) =
    removeEdge(edge.fromId, edge.toId, edge::class.serialName())

// AbyssEphemeralTransactionLike

inline fun <ID, reified E : EdgeLike<ID, ID>> AbyssEphemeralTransactionLike<ID>.removeEdge(fromId: ID, toId: ID) =
    removeEdge(fromId, toId, E::class.serialName())

fun <ID> AbyssEphemeralTransactionLike<ID>.removeEdge(edge: EdgeLike<ID, ID>) =
    removeEdge(edge.fromId, edge.toId, edge::class.serialName())

// Cross-schema removeCrossEdge — no ID/adapter involved, so no unchecked cast needed (unlike node()/
// edge() above, which shadow a type-parameterized member).

inline fun <reified E : EdgeLike<*, *>> AbyssTransactionLike<*>.removeCrossEdge(fromId: Any?, toId: Any?) =
    removeCrossEdge(E::class, fromId, toId)

inline fun <reified E : EdgeLike<*, *>> AbyssEphemeralTransactionLike<*>.removeCrossEdge(fromId: Any?, toId: Any?) =
    removeCrossEdge(E::class, fromId, toId)

// TraversalScope
//
// Extensions that don't need ID as a value parameter use TraversalScope<*> receiver.
// This avoids a type-inference conflict when the receiver's ID is abstract (e.g., inside the
// block of a non-inline exhaustReachable/detectCycle call).  TraversalScope<ConcreteID>
// is always a subtype of TraversalScope<*>, so the extension resolves correctly.
//
// Every function here reaches through `raw` (TraversalScope's @PublishedApi internal property) to
// call the actual raw primitive on TraversalBuilderLike — `raw` is invisible to ordinary caller
// source outside abyss-dsl, so this file is the only place that can call addHop/addNodeHop/
// filterFrontierBy*/flushFrontierNodes/flushHopEdges/countEdges/collectSubgraph directly.

suspend inline fun <reified E : EdgeLike<*, *>> TraversalScope<*>.outgoing(includeEphemeral: Boolean = false) =
    raw.addHop(HopDirection.OUTGOING, E::class.serialName(), includeEphemeral = includeEphemeral)

suspend inline fun <reified E : EdgeLike<*, *>> TraversalScope<*>.incoming() =
    raw.addHop(HopDirection.INCOMING, E::class.serialName())

// Untyped/mixed hop — union of neighbors across every edge type (TODO 2.21's adjacency index gives
// outAt/inAt a real path for this; previously there was no index at all for a type == null hop).
suspend fun TraversalScope<*>.outgoingAny(includeEphemeral: Boolean = false) = raw.addHop(HopDirection.OUTGOING, null, includeEphemeral = includeEphemeral)
suspend fun TraversalScope<*>.incomingAny() = raw.addHop(HopDirection.INCOMING, null)

@JvmName("outgoingEdgePredicate")
@Suppress("UNCHECKED_CAST")
suspend inline fun <reified E : EdgeLike<*, *>> TraversalScope<*>.outgoing(includeEphemeral: Boolean = false, noinline predicate: (E) -> Boolean) =
    raw.addHop(HopDirection.OUTGOING, E::class.serialName(), { predicate(it as E) }, includeEphemeral)

@JvmName("incomingEdgePredicate")
@Suppress("UNCHECKED_CAST")
suspend inline fun <reified E : EdgeLike<*, *>> TraversalScope<*>.incoming(noinline predicate: (E) -> Boolean) =
    raw.addHop(HopDirection.INCOMING, E::class.serialName(), { predicate(it as E) })

@JvmName("outgoingNodePredicate")
@Suppress("UNCHECKED_CAST")
suspend inline fun <ID, reified E : EdgeLike<*, *>, reified N : NodeLike<*>> TraversalScope<ID>.outgoing(noinline predicate: (N) -> Boolean) =
    raw.addNodeHop(HopDirection.OUTGOING, E::class.serialName(), N::class.serialName(), N::class.typeTag(), { predicate(it as N) })

@JvmName("incomingNodePredicate")
@Suppress("UNCHECKED_CAST")
suspend inline fun <ID, reified E : EdgeLike<*, *>, reified N : NodeLike<*>> TraversalScope<ID>.incoming(noinline predicate: (N) -> Boolean) =
    raw.addNodeHop(HopDirection.INCOMING, E::class.serialName(), N::class.serialName(), N::class.typeTag(), { predicate(it as N) })

suspend inline fun <reified E : EdgeLike<*, *>> TraversalScope<*>.countEdges(direction: HopDirection = HopDirection.OUTGOING) =
    countEdges(direction, E::class.serialName())

suspend inline fun <reified N : NodeLike<*>> TraversalScope<*>.nodes() =
    raw.filterFrontierByNode(N::class.serialName(), N::class.typeTag(), null)

@Suppress("UNCHECKED_CAST")
suspend inline fun <reified N : NodeLike<*>> TraversalScope<*>.nodes(noinline filter: (N) -> Boolean) =
    raw.filterFrontierByNode(N::class.serialName(), N::class.typeTag(), { filter(it as N) })

suspend inline fun <reified E : EdgeLike<*, *>, ID> TraversalScope<ID>.hasOutgoing(toId: ID) =
    raw.filterFrontierByOutEdgeTo(E::class.serialName(), toId)

suspend inline fun <reified E : EdgeLike<*, *>, reified N : NodeLike<*>> TraversalScope<*>.hasOutgoing() =
    raw.filterFrontierByOutEdgeToType(
        E::class.serialName(),
        N::class.serialName(),
        N::class.typeTag()
    )

suspend inline fun <reified E : EdgeLike<*, *>, ID> TraversalScope<ID>.hasIncoming(fromId: ID) =
    raw.filterFrontierByInEdgeFrom(E::class.serialName(), fromId)

suspend inline fun <reified E : EdgeLike<*, *>, reified N : NodeLike<*>> TraversalScope<*>.hasIncoming() =
    raw.filterFrontierByInEdgeFromType(
        E::class.serialName(),
        N::class.serialName(),
        N::class.typeTag()
    )

suspend fun <ID> TraversalScope<ID>.hasTraversal(block: suspend TraversalScope<ID>.() -> Unit) =
    raw.filterFrontierByTraversal(block)

suspend inline fun <reified N : NodeLike<*>> TraversalScope<*>.collectNodes(): Flow<N> =
    flushFrontierNodes().filterIsInstance<N>()

suspend inline fun <reified E : EdgeLike<*, *>> TraversalScope<*>.collectEdges(): Flow<E> =
    flushHopEdges().filterIsInstance<E>()

suspend fun <ID> TraversalScope<ID>.reaches(targetId: ID, block: suspend TraversalScope<ID>.() -> Unit): Boolean = checkReaches(targetId, block)

suspend fun <ID> TraversalScope<ID>.allReachable(block: suspend TraversalScope<ID>.() -> Unit): Subgraph = exhaustReachable(block)

suspend fun <ID> TraversalScope<ID>.hasCycle(block: suspend TraversalScope<ID>.() -> Unit): Boolean = detectCycle(block)

// Narrow a raw result's heterogeneous nodes to one concrete type.
inline fun <reified T : NodeLike<*>> Subgraph.resolve(): List<T> = nodes.filterIsInstance<T>()
inline fun <reified T : NodeLike<*>> Path.resolve(): List<T> = nodes.filterIsInstance<T>()

suspend fun <ID> TraversalScope<ID>.subgraph(): Subgraph = collectSubgraph()

// Merges a stream of Paths into one Subgraph, deduping nodes by id and edges by the same
// (fromId, toId, type) key ensureSubgraph uses — a node/edge revisited across paths is kept once.
suspend fun Flow<Path>.toSubgraph(): Subgraph {
    val nodes = LinkedHashMap<Any?, NodeLike<*>>()
    val edges = LinkedHashMap<Triple<Any?, Any?, String>, EdgeLike<*, *>>()
    collect { path ->
        path.nodes.forEach { nodes[it.id] = it }
        path.edges.forEach { edges[Triple(it.fromId, it.toId, edgeType(it))] = it }
    }
    return Subgraph(nodes.values.toList(), edges.values.toList())
}

// Type-filtered subgraph: passes N's @TypeTag so the collector skips fetching visited nodes of other
// types (falls back to a @SerialName fetch only for tag-unresolved nodes).
suspend inline fun <reified N : NodeLike<*>> TraversalScope<*>.subgraphOf(): Subgraph =
    collectSubgraph(N::class.serialName(), N::class.typeTag())

// AbyssEngineLike — graph algorithms

private fun edgeType(e: EdgeLike<*, *>) = e::class.serialName()

// Idempotently ensure a described subgraph exists: walk the paths and create only the nodes/edges
// not already present, leaving existing ones untouched (create-if-missing, not overwrite). Each
// Path's edge[i] connects node[i]→node[i+1], so committing all missing pieces of a self-consistent
// path in one transaction satisfies the integrity check. Single-schema only — cross-schema edges
// live in the HomogeneousSchemaGraph/HeterogeneousSchemaGraph container, not here.
@Suppress("UNCHECKED_CAST")
suspend fun <ID> AbyssEngineLike<ID>.ensureSubgraph(
    vararg paths: Path,
    checkIntegrity: Boolean = true,
): Either<AbyssError, Unit> = either {
    val nodes = paths.flatMap { it.nodes }.associateBy { it.id } as Map<ID, NodeLike<ID>>
    val edges = paths.flatMap { it.edges }
        .distinctBy { Triple(it.fromId, it.toId, edgeType(it)) } as List<EdgeLike<ID, ID>>

    // ponytail: read-before-write TOCTOU window; acceptable for an idempotent ensure —
    // the store commit is the final arbiter. Tighten only if a concurrent-clobber bug shows up.
    val missingNodes = buildList { for (n in nodes.values) if (!nodeExists(n.id).bind()) add(n) }
    val missingEdges = buildList {
        for (e in edges) if (!edgeExists(e.fromId, e.toId, edgeType(e)).bind()) add(e)
    }

    transaction(checkIntegrity) {
        missingNodes.forEach { addNode(it) }
        missingEdges.forEach { addEdge(it) }
    }.bind()
}

// fable.md 3.2 / TODO 3.13: allNodeIds() eagerly materializes the *entire* key set (not a
// streaming/paginated scan) — this walk then holds it all again as remaining/visited sets. At
// README-cited scale (36k users × 500 nodes = 18M keys) that's a multi-GB memory profile. TODO 1.23's
// store-scan (scanNodeIds) is built, but deliberately not part of AbyssEngineLike<ID> (see
// AbyssGraphSchema.scanNodeIds), so this extension can't reach it — and it wouldn't help anyway:
// remaining/visited still need full-graph membership to know when a walk is done, regardless of
// where ids are sourced from. Not fixable without changing what connected-components requires in memory.
suspend fun <ID> AbyssEngineLike<ID>.connectedComponents(): List<Set<ID>> {
    val remaining = allNodeIds().toList().toMutableSet()
    val components = mutableListOf<Set<ID>>()
    while (remaining.isNotEmpty()) {
        val start = remaining.first()
        val visited = mutableSetOf(start)
        val queue = ArrayDeque<ID>().also { it += start }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            outEdges(id).collect { if (it.toId !in visited) { visited += it.toId; queue += it.toId } }
            inEdges(id).collect { if (it.fromId !in visited) { visited += it.fromId; queue += it.fromId } }
        }
        components += visited
        remaining -= visited
    }
    return components
}
