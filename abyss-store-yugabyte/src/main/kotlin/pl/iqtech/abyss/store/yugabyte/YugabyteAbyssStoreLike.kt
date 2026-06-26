package pl.iqtech.abyss.store.yugabyte

import arrow.core.Either
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.io.Closeable
import java.net.InetSocketAddress
import java.sql.Timestamp
import java.sql.Types
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Duration

private sealed interface StoreOp {
    data class SaveNode(val node: NodeLike, val ttl: Duration?) : StoreOp
    data class SaveEdge(val edge: EdgeLike, val ttl: Duration?) : StoreOp
    data class DeleteNode(val id: UUID) : StoreOp
    data class DeleteEdge(val fromId: UUID, val toId: UUID, val type: String) : StoreOp
}

private fun StoreOp.goesYsql() = when (this) {
    is StoreOp.SaveNode -> ttl == null
    is StoreOp.SaveEdge -> ttl == null
    is StoreOp.DeleteNode, is StoreOp.DeleteEdge -> true
}

private fun StoreOp.goesYcql() = when (this) {
    is StoreOp.SaveNode -> ttl != null
    is StoreOp.SaveEdge -> ttl != null
    is StoreOp.DeleteNode, is StoreOp.DeleteEdge -> true
}

class YugabyteAbyssStoreLike(
    private val ysql: DataSource,
    private val ycql: CqlSession,
    module: SerializersModule = EmptySerializersModule()
) : AbyssStoreLike, Closeable {

    private val log = LoggerFactory.getLogger(YugabyteAbyssStoreLike::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        classDiscriminator = "type"
        serializersModule = baseJsonModule + module
    }

    private val nodeSer = PolymorphicSerializer(NodeLike::class)
    private val edgeSer = PolymorphicSerializer(EdgeLike::class)

    private val selectNodeYcql: PreparedStatement =
        ycql.prepare("SELECT data FROM abyss_test_graph.ephemeral_nodes WHERE id = ?")
    private val selectEdgeYcql: PreparedStatement =
        ycql.prepare("SELECT data FROM abyss_test_graph.ephemeral_edges WHERE from_id = ? AND to_id = ? AND type = ?")
    private val deleteNodeYcql: PreparedStatement =
        ycql.prepare("DELETE FROM abyss_test_graph.ephemeral_nodes WHERE id = ?")
    private val deleteEdgeYcql: PreparedStatement =
        ycql.prepare("DELETE FROM abyss_test_graph.ephemeral_edges WHERE from_id = ? AND to_id = ? AND type = ?")

    override suspend fun loadNode(id: UUID): Either<AbyssError, NodeLike?> =
        Either.catch {
            coroutineScope {
                val fromYsql = async(Dispatchers.IO) { queryNodeYsql(id) }
                val fromYcql = async(Dispatchers.IO) { queryNodeYcql(id) }
                awaitAll(fromYsql, fromYcql).firstOrNull { it != null }
            }
        }.mapLeft { AbyssError.Unexpected(it) }

    override suspend fun loadEdge(fromId: UUID, toId: UUID, type: String): Either<AbyssError, EdgeLike?> =
        Either.catch {
            coroutineScope {
                val fromYsql = async(Dispatchers.IO) { queryEdgeYsql(fromId, toId, type) }
                val fromYcql = async(Dispatchers.IO) { queryEdgeYcql(fromId, toId, type) }
                awaitAll(fromYsql, fromYcql).firstOrNull { it != null }
            }
        }.mapLeft { AbyssError.Unexpected(it) }

    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> =
        Either.catch {
            val tx = StoreTransaction()
            tx.block()
            val ysqlOps = tx.ops.filter { it.goesYsql() }
            val ycqlOps = tx.ops.filter { it.goesYcql() }
            if (ysqlOps.isNotEmpty()) withContext(Dispatchers.IO) { commitYsql(ysqlOps) }
            if (ycqlOps.isNotEmpty()) withContext(Dispatchers.IO) { commitYcql(ycqlOps) }
        }.mapLeft { AbyssError.Unexpected(it) }

    override fun close() {
        runCatching { ycql.close() }.onFailure { log.warn("Failed to close YCQL session", it) }
        runCatching { (ysql as? Closeable)?.close() }.onFailure { log.warn("Failed to close YSQL DataSource", it) }
    }

    private fun queryNodeYsql(id: UUID): NodeLike? =
        ysql.connection.use { conn ->
            conn.prepareStatement("SELECT data FROM abyss.nodes WHERE id = ?").use { stmt ->
                stmt.setObject(1, id)
                val rs = stmt.executeQuery()
                if (!rs.next()) return null
                json.decodeFromString(nodeSer, rs.getString("data"))
            }
        }

    private fun queryNodeYcql(id: UUID): NodeLike? {
        val row = ycql.execute(selectNodeYcql.bind(id)).one() ?: return null
        val data = row.getString("data") ?: return null
        return json.decodeFromString(nodeSer, data)
    }

    private fun queryEdgeYsql(fromId: UUID, toId: UUID, type: String): EdgeLike? =
        ysql.connection.use { conn ->
            conn.prepareStatement(
                "SELECT data FROM abyss.edges WHERE from_id = ? AND to_id = ? AND type = ?"
            ).use { stmt ->
                stmt.setObject(1, fromId)
                stmt.setObject(2, toId)
                stmt.setString(3, type)
                val rs = stmt.executeQuery()
                if (!rs.next()) return null
                json.decodeFromString(edgeSer, rs.getString("data"))
            }
        }

    private fun queryEdgeYcql(fromId: UUID, toId: UUID, type: String): EdgeLike? {
        val row = ycql.execute(selectEdgeYcql.bind(fromId, toId, type)).one() ?: return null
        val data = row.getString("data") ?: return null
        return json.decodeFromString(edgeSer, data)
    }

    private fun <T> jsonPair(ser: SerializationStrategy<T>, value: T): Pair<String, String> {
        val el = json.encodeToJsonElement(ser, value)
        return el.jsonObject["type"]!!.jsonPrimitive.content to el.toString()
    }

    private fun commitYsql(ops: List<StoreOp>) {
        ysql.connection.use { conn ->
            conn.autoCommit = false
            try {
                val upsertNode = conn.prepareStatement(
                    "INSERT INTO abyss.nodes (id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (id) DO UPDATE SET type = EXCLUDED.type, data = EXCLUDED.data, tags = EXCLUDED.tags, updated_at = EXCLUDED.updated_at"
                )
                val upsertEdge = conn.prepareStatement(
                    "INSERT INTO abyss.edges (from_id, to_id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (from_id, to_id, type) DO UPDATE SET data = EXCLUDED.data, tags = EXCLUDED.tags, updated_at = EXCLUDED.updated_at"
                )
                val delNode = conn.prepareStatement("DELETE FROM abyss.nodes WHERE id = ?")
                val delEdge = conn.prepareStatement("DELETE FROM abyss.edges WHERE from_id = ? AND to_id = ? AND type = ?")
                for (op in ops) when (op) {
                    is StoreOp.SaveNode -> {
                        val (type, data) = jsonPair(nodeSer, op.node)
                        upsertNode.setObject(1, op.node.id)
                        upsertNode.setString(2, type)
                        upsertNode.setObject(3, data, Types.OTHER)
                        upsertNode.setArray(4, conn.createArrayOf("text", op.node.tags.toTypedArray()))
                        upsertNode.setTimestamp(5, Timestamp.from(op.node.createdAt))
                        upsertNode.setTimestamp(6, Timestamp.from(op.node.updatedAt))
                        upsertNode.executeUpdate()
                    }
                    is StoreOp.SaveEdge -> {
                        val (type, data) = jsonPair(edgeSer, op.edge)
                        upsertEdge.setObject(1, op.edge.fromId)
                        upsertEdge.setObject(2, op.edge.toId)
                        upsertEdge.setString(3, type)
                        upsertEdge.setObject(4, data, Types.OTHER)
                        upsertEdge.setArray(5, conn.createArrayOf("text", op.edge.tags.toTypedArray()))
                        upsertEdge.setTimestamp(6, Timestamp.from(op.edge.createdAt))
                        upsertEdge.setTimestamp(7, Timestamp.from(op.edge.updatedAt))
                        upsertEdge.executeUpdate()
                    }
                    is StoreOp.DeleteNode -> { delNode.setObject(1, op.id); delNode.executeUpdate() }
                    is StoreOp.DeleteEdge -> {
                        delEdge.setObject(1, op.fromId); delEdge.setObject(2, op.toId); delEdge.setString(3, op.type)
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

    private fun commitYcql(ops: List<StoreOp>) {
        for (op in ops) when (op) {
            is StoreOp.SaveNode -> {
                val (type, data) = jsonPair(nodeSer, op.node)
                // ponytail: USING TTL omitted — per-row TTL is not supported on transactional YCQL tables
                // (transactions = true is required for secondary indexes). Use table-level TTL for expiry.
                ycql.execute(SimpleStatement.newInstance(
                    "INSERT INTO abyss_test_graph.ephemeral_nodes (id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                    op.node.id, type, data, op.node.tags, op.node.createdAt, op.node.updatedAt
                ))
            }
            is StoreOp.SaveEdge -> {
                val (type, data) = jsonPair(edgeSer, op.edge)
                ycql.execute(SimpleStatement.newInstance(
                    "INSERT INTO abyss_test_graph.ephemeral_edges (from_id, to_id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    op.edge.fromId, op.edge.toId, type, data, op.edge.tags, op.edge.createdAt, op.edge.updatedAt
                ))
            }
            is StoreOp.DeleteNode -> ycql.execute(deleteNodeYcql.bind(op.id))
            is StoreOp.DeleteEdge -> ycql.execute(deleteEdgeYcql.bind(op.fromId, op.toId, op.type))
        }
    }

    private inner class StoreTransaction : AbyssStoreTransactionLike {
        val ops = mutableListOf<StoreOp>()
        override fun saveNode(node: NodeLike, ttl: Duration?) { ops += StoreOp.SaveNode(node, ttl) }
        override fun saveEdge(edge: EdgeLike, ttl: Duration?) { ops += StoreOp.SaveEdge(edge, ttl) }
        override fun deleteNode(id: UUID) { ops += StoreOp.DeleteNode(id) }
        override fun deleteEdge(fromId: UUID, toId: UUID, type: String) { ops += StoreOp.DeleteEdge(fromId, toId, type) }
    }

    companion object {
        fun create(
            ysqlUrl: String,
            ysqlUser: String,
            ysqlPassword: String,
            ycqlHost: String = "localhost",
            ycqlPort: Int = 9042,
            ycqlDatacenter: String = "datacenter1",
            module: SerializersModule = EmptySerializersModule()
        ): YugabyteAbyssStoreLike {
            val dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = ysqlUrl
                username = ysqlUser
                password = ysqlPassword
                driverClassName = "org.postgresql.Driver"
            })
            val session = CqlSession.builder()
                .addContactPoint(InetSocketAddress(ycqlHost, ycqlPort))
                .withLocalDatacenter(ycqlDatacenter)
                .build()
            return YugabyteAbyssStoreLike(dataSource, session, module)
        }
    }
}
