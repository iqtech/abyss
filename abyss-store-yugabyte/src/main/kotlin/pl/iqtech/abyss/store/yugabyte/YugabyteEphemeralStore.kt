package pl.iqtech.abyss.store.yugabyte

import arrow.core.Either
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.datastax.oss.driver.api.core.cql.SimpleStatement
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
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreLike
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreTransactionLike
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.SchemaEdgeLike
import pl.iqtech.abyss.store.api.KeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.abyssSerializersModule
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.isDistantPast
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

private sealed interface EphemeralOp<ID> {
    data class SaveNode<ID>(val node: NodeLike<ID>, val ttl: Duration) : EphemeralOp<ID>
    data class SaveEdge<ID>(val edge: SchemaEdgeLike<ID>, val ttl: Duration) : EphemeralOp<ID>
    data class DeleteNode<ID>(val id: ID) : EphemeralOp<ID>
    data class DeleteEdge<ID>(val fromId: ID, val toId: ID, val type: String) : EphemeralOp<ID>
}

class YugabyteEphemeralStore<ID>(
    private val adapter: KeyAdapter<ID>,
    private val ycql: CqlSession,
    module: SerializersModule = EmptySerializersModule(),
    private val ycqlKeyspace: String = "abyss_test_graph"
) : AbyssEphemeralStoreLike<ID>, Closeable {

    private val log = LoggerFactory.getLogger(YugabyteEphemeralStore::class.java)

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
    private val edgeSer = PolymorphicSerializer(SchemaEdgeLike::class) as kotlinx.serialization.KSerializer<SchemaEdgeLike<*>>

    private val selectNodeYcql: PreparedStatement =
        ycql.prepare("SELECT data, ttl_expiration FROM $ycqlKeyspace.ephemeral_nodes WHERE id = ?")
    private val selectEdgeYcql: PreparedStatement =
        ycql.prepare("SELECT data, ttl_expiration FROM $ycqlKeyspace.ephemeral_edges WHERE from_id = ? AND to_id = ? AND type = ?")
    private val selectEdgesYcql: PreparedStatement =
        ycql.prepare("SELECT data, ttl_expiration FROM $ycqlKeyspace.ephemeral_edges WHERE from_id = ?")
    private val deleteNodeYcql: PreparedStatement =
        ycql.prepare("DELETE FROM $ycqlKeyspace.ephemeral_nodes WHERE id = ?")
    private val deleteEdgeYcql: PreparedStatement =
        ycql.prepare("DELETE FROM $ycqlKeyspace.ephemeral_edges WHERE from_id = ? AND to_id = ? AND type = ?")

    override suspend fun loadNode(id: ID): Either<AbyssError, Pair<NodeLike<ID>?, Duration?>> =
        Either.catch {
            @Suppress("UNCHECKED_CAST")
            withContext(Dispatchers.IO) { queryNodeYcql(id)?.let { (node, exp) -> node as NodeLike<ID> to remainingTtl(exp) } ?: (null to null) }
        }.mapLeft { AbyssError.Unexpected(it) }

    override suspend fun loadEdge(fromId: ID, toId: ID, type: String): Either<AbyssError, Pair<SchemaEdgeLike<ID>?, Duration?>> =
        Either.catch {
            @Suppress("UNCHECKED_CAST")
            withContext(Dispatchers.IO) { queryEdgeYcql(fromId, toId, type)?.let { (edge, exp) -> edge as SchemaEdgeLike<ID> to remainingTtl(exp) } ?: (null to null) }
        }.mapLeft { AbyssError.Unexpected(it) }

    override suspend fun loadEdges(fromId: ID): Either<AbyssError, List<Pair<SchemaEdgeLike<ID>, Duration?>>> =
        Either.catch {
            @Suppress("UNCHECKED_CAST")
            withContext(Dispatchers.IO) { queryEdgesYcql(selectEdgesYcql, fromId).map { (e, exp) -> e as SchemaEdgeLike<ID> to remainingTtl(exp) } }
        }.mapLeft { AbyssError.Unexpected(it) }

    // Ephemeral edges are outgoing-only (TODO 1.13): no reverse index is stored, so incoming
    // lookups have nothing to warm from. Callers needing reverse traversal model an explicit
    // opposite outgoing edge. Persistent in-edges are unaffected (YSQL scans the edges table).
    override suspend fun loadInEdges(toId: ID): Either<AbyssError, List<Pair<SchemaEdgeLike<ID>, Duration?>>> =
        Either.Right(emptyList())

    override suspend fun transaction(block: suspend AbyssEphemeralStoreTransactionLike<ID>.() -> Unit): Either<AbyssError, Unit> =
        Either.catch {
            val tx = EphemeralTransaction()
            tx.block()
            if (tx.ops.isNotEmpty()) withContext(Dispatchers.IO) { commitYcql(tx.ops) }
        }.mapLeft { AbyssError.Unexpected(it) }

    override fun close() {
        runCatching { ycql.close() }.onFailure { log.warn("Failed to close YCQL session", it) }
    }

    private fun remainingTtl(expiresAt: java.time.Instant?): Duration? {
        if (expiresAt == null) return null
        val remaining = java.time.Duration.between(java.time.Instant.now(), expiresAt).toMillis()
        return remaining.milliseconds
    }

    private fun idBuf(id: ID): ByteBuffer = ByteBuffer.wrap(adapter.toNodeId(id).bytes)

    private fun queryNodeYcql(id: ID): Pair<NodeLike<*>, java.time.Instant?>? {
        val row = ycql.execute(selectNodeYcql.bind(idBuf(id))).one() ?: return null
        val data = row.getString("data") ?: return null
        val ttlExpiration = row.getInstant("ttl_expiration")
        if(ttlExpiration?.isBefore(java.time.Instant.now()) ?: false) return null
        return json.decodeFromString(nodeSer, data) to ttlExpiration
    }

    private fun queryEdgeYcql(fromId: ID, toId: ID, type: String): Pair<SchemaEdgeLike<*>, java.time.Instant?>? {
        val row = ycql.execute(selectEdgeYcql.bind(idBuf(fromId), idBuf(toId), type)).one() ?: return null
        val data = row.getString("data") ?: return null
        val ttlExpiration = row.getInstant("ttl_expiration")
        if(ttlExpiration?.isBefore(java.time.Instant.now()) ?: false) return null
        return json.decodeFromString(edgeSer, data) to ttlExpiration
    }

    private fun queryEdgesYcql(stmt: PreparedStatement, id: ID): List<Pair<SchemaEdgeLike<*>, java.time.Instant?>> =
        ycql.execute(stmt.bind(idBuf(id)))
            .mapNotNull { row ->
                val data = row.getString("data") ?: return@mapNotNull null
                val ttlExpiration = row.getInstant("ttl_expiration")
                if(ttlExpiration?.isBefore(java.time.Instant.now()) ?: false) return@mapNotNull null
                json.decodeFromString(edgeSer, data) to ttlExpiration
            }

    private fun <T> jsonPair(ser: SerializationStrategy<T>, value: T): Pair<String, String> {
        val el = json.encodeToJsonElement(ser, value)
        return el.jsonObject["type"]!!.jsonPrimitive.content to el.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun commitYcql(ops: List<EphemeralOp<ID>>) {
        for (op in ops) when (op) {
            is EphemeralOp.SaveNode -> {
                val (type, data) = jsonPair(nodeSer, op.node as NodeLike<*>)
                val ttl = op.ttl.inWholeSeconds
                val now = java.time.Instant.now()
                val expiresAt = now.plusSeconds(ttl)
                ycql.execute(SimpleStatement.newInstance(
                    "INSERT INTO $ycqlKeyspace.ephemeral_nodes (id, type, data, tags, created_at, updated_at, ttl_expiration) VALUES (?, ?, ?, ?, ?, ?, ?) USING TTL $ttl",
                    idBuf(op.node.id), type, data, op.node.tags, now, now, expiresAt
                ))
            }
            // Outgoing-only (TODO 1.13): a single-row INSERT is atomic in YCQL, so no reverse
            // write, no heal machinery, no dangling window.
            is EphemeralOp.SaveEdge -> {
                val (type, data) = jsonPair(edgeSer, op.edge as SchemaEdgeLike<*>)
                val ttl = op.ttl.inWholeSeconds.toInt()
                val now = java.time.Instant.now()
                val expiresAt = now.plusSeconds(ttl.toLong())
                ycql.execute(SimpleStatement.newInstance(
                    "INSERT INTO $ycqlKeyspace.ephemeral_edges (from_id, to_id, type, data, tags, created_at, updated_at, ttl_expiration) VALUES (?, ?, ?, ?, ?, ?, ?, ?) USING TTL $ttl",
                    idBuf(op.edge.fromId), idBuf(op.edge.toId), type, data,
                    op.edge.tags, now, now, expiresAt
                ))
            }
            is EphemeralOp.DeleteNode -> ycql.execute(deleteNodeYcql.bind(idBuf(op.id)))
            is EphemeralOp.DeleteEdge -> ycql.execute(deleteEdgeYcql.bind(idBuf(op.fromId), idBuf(op.toId), op.type))
        }
    }

    private inner class EphemeralTransaction : AbyssEphemeralStoreTransactionLike<ID> {
        val ops = mutableListOf<EphemeralOp<ID>>()
        override fun saveNode(node: NodeLike<ID>, ttl: Duration) { ops += EphemeralOp.SaveNode(node, ttl) }
        override fun saveEdge(edge: SchemaEdgeLike<ID>, ttl: Duration) { ops += EphemeralOp.SaveEdge(edge, ttl) }
        override fun deleteNode(id: ID) { ops += EphemeralOp.DeleteNode(id) }
        override fun deleteEdge(fromId: ID, toId: ID, type: String) { ops += EphemeralOp.DeleteEdge(fromId, toId, type) }
    }

    companion object {
        fun <ID> create(
            adapter: KeyAdapter<ID>,
            ycqlHost: String = "localhost",
            ycqlPort: Int = 9042,
            ycqlDatacenter: String = "datacenter1",
            module: SerializersModule = EmptySerializersModule(),
            ycqlKeyspace: String = "abyss_test_graph"
        ): YugabyteEphemeralStore<ID> {
            val session = CqlSession.builder()
                .addContactPoint(InetSocketAddress(ycqlHost, ycqlPort))
                .withLocalDatacenter(ycqlDatacenter)
                .build()
            return YugabyteEphemeralStore(adapter, session, module, ycqlKeyspace)
        }
    }
}
