package pl.iqtech.abyss.store.yugabyte

import arrow.core.Either
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
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
import java.util.UUID
import javax.sql.DataSource

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

    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> = TODO()

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
