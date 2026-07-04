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
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.sql.DriverManager
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@Serializable
@SerialName("yb_test_node")
data class YbTestNode(
    override val id: Uuid,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
    val name: String
) : NodeLike<Uuid>

@Serializable
@SerialName("yb_test_edge")
data class YbTestEdge(
    override val fromId: Uuid,
    override val toId: Uuid,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
    val label: String
) : EdgeLike<Uuid, Uuid>

private val ybModule = SerializersModule {
    polymorphic(NodeLike::class) { subclass(YbTestNode::class) }
    polymorphic(EdgeLike::class) { subclass(YbTestEdge::class) }
}

private val ybPersistentStore by lazy {
    YugabytePersistentStore.create(
        ysqlUrl = "jdbc:postgresql://localhost:5433/abyss_test_graph",
        ysqlUser = "abyss",
        ysqlPassword = "abyss",
        module = ybModule
    )
}

private val ybEphemeralStore by lazy {
    YugabyteEphemeralStore.create(module = ybModule)
}

// Stores are NodeId-keyed now; tests convert their domain Uuids at the boundary.
private fun nid(u: Uuid): NodeId = UuidKeyAdapter.toNodeId(u)

class LoadTest {

    @Test fun `loadNode returns node inserted in ysql`() {
        val id = Uuid.random()
        insertYsqlNode(id, nodeJson(id, "ysql-node"))

        val result = runBlocking { ybPersistentStore.loadNode(nid(id)) }
        assertIs<Either.Right<Pair<NodeLike<*>?, *>>>(result)
        assertEquals("ysql-node", assertIs<YbTestNode>(result.value.first).name)
        assertEquals(null, result.value.second)
    }

    @Test fun `loadNode returns null for absent id`() {
        val result = runBlocking { ybPersistentStore.loadNode(nid(Uuid.random())) }
        assertIs<Either.Right<Pair<NodeLike<*>?, *>>>(result)
        assertEquals(null, result.value.first)
    }

    @Test fun `loadNode returns node inserted in ycql`() {
        val id = Uuid.random()
        insertYcqlNode(id, nodeJson(id, "ycql-node"))

        val result = runBlocking { ybEphemeralStore.loadNode(nid(id)) }
        assertIs<Either.Right<Pair<NodeLike<*>?, *>>>(result)
        assertEquals("ycql-node", assertIs<YbTestNode>(result.value.first).name)
    }

    @Test fun `loadNode from ycql returns positive remaining TTL`() {
        val node = YbTestNode(id = Uuid.random(), name = "ttl-node")
        assertIs<Either.Right<Unit>>(runBlocking { ybEphemeralStore.transaction { saveNode(nid(node.id), node, 3600.seconds) } })

        val result = runBlocking { ybEphemeralStore.loadNode(nid(node.id)) }
        assertIs<Either.Right<Pair<NodeLike<*>?, *>>>(result)
        assertEquals("ttl-node", assertIs<YbTestNode>(result.value.first).name)
        val remaining = result.value.second as? kotlin.time.Duration
        assertTrue(remaining != null && remaining > 0.seconds, "Expected positive remaining TTL, got $remaining")
    }

    @Test fun `loadEdge returns edge inserted in ysql`() {
        val fromId = Uuid.random()
        val toId = Uuid.random()
        insertYsqlEdge(fromId, toId, edgeJson(fromId, toId, "ysql-edge"))

        val result = runBlocking { ybPersistentStore.loadEdge(nid(fromId), nid(toId), "yb_test_edge") }
        assertIs<Either.Right<Pair<EdgeLike<*, *>?, *>>>(result)
        assertEquals("ysql-edge", assertIs<YbTestEdge>(result.value.first).label)
        assertEquals(null, result.value.second)
    }

    @Test fun `loadEdge returns null for absent key`() {
        val result = runBlocking { ybPersistentStore.loadEdge(nid(Uuid.random()), nid(Uuid.random()), "yb_test_edge") }
        assertIs<Either.Right<Pair<EdgeLike<*, *>?, *>>>(result)
        assertEquals(null, result.value.first)
    }

    @Test fun `transaction saveNode persists to ysql`() {
        val node = YbTestNode(id = Uuid.random(), name = "tx-ysql-node")
        assertIs<Either.Right<Unit>>(runBlocking { ybPersistentStore.transaction { saveNode(nid(node.id), node) } })
        val loaded = runBlocking { ybPersistentStore.loadNode(nid(node.id)) }
        assertEquals("tx-ysql-node", assertIs<YbTestNode>((loaded as Either.Right).value.first).name)
    }

    @Test fun `transaction saveNode with ttl persists to ycql`() {
        val node = YbTestNode(id = Uuid.random(), name = "tx-ycql-node")
        assertIs<Either.Right<Unit>>(runBlocking { ybEphemeralStore.transaction { saveNode(nid(node.id), node, 3600.seconds) } })
        val loaded = runBlocking { ybEphemeralStore.loadNode(nid(node.id)) }
        assertEquals("tx-ycql-node", assertIs<YbTestNode>((loaded as Either.Right).value.first).name)
    }

