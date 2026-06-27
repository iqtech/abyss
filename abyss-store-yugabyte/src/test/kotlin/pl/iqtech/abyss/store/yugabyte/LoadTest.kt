package pl.iqtech.abyss.store.yugabyte

import arrow.core.Either
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Contextual
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
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

@Serializable
@SerialName("yb_test_node")
data class YbTestNode(
    @Contextual override val id: UUID,
    override val tags: List<String> = emptyList(),
    @Contextual override val createdAt: Instant = Instant.EPOCH,
    @Contextual override val updatedAt: Instant = Instant.EPOCH,
    val name: String
) : NodeLike

@Serializable
@SerialName("yb_test_edge")
data class YbTestEdge(
    @Contextual override val fromId: UUID,
    @Contextual override val toId: UUID,
    override val tags: List<String> = emptyList(),
    @Contextual override val createdAt: Instant = Instant.EPOCH,
    @Contextual override val updatedAt: Instant = Instant.EPOCH,
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
        val id = UUID.randomUUID()
        val json = nodeJson(id, "ysql-node")
        insertYsqlNode(id, json)

        val result = runBlocking { ybStore.loadNode(id) }
        assertIs<Either.Right<NodeLike?>>(result)
        assertEquals("ysql-node", assertIs<YbTestNode>(result.value).name)
    }

    @Test fun `loadNode returns null for absent id`() {
        val result = runBlocking { ybStore.loadNode(UUID.randomUUID()) }
        assertIs<Either.Right<NodeLike?>>(result)
        assertEquals(null, result.value)
    }

    @Test fun `loadNode returns node inserted in ycql`() {
        val id = UUID.randomUUID()
        val json = nodeJson(id, "ycql-node")
        insertYcqlNode(id, json)

        val result = runBlocking { ybStore.loadNode(id) }
        assertIs<Either.Right<NodeLike?>>(result)
        assertEquals("ycql-node", assertIs<YbTestNode>(result.value).name)
    }

    @Test fun `loadEdge returns edge inserted in ysql`() {
        val fromId = UUID.randomUUID()
        val toId = UUID.randomUUID()
        val json = edgeJson(fromId, toId, "ysql-edge")
        insertYsqlEdge(fromId, toId, json)

        val result = runBlocking { ybStore.loadEdge(fromId, toId, "yb_test_edge") }
        assertIs<Either.Right<EdgeLike?>>(result)
        assertEquals("ysql-edge", assertIs<YbTestEdge>(result.value).label)
    }

    @Test fun `loadEdge returns null for absent key`() {
        val result = runBlocking { ybStore.loadEdge(UUID.randomUUID(), UUID.randomUUID(), "yb_test_edge") }
        assertIs<Either.Right<EdgeLike?>>(result)
        assertEquals(null, result.value)
    }

    @Test fun `transaction saveNode with null ttl persists to ysql`() {
        val node = YbTestNode(id = UUID.randomUUID(), name = "tx-ysql-node")
        assertIs<Either.Right<Unit>>(runBlocking { ybStore.transaction { saveNode(node) } })
        val loaded = runBlocking { ybStore.loadNode(node.id) }
        assertEquals("tx-ysql-node", assertIs<YbTestNode>((loaded as Either.Right).value).name)
    }

    @Test fun `transaction saveNode with ttl persists to ycql`() {
        val node = YbTestNode(id = UUID.randomUUID(), name = "tx-ycql-node")
        assertIs<Either.Right<Unit>>(runBlocking { ybStore.transaction { saveNode(node, 3600.seconds) } })
        val loaded = runBlocking { ybStore.loadNode(node.id) }
        assertEquals("tx-ycql-node", assertIs<YbTestNode>((loaded as Either.Right).value).name)
    }

    @Test fun `transaction saveEdge with null ttl persists to ysql`() {
        val edge = YbTestEdge(fromId = UUID.randomUUID(), toId = UUID.randomUUID(), label = "tx-ysql-edge")
        assertIs<Either.Right<Unit>>(runBlocking { ybStore.transaction { saveEdge(edge) } })
        val loaded = runBlocking { ybStore.loadEdge(edge.fromId, edge.toId, "yb_test_edge") }
        assertEquals("tx-ysql-edge", assertIs<YbTestEdge>((loaded as Either.Right).value).label)
    }

    @Test fun `ephemeral node with ttl is readable before expiry and gone after`() {
        val node = YbTestNode(id = UUID.randomUUID(), name = "ephemeral")
        assertIs<Either.Right<Unit>>(runBlocking { ybStore.transaction { saveNode(node, 5.seconds) } })

        Thread.sleep(1_000)
        val before = runBlocking { ybStore.loadNode(node.id) }
        assertEquals("ephemeral", assertIs<YbTestNode>((before as Either.Right).value).name)

        Thread.sleep(5_000)
        val after = runBlocking { ybStore.loadNode(node.id) }
        assertEquals(null, (after as Either.Right).value)
    }

    @Test fun `transaction deleteNode removes from ysql`() {
        val node = YbTestNode(id = UUID.randomUUID(), name = "to-delete")
        runBlocking { ybStore.transaction { saveNode(node) } }
        assertIs<Either.Right<Unit>>(runBlocking { ybStore.transaction { deleteNode(node.id) } })
        val loaded = runBlocking { ybStore.loadNode(node.id) }
        assertEquals(null, (loaded as Either.Right).value)
    }

    @Test fun `loadEdge returns edge inserted in ycql`() {
        val fromId = UUID.randomUUID()
        val toId = UUID.randomUUID()
        val json = edgeJson(fromId, toId, "ycql-edge")
        insertYcqlEdge(fromId, toId, json)

        val result = runBlocking { ybStore.loadEdge(fromId, toId, "yb_test_edge") }
        assertIs<Either.Right<EdgeLike?>>(result)
        assertEquals("ycql-edge", assertIs<YbTestEdge>(result.value).label)
    }

    @Test fun `loadEdges returns edges by fromId from ysql`() {
        val fromId = UUID.randomUUID()
        val toId1 = UUID.randomUUID()
        val toId2 = UUID.randomUUID()
        insertYsqlEdge(fromId, toId1, edgeJson(fromId, toId1, "edge-1"))
        insertYsqlEdge(fromId, toId2, edgeJson(fromId, toId2, "edge-2"))

        val result = runBlocking { ybStore.loadEdges(fromId) }
        assertIs<Either.Right<List<EdgeLike>>>(result)
        assertEquals(2, result.value.size)
    }

    @Test fun `loadEdges returns edges by fromId from ycql`() {
        val fromId = UUID.randomUUID()
        val toId1 = UUID.randomUUID()
        val toId2 = UUID.randomUUID()
        insertYcqlEdge(fromId, toId1, edgeJson(fromId, toId1, "ycql-1"))
        insertYcqlEdge(fromId, toId2, edgeJson(fromId, toId2, "ycql-2"))

        val result = runBlocking { ybStore.loadEdges(fromId) }
        assertIs<Either.Right<List<EdgeLike>>>(result)
        assertEquals(2, result.value.size)
    }

    @Test fun `loadInEdges returns edges by toId from ysql`() {
        val toId = UUID.randomUUID()
        val fromId1 = UUID.randomUUID()
        val fromId2 = UUID.randomUUID()
        insertYsqlEdge(fromId1, toId, edgeJson(fromId1, toId, "in-1"))
        insertYsqlEdge(fromId2, toId, edgeJson(fromId2, toId, "in-2"))

        val result = runBlocking { ybStore.loadInEdges(toId) }
        assertIs<Either.Right<List<EdgeLike>>>(result)
        assertEquals(2, result.value.size)
    }

    @Test fun `loadInEdges returns edges by toId from ycql`() {
        val toId = UUID.randomUUID()
        val fromId1 = UUID.randomUUID()
        val fromId2 = UUID.randomUUID()
        insertYcqlReverseEdge(fromId1, toId, edgeJson(fromId1, toId, "rev-1"))
        insertYcqlReverseEdge(fromId2, toId, edgeJson(fromId2, toId, "rev-2"))

        val result = runBlocking { ybStore.loadInEdges(toId) }
        assertIs<Either.Right<List<EdgeLike>>>(result)
        assertEquals(2, result.value.size)
    }

    @Test fun `transaction saveEdge with ttl writes to both ycql tables`() {
        val edge = YbTestEdge(fromId = UUID.randomUUID(), toId = UUID.randomUUID(), label = "dual-write")
        assertIs<Either.Right<Unit>>(runBlocking { ybStore.transaction { saveEdge(edge, 3600.seconds) } })

        val outResult = runBlocking { ybStore.loadEdges(edge.fromId) }
        assertEquals(1, (outResult as Either.Right).value.size)

        val inResult = runBlocking { ybStore.loadInEdges(edge.toId) }
        assertEquals(1, (inResult as Either.Right).value.size)
    }
}

