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

    suspend fun <T> from(nodeId: Uuid, block: suspend TraversalBuilderLike.() -> T): Either<AbyssError, T>

    suspend fun transaction(
        checkIntegrity: Boolean = true,
        block: suspend AbyssTransactionLike.() -> Unit
    ): Either<AbyssError, Unit>
}

interface AbyssTransactionLike {
    fun addNode(node: NodeLike, ttl: Duration? = null)
    fun removeNode(id: Uuid)

    fun addEdge(edge: EdgeLike, ttl: Duration? = null)
    fun removeEdge(fromId: Uuid, toId: Uuid, type: String)
}
