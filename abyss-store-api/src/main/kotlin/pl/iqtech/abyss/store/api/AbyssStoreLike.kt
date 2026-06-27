package pl.iqtech.abyss.store.api

import arrow.core.Either
import java.util.UUID
import kotlin.time.Duration

interface AbyssStoreLike {
    suspend fun loadNode(id: UUID): Either<AbyssError, NodeLike?>
    suspend fun loadEdge(fromId: UUID, toId: UUID, type: String): Either<AbyssError, EdgeLike?>
    suspend fun loadEdges(fromId: UUID): Either<AbyssError, List<EdgeLike>> = Either.Right(emptyList())
    suspend fun loadInEdges(toId: UUID): Either<AbyssError, List<EdgeLike>> = Either.Right(emptyList())
    suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit>
}

interface AbyssStoreTransactionLike {
    fun saveNode(node: NodeLike, ttl: Duration? = null)
    fun saveEdge(edge: EdgeLike, ttl: Duration? = null)
    fun deleteNode(id: UUID)
    fun deleteEdge(fromId: UUID, toId: UUID, type: String)
}
