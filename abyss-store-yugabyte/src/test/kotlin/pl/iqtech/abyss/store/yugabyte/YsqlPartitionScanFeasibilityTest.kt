package pl.iqtech.abyss.store.yugabyte

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

// Feasibility spike for TODO 1.23 / ai-scripts/StoreScanCapabilityRFC.md's YSQL section, mirroring
// TokenRangeScanFeasibilityTest for the YCQL side. YSQL has no client-visible token()/TokenMap —
// this checks what YugabyteDB actually offers as the equivalent before assuming anything.
class YsqlPartitionScanFeasibilityTest {

    private val url = "jdbc:postgresql://localhost:5433/abyss_test_graph"

    @Test fun `db is alive`() {
        DriverManager.getConnection(url, "abyss", "abyss").use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT version()")
                assertTrue(rs.next() && rs.getString(1) != null)
            }
        }
    }

    // yb_hash_code() is YugabyteDB's YSQL-side exposure of the internal hash-partition value for a
    // hash-sharded key — the YSQL analog of YCQL's token(). Unverified until now: does it exist in
    // this version, what type/range does it return, and is it stable for the same input.
    @Test fun `yb_hash_code exists and returns a stable, bounded value`() {
        DriverManager.getConnection(url, "abyss", "abyss").use { conn ->
            conn.prepareStatement("SELECT yb_hash_code(?)").use { stmt ->
                stmt.setBytes(1, byteArrayOf(1, 2, 3, 4))
                val rs1 = stmt.executeQuery()
                assertTrue(rs1.next())
                val v1 = rs1.getInt(1)

                stmt.setBytes(1, byteArrayOf(1, 2, 3, 4))
                val rs2 = stmt.executeQuery()
                assertTrue(rs2.next())
                val v2 = rs2.getInt(1)

                assertEquals(v1, v2, "yb_hash_code should be deterministic for the same input")
                assertTrue(v1 in 0..65535, "expected yb_hash_code in YugabyteDB's documented 0..65535 range, got $v1")
            }
        }
    }

    @Test fun `yb_hash_code varies across different keys`() {
        DriverManager.getConnection(url, "abyss", "abyss").use { conn ->
            conn.prepareStatement("SELECT yb_hash_code(?)").use { stmt ->
                val codes = List(20) { i ->
                    stmt.setBytes(1, byteArrayOf(i.toByte(), (i * 7).toByte()))
                    val rs = stmt.executeQuery()
                    rs.next()
                    rs.getInt(1)
                }
                assertTrue(codes.toSet().size > 1, "expected yb_hash_code to actually vary across distinct keys, got $codes")
            }
        }
    }

    // N-way fan-out mirroring TokenRangeScanFeasibilityTest's channelFlow test, but structurally
    // different where it has to be: JDBC has no async API at all (unlike the DataStax driver's
    // executeAsync), so "N coroutines" here means N separate blocking JDBC connections, each parked
    // on its own Dispatchers.IO thread — not genuine non-blocking I/O. java.sql.Connection also
    // isn't thread-safe, so (unlike YCQL's single shared CqlSession) each coroutine needs its own
    // connection from a pool sized to at least N. yb_hash_code()'s documented 0..65535 range plays
    // the role token ranges played for YCQL.
    @Test fun `N coroutines scan disjoint yb_hash_code ranges over separate JDBC connections, merged via channelFlow`() = runBlocking {
        val n = 4
        val markerType = "ysql_scan_spike_${UUID.randomUUID()}"
        val plantedIds = List(500) { Uuid.random() }

        DriverManager.getConnection(url, "abyss", "abyss").use { conn ->
            conn.prepareStatement(
                "INSERT INTO abyss.nodes (id, type, data, tags, created_at, updated_at) VALUES (?, ?, '{}', '{}', now(), now())"
            ).use { stmt ->
                plantedIds.forEach { id ->
                    stmt.setBytes(1, UuidKeyAdapter.toNodeId(id).bytes)
                    stmt.setString(2, markerType)
                    stmt.executeUpdate()
                }
            }
        }

        val pool = HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = "abyss"
            password = "abyss"
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = n
            minimumIdle = n
        })
        try {
            val chunkSize = (65536 + n - 1) / n
            val ranges = (0 until n).map { i -> (i * chunkSize) to minOf((i + 1) * chunkSize - 1, 65535) }

            val found = channelFlow<Uuid> {
                ranges.forEach { (lo, hi) ->
                    launch(Dispatchers.IO) {
                        pool.connection.use { conn ->
                            conn.prepareStatement(
                                "SELECT id FROM abyss.nodes WHERE yb_hash_code(id) BETWEEN ? AND ? AND type = ?"
                            ).apply { fetchSize = 100 }.use { stmt ->
                                stmt.setInt(1, lo)
                                stmt.setInt(2, hi)
                                stmt.setString(3, markerType)
                                val rs = stmt.executeQuery()
                                while (rs.next()) send(UuidKeyAdapter.fromNodeId(NodeId(rs.getBytes("id"))))
                            }
                        }
                    }
                }
            }.toList()

            assertEquals(plantedIds.size, found.size, "duplicate or missing rows across yb_hash_code range scans")
            assertEquals(plantedIds.toSet(), found.toSet())
        } finally {
            pool.close()
        }
    }

    // The tag lookup (RFC's other use case) is already a targeted, GIN-indexed query on its own —
    // no need for hash-range chunking there. The one reason to combine them: a tag-filtered result
    // set large enough that a single JDBC cursor is itself the client-side bottleneck, same
    // throughput argument as the unfiltered admin sweep. Two things to actually check, not assume:
    // (1) correctness — does AND-ing yb_hash_code(id) BETWEEN ? AND ? with tags @> ? still recover
    // exactly the tagged subset, no more, no less; (2) does the planner still use idx_nodes_tags
    // (GIN) alongside the hash-code bound, or fall back to a full range scan with tags as a row
    // filter — captured via EXPLAIN ANALYZE and reported, not asserted before we've seen it.
    @Test fun `combined yb_hash_code range + GIN tag filter recovers exactly the tagged subset`() = runBlocking {
        val n = 4
        val comboType = "ysql_combo_spike_${UUID.randomUUID()}"
        val taggedMarker = "combo_tag_${UUID.randomUUID()}"
        val taggedIds = List(300) { Uuid.random() }
        val untaggedIds = List(300) { Uuid.random() }

        DriverManager.getConnection(url, "abyss", "abyss").use { conn ->
            conn.prepareStatement(
                "INSERT INTO abyss.nodes (id, type, data, tags, created_at, updated_at) VALUES (?, ?, '{}', ?, now(), now())"
            ).use { stmt ->
                taggedIds.forEach { id ->
                    stmt.setBytes(1, UuidKeyAdapter.toNodeId(id).bytes)
                    stmt.setString(2, comboType)
                    stmt.setArray(3, conn.createArrayOf("text", arrayOf(taggedMarker)))
                    stmt.executeUpdate()
                }
                untaggedIds.forEach { id ->
                    stmt.setBytes(1, UuidKeyAdapter.toNodeId(id).bytes)
                    stmt.setString(2, comboType)
                    stmt.setArray(3, conn.createArrayOf("text", arrayOf("unrelated_tag_${UUID.randomUUID()}")))
                    stmt.executeUpdate()
                }
            }
        }

        val pool = HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = "abyss"
            password = "abyss"
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = n
            minimumIdle = n
        })
        try {
            val chunkSize = (65536 + n - 1) / n
            val ranges = (0 until n).map { i -> (i * chunkSize) to minOf((i + 1) * chunkSize - 1, 65535) }

            val found = channelFlow<Uuid> {
                ranges.forEach { (lo, hi) ->
                    launch(Dispatchers.IO) {
                        pool.connection.use { conn ->
                            conn.prepareStatement(
                                "SELECT id FROM abyss.nodes WHERE yb_hash_code(id) BETWEEN ? AND ? AND tags @> ?"
                            ).apply { fetchSize = 100 }.use { stmt ->
                                stmt.setInt(1, lo)
                                stmt.setInt(2, hi)
                                stmt.setArray(3, conn.createArrayOf("text", arrayOf(taggedMarker)))
                                val rs = stmt.executeQuery()
                                while (rs.next()) send(UuidKeyAdapter.fromNodeId(NodeId(rs.getBytes("id"))))
                            }
                        }
                    }
                }
            }.toList()

            assertEquals(taggedIds.size, found.size, "expected only the tagged subset (untagged rows must not leak in)")
            assertEquals(taggedIds.toSet(), found.toSet())

            DriverManager.getConnection(url, "abyss", "abyss").use { conn ->
                conn.prepareStatement(
                    "EXPLAIN (ANALYZE, FORMAT TEXT) SELECT id FROM abyss.nodes WHERE yb_hash_code(id) BETWEEN ? AND ? AND tags @> ?"
                ).use { stmt ->
                    stmt.setInt(1, ranges[0].first)
                    stmt.setInt(2, ranges[0].second)
                    stmt.setArray(3, conn.createArrayOf("text", arrayOf(taggedMarker)))
                    val rs = stmt.executeQuery()
                    val plan = buildString { while (rs.next()) appendLine(rs.getString(1)) }
                    println("=== EXPLAIN ANALYZE: yb_hash_code range + tags @> ===\n$plan")
                }
            }
        } finally {
            pool.close()
        }
    }
}
