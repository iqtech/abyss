package pl.iqtech.abyss.dsl

import arrow.core.Either
import kotlinx.coroutines.flow.Flow
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import java.util.UUID
import kotlin.time.Duration

interface AbyssEngineLike {

    suspend fun node(id: UUID): Either<AbyssError, NodeLike>
    suspend fun edge(fromId: UUID, toId: UUID, type: String): Either<AbyssError, EdgeLike>
    suspend fun nodeExists(id: UUID): Either<AbyssError, Boolean>
    suspend fun edgeExists(fromId: UUID, toId: UUID, type: String): Either<AbyssError, Boolean>

    fun outEdges(nodeId: UUID, pageSize: Int = 100): Flow<EdgeLike>
    fun outEdges(nodeId: UUID, type: String, pageSize: Int = 100): Flow<EdgeLike>
    fun inEdges(nodeId: UUID, pageSize: Int = 100): Flow<EdgeLike>
    fun inEdges(nodeId: UUID, type: String, pageSize: Int = 100): Flow<EdgeLike>

    suspend fun <T> from(nodeId: UUID, block: suspend TraversalBuilderLike.() -> T): Either<AbyssError, T>

    suspend fun transaction(block: suspend AbyssTransactionLike.() -> Unit): Either<AbyssError, Unit>
}

interface AbyssTransactionLike {
    fun addNode(node: NodeLike, ttl: Duration? = null)
    fun removeNode(id: UUID)

    fun addEdge(edge: EdgeLike, ttl: Duration? = null)
    fun removeEdge(fromId: UUID, toId: UUID, type: String)
}
