package pl.iqtech.abyss.store.yugabyte

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

// Phase 0 of TODO 1.33 (ai-scripts/TypeIndexScanPlan.md §6). Answers, against the live container:
//  1. Which YSQL path streams a whole type fastest, by selectivity: idx_nodes_type (A), a forced
//     single seq scan (S), or a yb_hash_code fan-out with `type = ?` as a filter (B, unhinted/hinted).
//  2. Does fetchSize stream at all with autocommit on (Hikari's default), for pgjdbc and the YB driver?
// Runs on a dedicated table with abyss.nodes' exact DDL, dropped afterwards. Prints; asserts row counts only.
class TypeScanFeasibilityTest {

    private val pgUrl = "jdbc:postgresql://localhost:5433/abyss_test_graph"
    private val ybUrl = "jdbc:yugabytedb://localhost:5433/abyss_test_graph?load-balance=false"
    private val table = "type_scan_spike_nodes"
    private val t = "abyss.$table"

    private val total = 200_000
    // type -> row count: ~0.1%, 5%, 30%, rest (~64.9%)
    private val types = linkedMapOf("t_0_1" to 200, "t_5" to 10_000, "t_30" to 60_000).also {
        it["t_65"] = total - it.values.sum()
    }
    private val payload = "x".repeat(240)

    private fun conn(url: String = pgUrl): Connection = DriverManager.getConnection(url, "abyss", "abyss")

    @Test fun `phase 0 - type scan path by selectivity and fetchSize streaming`() {
        if (System.getProperty("perf") == null) return
        try {
            setup()
            fetchSizeStreaming()
            pathsBySelectivity()
        } finally {
            conn().use { it.createStatement().execute("DROP TABLE IF EXISTS $t") }
        }
    }

