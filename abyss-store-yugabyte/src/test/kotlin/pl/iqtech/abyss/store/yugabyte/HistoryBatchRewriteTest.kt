package pl.iqtech.abyss.store.yugabyte

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.Timestamp
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

// For ai/flow nodes-history: does reWriteBatchedInserts (set in production, YugabytePersistentStore.create)
// still rewrite the node save once it becomes the version-counter CTE? Runs commitYsqlBatched's shape:
// one addBatch() per op, executeBatch() + commit per chunk of 1000. Rewrite is detected by the driver's
// return value: a rewritten batch reports SUCCESS_NO_INFO (-2) per entry, a statement-by-statement batch
// reports the row count (1). Each chunk saves 1000 distinct ids; later chunks update the same ids, so
// history versions climb. Op counts are odd so the last chunk is partial. Variants interleaved, round 0 warm-up.
class HistoryBatchRewriteTest {

    private val variants = listOf("plain", "history")
    private fun nodes(v: String) = "abyss.hist_batch_${v}_nodes"
    private fun history(v: String) = "abyss.hist_batch_${v}_history"

    private val upsertNode = { t: String ->
        "INSERT INTO $t AS n (id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (id) DO UPDATE SET type = EXCLUDED.type, data = EXCLUDED.data, " +
            "tags = ARRAY(SELECT DISTINCT UNNEST(n.tags || EXCLUDED.tags)), updated_at = EXCLUDED.updated_at"
    }
    private val upsertNodeWithHistory = { t: String, h: String ->
        "WITH u AS (INSERT INTO $t AS n (id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (id) DO UPDATE SET type = EXCLUDED.type, data = EXCLUDED.data, " +
            "tags = ARRAY(SELECT DISTINCT UNNEST(n.tags || EXCLUDED.tags)), updated_at = EXCLUDED.updated_at, " +
            "version = n.version + 1 RETURNING id, version, type, data, updated_at) " +
            "INSERT INTO $h (node_id, version, type, data, updated_at) SELECT id, version, type, data, updated_at FROM u"
    }
    private val payload = "x".repeat(240)

    private val batchSize = 1000
    private val sizes = listOf(1_003, 10_007)
    private val rounds = 5

