package pl.iqtech.abyss.store.yugabyte

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.Timestamp
import java.sql.Types
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

// Edge history probes, before any design: raw JDBC on the dev container, own schema, `-Pperf`, median of 5
// interleaved rounds after a warm-up, counts asserted every run. `edges` copies production (LIKE, all
// indexes) plus version. Two candidate history PKs: from = (from_id HASH, to_id, type, version), so a
// hub's history shares a tablet; key = ((from_id, to_id, type) HASH, version). spread = 1000 distinct
// from_ids, hub = one from_id. Chunks of 1000 with keys distinct inside a chunk, as the duplicate cut gives.
class EdgeHistoryProbeTest {

    private val url = "jdbc:yugabytedb://localhost:5433/abyss_test_graph"
    private val schema = "abyss_edgeprobe"
    private val rounds = 5
    private val batchSize = 1000
    private val type = "yb_test_edge"
    private val data = """{"type":"$type","label":"${"x".repeat(120)}"}"""
    private val historyPks = mapOf(
        "from" to "PRIMARY KEY (from_id HASH, to_id ASC, type ASC, version ASC)",
        "key" to "PRIMARY KEY ((from_id, to_id, type) HASH, version ASC)",
    )

    private data class Key(val from: ByteArray, val to: ByteArray)

    private fun bytes() = Random.nextBytes(16)
    private fun keys(hub: Boolean) = bytes().let { h -> List(batchSize) { Key(if (hub) h else bytes(), bytes()) } }

    private fun edges(v: String) = "$schema.e_$v"
    private fun history(v: String) = "$schema.h_$v"

    private fun upsert(v: String, version: Boolean) =
        "INSERT INTO ${edges(v)} AS e (from_id, to_id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) " + conflict(version)
    private fun conflict(version: Boolean) = "ON CONFLICT (from_id, to_id, type) DO UPDATE SET data = EXCLUDED.data, " +
        "tags = ARRAY(SELECT DISTINCT UNNEST(e.tags || EXCLUDED.tags)), updated_at = EXCLUDED.updated_at" + if (version) ", version = e.version + 1" else ""
    private fun appendHistory(v: String) = "RETURNING from_id, to_id, type, version, data, updated_at) " +
        "INSERT INTO ${history(v)} (from_id, to_id, type, version, data, updated_at) SELECT from_id, to_id, type, version, data, updated_at FROM u"
    private fun upsertHistory(v: String) = "WITH u AS (${upsert(v, true)} ${appendHistory(v)}"
    private fun upsertHistoryMultirow(v: String) =
        "WITH input AS (SELECT * FROM unnest(?::bytea[], ?::bytea[], ?::text[], ?::text[], ?::text[], ?::timestamptz[], ?::timestamptz[]) " +
        "AS r(from_id, to_id, type, data, tags, created_at, updated_at)), " +
        "u AS (INSERT INTO ${edges(v)} AS e (from_id, to_id, type, data, tags, created_at, updated_at) " +
        "SELECT from_id, to_id, type, data::jsonb, tags::text[], created_at, updated_at FROM input ${conflict(true)} ${appendHistory(v)}"

    private fun pool() = HikariDataSource(HikariConfig().apply {
        jdbcUrl = "$url?load-balance=false"; username = "abyss"; password = "abyss"; maximumPoolSize = 1; minimumIdle = 1
        addDataSourceProperty("prepareThreshold", "1")
        addDataSourceProperty("reWriteBatchedInserts", "true")
    })

    private fun ddl(vararg sql: String) = DriverManager.getConnection(url, "abyss", "abyss").use { c ->
        c.createStatement().use { s -> sql.forEach(s::execute) }
    }

    private fun createTables(v: String, pk: String) = ddl(
        "DROP TABLE IF EXISTS ${edges(v)}", "DROP TABLE IF EXISTS ${history(v)}",
        "CREATE TABLE ${edges(v)} (LIKE abyss.edges INCLUDING ALL)",
        "ALTER TABLE ${edges(v)} ADD COLUMN version BIGINT NOT NULL DEFAULT 1",
        """CREATE TABLE ${history(v)} (from_id BYTEA NOT NULL, to_id BYTEA NOT NULL, type TEXT NOT NULL, version BIGINT NOT NULL,
            data JSONB NOT NULL, updated_at TIMESTAMPTZ NOT NULL, ${historyPks.getValue(pk)})""",
    )

