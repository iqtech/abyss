package pl.iqtech.abyss.dsl

import arrow.core.Either
import kotlinx.coroutines.flow.Flow
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import kotlin.reflect.KClass
import kotlin.time.Duration

interface AbyssEngineLike<ID> {

    suspend fun node(id: ID): Either<AbyssError, NodeLike<ID>>
    suspend fun edge(fromId: ID, toId: ID, type: String): Either<AbyssError, EdgeLike<ID, ID>>
    suspend fun nodeExists(id: ID): Either<AbyssError, Boolean>
    suspend fun edgeExists(fromId: ID, toId: ID, type: String): Either<AbyssError, Boolean>

    fun outEdges(nodeId: ID, pageSize: Int = 100): Flow<EdgeLike<ID, ID>>
    fun outEdges(nodeId: ID, type: String, pageSize: Int = 100): Flow<EdgeLike<ID, ID>>
    fun inEdges(nodeId: ID, pageSize: Int = 100): Flow<EdgeLike<ID, ID>>
    fun inEdges(nodeId: ID, type: String, pageSize: Int = 100): Flow<EdgeLike<ID, ID>>

    fun allNodeIds(): Flow<ID>

    suspend fun <T> from(nodeId: ID, block: suspend TraversalBuilderLike<ID>.() -> T): Either<AbyssError, T>

    suspend fun transaction(
        checkIntegrity: Boolean = true,
        block: suspend AbyssTransactionLike<ID>.() -> Unit
    ): Either<AbyssError, Unit>

    suspend fun ephemeral(
        ttl: Duration,
        checkIntegrity: Boolean = true,
        block: suspend AbyssEphemeralTransactionLike<ID>.() -> Unit
    ): Either<AbyssError, Unit>

    // Bulk-load path: commits ops in independent chunks of `batchSize` instead of one atomic
    // transaction. Trades whole-call atomicity for throughput and bounded per-DB-transaction size —
    // on partial failure, chunks already committed stay committed. Intended for populating huge
    // graphs (e.g. ~1M elements), where saveNode/saveEdge's upsert semantics make retrying the
    // whole call after a failure safe.
    suspend fun batchTransaction(
        batchSize: Int = 1000,
        checkIntegrity: Boolean = true,
        block: suspend AbyssTransactionLike<ID>.() -> Unit
    ): Either<AbyssError, Unit>
}

interface AbyssTransactionLike<ID> {
    fun addNode(node: NodeLike<ID>, tags: Set<String> = emptySet())
    fun removeNode(id: ID)
    fun addEdge(edge: EdgeLike<ID, ID>, tags: Set<String> = emptySet())
    fun removeEdge(fromId: ID, toId: ID, type: String)
    // Cross-schema: requires a Homogeneous/HeterogeneousSchemaGraph container and edge::class to
    // carry @CrossSchemaEdge — see AbyssGraphSchema.transaction's crossEdgeCheck.
    fun addCrossEdge(edge: EdgeLike<*, *>, tags: Set<String> = emptySet())
    fun removeCrossEdge(edgeClass: KClass<out EdgeLike<*, *>>, fromId: Any?, toId: Any?)
    suspend fun modifyNode(id: ID, tags: Set<String> = emptySet(), transform: (NodeLike<ID>?) -> NodeLike<ID>)
    suspend fun modifyEdge(fromId: ID, toId: ID, type: String, tags: Set<String> = emptySet(), transform: (EdgeLike<ID, ID>?) -> EdgeLike<ID, ID>)
}

interface AbyssEphemeralTransactionLike<ID> {
    fun addNode(node: NodeLike<ID>, tags: Set<String> = emptySet())
    fun removeNode(id: ID)
    fun addEdge(edge: EdgeLike<ID, ID>, tags: Set<String> = emptySet())
    fun removeEdge(fromId: ID, toId: ID, type: String)
    fun addCrossEdge(edge: EdgeLike<*, *>, tags: Set<String> = emptySet())
    fun removeCrossEdge(edgeClass: KClass<out EdgeLike<*, *>>, fromId: Any?, toId: Any?)
    suspend fun modifyNode(id: ID, tags: Set<String> = emptySet(), transform: (NodeLike<ID>?) -> NodeLike<ID>)
    suspend fun modifyEdge(fromId: ID, toId: ID, type: String, tags: Set<String> = emptySet(), transform: (EdgeLike<ID, ID>?) -> EdgeLike<ID, ID>)
}
