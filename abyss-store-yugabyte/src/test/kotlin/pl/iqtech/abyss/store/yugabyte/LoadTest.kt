package pl.iqtech.abyss.store.yugabyte

import arrow.core.Either
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Types
import javax.sql.DataSource
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
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
    val name: String
) : NodeLike<Uuid>

@Serializable
@SerialName("yb_test_edge")
data class YbTestEdge(
    override val fromId: Uuid,
    override val toId: Uuid,
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
        ysqlUrl = "jdbc:yugabytedb://localhost:5433/abyss_test_graph",
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
        assertIs<Either.Right<Unit>>(runBlocking { ybEphemeralStore.transaction { saveNode(nid(node.id), node, 3600.seconds, emptySet()) } })

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
        assertIs<Either.Right<Unit>>(runBlocking { ybPersistentStore.transaction { saveNode(nid(node.id), node, emptySet()) } })
        val loaded = runBlocking { ybPersistentStore.loadNode(nid(node.id)) }
        assertEquals("tx-ysql-node", assertIs<YbTestNode>((loaded as Either.Right).value.first).name)
    }

    @Test fun `transaction saveNode with ttl persists to ycql`() {
        val node = YbTestNode(id = Uuid.random(), name = "tx-ycql-node")
        assertIs<Either.Right<Unit>>(runBlocking { ybEphemeralStore.transaction { saveNode(nid(node.id), node, 3600.seconds, emptySet()) } })
        val loaded = runBlocking { ybEphemeralStore.loadNode(nid(node.id)) }
        assertEquals("tx-ycql-node", assertIs<YbTestNode>((loaded as Either.Right).value.first).name)
    }

    @Test fun `transaction saveEdge persists to ysql`() {
        val edge = YbTestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "tx-ysql-edge")
        assertIs<Either.Right<Unit>>(runBlocking { ybPersistentStore.transaction { saveEdge(nid(edge.fromId), nid(edge.toId), edge, emptySet()) } })
        val loaded = runBlocking { ybPersistentStore.loadEdge(nid(edge.fromId), nid(edge.toId), "yb_test_edge") }
        assertEquals("tx-ysql-edge", assertIs<YbTestEdge>((loaded as Either.Right).value.first).label)
    }

    // ── tags (TODO 1.24): round-tripped via a raw read below for write assertions; scanNodeIds(tag)
    // (TODO 1.23, tested in its own section further down) is the real read path. ──────────────────

    @Test fun `transaction saveNode persists tags to the ysql tags column`() {
        val node = YbTestNode(id = Uuid.random(), name = "tagged-ysql-node")
        runBlocking { ybPersistentStore.transaction { saveNode(nid(node.id), node, setOf("alpha", "beta")) } }
        assertEquals(setOf("alpha", "beta"), readYsqlNodeTags(node.id))
    }

    @Test fun `transaction saveEdge persists tags to the ysql tags column`() {
        val edge = YbTestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "tagged-ysql-edge")
        runBlocking { ybPersistentStore.transaction { saveEdge(nid(edge.fromId), nid(edge.toId), edge, setOf("gamma")) } }
        assertEquals(setOf("gamma"), readYsqlEdgeTags(edge.fromId, edge.toId))
    }

    @Test fun `transaction saveNode persists tags to the ycql tags column`() {
        val node = YbTestNode(id = Uuid.random(), name = "tagged-ycql-node")
        runBlocking { ybEphemeralStore.transaction { saveNode(nid(node.id), node, 3600.seconds, setOf("delta")) } }
        assertEquals(setOf("delta"), readYcqlNodeTags(node.id))
    }

    @Test fun `transaction saveEdge persists tags to the ycql tags column`() {
        val edge = YbTestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "tagged-ycql-edge")
        runBlocking { ybEphemeralStore.transaction { saveEdge(nid(edge.fromId), nid(edge.toId), edge, 3600.seconds, setOf("epsilon")) } }
        assertEquals(setOf("epsilon"), readYcqlEdgeTags(edge.fromId, edge.toId))
    }

    @Test fun `repeated saveNode adds ysql tags instead of replacing them`() {
        val node = YbTestNode(id = Uuid.random(), name = "tagged-ysql-node-2")
        runBlocking { ybPersistentStore.transaction { saveNode(nid(node.id), node, setOf("alpha", "beta")) } }
        runBlocking { ybPersistentStore.transaction { saveNode(nid(node.id), node, setOf("beta", "gamma")) } }
        assertEquals(setOf("alpha", "beta", "gamma"), readYsqlNodeTags(node.id))
    }

    @Test fun `repeated saveEdge adds ysql tags instead of replacing them`() {
        val edge = YbTestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "tagged-ysql-edge-2")
        runBlocking { ybPersistentStore.transaction { saveEdge(nid(edge.fromId), nid(edge.toId), edge, setOf("alpha", "beta")) } }
        runBlocking { ybPersistentStore.transaction { saveEdge(nid(edge.fromId), nid(edge.toId), edge, setOf("beta", "gamma")) } }
        assertEquals(setOf("alpha", "beta", "gamma"), readYsqlEdgeTags(edge.fromId, edge.toId))
    }

    @Test fun `repeated saveNode adds ycql tags instead of replacing them`() {
        val node = YbTestNode(id = Uuid.random(), name = "tagged-ycql-node-2")
        runBlocking { ybEphemeralStore.transaction { saveNode(nid(node.id), node, 3600.seconds, setOf("alpha", "beta")) } }
        runBlocking { ybEphemeralStore.transaction { saveNode(nid(node.id), node, 3600.seconds, setOf("beta", "gamma")) } }
        assertEquals(setOf("alpha", "beta", "gamma"), readYcqlNodeTags(node.id))
    }

    @Test fun `repeated saveEdge adds ycql tags instead of replacing them`() {
        val edge = YbTestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "tagged-ycql-edge-2")
        runBlocking { ybEphemeralStore.transaction { saveEdge(nid(edge.fromId), nid(edge.toId), edge, 3600.seconds, setOf("alpha", "beta")) } }
        runBlocking { ybEphemeralStore.transaction { saveEdge(nid(edge.fromId), nid(edge.toId), edge, 3600.seconds, setOf("beta", "gamma")) } }
        assertEquals(setOf("alpha", "beta", "gamma"), readYcqlEdgeTags(edge.fromId, edge.toId))
    }

    @Test fun `ephemeral node with ttl is readable before expiry and gone after`() {
        val node = YbTestNode(id = Uuid.random(), name = "ephemeral")
        assertIs<Either.Right<Unit>>(runBlocking { ybEphemeralStore.transaction { saveNode(nid(node.id), node, 5.seconds, emptySet()) } })

        Thread.sleep(1_000)
        val before = runBlocking { ybEphemeralStore.loadNode(nid(node.id)) }
        assertEquals("ephemeral", assertIs<YbTestNode>((before as Either.Right).value.first).name)

        Thread.sleep(5_000)
        val after = runBlocking { ybEphemeralStore.loadNode(nid(node.id)) }
        assertEquals(null, (after as Either.Right).value.first)
    }

    @Test fun `transaction deleteNode removes from ysql`() {
        val node = YbTestNode(id = Uuid.random(), name = "to-delete")
        runBlocking { ybPersistentStore.transaction { saveNode(nid(node.id), node, emptySet()) } }
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

    // Solution 1 (neighbor tag rides the edge scan): the LEFT JOIN returns the to-node's type, null
    // when the endpoint node doesn't exist (dangling checkIntegrity=false edge).
    @Test fun `loadEdges returns to-node type as neighborType, null for a dangling edge`() {
        val fromId = Uuid.random()
        val liveTo = Uuid.random()
        val danglingTo = Uuid.random()
        insertYsqlNode(liveTo, nodeJson(liveTo, "neighbor"))
        insertYsqlEdge(fromId, liveTo, edgeJson(fromId, liveTo, "live"))
        insertYsqlEdge(fromId, danglingTo, edgeJson(fromId, danglingTo, "dangling"))

        val edges = (runBlocking { ybPersistentStore.loadEdges(nid(fromId)) } as Either.Right).value
        assertEquals("yb_test_node", edges.first { it.toId == nid(liveTo) }.neighborType)
        assertEquals(null, edges.first { it.toId == nid(danglingTo) }.neighborType, "dangling edge -> null neighborType")
    }

    @Test fun `loadInEdges returns from-node type as neighborType from ysql`() {
        val toId = Uuid.random()
        val fromLive = Uuid.random()
        insertYsqlNode(fromLive, nodeJson(fromLive, "src"))
        insertYsqlEdge(fromLive, toId, edgeJson(fromLive, toId, "in-live"))

        val edges = (runBlocking { ybPersistentStore.loadInEdges(nid(toId)) } as Either.Right).value
        assertEquals("yb_test_node", edges.first { it.fromId == nid(fromLive) }.neighborType)
    }

    // TODO 1.13: ephemeral edges are outgoing-only — loadInEdges has no reverse index to read.
    @Test fun `loadInEdges is always empty for ycql (outgoing-only)`() {
        val result = runBlocking { ybEphemeralStore.loadInEdges(nid(Uuid.random())) }
        assertIs<Either.Right<List<*>>>(result)
        assertEquals(0, result.value.size)
    }

    @Test fun `ephemeral saveEdge is outgoing-only - found via loadEdges, absent via loadInEdges`() {
        val edge = YbTestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "outgoing-only")
        assertIs<Either.Right<Unit>>(runBlocking { ybEphemeralStore.transaction { saveEdge(nid(edge.fromId), nid(edge.toId), edge, 3600.seconds, emptySet()) } })

        val outResult = runBlocking { ybEphemeralStore.loadEdges(nid(edge.fromId)) }
        assertEquals(1, (outResult as Either.Right).value.size)

        val inResult = runBlocking { ybEphemeralStore.loadInEdges(nid(edge.toId)) }
        assertEquals(0, (inResult as Either.Right).value.size)
    }

    // ── batchTransaction (TODO 1.21) ────────────────────────────────────────────

    @Test fun `batchTransaction commits more ops than batchSize across multiple chunks`() {
        val ids = List(125) { Uuid.random() }
        val result = runBlocking {
            ybPersistentStore.batchTransaction(batchSize = 50) {
                ids.forEach { id -> saveNode(nid(id), YbTestNode(id = id, name = "batched"), emptySet()) }
            }
        }
        assertIs<Either.Right<Unit>>(result)
        ids.forEach { id ->
            val loaded = runBlocking { ybPersistentStore.loadNode(nid(id)) }
            assertEquals("batched", assertIs<YbTestNode>((loaded as Either.Right).value.first).name)
        }
    }

    @Test fun `batchTransaction preserves op order for add-then-remove of the same key`() {
        val node = YbTestNode(id = Uuid.random(), name = "add-then-remove")
        val result = runBlocking {
            ybPersistentStore.batchTransaction {
                saveNode(nid(node.id), node, emptySet())
                deleteNode(nid(node.id))
            }
        }
        assertIs<Either.Right<Unit>>(result)
        val loaded = runBlocking { ybPersistentStore.loadNode(nid(node.id)) }
        assertEquals(null, (loaded as Either.Right).value.first)
    }

    @Test fun `batchTransaction preserves op order for remove-then-add of the same key`() {
        val node = YbTestNode(id = Uuid.random(), name = "remove-then-add")
        runBlocking { ybPersistentStore.transaction { saveNode(nid(node.id), YbTestNode(id = node.id, name = "stale"), emptySet()) } }

        val result = runBlocking {
            ybPersistentStore.batchTransaction {
                deleteNode(nid(node.id))
                saveNode(nid(node.id), node, emptySet())
            }
        }
        assertIs<Either.Right<Unit>>(result)
        val loaded = runBlocking { ybPersistentStore.loadNode(nid(node.id)) }
        assertEquals("remove-then-add", assertIs<YbTestNode>((loaded as Either.Right).value.first).name)
    }

    // Proves the documented non-atomic-across-chunks trade-off: a failure partway through only
    // rolls back its own chunk, chunks committed before it stay committed, and chunks after it are
    // never attempted. FailAfterNCommitsDataSource throws on the connection's Nth conn.commit()
    // call (the 2nd, i.e. chunk 2 of 3), simulated at the JDBC layer since nothing in the public
    // store API (upserts, unconditional deletes) can be made to fail deterministically otherwise.
    @Test fun `batchTransaction failure partway leaves earlier chunks committed and later chunks absent`() {
        val ids = List(6) { Uuid.random() }
        val failingStore = YugabytePersistentStore(FailAfterNCommitsDataSource(rawYsqlDataSource(), failAtCommit = 2), ybModule)

        val result = runBlocking {
            failingStore.batchTransaction(batchSize = 2) {
                ids.forEach { id -> saveNode(nid(id), YbTestNode(id = id, name = "batch-fail"), emptySet()) }
            }
        }
        assertIs<Either.Left<AbyssError.BatchPartiallyCommitted>>(result)
        // TODO 1.29 item 2: batchSize=2, failAtCommit=2 -> chunk 1 (2 ops) committed before chunk 2 failed.
        assertEquals(2, result.value.committedOps)

        ids.take(2).forEach { id ->
            val loaded = runBlocking { ybPersistentStore.loadNode(nid(id)) }
            assertEquals("batch-fail", assertIs<YbTestNode>((loaded as Either.Right).value.first).name)
        }
        ids.drop(2).forEach { id ->
            val loaded = runBlocking { ybPersistentStore.loadNode(nid(id)) }
            assertEquals(null, (loaded as Either.Right).value.first)
        }
    }

    // ── scan (TODO 1.23): admin/orphan-sweep. Untagged scans hit the whole table (shared across this
    // suite), so assertions check the planted set is fully recovered (containsAll), not exact
    // equality; the tagged scan uses a random per-test tag, so it CAN assert an exact match. ────────

    @Test fun `scanNodeIds with no tag recovers a planted set from ysql via yb_hash_code fan-out`() {
        val planted = List(40) { Uuid.random() }
        runBlocking { planted.forEach { id -> ybPersistentStore.transaction { saveNode(nid(id), YbTestNode(id = id, name = "scan-ysql"), emptySet()) } } }

        val found = runBlocking { ybPersistentStore.scanNodeIds().toList() }.toSet()
        assertTrue(found.containsAll(planted.map { nid(it) }), "expected every planted id to be recovered by the unfiltered scan")
    }

    @Test fun `scanNodeIds with a tag recovers exactly the tagged subset from ysql via GIN lookup`() {
        val tag = "scan-tag-${Uuid.random()}"
        val tagged = List(15) { Uuid.random() }
        val untagged = List(15) { Uuid.random() }
        runBlocking {
            tagged.forEach { id -> ybPersistentStore.transaction { saveNode(nid(id), YbTestNode(id = id, name = "scan-ysql-tagged"), setOf(tag)) } }
            untagged.forEach { id -> ybPersistentStore.transaction { saveNode(nid(id), YbTestNode(id = id, name = "scan-ysql-untagged"), emptySet()) } }
        }

        val found = runBlocking { ybPersistentStore.scanNodeIds(tag = tag).toList() }.toSet()
        assertEquals(tagged.map { nid(it) }.toSet(), found, "expected exactly the tagged subset, no untagged rows leaking in")
    }

    @Test fun `scanEdgeIds recovers planted (from, to) pairs from ysql`() {
        val planted = List(20) { Uuid.random() to Uuid.random() }
        runBlocking {
            planted.forEach { (from, to) ->
                ybPersistentStore.transaction { saveEdge(nid(from), nid(to), YbTestEdge(fromId = from, toId = to, label = "scan-edge"), emptySet()) }
            }
        }

        val found = runBlocking { ybPersistentStore.scanEdgeIds().toList() }.toSet()
        assertTrue(found.containsAll(planted.map { (from, to) -> nid(from) to nid(to) }), "expected every planted edge pair to be recovered")
    }

    @Test fun `scanNodeIds over ycql recovers a planted set, tagged and untagged`() {
        val tag = "scan-ycql-tag-${Uuid.random()}"
        val tagged = List(10) { Uuid.random() }
        val untagged = List(10) { Uuid.random() }
        runBlocking {
            tagged.forEach { id -> ybEphemeralStore.transaction { saveNode(nid(id), YbTestNode(id = id, name = "scan-ycql-tagged"), 3600.seconds, setOf(tag)) } }
            untagged.forEach { id -> ybEphemeralStore.transaction { saveNode(nid(id), YbTestNode(id = id, name = "scan-ycql-untagged"), 3600.seconds, emptySet()) } }
        }

        val foundUntagged = runBlocking { ybEphemeralStore.scanNodeIds().toList() }.toSet()
        assertTrue(foundUntagged.containsAll((tagged + untagged).map { nid(it) }), "expected every planted id back from the untagged token-range scan")

        val foundTagged = runBlocking { ybEphemeralStore.scanNodeIds(tag = tag).toList() }.toSet()
        assertEquals(tagged.map { nid(it) }.toSet(), foundTagged, "expected exactly the tagged subset via client-side filtering")
    }
}

