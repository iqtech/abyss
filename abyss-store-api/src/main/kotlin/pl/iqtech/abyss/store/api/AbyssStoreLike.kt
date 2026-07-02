package pl.iqtech.abyss.store.api

import arrow.core.Either
import kotlin.time.Duration

interface AbyssStoreLike<ID> {
    suspend fun loadNode(id: ID): Either<AbyssError, Pair<NodeLike<ID>?, Duration?>>
    suspend fun loadEdge(fromId: ID, toId: ID, type: String): Either<AbyssError, Pair<SchemaEdgeLike<ID>?, Duration?>>
    suspend fun loadEdges(fromId: ID): Either<AbyssError, List<Pair<SchemaEdgeLike<ID>, Duration?>>> = Either.Right(emptyList())
    suspend fun loadInEdges(toId: ID): Either<AbyssError, List<Pair<SchemaEdgeLike<ID>, Duration?>>> = Either.Right(emptyList())
    suspend fun transaction(block: suspend AbyssStoreTransactionLike<ID>.() -> Unit): Either<AbyssError, Unit>
}

interface AbyssStoreTransactionLike<ID> {
    fun saveNode(node: NodeLike<ID>)
    fun saveEdge(edge: SchemaEdgeLike<ID>)
    fun deleteNode(id: ID)
    fun deleteEdge(fromId: ID, toId: ID, type: String)
}

interface AbyssEphemeralStoreLike<ID> {
    suspend fun loadNode(id: ID): Either<AbyssError, Pair<NodeLike<ID>?, Duration?>>
    suspend fun loadEdge(fromId: ID, toId: ID, type: String): Either<AbyssError, Pair<SchemaEdgeLike<ID>?, Duration?>>
    suspend fun loadEdges(fromId: ID): Either<AbyssError, List<Pair<SchemaEdgeLike<ID>, Duration?>>> = Either.Right(emptyList())
    suspend fun loadInEdges(toId: ID): Either<AbyssError, List<Pair<SchemaEdgeLike<ID>, Duration?>>> = Either.Right(emptyList())
    suspend fun transaction(block: suspend AbyssEphemeralStoreTransactionLike<ID>.() -> Unit): Either<AbyssError, Unit>
}

interface AbyssEphemeralStoreTransactionLike<ID> {
    fun saveNode(node: NodeLike<ID>, ttl: Duration)
    fun saveEdge(edge: SchemaEdgeLike<ID>, ttl: Duration)
    fun deleteNode(id: ID)
    fun deleteEdge(fromId: ID, toId: ID, type: String)
}
