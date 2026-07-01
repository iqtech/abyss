package pl.iqtech.abyss.store.yugabyte

import arrow.core.Either
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import org.slf4j.LoggerFactory
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.KeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.abyssSerializersModule
import java.io.Closeable
import java.sql.Timestamp
import java.sql.Types
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.toJavaInstant

private sealed interface PersistentOp<ID> {
    data class SaveNode<ID>(val node: NodeLike<ID>) : PersistentOp<ID>
    data class SaveEdge<ID>(val edge: EdgeLike<ID>) : PersistentOp<ID>
    data class DeleteNode<ID>(val id: ID) : PersistentOp<ID>
    data class DeleteEdge<ID>(val fromId: ID, val toId: ID, val type: String) : PersistentOp<ID>
}

class YugabytePersistentStore<ID>(
    private val adapter: KeyAdapter<ID>,
    private val ysql: DataSource,
    module: SerializersModule = EmptySerializersModule(),
    private val ysqlSchema: String = "abyss"
) : AbyssStoreLike<ID>, Closeable {

    private val log = LoggerFactory.getLogger(YugabytePersistentStore::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        classDiscriminator = "type"
        serializersModule = abyssSerializersModule + module
    }

    @Suppress("UNCHECKED_CAST")
    private val nodeSer = PolymorphicSerializer(NodeLike::class) as kotlinx.serialization.KSerializer<NodeLike<*>>
    @Suppress("UNCHECKED_CAST")
    private val edgeSer = PolymorphicSerializer(EdgeLike::class) as kotlinx.serialization.KSerializer<EdgeLike<*>>

    override suspend fun loadNode(id: ID): Either<AbyssError, Pair<NodeLike<ID>?, Duration?>> =
        Either.catch {
            @Suppress("UNCHECKED_CAST")
            withContext(Dispatchers.IO) { queryNodeYsql(id)?.let { it as NodeLike<ID> to null } ?: (null to null) }
        }.mapLeft { AbyssError.Unexpected(it) }

    override suspend fun loadEdge(fromId: ID, toId: ID, type: String): Either<AbyssError, Pair<EdgeLike<ID>?, Duration?>> =
        Either.catch {
            @Suppress("UNCHECKED_CAST")
            withContext(Dispatchers.IO) { queryEdgeYsql(fromId, toId, type)?.let { it as EdgeLike<ID> to null } ?: (null to null) }
        }.mapLeft { AbyssError.Unexpected(it) }

    override suspend fun loadEdges(fromId: ID): Either<AbyssError, List<Pair<EdgeLike<ID>, Duration?>>> =
        Either.catch {
            @Suppress("UNCHECKED_CAST")
            withContext(Dispatchers.IO) { queryEdgesYsql("from_id", fromId).map { it as EdgeLike<ID> to null } }
        }.mapLeft { AbyssError.Unexpected(it) }

    override suspend fun loadInEdges(toId: ID): Either<AbyssError, List<Pair<EdgeLike<ID>, Duration?>>> =
        Either.catch {
            @Suppress("UNCHECKED_CAST")
            withContext(Dispatchers.IO) { queryEdgesYsql("to_id", toId).map { it as EdgeLike<ID> to null } }
        }.mapLeft { AbyssError.Unexpected(it) }

    override suspend fun transaction(block: suspend AbyssStoreTransactionLike<ID>.() -> Unit): Either<AbyssError, Unit> =
        Either.catch {
            val tx = PersistentTransaction()
            tx.block()
            if (tx.ops.isNotEmpty()) withContext(Dispatchers.IO) { commitYsql(tx.ops) }
        }.mapLeft { AbyssError.Unexpected(it) }

    override fun close() {
        runCatching { (ysql as? Closeable)?.close() }.onFailure { log.warn("Failed to close YSQL DataSource", it) }
    }

    private fun nodeIdBytes(id: ID): ByteArray = adapter.toNodeId(id).bytes

    private fun queryNodeYsql(id: ID): NodeLike<*>? =
        ysql.connection.use { conn ->
            conn.prepareStatement("SELECT data FROM $ysqlSchema.nodes WHERE id = ?").use { stmt ->
                stmt.setBytes(1, nodeIdBytes(id))
                val rs = stmt.executeQuery()
                if (!rs.next()) return null
                json.decodeFromString(nodeSer, rs.getString("data"))
            }
        }

    private fun queryEdgeYsql(fromId: ID, toId: ID, type: String): EdgeLike<*>? =
        ysql.connection.use { conn ->
            conn.prepareStatement(
                "SELECT data FROM $ysqlSchema.edges WHERE from_id = ? AND to_id = ? AND type = ?"
            ).use { stmt ->
                stmt.setBytes(1, nodeIdBytes(fromId))
                stmt.setBytes(2, nodeIdBytes(toId))
                stmt.setString(3, type)
                val rs = stmt.executeQuery()
                if (!rs.next()) return null
                json.decodeFromString(edgeSer, rs.getString("data"))
            }
        }

    private fun queryEdgesYsql(column: String, id: ID): List<EdgeLike<*>> =
        ysql.connection.use { conn ->
            conn.prepareStatement("SELECT data FROM $ysqlSchema.edges WHERE $column = ?").use { stmt ->
                stmt.setBytes(1, nodeIdBytes(id))
                val rs = stmt.executeQuery()
                buildList { while (rs.next()) add(json.decodeFromString(edgeSer, rs.getString("data"))) }
            }
        }

    private fun <T> jsonPair(ser: SerializationStrategy<T>, value: T): Pair<String, String> {
        val el = json.encodeToJsonElement(ser, value)
        return el.jsonObject["type"]!!.jsonPrimitive.content to el.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun commitYsql(ops: List<PersistentOp<ID>>) {
        ysql.connection.use { conn ->
            conn.autoCommit = false
            try {
                val upsertNode = conn.prepareStatement(
                    "INSERT INTO $ysqlSchema.nodes (id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (id) DO UPDATE SET type = EXCLUDED.type, data = EXCLUDED.data, tags = EXCLUDED.tags, updated_at = EXCLUDED.updated_at"
                )
                val upsertEdge = conn.prepareStatement(
                    "INSERT INTO $ysqlSchema.edges (from_id, to_id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (from_id, to_id, type) DO UPDATE SET data = EXCLUDED.data, tags = EXCLUDED.tags, updated_at = EXCLUDED.updated_at"
                )
                val delNode = conn.prepareStatement("DELETE FROM $ysqlSchema.nodes WHERE id = ?")
                val delEdge = conn.prepareStatement("DELETE FROM $ysqlSchema.edges WHERE from_id = ? AND to_id = ? AND type = ?")
                for (op in ops) when (op) {
                    is PersistentOp.SaveNode -> {
                        val (type, data) = jsonPair(nodeSer, op.node as NodeLike<*>)
                        upsertNode.setBytes(1, nodeIdBytes(op.node.id))
                        upsertNode.setString(2, type)
                        upsertNode.setObject(3, data, Types.OTHER)
                        upsertNode.setArray(4, conn.createArrayOf("text", op.node.tags.toTypedArray()))
                        upsertNode.setTimestamp(5, Timestamp.from(op.node.createdAt.toJavaInstant()))
                        upsertNode.setTimestamp(6, Timestamp.from(op.node.updatedAt.toJavaInstant()))
                        upsertNode.executeUpdate()
                    }
                    is PersistentOp.SaveEdge -> {
                        val (type, data) = jsonPair(edgeSer, op.edge as EdgeLike<*>)
                        upsertEdge.setBytes(1, nodeIdBytes(op.edge.fromId))
                        upsertEdge.setBytes(2, nodeIdBytes(op.edge.toId))
                        upsertEdge.setString(3, type)
                        upsertEdge.setObject(4, data, Types.OTHER)
                        upsertEdge.setArray(5, conn.createArrayOf("text", op.edge.tags.toTypedArray()))
                        upsertEdge.setTimestamp(6, Timestamp.from(op.edge.createdAt.toJavaInstant()))
                        upsertEdge.setTimestamp(7, Timestamp.from(op.edge.updatedAt.toJavaInstant()))
                        upsertEdge.executeUpdate()
                    }
                    is PersistentOp.DeleteNode -> { delNode.setBytes(1, nodeIdBytes(op.id)); delNode.executeUpdate() }
                    is PersistentOp.DeleteEdge -> {
                        delEdge.setBytes(1, nodeIdBytes(op.fromId))
                        delEdge.setBytes(2, nodeIdBytes(op.toId))
                        delEdge.setString(3, op.type)
                        delEdge.executeUpdate()
                    }
                }
                conn.commit()
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
    }

    private inner class PersistentTransaction : AbyssStoreTransactionLike<ID> {
        val ops = mutableListOf<PersistentOp<ID>>()
        override fun saveNode(node: NodeLike<ID>) { ops += PersistentOp.SaveNode(node) }
        override fun saveEdge(edge: EdgeLike<ID>) { ops += PersistentOp.SaveEdge(edge) }
        override fun deleteNode(id: ID) { ops += PersistentOp.DeleteNode(id) }
        override fun deleteEdge(fromId: ID, toId: ID, type: String) { ops += PersistentOp.DeleteEdge(fromId, toId, type) }
    }

    companion object {
        fun <ID> create(
            adapter: KeyAdapter<ID>,
            ysqlUrl: String,
            ysqlUser: String,
            ysqlPassword: String,
            module: SerializersModule = EmptySerializersModule(),
            ysqlSchema: String = "abyss",
            ysqlMaxPoolSize: Int = 20
        ): YugabytePersistentStore<ID> {
            val dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl         = ysqlUrl
                username        = ysqlUser
                password        = ysqlPassword
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = ysqlMaxPoolSize
                minimumIdle     = ysqlMaxPoolSize
                addDataSourceProperty("prepareThreshold", "1")
            })
            return YugabytePersistentStore(adapter, dataSource, module, ysqlSchema)
        }
    }
}