    private fun Statement.count(sql: String) = executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) }

    private fun bind(c: Connection, st: PreparedStatement, k: Key, tags: Array<String> = emptyArray()) {
        val now = Timestamp(System.currentTimeMillis())
        st.setBytes(1, k.from); st.setBytes(2, k.to); st.setString(3, type); st.setObject(4, data, Types.OTHER)
        st.setArray(5, c.createArrayOf("text", tags)); st.setTimestamp(6, now); st.setTimestamp(7, now)
    }

    private fun bindMultirow(c: Connection, st: PreparedStatement, rows: List<Key>) {
        val now = java.time.Instant.now().toString()
        st.setArray(1, c.createArrayOf("bytea", rows.map { it.from }.toTypedArray()))
        st.setArray(2, c.createArrayOf("bytea", rows.map { it.to }.toTypedArray()))
        st.setArray(3, c.createArrayOf("text", Array(rows.size) { type }))
        st.setArray(4, c.createArrayOf("text", Array(rows.size) { data }))
        st.setArray(5, c.createArrayOf("text", Array(rows.size) { "{}" }))
        st.setArray(6, c.createArrayOf("timestamptz", Array(rows.size) { now }))
        st.setArray(7, c.createArrayOf("timestamptz", Array(rows.size) { now }))
    }

    private fun report(tag: String, ms: Map<String, List<Double>>, ops: Int, base: String) {
        val baseMed = ms.getValue(base).sorted()[rounds / 2]
        ms.forEach { (v, all) ->
            val s = all.sorted()
            println("EPROBE %-8s %-16s %6d ops median=%7.0f ms  range=%.0f..%.0f  %6.0f ops/s  vs %s: %+.1f%%".format(
                tag, v, ops, s[rounds / 2], s.first(), s.last(), ops * 1000.0 / s[rounds / 2], base, (s[rounds / 2] / baseMed - 1) * 100))
        }
    }

    // Runs every shape once per round, interleaved, round 0 unrecorded.
    private fun measure(shapes: List<String>, run: (String) -> Double): Map<String, List<Double>> {
        val ms = shapes.associateWith { mutableListOf<Double>() }
        repeat(rounds + 1) { round ->
            shapes.indices.forEach { k ->
                val v = shapes[(k + round) % shapes.size]
                val d = run(v)
                if (round > 0) ms.getValue(v) += d
            }
        }
        return ms
    }

    private fun timed(block: () -> Unit): Double { val s = System.nanoTime(); block(); return (System.nanoTime() - s) / 1e6 }

    private fun withSchema(block: () -> Unit) {
        if (System.getProperty("perf") == null) return
        ddl("DROP SCHEMA IF EXISTS $schema CASCADE", "CREATE SCHEMA $schema")
        try { block() } finally { ddl("DROP SCHEMA IF EXISTS $schema CASCADE") }
    }

    // modifyEdge today (delete, then insert) against an upsert of the same key, one transaction per event.
    @Test fun `modifyEdge shape - delete plus insert vs upsert, cost and tags`() = withSchema {
        val shapes = listOf("del_ins", "upsert")
        shapes.forEach { createTables(it, "key") }
        val k = Key(bytes(), bytes())
        val n = 1_001
        pool().use { pool ->
            val ms = measure(shapes) { v ->
                pool.connection.use { c ->
                    c.autoCommit = false
                    c.prepareStatement("DELETE FROM ${edges(v)} WHERE from_id = ? AND to_id = ? AND type = ?").use { del ->
                        c.prepareStatement(upsert(v, true)).use { up ->
                            fun modify(tag: String) {
                                if (v == "del_ins") { del.setBytes(1, k.from); del.setBytes(2, k.to); del.setString(3, type); del.executeUpdate() }
                                bind(c, up, k, arrayOf(tag)); up.executeUpdate(); c.commit()
                            }
                            bind(c, up, k, arrayOf("stable")); up.executeUpdate(); c.commit()
                            // The tag stays put while timed: a new one per event grows the union and
                            // rewrites the array through the GIN index, which measures the tags, not the shape.
                            timed { repeat(n) { modify("stable") } }.also {
                                modify("other")
                                @Suppress("UNCHECKED_CAST")
                                val tags = c.createStatement().use { s -> s.executeQuery("SELECT tags FROM ${edges(v)}").use { rs -> rs.next(); (rs.getArray(1).array as Array<String>).size } }
                                assertEquals(if (v == "del_ins") 1 else 2, tags, "$v: delete+insert replaces tags, upsert unions them")
                                c.createStatement().use { s -> s.execute("TRUNCATE ${edges(v)}") }; c.commit()
                            }
                        }
                    }
                }
            }
            report("modify", ms, n, "del_ins")
        }
    }

    // Single op, 1 writer, spread keys: mostly inserts. History statement per PK shape.
    @Test fun `single op - plain, version, history under both PKs`() = withSchema {
        val shapes = listOf("plain", "plain_ver", "hist_from", "hist_key")
        shapes.forEach { createTables(it, if (it == "hist_from") "from" else "key") }
        val n = 1_001
        pool().use { pool ->
            val ms = measure(shapes) { v ->
                val ks = keys(hub = false)
                val sql = when (v) { "plain" -> upsert(v, false); "plain_ver" -> upsert(v, true); else -> upsertHistory(v) }
                val d = pool.connection.use { c ->
                    c.autoCommit = false
                    c.prepareStatement(sql).use { st -> timed { repeat(n) { i -> bind(c, st, ks[i % batchSize]); st.executeUpdate(); c.commit() } } }
                }
                ddl("TRUNCATE ${edges(v)}, ${history(v)}")
                d
            }
            report("single", ms, n, "plain")
        }
    }

    @Test fun `batched - plain, version, multirow history under both PKs, spread and hub`() = withSchema {
        val shapes = listOf("plain", "plain_ver", "multi_from", "multi_key")
        val n = 10_007
        for (hub in listOf(false, true)) {
            shapes.forEach { createTables(it, if (it == "multi_from") "from" else "key") }
            pool().use { pool ->
                val ms = measure(shapes) { v ->
                    val ks = keys(hub)
                    val hist = v.startsWith("multi")
                    val sql = when { hist -> upsertHistoryMultirow(v); v == "plain" -> upsert(v, false); else -> upsert(v, true) }
                    val d = pool.connection.use { c ->
                        c.autoCommit = false
                        c.prepareStatement(sql).use { st ->
                            timed {
                                for (chunk in (0 until n).chunked(batchSize)) {
                                    if (hist) { bindMultirow(c, st, chunk.map { ks[it % batchSize] }); assertEquals(chunk.size, st.executeUpdate()) }
                                    else { chunk.forEach { bind(c, st, ks[it % batchSize]); st.addBatch() }; st.executeBatch() }
                                    c.commit()
                                }
                            }
                        }
                    }
                    pool.connection.use { c -> c.createStatement().use { s ->
                        assertEquals(batchSize.toLong(), s.count("SELECT count(*) FROM ${edges(v)}"), "$v edges")
                        if (v != "plain") assertEquals(n.toLong(), s.count("SELECT sum(version) FROM ${edges(v)}"), "$v version sum")
                        assertEquals(if (hist) n.toLong() else 0L, s.count("SELECT count(*) FROM ${history(v)}"), "$v history")
                        s.execute("TRUNCATE ${edges(v)}, ${history(v)}")
                    } }
                    d
                }
                report(if (hub) "hub" else "spread", ms, n, "plain")
            }
        }
    }

    // DeleteEdge carries its type, so a plain type keeps the bare statement; only a history type cascades.
    @Test fun `delete - bare vs cascade on a history type, batched`() = withSchema {
        val shapes = listOf("del_bare", "del_cascade")
        shapes.forEach { createTables(it, "key") }
        val n = 3_003
        pool().use { pool ->
            val ms = measure(shapes) { v ->
                val ks = List(n) { Key(bytes(), bytes()) }
                pool.connection.use { c ->
                    c.autoCommit = false
                    c.prepareStatement(upsertHistoryMultirow(v)).use { st ->
                        ks.chunked(batchSize).forEach { bindMultirow(c, st, it); st.executeUpdate(); c.commit() }
                    }
                    val sql = if (v == "del_bare") "DELETE FROM ${edges(v)} WHERE from_id = ? AND to_id = ? AND type = ?"
                    else "WITH d AS (DELETE FROM ${edges(v)} WHERE from_id = ? AND to_id = ? AND type = ?) " +
                        "DELETE FROM ${history(v)} WHERE from_id = ? AND to_id = ? AND type = ?"
                    val d = c.prepareStatement(sql).use { st ->
                        timed {
                            for (chunk in ks.chunked(batchSize)) {
                                chunk.forEach {
                                    st.setBytes(1, it.from); st.setBytes(2, it.to); st.setString(3, type)
                                    if (v != "del_bare") { st.setBytes(4, it.from); st.setBytes(5, it.to); st.setString(6, type) }
                                    st.addBatch()
                                }
                                st.executeBatch(); c.commit()
                            }
                        }
                    }
                    c.createStatement().use { s ->
                        assertEquals(0L, s.count("SELECT count(*) FROM ${edges(v)}"), "$v edges left")
                        assertEquals(if (v == "del_bare") n.toLong() else 0L, s.count("SELECT count(*) FROM ${history(v)}"), "$v history left")
                        s.execute("TRUNCATE ${edges(v)}, ${history(v)}")
                    }
                    c.commit()
                    d
                }
            }
            report("delete", ms, n, "del_bare")
        }
    }

    // Through the real store: one event = a node save plus an edge save, in op order (each op its own
    // group today) against the same pairs with each chunk's node saves ahead of its edge saves.
    @Test fun `real store - alternating node and edge saves in a batch`() = withSchema {
        ddl(
            "CREATE TABLE $schema.nodes (LIKE abyss.nodes INCLUDING ALL)",
            "CREATE TABLE $schema.edges (LIKE abyss.edges INCLUDING ALL)",
            "CREATE TABLE $schema.node_history (LIKE abyss.node_history INCLUDING ALL)",
        )
        val store = YugabytePersistentStore.create(
            ysqlUrl = url, ysqlUser = "abyss", ysqlPassword = "abyss", ysqlSchema = schema, ysqlMaxPoolSize = 2,
            module = SerializersModule {
                polymorphic(NodeLike::class) { subclass(YbTestNode::class) }
                polymorphic(EdgeLike::class) { subclass(YbTestEdge::class) }
            },
        )
        val pairs = 5_003
        val hub = Uuid.random()
        fun AbyssStoreTransactionLike.node(u: Uuid) = saveNode(UuidKeyAdapter.toNodeId(u), YbTestNode(id = u, name = "n"), emptySet())
        fun AbyssStoreTransactionLike.edge(u: Uuid) =
            saveEdge(UuidKeyAdapter.toNodeId(u), UuidKeyAdapter.toNodeId(hub), YbTestEdge(fromId = u, toId = hub, label = "e"), emptySet())
        try {
            val ms = measure(listOf("alternating", "nodes_then_edges")) { v ->
                val ids = List(pairs) { Uuid.random() }
                val d = timed { runBlocking {
                    assertEquals(null, store.batchTransaction(batchSize) {
                        if (v == "alternating") ids.forEach { node(it); edge(it) }
                        else ids.chunked(batchSize / 2).forEach { c -> c.forEach { node(it) }; c.forEach { edge(it) } }
                    }.leftOrNull()?.toString())
                } }
                ddl("TRUNCATE $schema.nodes, $schema.edges")
                d
            }
            report("event", ms, pairs * 2, "nodes_then_edges")
        } finally { store.close() }
    }
}