private fun nodeJson(id: Uuid, name: String) =
    """{"type":"yb_test_node","id":"$id","createdAt":"1970-01-01T00:00:00Z","updatedAt":"1970-01-01T00:00:00Z","name":"$name"}"""

private fun edgeJson(fromId: Uuid, toId: Uuid, label: String) =
    """{"type":"yb_test_edge","fromId":"$fromId","toId":"$toId","createdAt":"1970-01-01T00:00:00Z","updatedAt":"1970-01-01T00:00:00Z","label":"$label"}"""

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

private fun readYsqlNodeTags(id: Uuid): Set<String> =
    DriverManager.getConnection("jdbc:postgresql://localhost:5433/abyss_test_graph", "abyss", "abyss").use { conn ->
        conn.prepareStatement("SELECT tags FROM abyss.nodes WHERE id = ?").use { stmt ->
            stmt.setBytes(1, UuidKeyAdapter.toNodeId(id).bytes)
            stmt.executeQuery().use { rs -> rs.next(); (rs.getArray("tags").array as Array<*>).map { it as String }.toSet() }
        }
    }

private fun readYsqlEdgeTags(fromId: Uuid, toId: Uuid): Set<String> =
    DriverManager.getConnection("jdbc:postgresql://localhost:5433/abyss_test_graph", "abyss", "abyss").use { conn ->
        conn.prepareStatement("SELECT tags FROM abyss.edges WHERE from_id = ? AND to_id = ?").use { stmt ->
            stmt.setBytes(1, UuidKeyAdapter.toNodeId(fromId).bytes)
            stmt.setBytes(2, UuidKeyAdapter.toNodeId(toId).bytes)
            stmt.executeQuery().use { rs -> rs.next(); (rs.getArray("tags").array as Array<*>).map { it as String }.toSet() }
        }
    }

