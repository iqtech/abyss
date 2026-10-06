package pl.iqtech.abyss.store.yugabyte

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.sql.Connection
import java.sql.Timestamp
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

// Write cost of the type index, for the paging question in ai-scripts/TypeIndexScanPlan.md: is
// (type HASH, id ASC) — the index a keyset page needs — slower to maintain than today's (type)?
// Three tables with abyss.nodes' exact DDL and GIN tags index, differing only in the type index, driven
// by YugabytePersistentStore's own node upsert. Variants are interleaved per round, order rotated, so
// drift on the dev box hits all three alike. Prints medians; asserts row counts and the index shapes.
class TypeIndexWriteCostTest {

    private val variants = linkedMapOf(
        "none" to null,
        "type" to "(type)",
        "type_id" to "(type HASH, id ASC)",
    )
    private fun table(v: String) = "abyss.type_idx_spike_$v"

    private val upsert = { t: String ->
        "INSERT INTO $t AS n (id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (id) DO UPDATE SET type = EXCLUDED.type, data = EXCLUDED.data, " +
            "tags = ARRAY(SELECT DISTINCT UNNEST(n.tags || EXCLUDED.tags)), updated_at = EXCLUDED.updated_at"
    }
    private val payload = "x".repeat(240)

    // Skewed like Phase 0: ~0.1%, 5%, 30%, rest. One type's whole index range sits on one index tablet.
    private fun typeOf(i: Int) = when { i % 1000 == 7 -> "t_0_1"; i % 20 == 3 -> "t_5"; i % 10 < 3 -> "t_30"; else -> "t_65" }

    private val single = 1000      // single-row transactions, sequential
    private val conc = 8           // connections for the concurrent single-row workload
    private val perConn = 500
    private val batchRows = 20_000
    private val batchSize = 1000
    private val rounds = 5

    @Test fun `type index write cost - none vs (type) vs (type HASH, id ASC)`() {
        if (System.getProperty("perf") == null) return
        val pool = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:yugabytedb://localhost:5433/abyss_test_graph?load-balance=false"
            username = "abyss"; password = "abyss"; maximumPoolSize = conc; minimumIdle = conc
        })
        try {
            pool.connection.use { c -> c.createStatement().use { s ->
                variants.forEach { (v, idx) ->
                    s.execute("DROP TABLE IF EXISTS ${table(v)}")
                    s.execute("""CREATE TABLE ${table(v)} (
                        id BYTEA PRIMARY KEY, type TEXT NOT NULL, data JSONB NOT NULL,
                        tags TEXT[] NOT NULL DEFAULT '{}', created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL)""")
                    if (idx != null) s.execute("CREATE INDEX type_idx_spike_${v}_type ON ${table(v)} $idx")
                    s.execute("CREATE INDEX type_idx_spike_${v}_tags ON ${table(v)} USING GIN (tags)")
                }
                val defs = buildMap { s.executeQuery("SELECT indexname, indexdef FROM pg_indexes WHERE indexname LIKE 'type_idx_spike_%_type'").use { rs -> while (rs.next()) put(rs.getString(1), rs.getString(2)) } }
                println("WCOST index defs: $defs")
                assertTrue(defs.getValue("type_idx_spike_type_type").contains("(type HASH)"), "current index shape: $defs")
                assertTrue(defs.getValue("type_idx_spike_type_id_type").contains("(type HASH, id ASC)"), "proposed index shape: $defs")
            } }

            val names = variants.keys.toList()
            val ms = mutableMapOf<Pair<String, String>, MutableList<Double>>() // (workload, variant) -> ms per round
            repeat(rounds + 1) { round ->
                // rotate the order each round
                for (v in names.indices.map { names[(it + round) % names.size] }) {
                    val t = table(v)
                    val ids = List(single) { Uuid.random() }
                    val w = linkedMapOf(
                        "1 single-row txn, sequential" to timed { singles(pool, t, ids, 0) },
                        "2 single-row txn, $conc connections" to timed { runBlocking {
                            (0 until conc).map { k -> async(Dispatchers.IO) { singles(pool, t, List(perConn) { Uuid.random() }, k * perConn) } }.awaitAll()
                        } },
                        "3 batch of $batchSize per txn" to timed { batches(pool, t) },
                        "4 upsert-update existing, sequential" to timed { singles(pool, t, ids, 0) },
                    )
                    if (round > 0) w.forEach { (name, d) -> ms.getOrPut(name to v) { mutableListOf() } += d } // round 0 = warm-up
                }
            }

            val perRound = single + conc * perConn + batchRows // workload 4 rewrites workload 1's rows
            pool.connection.use { c -> c.createStatement().use { s -> names.forEach { v ->
                s.executeQuery("SELECT count(*) FROM ${table(v)}").use { rs -> rs.next(); assertEquals((rounds + 1L) * perRound, rs.getLong(1), "rows in ${table(v)}") }
            } } }

            val rowsOf = mapOf('1' to single, '2' to conc * perConn, '3' to batchRows, '4' to single)
            ms.keys.map { it.first }.distinct().forEach { wl ->
                val med = names.associateWith { v -> ms.getValue(wl to v).sorted()[rounds / 2] }
                names.forEach { v ->
                    val all = ms.getValue(wl to v).sorted()
                    println("WCOST %-40s %-8s median=%7.0f ms  range=%.0f..%.0f  %6.0f rows/s  vs type: %+.1f%%".format(
                        wl, v, med.getValue(v), all.first(), all.last(), rowsOf.getValue(wl[0]) * 1000.0 / med.getValue(v),
                        (med.getValue(v) / med.getValue("type") - 1) * 100))
                }
            }
        } finally {
            pool.connection.use { c -> c.createStatement().use { s -> variants.keys.forEach { s.execute("DROP TABLE IF EXISTS ${table(it)}") } } }
            pool.close()
        }
    }

    private fun timed(block: () -> Unit): Double { val s = System.nanoTime(); block(); return (System.nanoTime() - s) / 1e6 }

    private fun bind(c: Connection, st: java.sql.PreparedStatement, id: Uuid, i: Int) {
        val type = typeOf(i)
        val now = Timestamp(System.currentTimeMillis())
        st.setBytes(1, UuidKeyAdapter.toNodeId(id).bytes)
        st.setString(2, type)
        st.setObject(3, """{"type":"$type","id":"$id","name":"node-$i","payload":"$payload"}""", Types.OTHER)
        st.setArray(4, c.createArrayOf("text", emptyArray<String>()))
        st.setTimestamp(5, now); st.setTimestamp(6, now)
    }

    // One row per transaction, the per-event ingest shape (commitYsql with a single op).
    private fun singles(pool: HikariDataSource, t: String, ids: List<Uuid>, offset: Int) = pool.connection.use { c ->
        c.autoCommit = false
        c.prepareStatement(upsert(t)).use { st -> ids.forEachIndexed { i, id -> bind(c, st, id, offset + i); st.executeUpdate(); c.commit() } }
    }

    private fun batches(pool: HikariDataSource, t: String) = pool.connection.use { c ->
        c.autoCommit = false
        c.prepareStatement(upsert(t)).use { st ->
            repeat(batchRows) { i ->
                bind(c, st, Uuid.random(), i); st.addBatch()
                if ((i + 1) % batchSize == 0) { st.executeBatch(); c.commit() }
            }
        }
    }
}