private fun nodeJson(id: UUID, name: String) =
    """{"type":"yb_test_node","id":"$id","tags":[],"createdAt":"1970-01-01T00:00:00Z","updatedAt":"1970-01-01T00:00:00Z","name":"$name"}"""

private fun edgeJson(fromId: UUID, toId: UUID, label: String) =
    """{"type":"yb_test_edge","fromId":"$fromId","toId":"$toId","tags":[],"createdAt":"1970-01-01T00:00:00Z","updatedAt":"1970-01-01T00:00:00Z","label":"$label"}"""

private fun insertYsqlNode(id: UUID, json: String) {
    DriverManager.getConnection("jdbc:postgresql://localhost:5433/abyss_test_graph", "abyss", "abyss").use { conn ->
        conn.prepareStatement(
            "INSERT INTO abyss.nodes (id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, '{}', now(), now())"
        ).use { stmt ->
            stmt.setObject(1, id)
            stmt.setString(2, "yb_test_node")
            stmt.setObject(3, json, Types.OTHER)
            stmt.executeUpdate()
        }
    }
}

private fun insertYcqlNode(id: UUID, json: String) {
    CqlSession.builder()
        .addContactPoint(InetSocketAddress("localhost", 9042))
        .withLocalDatacenter("datacenter1")
        .build()
        .use { session ->
            session.execute(
                SimpleStatement.newInstance(
                    "INSERT INTO abyss_test_graph.ephemeral_nodes (id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                    id, "yb_test_node", json, emptyList<String>(), Instant.EPOCH, Instant.EPOCH
                )
            )
        }
}

