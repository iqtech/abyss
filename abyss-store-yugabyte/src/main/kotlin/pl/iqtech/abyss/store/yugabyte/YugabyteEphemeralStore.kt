package pl.iqtech.abyss.store.yugabyte

import arrow.core.Either
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.datastax.oss.driver.api.core.cql.SimpleStatement
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
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreLike
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreTransactionLike
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.StoredEdge
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.abyssSerializersModule
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private sealed interface EphemeralOp {
    data class SaveNode(val id: NodeId, val node: NodeLike<*>, val ttl: Duration, val tags: Set<String>) : EphemeralOp
    data class SaveEdge(val fromId: NodeId, val toId: NodeId, val edge: EdgeLike<*, *>, val ttl: Duration, val tags: Set<String>) : EphemeralOp
    data class DeleteNode(val id: NodeId) : EphemeralOp
    data class DeleteEdge(val fromId: NodeId, val toId: NodeId, val type: String) : EphemeralOp
}

class YugabyteEphemeralStore(
    private val ycql: CqlSession,
    module: SerializersModule = EmptySerializersModule(),
    private val ycqlKeyspace: String = "abyss_test_graph"
) : AbyssEphemeralStoreLike, Closeable {

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
    private val edgeSer = PolymorphicSerializer(EdgeLike::class) as kotlinx.serialization.KSerializer<EdgeLike<*, *>>

    private val selectNodeYcql: PreparedStatement =
        ycql.prepare("SELECT data, ttl_expiration FROM $ycqlKeyspace.ephemeral_nodes WHERE id = ?")
    private val selectEdgeYcql: PreparedStatement =
        ycql.prepare("SELECT data, ttl_expiration FROM $ycqlKeyspace.ephemeral_edges WHERE from_id = ? AND to_id = ? AND type = ?")
    private val selectEdgesYcql: PreparedStatement =
        ycql.prepare("SELECT to_id, data, ttl_expiration FROM $ycqlKeyspace.ephemeral_edges WHERE from_id = ?")
    private val deleteNodeYcql: PreparedStatement =
        ycql.prepare("DELETE FROM $ycqlKeyspace.ephemeral_nodes WHERE id = ?")
    private val deleteEdgeYcql: PreparedStatement =
        ycql.prepare("DELETE FROM $ycqlKeyspace.ephemeral_edges WHERE from_id = ? AND to_id = ? AND type = ?")

    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> =
        Either.catch {
            withContext(Dispatchers.IO) { queryNodeYcql(id)?.let { (node, exp) -> node to remainingTtl(exp) } ?: (null to null) }
        }.mapLeft { AbyssError.Unexpected(it) }

    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> =
        Either.catch {
            withContext(Dispatchers.IO) { queryEdgeYcql(fromId, toId, type)?.let { (edge, exp) -> edge to remainingTtl(exp) } ?: (null to null) }
        }.mapLeft { AbyssError.Unexpected(it) }

    override suspend fun loadEdges(fromId: NodeId): Either<AbyssError, List<StoredEdge>> =
        Either.catch {
            withContext(Dispatchers.IO) { queryEdgesYcql(selectEdgesYcql, fromId).map { (to, edge, exp) -> StoredEdge(fromId, to, edge, remainingTtl(exp)) } }
        }.mapLeft { AbyssError.Unexpected(it) }

    // Ephemeral edges are outgoing-only (TODO 1.13): no reverse index is stored, so incoming
    // lookups have nothing to warm from. Callers needing reverse traversal model an explicit
    // opposite outgoing edge. Persistent in-edges are unaffected (YSQL scans the edges table).
    override suspend fun loadInEdges(toId: NodeId): Either<AbyssError, List<StoredEdge>> =
        Either.Right(emptyList())

    // Admin/orphan-sweep scan (TODO 1.23), token-range fan-out proven in
    // TokenRangeScanFeasibilityTest. ephemeral_nodes has a `tags` column but no secondary index and
    // no `transactions=true` (can't coexist with per-row TTL) — a CQL `tags CONTAINS ?` predicate
    // would need ALLOW FILTERING, so the tag is matched client-side against each row instead, same
    // as the proven test.
    override fun scanNodeIds(tag: String?, parallelism: Int): Flow<NodeId> = channelFlow {
        val tokenMap = ycql.metadata.tokenMap.orElseThrow { IllegalStateException("no TokenMap for this session") }
        val ranges = tokenMap.tokenRanges.flatMap { it.unwrap() }.flatMap { it.splitEvenly(parallelism) }
        val quarters = ranges.withIndex().groupBy { (i, _) -> i % parallelism }.values.map { chunk -> chunk.map { it.value } }
        quarters.forEach { quarter ->
            launch(Dispatchers.IO) {
                quarter.forEach { range ->
                    val startTok = tokenMap.format(range.start)
                    val endTok = tokenMap.format(range.end)
                    val rs = ycql.execute(SimpleStatement.newInstance(
                        "SELECT id, tags FROM $ycqlKeyspace.ephemeral_nodes WHERE token(id) > $startTok AND token(id) <= $endTok"
                    ))
                    for (row in rs) {
                        val tags = row.getSet("tags", String::class.java) ?: emptySet()
                        if (tag == null || tags.contains(tag)) send(row.getByteBuffer("id")!!.toNodeId())
                    }
                }
            }
        }
    }

    override suspend fun transaction(block: suspend AbyssEphemeralStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> =
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

    private fun idBuf(id: NodeId): ByteBuffer = ByteBuffer.wrap(id.bytes)

    private fun ByteBuffer.toNodeId(): NodeId = ByteArray(remaining()).also { duplicate().get(it) }.let { NodeId(it) }

    private fun queryNodeYcql(id: NodeId): Pair<NodeLike<*>, java.time.Instant?>? {
        val row = ycql.execute(selectNodeYcql.bind(idBuf(id))).one() ?: return null
        val data = row.getString("data") ?: return null
        val ttlExpiration = row.getInstant("ttl_expiration")
        if(ttlExpiration?.isBefore(java.time.Instant.now()) ?: false) return null
        return json.decodeFromString(nodeSer, data) to ttlExpiration
    }

    private fun queryEdgeYcql(fromId: NodeId, toId: NodeId, type: String): Pair<EdgeLike<*, *>, java.time.Instant?>? {
        val row = ycql.execute(selectEdgeYcql.bind(idBuf(fromId), idBuf(toId), type)).one() ?: return null
        val data = row.getString("data") ?: return null
        val ttlExpiration = row.getInstant("ttl_expiration")
        if(ttlExpiration?.isBefore(java.time.Instant.now()) ?: false) return null
        return json.decodeFromString(edgeSer, data) to ttlExpiration
    }

    private fun queryEdgesYcql(stmt: PreparedStatement, id: NodeId): List<Triple<NodeId, EdgeLike<*, *>, java.time.Instant?>> =
        ycql.execute(stmt.bind(idBuf(id)))
            .mapNotNull { row ->
                val data = row.getString("data") ?: return@mapNotNull null
                val ttlExpiration = row.getInstant("ttl_expiration")
                if(ttlExpiration?.isBefore(java.time.Instant.now()) ?: false) return@mapNotNull null
                val to = row.getByteBuffer("to_id")?.toNodeId() ?: return@mapNotNull null
                Triple(to, json.decodeFromString(edgeSer, data), ttlExpiration)
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
                val now = java.time.Instant.now()
                val expiresAt = now.plusSeconds(ttl)
                ycql.execute(SimpleStatement.newInstance(
                    "UPDATE $ycqlKeyspace.ephemeral_nodes USING TTL $ttl SET type = ?, data = ?, tags = tags + ?, " +
                    "created_at = ?, updated_at = ?, ttl_expiration = ? WHERE id = ?",
                    type, data, op.tags, now, now, expiresAt, idBuf(op.id)
                ))
            }
            // Outgoing-only (TODO 1.13): a single-row INSERT is atomic in YCQL, so no reverse
            // write, no heal machinery, no dangling window.
            is EphemeralOp.SaveEdge -> {
                val (type, data) = jsonPair(edgeSer, op.edge)
                val ttl = op.ttl.inWholeSeconds.toInt()
                val now = java.time.Instant.now()
                val expiresAt = now.plusSeconds(ttl.toLong())
                ycql.execute(SimpleStatement.newInstance(
                    "UPDATE $ycqlKeyspace.ephemeral_edges USING TTL $ttl SET data = ?, tags = tags + ?, " +
                    "created_at = ?, updated_at = ?, ttl_expiration = ? WHERE from_id = ? AND to_id = ? AND type = ?",
                    data, op.tags, now, now, expiresAt, idBuf(op.fromId), idBuf(op.toId), type
                ))
            }
            is EphemeralOp.DeleteNode -> ycql.execute(deleteNodeYcql.bind(idBuf(op.id)))
            is EphemeralOp.DeleteEdge -> ycql.execute(deleteEdgeYcql.bind(idBuf(op.fromId), idBuf(op.toId), op.type))
        }
    }

    private inner class EphemeralTransaction : AbyssEphemeralStoreTransactionLike {
        val ops = mutableListOf<EphemeralOp>()
        override fun saveNode(id: NodeId, node: NodeLike<*>, ttl: Duration, tags: Set<String>) { ops += EphemeralOp.SaveNode(id, node, ttl, tags) }
        override fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, ttl: Duration, tags: Set<String>) { ops += EphemeralOp.SaveEdge(fromId, toId, edge, ttl, tags) }
        override fun deleteNode(id: NodeId) { ops += EphemeralOp.DeleteNode(id) }
        override fun deleteEdge(fromId: NodeId, toId: NodeId, type: String) { ops += EphemeralOp.DeleteEdge(fromId, toId, type) }
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
