package pl.iqtech.abyss.store.yugabyte

import arrow.core.Either
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreLike
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreTransactionLike
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.abyssSerializersModule
import java.io.Closeable
import java.net.InetSocketAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

private sealed interface EphemeralOp {
    data class SaveNode(val node: NodeLike, val ttl: Duration) : EphemeralOp
    data class SaveEdge(val edge: EdgeLike, val ttl: Duration) : EphemeralOp
    data class DeleteNode(val id: Uuid) : EphemeralOp
    data class DeleteEdge(val fromId: Uuid, val toId: Uuid, val type: String) : EphemeralOp
}

class YugabyteEphemeralStore(
    private val ycql: CqlSession,
    module: SerializersModule = EmptySerializersModule(),
    private val ycqlKeyspace: String = "abyss_test_graph"
) : AbyssEphemeralStoreLike, Closeable {

    private val log = LoggerFactory.getLogger(YugabyteEphemeralStore::class.java)
    private val healScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        classDiscriminator = "type"
        serializersModule = abyssSerializersModule + module
    }

    private val nodeSer = PolymorphicSerializer(NodeLike::class)
    private val edgeSer = PolymorphicSerializer(EdgeLike::class)

    private val selectNodeYcql: PreparedStatement =
        ycql.prepare("SELECT data, ttl_expiration FROM $ycqlKeyspace.ephemeral_nodes WHERE id = ?")
    private val selectEdgeYcql: PreparedStatement =
        ycql.prepare("SELECT data, ttl_expiration FROM $ycqlKeyspace.ephemeral_edges WHERE from_id = ? AND to_id = ? AND type = ?")
    private val selectEdgesYcql: PreparedStatement =
        ycql.prepare("SELECT data, ttl_expiration FROM $ycqlKeyspace.ephemeral_edges WHERE from_id = ?")
    private val selectInEdgesYcql: PreparedStatement =
        ycql.prepare("SELECT data, ttl_expiration FROM $ycqlKeyspace.ephemeral_reverse_edges WHERE to_id = ?")
    private val deleteNodeYcql: PreparedStatement =
        ycql.prepare("DELETE FROM $ycqlKeyspace.ephemeral_nodes WHERE id = ?")
    private val deleteEdgeYcql: PreparedStatement =
        ycql.prepare("DELETE FROM $ycqlKeyspace.ephemeral_edges WHERE from_id = ? AND to_id = ? AND type = ?")
    private val insertReverseEdgeYcql: PreparedStatement =
        ycql.prepare("INSERT INTO $ycqlKeyspace.ephemeral_reverse_edges (to_id, from_id, type, data, tags, created_at, updated_at, ttl_expiration) VALUES (?, ?, ?, ?, ?, ?, ?, ?) USING TTL ?")
    private val deleteReverseEdgeYcql: PreparedStatement =
        ycql.prepare("DELETE FROM $ycqlKeyspace.ephemeral_reverse_edges WHERE to_id = ? AND from_id = ? AND type = ?")

    override suspend fun loadNode(id: Uuid): Either<AbyssError, Pair<NodeLike?, Duration?>> =
        Either.catch { withContext(Dispatchers.IO) { queryNodeYcql(id)?.let { (node, exp) -> node to remainingTtl(exp) } ?: (null to null) } }
            .mapLeft { AbyssError.Unexpected(it) }

    override suspend fun loadEdge(fromId: Uuid, toId: Uuid, type: String): Either<AbyssError, Pair<EdgeLike?, Duration?>> =
        Either.catch { withContext(Dispatchers.IO) { queryEdgeYcql(fromId, toId, type)?.let { (edge, exp) -> edge to remainingTtl(exp) } ?: (null to null) } }
            .mapLeft { AbyssError.Unexpected(it) }

    override suspend fun loadEdges(fromId: Uuid): Either<AbyssError, List<Pair<EdgeLike, Duration?>>> =
        Either.catch { withContext(Dispatchers.IO) { queryEdgesYcql(selectEdgesYcql, fromId).map { (e, exp) -> e to remainingTtl(exp) } } }
            .mapLeft { AbyssError.Unexpected(it) }

    override suspend fun loadInEdges(toId: Uuid): Either<AbyssError, List<Pair<EdgeLike, Duration?>>> =
        Either.catch { withContext(Dispatchers.IO) { queryEdgesYcql(selectInEdgesYcql, toId).map { (e, exp) -> e to remainingTtl(exp) } } }
            .mapLeft { AbyssError.Unexpected(it) }

    override suspend fun transaction(block: suspend AbyssEphemeralStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> =
        Either.catch {
            val tx = EphemeralTransaction()
            tx.block()
            if (tx.ops.isNotEmpty()) withContext(Dispatchers.IO) { commitYcql(tx.ops) }
        }.mapLeft { AbyssError.Unexpected(it) }

    override fun close() {
        healScope.cancel()
        runCatching { ycql.close() }.onFailure { log.warn("Failed to close YCQL session", it) }
    }

    private fun remainingTtl(expiresAt: java.time.Instant?): Duration? {
        if (expiresAt == null) return null
        val remaining = java.time.Duration.between(java.time.Instant.now(), expiresAt).toMillis()
        return remaining.milliseconds
    }

    private fun queryNodeYcql(id: Uuid): Pair<NodeLike, java.time.Instant?>? {
        val row = ycql.execute(selectNodeYcql.bind(id.toJavaUuid())).one() ?: return null
        val data = row.getString("data") ?: return null
        return json.decodeFromString(nodeSer, data) to row.getInstant("ttl_expiration")
    }

    private fun queryEdgeYcql(fromId: Uuid, toId: Uuid, type: String): Pair<EdgeLike, java.time.Instant?>? {
        val row = ycql.execute(selectEdgeYcql.bind(fromId.toJavaUuid(), toId.toJavaUuid(), type)).one() ?: return null
        val data = row.getString("data") ?: return null
        return json.decodeFromString(edgeSer, data) to row.getInstant("ttl_expiration")
    }

    private fun queryEdgesYcql(stmt: PreparedStatement, id: Uuid): List<Pair<EdgeLike, java.time.Instant?>> =
        ycql.execute(stmt.bind(id.toJavaUuid()))
            .mapNotNull { row ->
                val data = row.getString("data") ?: return@mapNotNull null
                json.decodeFromString(edgeSer, data) to row.getInstant("ttl_expiration")
            }

    private fun <T> jsonPair(ser: SerializationStrategy<T>, value: T): Pair<String, String> {
        val el = json.encodeToJsonElement(ser, value)
        return el.jsonObject["type"]!!.jsonPrimitive.content to el.toString()
    }

    private fun commitYcql(ops: List<EphemeralOp>) {
        for (op in ops) when (op) {
            is EphemeralOp.SaveNode -> {
                val (type, data) = jsonPair(nodeSer, op.node)
                val ttl = op.ttl.inWholeSeconds
                val expiresAt = java.time.Instant.now().plusSeconds(ttl)
                ycql.execute(SimpleStatement.newInstance(
                    "INSERT INTO $ycqlKeyspace.ephemeral_nodes (id, type, data, tags, created_at, updated_at, ttl_expiration) VALUES (?, ?, ?, ?, ?, ?, ?) USING TTL $ttl",
                    op.node.id.toJavaUuid(), type, data, op.node.tags, op.node.createdAt.toJavaInstant(), op.node.updatedAt.toJavaInstant(), expiresAt
                ))
            }
            is EphemeralOp.SaveEdge -> {
                val (type, data) = jsonPair(edgeSer, op.edge)
                val ttl = op.ttl.inWholeSeconds.toInt()
                val expiresAt = java.time.Instant.now().plusSeconds(ttl.toLong())
                // reverse table first: if this fails nothing is visible; primary failure leaves a benign dangling entry
                ycql.execute(insertReverseEdgeYcql.bind(op.edge.toId.toJavaUuid(), op.edge.fromId.toJavaUuid(), type, data, op.edge.tags, op.edge.createdAt.toJavaInstant(), op.edge.updatedAt.toJavaInstant(), expiresAt, ttl))
                try {
                    writePrimaryEdge(op, ttl, expiresAt)
                } catch (e: Exception) {
                    val failedAt = java.time.Instant.now()
                    log.error("Primary edge write failed, scheduling heal for ${op.edge.fromId}→${op.edge.toId}", e)
                    healScope.launch { healEdge(op, failedAt) }
                    throw e
                }
            }
            is EphemeralOp.DeleteNode -> ycql.execute(deleteNodeYcql.bind(op.id.toJavaUuid()))
            is EphemeralOp.DeleteEdge -> {
                ycql.execute(deleteReverseEdgeYcql.bind(op.toId.toJavaUuid(), op.fromId.toJavaUuid(), op.type))
                ycql.execute(deleteEdgeYcql.bind(op.fromId.toJavaUuid(), op.toId.toJavaUuid(), op.type))
            }
        }
    }

    private fun writePrimaryEdge(op: EphemeralOp.SaveEdge, ttlSeconds: Int, expiresAt: java.time.Instant) {
        val (type, data) = jsonPair(edgeSer, op.edge)
        ycql.execute(SimpleStatement.newInstance(
            "INSERT INTO $ycqlKeyspace.ephemeral_edges (from_id, to_id, type, data, tags, created_at, updated_at, ttl_expiration) VALUES (?, ?, ?, ?, ?, ?, ?, ?) USING TTL $ttlSeconds",
            op.edge.fromId.toJavaUuid(), op.edge.toId.toJavaUuid(), type, data,
            op.edge.tags, op.edge.createdAt.toJavaInstant(), op.edge.updatedAt.toJavaInstant(), expiresAt
        ))
    }

    private suspend fun healEdge(op: EphemeralOp.SaveEdge, failedAt: java.time.Instant) {
        val originalTtlSeconds = op.ttl.inWholeSeconds
        var delayMs = 1_000L
        repeat(5) { attempt ->
            delay(delayMs)
            val elapsed = java.time.Duration.between(failedAt, java.time.Instant.now()).seconds
            val remainingTtl = (originalTtlSeconds - elapsed).toInt()
            if (remainingTtl <= 0) {
                log.warn("TTL exceeded for edge ${op.edge.fromId}→${op.edge.toId}, dropping heal — reverse entry will expire naturally")
                return
            }
            try {
                val healExpiresAt = java.time.Instant.now().plusSeconds(remainingTtl.toLong())
                withContext(Dispatchers.IO) { writePrimaryEdge(op, remainingTtl, healExpiresAt) }
                log.info("Healed edge ${op.edge.fromId}→${op.edge.toId} on attempt ${attempt + 1}")
                return
            } catch (e: Exception) {
                log.warn("Heal attempt ${attempt + 1}/5 failed for ${op.edge.fromId}→${op.edge.toId}", e)
                delayMs *= 2
            }
        }
        log.error("Failed to heal edge ${op.edge.fromId}→${op.edge.toId} after 5 attempts — reverse entry will expire via TTL")
    }

    private inner class EphemeralTransaction : AbyssEphemeralStoreTransactionLike {
        val ops = mutableListOf<EphemeralOp>()
        override fun saveNode(node: NodeLike, ttl: Duration) { ops += EphemeralOp.SaveNode(node, ttl) }
        override fun saveEdge(edge: EdgeLike, ttl: Duration) { ops += EphemeralOp.SaveEdge(edge, ttl) }
        override fun deleteNode(id: Uuid) { ops += EphemeralOp.DeleteNode(id) }
        override fun deleteEdge(fromId: Uuid, toId: Uuid, type: String) { ops += EphemeralOp.DeleteEdge(fromId, toId, type) }
    }

    companion object {
        fun create(
            ycqlHost: String = "localhost",
            ycqlPort: Int = 9042,
            ycqlDatacenter: String = "datacenter1",
            module: SerializersModule = EmptySerializersModule(),
            ycqlKeyspace: String = "abyss_test_graph"
        ): YugabyteEphemeralStore {
            val session = CqlSession.builder()
                .addContactPoint(InetSocketAddress(ycqlHost, ycqlPort))
                .withLocalDatacenter(ycqlDatacenter)
                .build()
            return YugabyteEphemeralStore(session, module, ycqlKeyspace)
        }
    }
}
