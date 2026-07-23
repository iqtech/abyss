package pl.iqtech.abyss.store.yugabyte

import arrow.core.Either
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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
import pl.iqtech.abyss.store.api.StoredEdge
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.abyssSerializersModule
import java.io.Closeable
import java.sql.Timestamp
import java.sql.Types
import javax.sql.DataSource
import kotlin.time.toJavaInstant

private sealed interface PersistentOp {
    data class SaveNode(val id: NodeId, val node: NodeLike<*>, val tags: Set<String>) : PersistentOp
    data class SaveEdge(val fromId: NodeId, val toId: NodeId, val edge: EdgeLike<*, *>, val tags: Set<String>) : PersistentOp
    data class DeleteNode(val id: NodeId) : PersistentOp
    data class DeleteEdge(val fromId: NodeId, val toId: NodeId, val type: String) : PersistentOp
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
        encodeDefaults = true
        classDiscriminator = "type"
        serializersModule = abyssSerializersModule + module
    }

    @Suppress("UNCHECKED_CAST")
    private val nodeSer = PolymorphicSerializer(NodeLike::class) as kotlinx.serialization.KSerializer<NodeLike<*>>
    @Suppress("UNCHECKED_CAST")
    private val edgeSer = PolymorphicSerializer(EdgeLike::class) as kotlinx.serialization.KSerializer<EdgeLike<*, *>>

    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, kotlin.time.Duration?>> =
        Either.catch {
            withContext(Dispatchers.IO) { queryNodeYsql(id)?.let { it to null } ?: (null to null) }
        }.mapLeft { AbyssError.Unexpected(it) }

    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, kotlin.time.Duration?>> =
        Either.catch {
            withContext(Dispatchers.IO) { queryEdgeYsql(fromId, toId, type)?.let { it to null } ?: (null to null) }
        }.mapLeft { AbyssError.Unexpected(it) }

    override suspend fun loadEdges(fromId: NodeId): Either<AbyssError, List<StoredEdge>> =
        Either.catch {
            withContext(Dispatchers.IO) { queryEdgesYsql("from_id", fromId, "to_id").map { (to, edge, toType) -> StoredEdge(fromId, to, edge, null, toType) } }
        }.mapLeft { AbyssError.Unexpected(it) }

    override suspend fun loadInEdges(toId: NodeId): Either<AbyssError, List<StoredEdge>> =
        Either.catch {
            withContext(Dispatchers.IO) { queryEdgesYsql("to_id", toId, "from_id").map { (from, edge, fromType) -> StoredEdge(from, toId, edge, null, fromType) } }
        }.mapLeft { AbyssError.Unexpected(it) }

    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> =
        Either.catch {
            val tx = PersistentTransaction()
            tx.block()
            if (tx.ops.isNotEmpty()) withContext(Dispatchers.IO) { commitYsql(tx.ops) }
        }.mapLeft { AbyssError.Unexpected(it) }

    // Bulk-load path, independent of transaction()/commitYsql: commits ops in chunks of `batchSize`,
    // each chunk its own DB transaction via JDBC addBatch()/executeBatch() instead of one
    // executeUpdate() per op. Trades whole-call atomicity for throughput and bounded per-transaction
    // size — a failing chunk rolls back, but chunks already committed before it stay committed.
    override suspend fun batchTransaction(
        batchSize: Int,
        block: suspend AbyssStoreTransactionLike.() -> Unit
    ): Either<AbyssError, Unit> =
        Either.catch {
            val tx = PersistentTransaction()
            tx.block()
            if (tx.ops.isNotEmpty()) withContext(Dispatchers.IO) { commitYsqlBatched(tx.ops, batchSize) }
        }.mapLeft { AbyssError.Unexpected(it) }

    override fun close() {
        runCatching { (ysql as? Closeable)?.close() }.onFailure { log.warn("Failed to close YSQL DataSource", it) }
    }

    // Admin/orphan-sweep scan (TODO 1.23). Two genuinely different paths, proven in
    // YsqlPartitionScanFeasibilityTest: tag == null fans out over yb_hash_code(id) ranges (0..65535);
    // tag != null is one plain GIN `tags @>` query with NO hash-range fan-out — combining the two
    // doesn't parallelize (the GIN scan fetches every tag match globally regardless, proven via
    // EXPLAIN ANALYZE), so pretending otherwise would just re-scan the same match N times.
    override fun scanNodeIds(tag: String?, parallelism: Int): Flow<NodeId> = channelFlow {
        if (tag != null) {
            launch(Dispatchers.IO) {
                ysql.connection.use { conn ->
                    conn.prepareStatement("SELECT id FROM $ysqlSchema.nodes WHERE tags @> ?").use { stmt ->
                        stmt.setArray(1, conn.createArrayOf("text", arrayOf(tag)))
                        val rs = stmt.executeQuery()
                        while (rs.next()) send(NodeId(rs.getBytes("id")))
                    }
                }
            }
            return@channelFlow
        }
        hashCodeRanges(parallelism).forEach { (lo, hi) ->
            launch(Dispatchers.IO) {
                ysql.connection.use { conn ->
                    conn.prepareStatement(
                        "SELECT id FROM $ysqlSchema.nodes WHERE yb_hash_code(id) BETWEEN ? AND ?"
                    ).apply { fetchSize = 500 }.use { stmt ->
                        stmt.setInt(1, lo)
                        stmt.setInt(2, hi)
                        val rs = stmt.executeQuery()
                        while (rs.next()) send(NodeId(rs.getBytes("id")))
                    }
                }
            }
        }
    }

    override fun scanEdgeIds(parallelism: Int): Flow<Pair<NodeId, NodeId>> = channelFlow {
        hashCodeRanges(parallelism).forEach { (lo, hi) ->
            launch(Dispatchers.IO) {
                ysql.connection.use { conn ->
                    conn.prepareStatement(
                        "SELECT from_id, to_id FROM $ysqlSchema.edges WHERE yb_hash_code(from_id) BETWEEN ? AND ?"
                    ).apply { fetchSize = 500 }.use { stmt ->
                        stmt.setInt(1, lo)
                        stmt.setInt(2, hi)
                        val rs = stmt.executeQuery()
                        while (rs.next()) send(NodeId(rs.getBytes("from_id")) to NodeId(rs.getBytes("to_id")))
                    }
                }
            }
        }
    }

    // yb_hash_code() is bounded 0..65535 (proven in YsqlPartitionScanFeasibilityTest); splits it into
    // `parallelism` disjoint inclusive ranges for a channelFlow fan-out.
    private fun hashCodeRanges(parallelism: Int): List<Pair<Int, Int>> {
        val chunkSize = (65536 + parallelism - 1) / parallelism
        return (0 until parallelism).map { i -> (i * chunkSize) to minOf((i + 1) * chunkSize - 1, 65535) }
    }

    private fun queryNodeYsql(id: NodeId): NodeLike<*>? =
        ysql.connection.use { conn ->
            conn.prepareStatement("SELECT data FROM $ysqlSchema.nodes WHERE id = ?").use { stmt ->
                stmt.setBytes(1, id.bytes)
                val rs = stmt.executeQuery()
                if (!rs.next()) return null
                json.decodeFromString(nodeSer, rs.getString("data"))
            }
        }

    private fun queryEdgeYsql(fromId: NodeId, toId: NodeId, type: String): EdgeLike<*, *>? =
        ysql.connection.use { conn ->
            conn.prepareStatement(
                "SELECT data FROM $ysqlSchema.edges WHERE from_id = ? AND to_id = ? AND type = ?"
            ).use { stmt ->
                stmt.setBytes(1, fromId.bytes)
                stmt.setBytes(2, toId.bytes)
                stmt.setString(3, type)
                val rs = stmt.executeQuery()
                if (!rs.next()) return null
                json.decodeFromString(edgeSer, rs.getString("data"))
            }
        }

    // whereCol/selectCol are internal constants ("from_id"/"to_id"), never user input. The LEFT JOIN
    // returns the scanned-toward neighbor's node type in the same round trip (null for a dangling edge
    // whose endpoint node doesn't exist) so a cold adjacency warm resolves neighbor @TypeTags without a
    // point-read per neighbor. Join is on the neighbor's PK (indexed), so it adds probes, not round trips.
    private fun queryEdgesYsql(whereCol: String, id: NodeId, selectCol: String): List<Triple<NodeId, EdgeLike<*, *>, String?>> =
        ysql.connection.use { conn ->
            conn.prepareStatement(
                "SELECT e.$selectCol, e.data, n.type AS neighbor_type FROM $ysqlSchema.edges e " +
                "LEFT JOIN $ysqlSchema.nodes n ON n.id = e.$selectCol WHERE e.$whereCol = ?"
            ).use { stmt ->
                stmt.setBytes(1, id.bytes)
                val rs = stmt.executeQuery()
                buildList { while (rs.next()) add(Triple(NodeId(rs.getBytes(selectCol)), json.decodeFromString(edgeSer, rs.getString("data")), rs.getString("neighbor_type"))) }
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
                    "INSERT INTO $ysqlSchema.nodes AS n (id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (id) DO UPDATE SET type = EXCLUDED.type, data = EXCLUDED.data, " +
                    "tags = ARRAY(SELECT DISTINCT UNNEST(n.tags || EXCLUDED.tags)), updated_at = EXCLUDED.updated_at"
                )
                val upsertEdge = conn.prepareStatement(
                    "INSERT INTO $ysqlSchema.edges AS e (from_id, to_id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (from_id, to_id, type) DO UPDATE SET data = EXCLUDED.data, " +
                    "tags = ARRAY(SELECT DISTINCT UNNEST(e.tags || EXCLUDED.tags)), updated_at = EXCLUDED.updated_at"
                )
                val delNode = conn.prepareStatement("DELETE FROM $ysqlSchema.nodes WHERE id = ?")
                val delEdge = conn.prepareStatement("DELETE FROM $ysqlSchema.edges WHERE from_id = ? AND to_id = ? AND type = ?")
                for (op in ops) when (op) {
                    is PersistentOp.SaveNode -> {
                        val (type, data) = jsonPair(nodeSer, op.node)
                        upsertNode.setBytes(1, op.id.bytes)
                        upsertNode.setString(2, type)
                        upsertNode.setObject(3, data, Types.OTHER)
                        upsertNode.setArray(4, conn.createArrayOf("text", op.tags.toTypedArray()))
                        upsertNode.setTimestamp(5, Timestamp.from(op.node.createdAt.toJavaInstant()))
                        upsertNode.setTimestamp(6, Timestamp.from(op.node.updatedAt.toJavaInstant()))
                        upsertNode.executeUpdate()
                    }
                    is PersistentOp.SaveEdge -> {
                        val (type, data) = jsonPair(edgeSer, op.edge)
                        upsertEdge.setBytes(1, op.fromId.bytes)
                        upsertEdge.setBytes(2, op.toId.bytes)
                        upsertEdge.setString(3, type)
                        upsertEdge.setObject(4, data, Types.OTHER)
                        upsertEdge.setArray(5, conn.createArrayOf("text", op.tags.toTypedArray()))
                        upsertEdge.setTimestamp(6, Timestamp.from(op.edge.createdAt.toJavaInstant()))
                        upsertEdge.setTimestamp(7, Timestamp.from(op.edge.updatedAt.toJavaInstant()))
                        upsertEdge.executeUpdate()
                    }
                    is PersistentOp.DeleteNode -> { delNode.setBytes(1, op.id.bytes); delNode.executeUpdate() }
                    is PersistentOp.DeleteEdge -> {
                        delEdge.setBytes(1, op.fromId.bytes)
                        delEdge.setBytes(2, op.toId.bytes)
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

    // Independent of commitYsql (own inline binding, not shared with it) — kept that way
    // deliberately so this never risks the existing, already-tested single-transaction commit path.
    // One connection for the whole call, but one conn.commit() per chunk (bounded transaction size).
    // Within a chunk, consecutive ops of the same statement type are run-length-grouped into one
    // addBatch()/executeBatch() round, preserving the caller's original cross-type op order (so a
    // mixed AddNode(X)/RemoveNode(X) sequence within one chunk still resolves the same way
    // commitYsql's op-by-op execution would).
    private fun commitYsqlBatched(ops: List<PersistentOp>, batchSize: Int) {
        ysql.connection.use { conn ->
            conn.autoCommit = false
            val upsertNode = conn.prepareStatement(
                "INSERT INTO $ysqlSchema.nodes AS n (id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (id) DO UPDATE SET type = EXCLUDED.type, data = EXCLUDED.data, " +
                "tags = ARRAY(SELECT DISTINCT UNNEST(n.tags || EXCLUDED.tags)), updated_at = EXCLUDED.updated_at"
            )
            val upsertEdge = conn.prepareStatement(
                "INSERT INTO $ysqlSchema.edges AS e (from_id, to_id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (from_id, to_id, type) DO UPDATE SET data = EXCLUDED.data, " +
                "tags = ARRAY(SELECT DISTINCT UNNEST(e.tags || EXCLUDED.tags)), updated_at = EXCLUDED.updated_at"
            )
            val delNode = conn.prepareStatement("DELETE FROM $ysqlSchema.nodes WHERE id = ?")
            val delEdge = conn.prepareStatement("DELETE FROM $ysqlSchema.edges WHERE from_id = ? AND to_id = ? AND type = ?")

            fun bind(op: PersistentOp): java.sql.PreparedStatement = when (op) {
                is PersistentOp.SaveNode -> {
                    val (type, data) = jsonPair(nodeSer, op.node)
                    upsertNode.setBytes(1, op.id.bytes)
                    upsertNode.setString(2, type)
                    upsertNode.setObject(3, data, Types.OTHER)
                    upsertNode.setArray(4, conn.createArrayOf("text", op.tags.toTypedArray()))
                    upsertNode.setTimestamp(5, Timestamp.from(op.node.createdAt.toJavaInstant()))
                    upsertNode.setTimestamp(6, Timestamp.from(op.node.updatedAt.toJavaInstant()))
                    upsertNode
                }
                is PersistentOp.SaveEdge -> {
                    val (type, data) = jsonPair(edgeSer, op.edge)
                    upsertEdge.setBytes(1, op.fromId.bytes)
                    upsertEdge.setBytes(2, op.toId.bytes)
                    upsertEdge.setString(3, type)
                    upsertEdge.setObject(4, data, Types.OTHER)
                    upsertEdge.setArray(5, conn.createArrayOf("text", op.tags.toTypedArray()))
                    upsertEdge.setTimestamp(6, Timestamp.from(op.edge.createdAt.toJavaInstant()))
                    upsertEdge.setTimestamp(7, Timestamp.from(op.edge.updatedAt.toJavaInstant()))
                    upsertEdge
                }
                is PersistentOp.DeleteNode -> delNode.apply { setBytes(1, op.id.bytes) }
                is PersistentOp.DeleteEdge -> delEdge.apply {
                    setBytes(1, op.fromId.bytes)
                    setBytes(2, op.toId.bytes)
                    setString(3, op.type)
                }
            }

            for (chunk in ops.chunked(batchSize)) {
                try {
                    var i = 0
                    while (i < chunk.size) {
                        val stmt = bind(chunk[i]).also { it.addBatch() }
                        var j = i + 1
                        while (j < chunk.size && chunk[j]::class == chunk[i]::class) {
                            bind(chunk[j]).addBatch()
                            j++
                        }
                        stmt.executeBatch()
                        i = j
                    }
                    conn.commit()
                } catch (e: Throwable) {
                    conn.rollback()
                    throw e
                }
            }
        }
    }

    private inner class PersistentTransaction : AbyssStoreTransactionLike {
        val ops = mutableListOf<PersistentOp>()
        override fun saveNode(id: NodeId, node: NodeLike<*>, tags: Set<String>) { ops += PersistentOp.SaveNode(id, node, tags) }
        override fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, tags: Set<String>) { ops += PersistentOp.SaveEdge(fromId, toId, edge, tags) }
        override fun deleteNode(id: NodeId) { ops += PersistentOp.DeleteNode(id) }
        override fun deleteEdge(fromId: NodeId, toId: NodeId, type: String) { ops += PersistentOp.DeleteEdge(fromId, toId, type) }
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
                // Lets pgjdbc rewrite addBatch()/executeBatch() calls into one multi-values INSERT
                // wire message (commitYsqlBatched). No-op for commitYsql's plain executeUpdate() loop.
                addDataSourceProperty("reWriteBatchedInserts", "true")
            })
            return YugabytePersistentStore(dataSource, module, ysqlSchema)
        }
    }
}
