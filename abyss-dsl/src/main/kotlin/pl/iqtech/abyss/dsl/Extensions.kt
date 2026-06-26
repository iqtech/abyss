package pl.iqtech.abyss.dsl

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.serialization.SerialName
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import java.util.UUID
import kotlin.reflect.full.findAnnotation

// AbyssEngineLike

suspend inline fun <reified N : NodeLike> AbyssEngineLike.node(id: UUID): Either<AbyssError, N> =
    node(id).flatMap { (it as? N)?.right() ?: AbyssError.NodeNotFound(id).left() }

suspend inline fun <reified E : EdgeLike> AbyssEngineLike.edge(fromId: UUID, toId: UUID): Either<AbyssError, E> {
    val type = E::class.findAnnotation<SerialName>()!!.value
    return edge(fromId, toId, type).flatMap { (it as? E)?.right() ?: AbyssError.EdgeNotFound(fromId, toId, type).left() }
}

suspend inline fun <reified E : EdgeLike> AbyssEngineLike.edgeExists(fromId: UUID, toId: UUID): Either<AbyssError, Boolean> =
    edgeExists(fromId, toId, E::class.findAnnotation<SerialName>()!!.value)

inline fun <reified E : EdgeLike> AbyssEngineLike.outEdges(nodeId: UUID, pageSize: Int = 100): Flow<E> =
    outEdges(nodeId, E::class.findAnnotation<SerialName>()!!.value, pageSize).filterIsInstance<E>()

inline fun <reified E : EdgeLike> AbyssEngineLike.inEdges(nodeId: UUID, pageSize: Int = 100): Flow<E> =
    inEdges(nodeId, E::class.findAnnotation<SerialName>()!!.value, pageSize).filterIsInstance<E>()

// AbyssTransactionLike

inline fun <reified E : EdgeLike> AbyssTransactionLike.removeEdge(fromId: UUID, toId: UUID) =
    removeEdge(fromId, toId, E::class.findAnnotation<SerialName>()!!.value)

// TraversalBuilderLike

inline fun <reified E : EdgeLike> TraversalBuilderLike.outgoing() =
    addHop(HopDirection.OUTGOING, E::class.findAnnotation<SerialName>()!!.value)

inline fun <reified E : EdgeLike> TraversalBuilderLike.incoming() =
    addHop(HopDirection.INCOMING, E::class.findAnnotation<SerialName>()!!.value)

@JvmName("outgoingEdgePredicate")
inline fun <reified E : EdgeLike> TraversalBuilderLike.outgoing(noinline predicate: (E) -> Boolean) =
    addHop(HopDirection.OUTGOING, E::class.findAnnotation<SerialName>()!!.value, { predicate(it as E) })

@JvmName("incomingEdgePredicate")
inline fun <reified E : EdgeLike> TraversalBuilderLike.incoming(noinline predicate: (E) -> Boolean) =
    addHop(HopDirection.INCOMING, E::class.findAnnotation<SerialName>()!!.value, { predicate(it as E) })

@JvmName("outgoingNodePredicate")
inline fun <reified E : EdgeLike, reified N : NodeLike> TraversalBuilderLike.outgoing(noinline predicate: (N) -> Boolean) =
    addNodeHop(HopDirection.OUTGOING, E::class.findAnnotation<SerialName>()!!.value, N::class.findAnnotation<SerialName>()!!.value, { predicate(it as N) })

@JvmName("incomingNodePredicate")
inline fun <reified E : EdgeLike, reified N : NodeLike> TraversalBuilderLike.incoming(noinline predicate: (N) -> Boolean) =
    addNodeHop(HopDirection.INCOMING, E::class.findAnnotation<SerialName>()!!.value, N::class.findAnnotation<SerialName>()!!.value, { predicate(it as N) })

inline fun <reified N : NodeLike> TraversalBuilderLike.nodes(noinline filter: ((N) -> Boolean)? = null): Flow<N> =
    collectNodes(N::class.findAnnotation<SerialName>()!!.value, filter?.let { f -> { f(it as N) } }).filterIsInstance<N>()

fun TraversalBuilderLike.reaches(targetId: UUID, block: TraversalBuilderLike.() -> Unit): Boolean = checkReaches(targetId, block)