private fun readYcqlNodeTags(id: Uuid): Set<String> =
    CqlSession.builder()
        .addContactPoint(InetSocketAddress("localhost", 9042))
        .withLocalDatacenter("datacenter1")
        .build()
        .use { session ->
            session.execute(
                SimpleStatement.newInstance(
                    "SELECT tags FROM abyss_test_graph.ephemeral_nodes WHERE id = ?",
                    ByteBuffer.wrap(UuidKeyAdapter.toNodeId(id).bytes)
                )
            ).one()!!.getSet("tags", String::class.java)!!
        }

private fun readYcqlEdgeTags(fromId: Uuid, toId: Uuid): Set<String> =
    CqlSession.builder()
        .addContactPoint(InetSocketAddress("localhost", 9042))
        .withLocalDatacenter("datacenter1")
        .build()
        .use { session ->
            session.execute(
                SimpleStatement.newInstance(
                    "SELECT tags FROM abyss_test_graph.ephemeral_edges WHERE from_id = ? AND to_id = ?",
                    ByteBuffer.wrap(UuidKeyAdapter.toNodeId(fromId).bytes), ByteBuffer.wrap(UuidKeyAdapter.toNodeId(toId).bytes)
                )
            ).one()!!.getSet("tags", String::class.java)!!
        }

private fun rawYsqlDataSource(): DataSource = HikariDataSource(HikariConfig().apply {
    jdbcUrl = "jdbc:postgresql://localhost:5433/abyss_test_graph"
    username = "abyss"
    password = "abyss"
    driverClassName = "org.postgresql.Driver"
    maximumPoolSize = 2
    minimumIdle = 1
})

// Wraps a real DataSource's connections so the Nth conn.commit() call throws instead of committing
// — the only way to force a deterministic mid-batch failure, since every op batchTransaction's
// public API exposes (upserts, unconditional deletes) succeeds regardless of prior state.
private class FailAfterNCommitsDataSource(
    private val delegate: DataSource,
    private val failAtCommit: Int,
) : DataSource by delegate {
    private var commitCount = 0

    override fun getConnection(): Connection {
        val real = delegate.connection
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "commit") {
                commitCount++
                if (commitCount == failAtCommit) throw SQLException("simulated failure at commit #$commitCount")
            }
            if (args == null) method.invoke(real) else method.invoke(real, *args)
        } as Connection
    }
}

