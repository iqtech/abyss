package pl.iqtech.abyss.store.yugabyte

import arrow.core.Either
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import java.net.InetSocketAddress
import java.sql.DriverManager
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

@Serializable
@SerialName("yb_test_node")
data class YbTestNode(
    override val id: Uuid,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
    val name: String
) : NodeLike

@Serializable
@SerialName("yb_test_edge")
data class YbTestEdge(
    override val fromId: Uuid,
    override val toId: Uuid,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
    val label: String
) : EdgeLike

private val ybModule = SerializersModule {
    polymorphic(NodeLike::class) { subclass(YbTestNode::class) }
    polymorphic(EdgeLike::class) { subclass(YbTestEdge::class) }
}

private val ybStore by lazy {
    YugabyteAbyssStoreLike.create(
        ysqlUrl = "jdbc:postgresql://localhost:5433/abyss_test_graph",
        ysqlUser = "abyss",
        ysqlPassword = "abyss",
        module = ybModule
    )
}

class LoadTest {

    @Test fun `loadNode returns node inserted in ysql`() {
        val id = Uuid.random()
        val json = nodeJson(id, "ysql-node")
        insertYsqlNode(id, json)

        val result = runBlocking { ybStore.loadNode(id) }
        assertIs<Either.Right<Pair<NodeLike?, *>>>(result)
        assertEquals("ysql-node", assertIs<YbTestNode>(result.value.first).name)
        assertEquals(null, result.value.second)
    }

    @Test fun `loadNode returns null for absent id`() {
        val result = runBlocking { ybStore.loadNode(Uuid.random()) }
        assertIs<Either.Right<Pair<NodeLike?, *>>>(result)
        assertEquals(null, result.value.first)
    }

    @Test fun `loadNode returns node inserted in ycql`() {
        val id = Uuid.random()
        val json = nodeJson(id, "ycql-node")
        insertYcqlNode(id, json)

        val result = runBlocking { ybStore.loadNode(id) }
        assertIs<Either.Right<Pair<NodeLike?, *>>>(result)
        assertEquals("ycql-node", assertIs<YbTestNode>(result.value.first).name)
    }

    @Test fun `loadNode from ycql returns positive remaining TTL`() {
        val node = YbTestNode(id = Uuid.random(), name = "ttl-node")
        assertIs<Either.Right<Unit>>(runBlocking { ybStore.transaction { saveNode(node, 3600.seconds) } })

        val result = runBlocking { ybStore.loadNode(node.id) }
        assertIs<Either.Right<Pair<NodeLike?, *>>>(result)
        assertEquals("ttl-node", assertIs<YbTestNode>(result.value.first).name)
        val remaining = result.value.second as? kotlin.time.Duration
        assertTrue(remaining != null && remaining > 0.seconds, "Expected positive remaining TTL, got $remaining")
    }

    @Test fun `loadEdge returns edge inserted in ysql`() {
        val fromId = Uuid.random()
        val toId = Uuid.random()
        val json = edgeJson(fromId, toId, "ysql-edge")
        insertYsqlEdge(fromId, toId, json)

        val result = runBlocking { ybStore.loadEdge(fromId, toId, "yb_test_edge") }
        assertIs<Either.Right<Pair<EdgeLike?, *>>>(result)
        assertEquals("ysql-edge", assertIs<YbTestEdge>(result.value.first).label)
        assertEquals(null, result.value.second)
    }

    @Test fun `loadEdge returns null for absent key`() {
        val result = runBlocking { ybStore.loadEdge(Uuid.random(), Uuid.random(), "yb_test_edge") }
        assertIs<Either.Right<Pair<EdgeLike?, *>>>(result)
        assertEquals(null, result.value.first)
    }

    @Test fun `transaction saveNode with null ttl persists to ysql`() {
        val node = YbTestNode(id = Uuid.random(), name = "tx-ysql-node")
        assertIs<Either.Right<Unit>>(runBlocking { ybStore.transaction { saveNode(node) } })
        val loaded = runBlocking { ybStore.loadNode(node.id) }
        assertEquals("tx-ysql-node", assertIs<YbTestNode>((loaded as Either.Right).value.first).name)
    }

    @Test fun `transaction saveNode with ttl persists to ycql`() {
        val node = YbTestNode(id = Uuid.random(), name = "tx-ycql-node")
        assertIs<Either.Right<Unit>>(runBlocking { ybStore.transaction { saveNode(node, 3600.seconds) } })
        val loaded = runBlocking { ybStore.loadNode(node.id) }
        assertEquals("tx-ycql-node", assertIs<YbTestNode>((loaded as Either.Right).value.first).name)
    }

