package pl.iqtech.abyss.dsl

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.SerialName
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.SchemaEdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import kotlin.reflect.full.findAnnotation

// AbyssEngineLike — typed lookups use star-projection receiver so member functions don't shadow them.
// id/fromId/toId are Any? to avoid a signature clash with the typed members.

@Suppress("UNCHECKED_CAST")
suspend inline fun <reified N : NodeLike<*>> AbyssEngineLike<*>.node(id: Any?): Either<AbyssError, N> =
    (this as AbyssEngineLike<Any?>).node(id as Any?)
        .flatMap { (it as? N)?.right() ?: AbyssError.NodeNotFound(id as Any).left() }

@Suppress("UNCHECKED_CAST")
suspend inline fun <reified E : SchemaEdgeLike<*>> AbyssEngineLike<*>.edge(fromId: Any?, toId: Any?): Either<AbyssError, E> {
    val type = E::class.findAnnotation<SerialName>()!!.value
    return (this as AbyssEngineLike<Any?>).edge(fromId as Any?, toId as Any?, type)
        .flatMap { (it as? E)?.right() ?: AbyssError.EdgeNotFound(fromId as Any, toId as Any, type).left() }
}

@Suppress("UNCHECKED_CAST")
suspend inline fun <reified E : SchemaEdgeLike<*>> AbyssEngineLike<*>.edgeExists(fromId: Any?, toId: Any?): Either<AbyssError, Boolean> =
    (this as AbyssEngineLike<Any?>).edgeExists(fromId as Any?, toId as Any?, E::class.findAnnotation<SerialName>()!!.value)

@Suppress("UNCHECKED_CAST")
inline fun <reified E : SchemaEdgeLike<*>> AbyssEngineLike<*>.outEdges(nodeId: Any?, pageSize: Int = 100): Flow<E> =
    (this as AbyssEngineLike<Any?>).outEdges(nodeId as Any?, E::class.findAnnotation<SerialName>()!!.value, pageSize).filterIsInstance<E>()

@Suppress("UNCHECKED_CAST")
inline fun <reified E : SchemaEdgeLike<*>> AbyssEngineLike<*>.inEdges(nodeId: Any?, pageSize: Int = 100): Flow<E> =
    (this as AbyssEngineLike<Any?>).inEdges(nodeId as Any?, E::class.findAnnotation<SerialName>()!!.value, pageSize).filterIsInstance<E>()

// AbyssTransactionLike

inline fun <ID, reified E : SchemaEdgeLike<ID>> AbyssTransactionLike<ID>.removeEdge(fromId: ID, toId: ID) =
    removeEdge(fromId, toId, E::class.findAnnotation<SerialName>()!!.value)

fun <ID> AbyssTransactionLike<ID>.removeEdge(edge: SchemaEdgeLike<ID>) =
    removeEdge(edge.fromId, edge.toId, edge::class.findAnnotation<SerialName>()!!.value)

// AbyssEphemeralTransactionLike

inline fun <ID, reified E : SchemaEdgeLike<ID>> AbyssEphemeralTransactionLike<ID>.removeEdge(fromId: ID, toId: ID) =
    removeEdge(fromId, toId, E::class.findAnnotation<SerialName>()!!.value)

fun <ID> AbyssEphemeralTransactionLike<ID>.removeEdge(edge: SchemaEdgeLike<ID>) =
    removeEdge(edge.fromId, edge.toId, edge::class.findAnnotation<SerialName>()!!.value)

// TraversalBuilderLike
//
// Extensions that don't need ID as a value parameter use TraversalBuilderLike<*> receiver.
// This avoids a type-inference conflict when the receiver's ID is abstract (e.g., inside the
// block of a non-inline exhaustReachable/detectCycle call).  TraversalBuilderLike<ConcreteID>
// is always a subtype of TraversalBuilderLike<*>, so the extension resolves correctly.

suspend inline fun <reified E : EdgeLike<*, *>> TraversalBuilderLike<*>.outgoing() =
    addHop(HopDirection.OUTGOING, E::class.findAnnotation<SerialName>()!!.value)

suspend inline fun <reified E : EdgeLike<*, *>> TraversalBuilderLike<*>.incoming() =
    addHop(HopDirection.INCOMING, E::class.findAnnotation<SerialName>()!!.value)

@JvmName("outgoingEdgePredicate")
@Suppress("UNCHECKED_CAST")
suspend inline fun <reified E : EdgeLike<*, *>> TraversalBuilderLike<*>.outgoing(noinline predicate: (E) -> Boolean) =
    addHop(HopDirection.OUTGOING, E::class.findAnnotation<SerialName>()!!.value, { predicate(it as E) })

@JvmName("incomingEdgePredicate")
@Suppress("UNCHECKED_CAST")
suspend inline fun <reified E : EdgeLike<*, *>> TraversalBuilderLike<*>.incoming(noinline predicate: (E) -> Boolean) =
    addHop(HopDirection.INCOMING, E::class.findAnnotation<SerialName>()!!.value, { predicate(it as E) })

