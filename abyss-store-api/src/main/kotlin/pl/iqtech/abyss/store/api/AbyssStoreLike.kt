package pl.iqtech.abyss.store.api

import arrow.core.Either
import kotlin.time.Duration

// An edge as the store returns it from a scan: the value plus BOTH endpoint NodeIds read straight from
// the PK columns, so a caller can rebuild the cache key without a per-schema adapter (the domain
// endpoint ids inside the value can't be turned back into tagged NodeIds untyped).
data class StoredEdge(val fromId: NodeId, val toId: NodeId, val edge: EdgeLike<*, *>, val remaining: Duration?)

// Stores are keyed by the self-describing NodeId (its bytes are the PK) and hold polymorphic
// NodeLike<*>/EdgeLike<*, *> values — one shared store backs every schema in a container, so there
// is no per-schema <ID> parameter or adapter here. The caller (typed facade) supplies the NodeId.
interface AbyssStoreLike {
    suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>>
    suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>>
    suspend fun loadEdges(fromId: NodeId): Either<AbyssError, List<StoredEdge>> = Either.Right(emptyList())
    suspend fun loadInEdges(toId: NodeId): Either<AbyssError, List<StoredEdge>> = Either.Right(emptyList())
    suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit>

    // Bulk-load path, deliberately independent of transaction(): commits ops in chunks of
    // `batchSize`, each chunk its own DB transaction, instead of one atomic transaction for the
    // whole list. Default just delegates to transaction() unchunked (correct, not faster) — only
    // YugabytePersistentStore overrides this with real JDBC batch commits.
    suspend fun batchTransaction(
        batchSize: Int = 1000,
        block: suspend AbyssStoreTransactionLike.() -> Unit
    ): Either<AbyssError, Unit> = transaction(block)
}

interface AbyssStoreTransactionLike {
    fun saveNode(id: NodeId, node: NodeLike<*>, tags: Set<String>)
    fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, tags: Set<String>)
    fun deleteNode(id: NodeId)
    fun deleteEdge(fromId: NodeId, toId: NodeId, type: String)
}

interface AbyssEphemeralStoreLike {
    suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>>
    suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>>
    suspend fun loadEdges(fromId: NodeId): Either<AbyssError, List<StoredEdge>> = Either.Right(emptyList())
    suspend fun loadInEdges(toId: NodeId): Either<AbyssError, List<StoredEdge>> = Either.Right(emptyList())
    suspend fun transaction(block: suspend AbyssEphemeralStoreTransactionLike.() -> Unit): Either<AbyssError, Unit>
}

interface AbyssEphemeralStoreTransactionLike {
    fun saveNode(id: NodeId, node: NodeLike<*>, ttl: Duration, tags: Set<String>)
    fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, ttl: Duration, tags: Set<String>)
    fun deleteNode(id: NodeId)
    fun deleteEdge(fromId: NodeId, toId: NodeId, type: String)
}