    private fun pool(rewrite: Boolean) = HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:yugabytedb://localhost:5433/abyss_test_graph?load-balance=false"
        username = "abyss"; password = "abyss"; maximumPoolSize = 1; minimumIdle = 1
        addDataSourceProperty("prepareThreshold", "1")
        addDataSourceProperty("reWriteBatchedInserts", rewrite.toString())
    })

    @Test fun `batched history CTE vs plain upsert - insert rewriting and cost`() {
        if (System.getProperty("perf") == null) return
        val pools = listOf(true, false).associateWith { pool(it) }
        try {
            val ms = mutableMapOf<Triple<Int, String, Boolean>, MutableList<Double>>() // (ops, variant, rewrite) -> ms
            val rewritten = mutableMapOf<Pair<String, Boolean>, Boolean>()
            val configs = variants.flatMap { v -> listOf(true, false).map { v to it } }
            repeat(rounds + 1) { round ->
                for (n in sizes) for (k in configs.indices) {
                    val (v, rw) = configs[(k + round) % configs.size]
                    pools.getValue(rw).connection.use { c -> c.createStatement().use { createTables(it, v) } }
                    var noInfo = false
                    val d = timed { noInfo = saveBatched(pools.getValue(rw), v, n) }
                    if (round > 0) ms.getOrPut(Triple(n, v, rw)) { mutableListOf() } += d
                    rewritten[v to rw] = noInfo
                    pools.getValue(rw).connection.use { c -> c.createStatement().use { s -> assertSaved(s, v, n) } }
                }
            }

            configs.forEach { (v, rw) -> println("HBATCH rewritten %-8s reWriteBatchedInserts=%-5s -> %s".format(v, rw, rewritten[v to rw])) }
            for (n in sizes) {
                val base = ms.getValue(Triple(n, "plain", true)).sorted()[rounds / 2]
                configs.forEach { (v, rw) ->
                    val all = ms.getValue(Triple(n, v, rw)).sorted()
                    val med = all[rounds / 2]
                    println("HBATCH %6d ops %-8s rewrite=%-5s median=%7.0f ms  range=%.0f..%.0f  %6.0f ops/s  vs plain+rewrite: %+.1f%%".format(
                        n, v, rw, med, all.first(), all.last(), n * 1000.0 / med, (med / base - 1) * 100))
                }
            }
        } finally {
            pools.getValue(true).connection.use { c -> c.createStatement().use { s -> variants.forEach { v -> dropTables(s, v) } } }
            pools.values.forEach { it.close() }
        }
    }

    // Every op landed; for history, each entity's history is exactly versions 1..k and the node sits on k.
    private fun assertSaved(s: Statement, v: String, n: Int) {
        fun count(sql: String) = s.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) }
        assertEquals(minOf(n, batchSize).toLong(), count("SELECT count(*) FROM ${nodes(v)}"), "$v nodes")
        if (v != "history") return
        assertEquals(n.toLong(), count("SELECT count(*) FROM ${history(v)}"), "history rows")
        assertEquals(0L, count("""SELECT count(*) FROM (SELECT node_id, min(version) lo, max(version) hi, count(*) k
            FROM ${history(v)} GROUP BY node_id) g JOIN ${nodes(v)} x ON x.id = g.node_id
            WHERE g.lo <> 1 OR g.hi <> g.k OR x.version <> g.hi"""), "history gaps or version drift")
        // entity e is saved once per i in [0, n) with i % batchSize == e: ceil((n - e) / batchSize) times
        assertEquals((n + batchSize - 1L) / batchSize, count("SELECT max(version) FROM ${nodes(v)}"), "max version")
    }

    // commitYsqlBatched's loop for one statement type. Returns true when the driver reported SUCCESS_NO_INFO.
    private fun saveBatched(pool: HikariDataSource, v: String, n: Int): Boolean = pool.connection.use { c ->
        c.autoCommit = false
        val ids = List(batchSize) { Uuid.random() }
        var noInfo = false
        c.prepareStatement(if (v == "history") upsertNodeWithHistory(nodes(v), history(v)) else upsertNode(nodes(v))).use { st ->
            for (chunk in (0 until n).chunked(batchSize)) {
                chunk.forEach { i -> bindNode(c, st, ids[i % batchSize], i); st.addBatch() }
                val counts = st.executeBatch()
                if (counts.any { it == Statement.SUCCESS_NO_INFO }) noInfo = true
                c.commit()
            }
        }
        noInfo
    }

    // Production DDL (ysql-schema.sql) plus the version column, and node_history as decided in the idea.
    private fun createTables(s: Statement, v: String) {
        dropTables(s, v)
        s.execute("""CREATE TABLE ${nodes(v)} (id BYTEA PRIMARY KEY, type TEXT NOT NULL, data JSONB NOT NULL,
            tags TEXT[] NOT NULL DEFAULT '{}', created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
            version BIGINT NOT NULL DEFAULT 1)""")
        s.execute("CREATE INDEX hist_batch_${v}_nodes_type ON ${nodes(v)} (type)")
        s.execute("CREATE INDEX hist_batch_${v}_nodes_tags ON ${nodes(v)} USING GIN (tags)")
        s.execute("""CREATE TABLE ${history(v)} (node_id BYTEA NOT NULL, version BIGINT NOT NULL, type TEXT NOT NULL,
            data JSONB NOT NULL, updated_at TIMESTAMPTZ NOT NULL, PRIMARY KEY (node_id HASH, version ASC))""")
    }

    private fun dropTables(s: Statement, v: String) {
        s.execute("DROP TABLE IF EXISTS ${history(v)}"); s.execute("DROP TABLE IF EXISTS ${nodes(v)}")
    }

    private fun timed(block: () -> Unit): Double { val s = System.nanoTime(); block(); return (System.nanoTime() - s) / 1e6 }

    private fun bindNode(c: Connection, st: PreparedStatement, id: Uuid, seq: Int) {
        val now = Timestamp(System.currentTimeMillis())
        st.setBytes(1, UuidKeyAdapter.toNodeId(id).bytes)
        st.setString(2, "device")
        st.setObject(3, """{"type":"device","id":"$id","seq":$seq,"payload":"$payload"}""", Types.OTHER)
        st.setArray(4, c.createArrayOf("text", emptyArray<String>()))
        st.setTimestamp(5, now); st.setTimestamp(6, now)
    }
}
