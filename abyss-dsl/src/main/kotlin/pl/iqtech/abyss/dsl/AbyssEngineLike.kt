package pl.iqtech.abyss.dsl

import arrow.core.Either
import kotlinx.coroutines.flow.Flow
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import kotlin.time.Duration

interface AbyssEngineLike<ID> {

    suspend fun node(id: ID): Either<AbyssError, NodeLike<ID>>
    suspend fun edge(fromId: ID, toId: ID, type: String): Either<AbyssError, EdgeLike<ID>>
    suspend fun nodeExists(id: ID): Either<AbyssError, Boolean>
    suspend fun edgeExists(fromId: ID, toId: ID, type: String): Either<AbyssError, Boolean>

    fun outEdges(nodeId: ID, pageSize: Int = 100): Flow<EdgeLike<ID>>
    fun outEdges(nodeId: ID, type: String, pageSize: Int = 100): Flow<EdgeLike<ID>>
    fun inEdges(nodeId: ID, pageSize: Int = 100): Flow<EdgeLike<ID>>
    fun inEdges(nodeId: ID, type: String, pageSize: Int = 100): Flow<EdgeLike<ID>>

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
}

interface AbyssTransactionLike<ID> {
    fun addNode(node: NodeLike<ID>)
    fun removeNode(id: ID)
    fun addEdge(edge: EdgeLike<ID>)
    fun removeEdge(fromId: ID, toId: ID, type: String)
    suspend fun modifyNode(id: ID, transform: (NodeLike<ID>?) -> NodeLike<ID>)
    suspend fun modifyEdge(fromId: ID, toId: ID, type: String, transform: (EdgeLike<ID>?) -> EdgeLike<ID>)
}

interface AbyssEphemeralTransactionLike<ID> {
    fun addNode(node: NodeLike<ID>)
    fun removeNode(id: ID)
    fun addEdge(edge: EdgeLike<ID>)
    fun removeEdge(fromId: ID, toId: ID, type: String)
    suspend fun modifyNode(id: ID, transform: (NodeLike<ID>?) -> NodeLike<ID>)
    suspend fun modifyEdge(fromId: ID, toId: ID, type: String, transform: (EdgeLike<ID>?) -> EdgeLike<ID>)
}