    @Test fun `transaction saveEdge persists to ysql`() {
        val edge = YbTestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "tx-ysql-edge")
        assertIs<Either.Right<Unit>>(runBlocking { ybPersistentStore.transaction { saveEdge(nid(edge.fromId), nid(edge.toId), edge) } })
        val loaded = runBlocking { ybPersistentStore.loadEdge(nid(edge.fromId), nid(edge.toId), "yb_test_edge") }
        assertEquals("tx-ysql-edge", assertIs<YbTestEdge>((loaded as Either.Right).value.first).label)
    }

    @Test fun `ephemeral node with ttl is readable before expiry and gone after`() {
        val node = YbTestNode(id = Uuid.random(), name = "ephemeral")
        assertIs<Either.Right<Unit>>(runBlocking { ybEphemeralStore.transaction { saveNode(nid(node.id), node, 5.seconds) } })

        Thread.sleep(1_000)
        val before = runBlocking { ybEphemeralStore.loadNode(nid(node.id)) }
        assertEquals("ephemeral", assertIs<YbTestNode>((before as Either.Right).value.first).name)

        Thread.sleep(5_000)
        val after = runBlocking { ybEphemeralStore.loadNode(nid(node.id)) }
        assertEquals(null, (after as Either.Right).value.first)
    }

    @Test fun `transaction deleteNode removes from ysql`() {
        val node = YbTestNode(id = Uuid.random(), name = "to-delete")
        runBlocking { ybPersistentStore.transaction { saveNode(nid(node.id), node) } }
        assertIs<Either.Right<Unit>>(runBlocking { ybPersistentStore.transaction { deleteNode(nid(node.id)) } })
        val loaded = runBlocking { ybPersistentStore.loadNode(nid(node.id)) }
        assertEquals(null, (loaded as Either.Right).value.first)
    }

    @Test fun `loadEdge returns edge inserted in ycql`() {
        val fromId = Uuid.random()
        val toId = Uuid.random()
        insertYcqlEdge(fromId, toId, edgeJson(fromId, toId, "ycql-edge"))

        val result = runBlocking { ybEphemeralStore.loadEdge(nid(fromId), nid(toId), "yb_test_edge") }
        assertIs<Either.Right<Pair<EdgeLike<*, *>?, *>>>(result)
        assertEquals("ycql-edge", assertIs<YbTestEdge>(result.value.first).label)
    }

    @Test fun `loadEdges returns edges by fromId from ysql`() {
        val fromId = Uuid.random()
        val toId1 = Uuid.random()
        val toId2 = Uuid.random()
        insertYsqlEdge(fromId, toId1, edgeJson(fromId, toId1, "edge-1"))
        insertYsqlEdge(fromId, toId2, edgeJson(fromId, toId2, "edge-2"))

        val result = runBlocking { ybPersistentStore.loadEdges(nid(fromId)) }
        assertIs<Either.Right<List<*>>>(result)
        assertEquals(2, result.value.size)
    }

    @Test fun `loadEdges returns edges by fromId from ycql`() {
        val fromId = Uuid.random()
        val toId1 = Uuid.random()
        val toId2 = Uuid.random()
        insertYcqlEdge(fromId, toId1, edgeJson(fromId, toId1, "ycql-1"))
        insertYcqlEdge(fromId, toId2, edgeJson(fromId, toId2, "ycql-2"))

        val result = runBlocking { ybEphemeralStore.loadEdges(nid(fromId)) }
        assertIs<Either.Right<List<*>>>(result)
        assertEquals(2, result.value.size)
    }

    @Test fun `loadInEdges returns edges by toId from ysql`() {
        val toId = Uuid.random()
        val fromId1 = Uuid.random()
        val fromId2 = Uuid.random()
        insertYsqlEdge(fromId1, toId, edgeJson(fromId1, toId, "in-1"))
        insertYsqlEdge(fromId2, toId, edgeJson(fromId2, toId, "in-2"))

        val result = runBlocking { ybPersistentStore.loadInEdges(nid(toId)) }
        assertIs<Either.Right<List<*>>>(result)
        assertEquals(2, result.value.size)
    }

    // TODO 1.13: ephemeral edges are outgoing-only — loadInEdges has no reverse index to read.
    @Test fun `loadInEdges is always empty for ycql (outgoing-only)`() {
        val result = runBlocking { ybEphemeralStore.loadInEdges(nid(Uuid.random())) }
        assertIs<Either.Right<List<*>>>(result)
        assertEquals(0, result.value.size)
    }

    @Test fun `ephemeral saveEdge is outgoing-only - found via loadEdges, absent via loadInEdges`() {
        val edge = YbTestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "outgoing-only")
        assertIs<Either.Right<Unit>>(runBlocking { ybEphemeralStore.transaction { saveEdge(nid(edge.fromId), nid(edge.toId), edge, 3600.seconds) } })

        val outResult = runBlocking { ybEphemeralStore.loadEdges(nid(edge.fromId)) }
        assertEquals(1, (outResult as Either.Right).value.size)

        val inResult = runBlocking { ybEphemeralStore.loadInEdges(nid(edge.toId)) }
        assertEquals(0, (inResult as Either.Right).value.size)
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
            stmt.setBytes(1, UuidKeyAdapter.toNodeId(id).bytes)
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
                    ByteBuffer.wrap(UuidKeyAdapter.toNodeId(id).bytes), "yb_test_node", json, emptyList<String>(), java.time.Instant.EPOCH, java.time.Instant.EPOCH,
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
            stmt.setBytes(1, UuidKeyAdapter.toNodeId(fromId).bytes)
            stmt.setBytes(2, UuidKeyAdapter.toNodeId(toId).bytes)
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
                    ByteBuffer.wrap(UuidKeyAdapter.toNodeId(fromId).bytes), ByteBuffer.wrap(UuidKeyAdapter.toNodeId(toId).bytes), "yb_test_edge", json, emptyList<String>(), java.time.Instant.EPOCH, java.time.Instant.EPOCH,
                    java.time.Instant.now().plusSeconds(3600)
                )
            )
        }
}

