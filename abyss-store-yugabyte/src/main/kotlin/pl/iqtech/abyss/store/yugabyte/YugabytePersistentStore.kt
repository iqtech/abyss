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
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.abyssSerializersModule
import java.io.Closeable
import java.sql.Timestamp
import java.sql.Types
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

private sealed interface PersistentOp {
    data class SaveNode(val node: NodeLike) : PersistentOp
    data class SaveEdge(val edge: EdgeLike) : PersistentOp
    data class DeleteNode(val id: Uuid) : PersistentOp
    data class DeleteEdge(val fromId: Uuid, val toId: Uuid, val type: String) : PersistentOp
}

class YugabytePersistentStore(
    private val ysql: DataSource,
    module: SerializersModule = EmptySerializersModule(),
    private val ysqlSchema: String = "abyss"
) : AbyssStoreLike, Closeable {

    private val log = LoggerFactory.getLogger(YugabytePersistentStore::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        classDiscriminator = "type"
        serializersModule = abyssSerializersModule + module
    }

    private val nodeSer = PolymorphicSerializer(NodeLike::class)
    private val edgeSer = PolymorphicSerializer(EdgeLike::class)

    override suspend fun loadNode(id: Uuid): Either<AbyssError, Pair<NodeLike?, Duration?>> =
        Either.catch { withContext(Dispatchers.IO) { queryNodeYsql(id)?.let { it to null } ?: (null to null) } }
            .mapLeft { AbyssError.Unexpected(it) }

    override suspend fun loadEdge(fromId: Uuid, toId: Uuid, type: String): Either<AbyssError, Pair<EdgeLike?, Duration?>> =
        Either.catch { withContext(Dispatchers.IO) { queryEdgeYsql(fromId, toId, type)?.let { it to null } ?: (null to null) } }
            .mapLeft { AbyssError.Unexpected(it) }

    override suspend fun loadEdges(fromId: Uuid): Either<AbyssError, List<Pair<EdgeLike, Duration?>>> =
        Either.catch { withContext(Dispatchers.IO) { queryEdgesYsql("from_id", fromId).map { it to null } } }
            .mapLeft { AbyssError.Unexpected(it) }

    override suspend fun loadInEdges(toId: Uuid): Either<AbyssError, List<Pair<EdgeLike, Duration?>>> =
        Either.catch { withContext(Dispatchers.IO) { queryEdgesYsql("to_id", toId).map { it to null } } }
            .mapLeft { AbyssError.Unexpected(it) }

    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> =
        Either.catch {
            val tx = PersistentTransaction()
            tx.block()
            if (tx.ops.isNotEmpty()) withContext(Dispatchers.IO) { commitYsql(tx.ops) }
        }.mapLeft { AbyssError.Unexpected(it) }

    override fun close() {
        runCatching { (ysql as? Closeable)?.close() }.onFailure { log.warn("Failed to close YSQL DataSource", it) }
    }

    private fun queryNodeYsql(id: Uuid): NodeLike? =
        ysql.connection.use { conn ->
            conn.prepareStatement("SELECT data FROM $ysqlSchema.nodes WHERE id = ?").use { stmt ->
                stmt.setObject(1, id.toJavaUuid())
                val rs = stmt.executeQuery()
                if (!rs.next()) return null
                json.decodeFromString(nodeSer, rs.getString("data"))
            }
        }

    private fun queryEdgeYsql(fromId: Uuid, toId: Uuid, type: String): EdgeLike? =
        ysql.connection.use { conn ->
            conn.prepareStatement(
                "SELECT data FROM $ysqlSchema.edges WHERE from_id = ? AND to_id = ? AND type = ?"
            ).use { stmt ->
                stmt.setObject(1, fromId.toJavaUuid())
                stmt.setObject(2, toId.toJavaUuid())
                stmt.setString(3, type)
                val rs = stmt.executeQuery()
                if (!rs.next()) return null
                json.decodeFromString(edgeSer, rs.getString("data"))
            }
        }

    private fun queryEdgesYsql(column: String, id: Uuid): List<EdgeLike> =
        ysql.connection.use { conn ->
            conn.prepareStatement("SELECT data FROM $ysqlSchema.edges WHERE $column = ?").use { stmt ->
                stmt.setObject(1, id.toJavaUuid())
                val rs = stmt.executeQuery()
                buildList { while (rs.next()) add(json.decodeFromString(edgeSer, rs.getString("data"))) }
            }
        }

    private fun <T> jsonPair(ser: SerializationStrategy<T>, value: T): Pair<String, String> {
        val el = json.encodeToJsonElement(ser, value)
        return el.jsonObject["type"]!!.jsonPrimitive.content to el.toString()
    }

    private fun commitYsql(ops: List<PersistentOp>) {
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
                        val (type, data) = jsonPair(nodeSer, op.node)
                        upsertNode.setObject(1, op.node.id.toJavaUuid())
                        upsertNode.setString(2, type)
                        upsertNode.setObject(3, data, Types.OTHER)
                        upsertNode.setArray(4, conn.createArrayOf("text", op.node.tags.toTypedArray()))
                        upsertNode.setTimestamp(5, Timestamp.from(op.node.createdAt.toJavaInstant()))
                        upsertNode.setTimestamp(6, Timestamp.from(op.node.updatedAt.toJavaInstant()))
                        upsertNode.executeUpdate()
                    }
                    is PersistentOp.SaveEdge -> {
                        val (type, data) = jsonPair(edgeSer, op.edge)
                        upsertEdge.setObject(1, op.edge.fromId.toJavaUuid())
                        upsertEdge.setObject(2, op.edge.toId.toJavaUuid())
                        upsertEdge.setString(3, type)
                        upsertEdge.setObject(4, data, Types.OTHER)
                        upsertEdge.setArray(5, conn.createArrayOf("text", op.edge.tags.toTypedArray()))
                        upsertEdge.setTimestamp(6, Timestamp.from(op.edge.createdAt.toJavaInstant()))
                        upsertEdge.setTimestamp(7, Timestamp.from(op.edge.updatedAt.toJavaInstant()))
                        upsertEdge.executeUpdate()
                    }
                    is PersistentOp.DeleteNode -> { delNode.setObject(1, op.id.toJavaUuid()); delNode.executeUpdate() }
                    is PersistentOp.DeleteEdge -> {
                        delEdge.setObject(1, op.fromId.toJavaUuid())
                        delEdge.setObject(2, op.toId.toJavaUuid())
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

    private inner class PersistentTransaction : AbyssStoreTransactionLike {
        val ops = mutableListOf<PersistentOp>()
        override fun saveNode(node: NodeLike) { ops += PersistentOp.SaveNode(node) }
        override fun saveEdge(edge: EdgeLike) { ops += PersistentOp.SaveEdge(edge) }
        override fun deleteNode(id: Uuid) { ops += PersistentOp.DeleteNode(id) }
        override fun deleteEdge(fromId: Uuid, toId: Uuid, type: String) { ops += PersistentOp.DeleteEdge(fromId, toId, type) }
    }

    companion object {
        fun create(
            ysqlUrl: String,
            ysqlUser: String,
            ysqlPassword: String,
            module: SerializersModule = EmptySerializersModule(),
            ysqlSchema: String = "abyss",
            ysqlMaxPoolSize: Int = 20
        ): YugabytePersistentStore {
            val dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl         = ysqlUrl
                username        = ysqlUser
                password        = ysqlPassword
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = ysqlMaxPoolSize
                minimumIdle     = ysqlMaxPoolSize
                addDataSourceProperty("prepareThreshold", "1")
            })
            return YugabytePersistentStore(dataSource, module, ysqlSchema)
        }
    }
}