private fun insertYsqlEdge(fromId: UUID, toId: UUID, json: String) {
    DriverManager.getConnection("jdbc:postgresql://localhost:5433/abyss_test_graph", "abyss", "abyss").use { conn ->
        conn.prepareStatement(
            "INSERT INTO abyss.edges (from_id, to_id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, '{}', now(), now())"
        ).use { stmt ->
            stmt.setObject(1, fromId)
            stmt.setObject(2, toId)
            stmt.setString(3, "yb_test_edge")
            stmt.setObject(4, json, Types.OTHER)
            stmt.executeUpdate()
        }
    }
}

private fun insertYcqlEdge(fromId: UUID, toId: UUID, json: String) {
    CqlSession.builder()
        .addContactPoint(InetSocketAddress("localhost", 9042))
        .withLocalDatacenter("datacenter1")
        .build()
        .use { session ->
            session.execute(
                SimpleStatement.newInstance(
                    "INSERT INTO abyss_test_graph.ephemeral_edges (from_id, to_id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    fromId, toId, "yb_test_edge", json, emptyList<String>(), Instant.EPOCH, Instant.EPOCH
                )
            )
        }
}

private fun insertYcqlReverseEdge(fromId: UUID, toId: UUID, json: String) {
    CqlSession.builder()
        .addContactPoint(InetSocketAddress("localhost", 9042))
        .withLocalDatacenter("datacenter1")
        .build()
        .use { session ->
            session.execute(
                SimpleStatement.newInstance(
                    "INSERT INTO abyss_test_graph.ephemeral_reverse_edges (to_id, from_id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    toId, fromId, "yb_test_edge", json, emptyList<String>(), Instant.EPOCH, Instant.EPOCH
                )
            )
        }
}