    private fun setup() {
        conn().use { c ->
            c.createStatement().use { s ->
                s.execute("DROP TABLE IF EXISTS $t")
                s.execute("""CREATE TABLE $t (
                    id BYTEA PRIMARY KEY, type TEXT NOT NULL, data JSONB NOT NULL,
                    tags TEXT[] NOT NULL DEFAULT '{}', created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL)""")
                s.execute("CREATE INDEX ${table}_type ON $t (type)")
                s.execute("CREATE INDEX ${table}_tags ON $t USING GIN (tags)")
            }
        }
        val t0 = System.nanoTime()
        conn("$pgUrl?reWriteBatchedInserts=true").use { c ->
            c.autoCommit = false
            c.prepareStatement("INSERT INTO $t (id, type, data, created_at, updated_at) VALUES (?, ?, ?::jsonb, now(), now())").use { st ->
                var n = 0
                types.forEach { (type, count) ->
                    repeat(count) {
                        val id = Uuid.random()
                        st.setBytes(1, UuidKeyAdapter.toNodeId(id).bytes)
                        st.setString(2, type)
                        st.setString(3, """{"type":"$type","id":"$id","name":"node-$n","payload":"$payload"}""")
                        st.addBatch()
                        if (++n % 1000 == 0) { st.executeBatch(); c.commit() }
                    }
                }
                st.executeBatch(); c.commit()
            }
            c.autoCommit = true
            c.createStatement().use { it.execute("ANALYZE $t") }
        }
        println("PHASE0 seeded $total rows ${types} in ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    // Heap held right after executeQuery + first next(). A real cursor holds ~fetchSize rows;
    // a materialized result holds all of them.
    private fun fetchSizeStreaming() {
        val big = "t_65"
        // label -> (sql, params, expected rows; null = whatever the range holds)
        val queries = listOf(
            Triple("full", "SELECT id, data FROM $t", emptyList<Any>()) to total,
            Triple("A typed index", "SELECT id, data FROM $t WHERE type = ?", listOf<Any>(big)) to types.getValue(big),
            Triple("B typed pkey range 1/4", "/*+ IndexScan($table ${table}_pkey) */ SELECT id, data FROM $t WHERE yb_hash_code(id) BETWEEN ? AND ? AND type = ?",
                listOf<Any>(0, 16383, big)) to null,
        )
        for ((q, expected) in queries) for ((driver, url) in listOf("pgjdbc" to pgUrl, "yb-smart" to ybUrl)) for (autoCommit in listOf(true, false)) {
            val (label, sql, params) = q
            conn(url).use { c ->
                c.autoCommit = autoCommit
                c.prepareStatement(sql).apply { fetchSize = 500 }.use { st ->
                    params.forEachIndexed { i, p -> if (p is Int) st.setInt(i + 1, p) else st.setString(i + 1, p as String) }
                    val before = usedHeapMb()
                    val rs = st.executeQuery()
                    rs.next()
                    val held = usedHeapMb() - before
                    var rows = 1
                    while (rs.next()) rows++
                    if (expected != null) assertEquals(expected, rows)
                    println("PHASE0 fetchSize=500 query=$label driver=$driver autoCommit=$autoCommit heldAfterFirstRow=${"%.1f".format(held)} MB rows=$rows")
                    // A cursor holds ~fetchSize rows (~0.2 MB); a materialized result holds ~450 B per row.
                    if (autoCommit) assertTrue(held > 5.0, "$label/$driver: expected a materialized result with autocommit on, held $held MB")
                    else assertTrue(held < 5.0, "$label/$driver: expected a streamed cursor with autocommit off, held $held MB")
                }
                if (!autoCommit) c.commit()
            }
        }
    }

    private fun usedHeapMb(): Double {
        repeat(3) { System.gc(); Thread.sleep(50) }
        val r = Runtime.getRuntime()
        return (r.totalMemory() - r.freeMemory()) / 1_048_576.0
    }

    private fun pathsBySelectivity() {
        val pool = HikariDataSource(HikariConfig().apply {
            jdbcUrl = pgUrl; username = "abyss"; password = "abyss"
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 8; minimumIdle = 8
        })
        try {
            val hint = "/*+ SeqScan($table) */ "
            // SeqScan also forbids the pkey index, the only path where yb_hash_code(id) bounds can become an
            // Index Cond — so the range-pruning candidate is a pkey IndexScan hint, not SeqScan.
            val pkHint = "/*+ IndexScan($table ${table}_pkey) */ "
            explain(pool, "SELECT id FROM $t WHERE yb_hash_code(id) BETWEEN ? AND ?", listOf(0, 16383), "1.23 scanNodeIds range 1/4 (as shipped)")
            types.forEach { (type, expected) ->
                println("PHASE0 ==== type=$type rows=$expected (${"%.1f".format(expected * 100.0 / total)}%)")
                explain(pool, "SELECT id, data FROM $t WHERE type = ?", listOf(type), "A index")
                explain(pool, "${hint}SELECT id, data FROM $t WHERE type = ?", listOf(type), "S seq (hinted)")
                explain(pool, "SELECT id, data FROM $t WHERE yb_hash_code(id) BETWEEN ? AND ? AND type = ?", listOf(0, 16383, type), "B range 1/4 (unhinted)")
                explain(pool, "${hint}SELECT id, data FROM $t WHERE yb_hash_code(id) BETWEEN ? AND ? AND type = ?", listOf(0, 16383, type), "B range 1/4 (hinted)")
                explain(pool, "${pkHint}SELECT id, data FROM $t WHERE yb_hash_code(id) BETWEEN ? AND ? AND type = ?", listOf(0, 16383, type), "B range 1/4 (pkey-hinted)")

                val variants = listOf<Pair<String, () -> Int>>(
                    "A index" to { single(pool, "SELECT id, data FROM $t WHERE type = ?", type) },
                    "S seq" to { single(pool, "${hint}SELECT id, data FROM $t WHERE type = ?", type) },
                    "B4 unhinted" to { fanOut(pool, 4, "", type) },
                    "B8 unhinted" to { fanOut(pool, 8, "", type) },
                    "B4 hinted" to { fanOut(pool, 4, hint, type) },
                    "B8 hinted" to { fanOut(pool, 8, hint, type) },
                    "B4 pkey" to { fanOut(pool, 4, pkHint, type) },
                    "B8 pkey" to { fanOut(pool, 8, pkHint, type) },
                )
                variants.forEach { (name, run) ->
                    assertEquals(expected, run(), "$name row count") // warm-up
                    val ms = List(3) { val s = System.nanoTime(); assertEquals(expected, run()); (System.nanoTime() - s) / 1_000_000 }.sorted()
                    println("PHASE0 type=$type variant=$name median=${ms[1]} ms range=${ms.first()}..${ms.last()} ms")
                }
            }
        } finally { pool.close() }
    }

    private fun explain(pool: HikariDataSource, sql: String, params: List<Any>, label: String) {
        pool.connection.use { c ->
            // Hint comment must lead the statement pg_hint_plan sees, so it goes before EXPLAIN's target.
            val stmt = if (sql.startsWith("/*+")) sql.substringBefore("*/") + "*/ EXPLAIN (ANALYZE, DIST, COSTS OFF, SUMMARY OFF) " + sql.substringAfter("*/ ")
                       else "EXPLAIN (ANALYZE, DIST, COSTS OFF, SUMMARY OFF) $sql"
            c.prepareStatement(stmt).use { st ->
                params.forEachIndexed { i, p -> if (p is Int) st.setInt(i + 1, p) else st.setString(i + 1, p as String) }
                val rs = st.executeQuery()
                println("PHASE0 -- EXPLAIN $label")
                while (rs.next()) println("PHASE0      ${rs.getString(1)}")
            }
        }
    }

    // Streamed consumption: autocommit off + fetchSize so a result never sits whole on the heap.
    private fun single(pool: HikariDataSource, sql: String, type: String): Int = pool.connection.use { c ->
        c.autoCommit = false
        try {
            c.prepareStatement(sql).apply { fetchSize = 1000 }.use { st ->
                st.setString(1, type)
                val rs = st.executeQuery()
                var n = 0
                while (rs.next()) { rs.getBytes(1); rs.getString(2); n++ }
                n
            }
        } finally { c.commit(); c.autoCommit = true }
    }

    private fun fanOut(pool: HikariDataSource, n: Int, hint: String, type: String): Int = runBlocking {
        val chunk = (65536 + n - 1) / n
        (0 until n).map { i ->
            async(Dispatchers.IO) {
                pool.connection.use { c ->
                    c.autoCommit = false
                    try {
                        c.prepareStatement("${hint}SELECT id, data FROM $t WHERE yb_hash_code(id) BETWEEN ? AND ? AND type = ?")
                            .apply { fetchSize = 1000 }.use { st ->
                                st.setInt(1, i * chunk); st.setInt(2, minOf((i + 1) * chunk - 1, 65535)); st.setString(3, type)
                                val rs = st.executeQuery()
                                var k = 0
                                while (rs.next()) { rs.getBytes(1); rs.getString(2); k++ }
                                k
                            }
                    } finally { c.commit(); c.autoCommit = true }
                }
            }
        }.awaitAll().sum()
    }
}
