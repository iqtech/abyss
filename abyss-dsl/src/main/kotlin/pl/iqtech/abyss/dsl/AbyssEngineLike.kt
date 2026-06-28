package pl.iqtech.abyss.dsl

import arrow.core.Either
import kotlinx.coroutines.flow.Flow
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import kotlin.uuid.Uuid
import kotlin.time.Duration

interface AbyssEngineLike {

    suspend fun node(id: Uuid): Either<AbyssError, NodeLike>
    suspend fun edge(fromId: Uuid, toId: Uuid, type: String): Either<AbyssError, EdgeLike>
    suspend fun nodeExists(id: Uuid): Either<AbyssError, Boolean>
    suspend fun edgeExists(fromId: Uuid, toId: Uuid, type: String): Either<AbyssError, Boolean>

    fun outEdges(nodeId: Uuid, pageSize: Int = 100): Flow<EdgeLike>
    fun outEdges(nodeId: Uuid, type: String, pageSize: Int = 100): Flow<EdgeLike>
    fun inEdges(nodeId: Uuid, pageSize: Int = 100): Flow<EdgeLike>
    fun inEdges(nodeId: Uuid, type: String, pageSize: Int = 100): Flow<EdgeLike>

    fun allNodeIds(): Flow<Uuid>

    suspend fun <T> from(nodeId: Uuid, block: suspend TraversalBuilderLike.() -> T): Either<AbyssError, T>

    suspend fun transaction(
        checkIntegrity: Boolean = true,
        block: suspend AbyssTransactionLike.() -> Unit
    ): Either<AbyssError, Unit>

    suspend fun ephemeral(
        ttl: Duration,
        checkIntegrity: Boolean = true,
        block: suspend AbyssEphemeralTransactionLike.() -> Unit
    ): Either<AbyssError, Unit>
}

interface AbyssTransactionLike {
    fun addNode(node: NodeLike)
    fun removeNode(id: Uuid)

    fun addEdge(edge: EdgeLike)
    fun removeEdge(fromId: Uuid, toId: Uuid, type: String)
    fun modifyEdge(old: EdgeLike, new: EdgeLike)
}

interface AbyssEphemeralTransactionLike {
    fun addNode(node: NodeLike)
    fun removeNode(id: Uuid)

    fun addEdge(edge: EdgeLike)
    fun removeEdge(fromId: Uuid, toId: Uuid, type: String)
    fun modifyEdge(old: EdgeLike, new: EdgeLike)
}