    @Test fun `transaction saveEdge with null ttl persists to ysql`() {
        val edge = YbTestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "tx-ysql-edge")
        assertIs<Either.Right<Unit>>(runBlocking { ybStore.transaction { saveEdge(edge) } })
        val loaded = runBlocking { ybStore.loadEdge(edge.fromId, edge.toId, "yb_test_edge") }
        assertEquals("tx-ysql-edge", assertIs<YbTestEdge>((loaded as Either.Right).value.first).label)
    }

    @Test fun `ephemeral node with ttl is readable before expiry and gone after`() {
        val node = YbTestNode(id = Uuid.random(), name = "ephemeral")
        assertIs<Either.Right<Unit>>(runBlocking { ybStore.transaction { saveNode(node, 5.seconds) } })

        Thread.sleep(1_000)
        val before = runBlocking { ybStore.loadNode(node.id) }
        assertEquals("ephemeral", assertIs<YbTestNode>((before as Either.Right).value.first).name)

        Thread.sleep(5_000)
        val after = runBlocking { ybStore.loadNode(node.id) }
        assertEquals(null, (after as Either.Right).value.first)
    }

    @Test fun `transaction deleteNode removes from ysql`() {
        val node = YbTestNode(id = Uuid.random(), name = "to-delete")
        runBlocking { ybStore.transaction { saveNode(node) } }
        assertIs<Either.Right<Unit>>(runBlocking { ybStore.transaction { deleteNode(node.id) } })
        val loaded = runBlocking { ybStore.loadNode(node.id) }
        assertEquals(null, (loaded as Either.Right).value.first)
    }

    @Test fun `loadEdge returns edge inserted in ycql`() {
        val fromId = Uuid.random()
        val toId = Uuid.random()
        val json = edgeJson(fromId, toId, "ycql-edge")
        insertYcqlEdge(fromId, toId, json)

        val result = runBlocking { ybStore.loadEdge(fromId, toId, "yb_test_edge") }
        assertIs<Either.Right<Pair<EdgeLike?, *>>>(result)
        assertEquals("ycql-edge", assertIs<YbTestEdge>(result.value.first).label)
    }

    @Test fun `loadEdges returns edges by fromId from ysql`() {
        val fromId = Uuid.random()
        val toId1 = Uuid.random()
        val toId2 = Uuid.random()
        insertYsqlEdge(fromId, toId1, edgeJson(fromId, toId1, "edge-1"))
        insertYsqlEdge(fromId, toId2, edgeJson(fromId, toId2, "edge-2"))

        val result = runBlocking { ybStore.loadEdges(fromId) }
        assertIs<Either.Right<List<*>>>(result)
        assertEquals(2, result.value.size)
    }

    @Test fun `loadEdges returns edges by fromId from ycql`() {
        val fromId = Uuid.random()
        val toId1 = Uuid.random()
        val toId2 = Uuid.random()
        insertYcqlEdge(fromId, toId1, edgeJson(fromId, toId1, "ycql-1"))
        insertYcqlEdge(fromId, toId2, edgeJson(fromId, toId2, "ycql-2"))

        val result = runBlocking { ybStore.loadEdges(fromId) }
        assertIs<Either.Right<List<*>>>(result)
        assertEquals(2, result.value.size)
    }

    @Test fun `loadInEdges returns edges by toId from ysql`() {
        val toId = Uuid.random()
        val fromId1 = Uuid.random()
        val fromId2 = Uuid.random()
        insertYsqlEdge(fromId1, toId, edgeJson(fromId1, toId, "in-1"))
        insertYsqlEdge(fromId2, toId, edgeJson(fromId2, toId, "in-2"))

        val result = runBlocking { ybStore.loadInEdges(toId) }
        assertIs<Either.Right<List<*>>>(result)
        assertEquals(2, result.value.size)
    }

    @Test fun `loadInEdges returns edges by toId from ycql`() {
        val toId = Uuid.random()
        val fromId1 = Uuid.random()
        val fromId2 = Uuid.random()
        insertYcqlReverseEdge(fromId1, toId, edgeJson(fromId1, toId, "rev-1"))
        insertYcqlReverseEdge(fromId2, toId, edgeJson(fromId2, toId, "rev-2"))

        val result = runBlocking { ybStore.loadInEdges(toId) }
        assertIs<Either.Right<List<*>>>(result)
        assertEquals(2, result.value.size)
    }

    @Test fun `transaction saveEdge with ttl writes to both ycql tables`() {
        val edge = YbTestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "dual-write")
        assertIs<Either.Right<Unit>>(runBlocking { ybStore.transaction { saveEdge(edge, 3600.seconds) } })

        val outResult = runBlocking { ybStore.loadEdges(edge.fromId) }
        assertEquals(1, (outResult as Either.Right).value.size)

        val inResult = runBlocking { ybStore.loadInEdges(edge.toId) }
        assertEquals(1, (inResult as Either.Right).value.size)
    }
}

