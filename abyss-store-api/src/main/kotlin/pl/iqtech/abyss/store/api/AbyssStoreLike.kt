package pl.iqtech.abyss.store.api

import arrow.core.Either
import kotlin.uuid.Uuid
import kotlin.time.Duration

interface AbyssStoreLike {
    suspend fun loadNode(id: Uuid): Either<AbyssError, Pair<NodeLike?, Duration?>>
    suspend fun loadEdge(fromId: Uuid, toId: Uuid, type: String): Either<AbyssError, Pair<EdgeLike?, Duration?>>
    suspend fun loadEdges(fromId: Uuid): Either<AbyssError, List<Pair<EdgeLike, Duration?>>> = Either.Right(emptyList())
    suspend fun loadInEdges(toId: Uuid): Either<AbyssError, List<Pair<EdgeLike, Duration?>>> = Either.Right(emptyList())
    suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit>
}

interface AbyssStoreTransactionLike {
    fun saveNode(node: NodeLike)
    fun saveEdge(edge: EdgeLike)
    fun deleteNode(id: Uuid)
    fun deleteEdge(fromId: Uuid, toId: Uuid, type: String)
}

interface AbyssEphemeralStoreLike {
    suspend fun loadNode(id: Uuid): Either<AbyssError, Pair<NodeLike?, Duration?>>
    suspend fun loadEdge(fromId: Uuid, toId: Uuid, type: String): Either<AbyssError, Pair<EdgeLike?, Duration?>>
    suspend fun loadEdges(fromId: Uuid): Either<AbyssError, List<Pair<EdgeLike, Duration?>>> = Either.Right(emptyList())
    suspend fun loadInEdges(toId: Uuid): Either<AbyssError, List<Pair<EdgeLike, Duration?>>> = Either.Right(emptyList())
    suspend fun transaction(block: suspend AbyssEphemeralStoreTransactionLike.() -> Unit): Either<AbyssError, Unit>
}

interface AbyssEphemeralStoreTransactionLike {
    fun saveNode(node: NodeLike, ttl: Duration)
    fun saveEdge(edge: EdgeLike, ttl: Duration)
    fun deleteNode(id: Uuid)
    fun deleteEdge(fromId: Uuid, toId: Uuid, type: String)
}