@JvmName("outgoingNodePredicate")
@Suppress("UNCHECKED_CAST")
suspend inline fun <ID, reified E : EdgeLike<*, *>, reified N : NodeLike<*>> TraversalBuilderLike<ID>.outgoing(noinline predicate: (N) -> Boolean) =
    addNodeHop(HopDirection.OUTGOING, E::class.findAnnotation<SerialName>()!!.value, N::class.findAnnotation<SerialName>()!!.value, { predicate(it as N) })

@JvmName("incomingNodePredicate")
@Suppress("UNCHECKED_CAST")
suspend inline fun <ID, reified E : EdgeLike<*, *>, reified N : NodeLike<*>> TraversalBuilderLike<ID>.incoming(noinline predicate: (N) -> Boolean) =
    addNodeHop(HopDirection.INCOMING, E::class.findAnnotation<SerialName>()!!.value, N::class.findAnnotation<SerialName>()!!.value, { predicate(it as N) })

suspend inline fun <reified E : EdgeLike<*, *>> TraversalBuilderLike<*>.countEdges(direction: HopDirection = HopDirection.OUTGOING) =
    countEdges(direction, E::class.findAnnotation<SerialName>()!!.value)

suspend inline fun <reified N : NodeLike<*>> TraversalBuilderLike<*>.nodes() =
    filterFrontierByNode(N::class.findAnnotation<SerialName>()!!.value, null)

@Suppress("UNCHECKED_CAST")
suspend inline fun <reified N : NodeLike<*>> TraversalBuilderLike<*>.nodes(noinline filter: (N) -> Boolean) =
    filterFrontierByNode(N::class.findAnnotation<SerialName>()!!.value, { filter(it as N) })

suspend inline fun <reified E : EdgeLike<*, *>, ID> TraversalBuilderLike<ID>.hasOutgoing(toId: ID) =
    filterFrontierByOutEdgeTo(E::class.findAnnotation<SerialName>()!!.value, toId)

suspend inline fun <reified E : EdgeLike<*, *>, reified N : NodeLike<*>> TraversalBuilderLike<*>.hasOutgoing() =
    filterFrontierByOutEdgeToType(
        E::class.findAnnotation<SerialName>()!!.value,
        N::class.findAnnotation<SerialName>()!!.value
    )

suspend inline fun <reified E : EdgeLike<*, *>, ID> TraversalBuilderLike<ID>.hasIncoming(fromId: ID) =
    filterFrontierByInEdgeFrom(E::class.findAnnotation<SerialName>()!!.value, fromId)

suspend inline fun <reified E : EdgeLike<*, *>, reified N : NodeLike<*>> TraversalBuilderLike<*>.hasIncoming() =
    filterFrontierByInEdgeFromType(
        E::class.findAnnotation<SerialName>()!!.value,
        N::class.findAnnotation<SerialName>()!!.value
    )

suspend fun <ID> TraversalBuilderLike<ID>.hasTraversal(block: suspend TraversalBuilderLike<ID>.() -> Unit) =
    filterFrontierByTraversal(block)

suspend inline fun <reified N : NodeLike<*>> TraversalBuilderLike<*>.collectNodes(): Flow<N> =
    flushFrontierNodes().filterIsInstance<N>()

suspend fun <ID> TraversalBuilderLike<ID>.reaches(targetId: ID, block: suspend TraversalBuilderLike<ID>.() -> Unit): Boolean = checkReaches(targetId, block)

suspend fun <ID> TraversalBuilderLike<ID>.allReachable(block: suspend TraversalBuilderLike<ID>.() -> Unit): Subgraph = exhaustReachable(block)

suspend fun <ID> TraversalBuilderLike<ID>.hasCycle(block: suspend TraversalBuilderLike<ID>.() -> Unit): Boolean = detectCycle(block)

// Narrow a raw result's heterogeneous nodes to one concrete type.
inline fun <reified T : NodeLike<*>> Subgraph.resolve(): List<T> = nodes.filterIsInstance<T>()
inline fun <reified T : NodeLike<*>> Path.resolve(): List<T> = nodes.filterIsInstance<T>()

suspend fun <ID> TraversalBuilderLike<ID>.subgraph(): Subgraph = collectSubgraph()

// AbyssEngineLike — graph algorithms

private fun edgeType(e: EdgeLike<*, *>) = e::class.findAnnotation<SerialName>()!!.value

// Idempotently ensure a described subgraph exists: walk the paths and create only the nodes/edges
// not already present, leaving existing ones untouched (create-if-missing, not overwrite). Each
// Path's edge[i] connects node[i]→node[i+1], so committing all missing pieces of a self-consistent
// path in one transaction satisfies the integrity check. Single-schema only — cross-schema edges
// live in the AbyssGraph container, not here.
@Suppress("UNCHECKED_CAST")
suspend fun <ID> AbyssEngineLike<ID>.ensureSubgraph(
    vararg paths: Path,
    checkIntegrity: Boolean = true,
): Either<AbyssError, Unit> = either {
    val nodes = paths.flatMap { it.nodes }.associateBy { it.id } as Map<ID, NodeLike<ID>>
    val edges = paths.flatMap { it.edges }
        .distinctBy { Triple(it.fromId, it.toId, edgeType(it)) } as List<SchemaEdgeLike<ID>>

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