private fun nodeJson(id: Uuid, name: String) =
    """{"type":"yb_test_node","id":"$id","tags":[],"createdAt":"1970-01-01T00:00:00Z","updatedAt":"1970-01-01T00:00:00Z","name":"$name"}"""

private fun edgeJson(fromId: Uuid, toId: Uuid, label: String) =
    """{"type":"yb_test_edge","fromId":"$fromId","toId":"$toId","tags":[],"createdAt":"1970-01-01T00:00:00Z","updatedAt":"1970-01-01T00:00:00Z","label":"$label"}"""

private fun insertYsqlNode(id: Uuid, json: String) {
    DriverManager.getConnection("jdbc:postgresql://localhost:5433/abyss_test_graph", "abyss", "abyss").use { conn ->
        conn.prepareStatement(
            "INSERT INTO abyss.nodes (id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, '{}', now(), now())"
        ).use { stmt ->
            stmt.setObject(1, id.toJavaUuid())
            stmt.setString(2, "yb_test_node")
            stmt.setObject(3, json, Types.OTHER)
            stmt.executeUpdate()
        }
    }
}

private fun insertYcqlNode(id: Uuid, json: String) {
    CqlSession.builder()
        .addContactPoint(InetSocketAddress("localhost", 9042))
        .withLocalDatacenter("datacenter1")
        .build()
        .use { session ->
            session.execute(
                SimpleStatement.newInstance(
                    "INSERT INTO abyss_test_graph.ephemeral_nodes (id, type, data, tags, created_at, updated_at, ttl_expiration) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    id.toJavaUuid(), "yb_test_node", json, emptyList<String>(), java.time.Instant.EPOCH, java.time.Instant.EPOCH,
                    java.time.Instant.now().plusSeconds(3600)
                )
            )
        }
}

private fun insertYsqlEdge(fromId: Uuid, toId: Uuid, json: String) {
    DriverManager.getConnection("jdbc:postgresql://localhost:5433/abyss_test_graph", "abyss", "abyss").use { conn ->
        conn.prepareStatement(
            "INSERT INTO abyss.edges (from_id, to_id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, '{}', now(), now())"
        ).use { stmt ->
            stmt.setObject(1, fromId.toJavaUuid())
            stmt.setObject(2, toId.toJavaUuid())
            stmt.setString(3, "yb_test_edge")
            stmt.setObject(4, json, Types.OTHER)
            stmt.executeUpdate()
        }
    }
}

private fun insertYcqlEdge(fromId: Uuid, toId: Uuid, json: String) {
    CqlSession.builder()
        .addContactPoint(InetSocketAddress("localhost", 9042))
        .withLocalDatacenter("datacenter1")
        .build()
        .use { session ->
            session.execute(
                SimpleStatement.newInstance(
                    "INSERT INTO abyss_test_graph.ephemeral_edges (from_id, to_id, type, data, tags, created_at, updated_at, ttl_expiration) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    fromId.toJavaUuid(), toId.toJavaUuid(), "yb_test_edge", json, emptyList<String>(), java.time.Instant.EPOCH, java.time.Instant.EPOCH,
                    java.time.Instant.now().plusSeconds(3600)
                )
            )
        }
}

private fun insertYcqlReverseEdge(fromId: Uuid, toId: Uuid, json: String) {
    CqlSession.builder()
        .addContactPoint(InetSocketAddress("localhost", 9042))
        .withLocalDatacenter("datacenter1")
        .build()
        .use { session ->
            session.execute(
                SimpleStatement.newInstance(
                    "INSERT INTO abyss_test_graph.ephemeral_reverse_edges (to_id, from_id, type, data, tags, created_at, updated_at, ttl_expiration) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    toId.toJavaUuid(), fromId.toJavaUuid(), "yb_test_edge", json, emptyList<String>(), java.time.Instant.EPOCH, java.time.Instant.EPOCH,
                    java.time.Instant.now().plusSeconds(3600)
                )
            )
        }
}
