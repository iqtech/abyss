package pl.iqtech.abyss.store.yugabyte

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

// Write cost of keeping an entity's past, for ai/flow nodes-history: the version-counter CTE (one extra
// row in node_history) vs the graph-native chain (event node + has-next edge + tail pointer moved as
// RemoveEdge + AddEdge, the modifyEdge shape for a moved key), both against the plain live-state upsert.
// Each variant gets its own copy of abyss.nodes / abyss.edges with the production DDL and every index.
// One event per transaction. DB only: the chain's Hazelcast cost (puts, adjacency processors) is not
// measured, so the chain is understated. Variants interleaved per round, order rotated, round 0 = warm-up.
class HistoryVsChainWriteCostTest {

    // trigger: plain upsert plus version bump and history flag; an AFTER row trigger writes history.
    private val variants = listOf("plain", "history", "chain", "trigger")
    private fun nodes(v: String) = "abyss.hist_spike_${v}_nodes"
    private fun edges(v: String) = "abyss.hist_spike_${v}_edges"
    private fun history(v: String) = "abyss.hist_spike_${v}_history"
    private fun historyFn(v: String) = "abyss.hist_spike_${v}_history_append"

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
    private val upsertNodeFlagged = { t: String ->
        "INSERT INTO $t AS n (id, type, data, tags, created_at, updated_at, history) VALUES (?, ?, ?, ?, ?, ?, true) " +
            "ON CONFLICT (id) DO UPDATE SET type = EXCLUDED.type, data = EXCLUDED.data, " +
            "tags = ARRAY(SELECT DISTINCT UNNEST(n.tags || EXCLUDED.tags)), updated_at = EXCLUDED.updated_at, " +
            "version = n.version + 1"
    }
    private fun liveStatement(v: String) = when (v) {
        "history" -> upsertNodeWithHistory(nodes(v), history(v))
        "trigger" -> upsertNodeFlagged(nodes(v))
        else -> upsertNode(nodes(v))
    }
    private val upsertEdge = { t: String ->
        "INSERT INTO $t AS e (from_id, to_id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (from_id, to_id, type) DO UPDATE SET data = EXCLUDED.data, " +
            "tags = ARRAY(SELECT DISTINCT UNNEST(e.tags || EXCLUDED.tags)), updated_at = EXCLUDED.updated_at"
    }
    private val deleteEdge = { t: String -> "DELETE FROM $t WHERE from_id = ? AND to_id = ? AND type = ?" }
    private val payload = "x".repeat(240)

    private val seqEvents = 1000   // one writer, one entity
    private val conc = 16          // writers, each on its own entity
    private val perWriter = 200
    private val rounds = 5

    @Test fun `history row vs has-next chain vs plain upsert - write cost`() {
        if (System.getProperty("perf") == null) return
        val pool = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:yugabytedb://localhost:5433/abyss_test_graph?load-balance=false"
            username = "abyss"; password = "abyss"; maximumPoolSize = conc; minimumIdle = conc
        })
        try {
            pool.connection.use { c -> c.createStatement().use { s -> variants.forEach { v -> createTables(s, v) } } }

            val ms = mutableMapOf<Pair<String, String>, MutableList<Double>>() // (workload, variant) -> ms per round
            repeat(rounds + 1) { round ->
                for (v in variants.indices.map { variants[(it + round) % variants.size] }) {
                    val w = linkedMapOf(
                        "1 one writer, one entity" to timed { appendEvents(pool, v, Uuid.random(), seqEvents) },
                        "2 $conc writers, own entity each" to timed { runBlocking {
                            (0 until conc).map { async(Dispatchers.IO) { appendEvents(pool, v, Uuid.random(), perWriter) } }.awaitAll()
                        } },
                    )
                    if (round > 0) w.forEach { (name, d) -> ms.getOrPut(name to v) { mutableListOf() } += d }
                }
            }

            val entities = (rounds + 1L) * (1 + conc)
            val events = (rounds + 1L) * (seqEvents + conc * perWriter)
            pool.connection.use { c -> c.createStatement().use { s ->
                fun count(sql: String) = s.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) }
                assertEquals(entities, count("SELECT count(*) FROM ${nodes("plain")}"), "plain nodes")
                for (v in listOf("history", "trigger")) {
                    assertEquals(entities, count("SELECT count(*) FROM ${nodes(v)}"), "$v nodes")
                    assertEquals(events, count("SELECT count(*) FROM ${history(v)}"), "$v history rows")
                    assertEquals(0L, count(gapQuery(nodes(v), history(v))), "$v history gaps or version drift")
                    assertEquals(entities, count("SELECT count(DISTINCT node_id) FROM ${history(v)}"), "$v entities with history")
                }
                assertEquals(entities + events, count("SELECT count(*) FROM ${nodes("chain")}"), "chain nodes")
                // one has-next per event, one tail pointer per entity
                assertEquals(events + entities, count("SELECT count(*) FROM ${edges("chain")}"), "chain edges")
            } }

            val eventsOf = mapOf('1' to seqEvents, '2' to conc * perWriter)
            ms.keys.map { it.first }.distinct().forEach { wl ->
                val med = variants.associateWith { v -> ms.getValue(wl to v).sorted()[rounds / 2] }
                variants.forEach { v ->
                    val all = ms.getValue(wl to v).sorted()
                    println("HCOST %-32s %-8s median=%7.0f ms  range=%.0f..%.0f  %6.0f events/s  vs plain: %+.1f%%".format(
                        wl, v, med.getValue(v), all.first(), all.last(), eventsOf.getValue(wl[0]) * 1000.0 / med.getValue(v),
                        (med.getValue(v) / med.getValue("plain") - 1) * 100))
                }
            }
        } finally {
            pool.connection.use { c -> c.createStatement().use { s -> variants.forEach { v -> dropTables(s, v) } } }
            pool.close()
        }
    }

    // Read-committed exactness: `conc` writers update ONE id concurrently. History must hold exactly
    // versions 1..total with the node on the last one. The run above gives each writer its own id, so it
    // never contends. One transaction per event; rate reported, not compared (hot-id writes are slow).
    @Test fun `history CTE vs trigger - 16 writers on one id stay gap-free`() {
        if (System.getProperty("perf") == null) return
        val pool = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:yugabytedb://localhost:5433/abyss_test_graph?load-balance=false"
            username = "abyss"; password = "abyss"; maximumPoolSize = conc; minimumIdle = conc
        })
        val perWriterHot = 21 // odd, so total (336) isn't a round number
        val hotVariants = listOf("plain", "history", "trigger") // plain: production upsert, the no-history control
        val failures = linkedMapOf<String, String>()
        try {
            for (v in hotVariants) {
                pool.connection.use { c -> c.createStatement().use { createTables(it, v) } }
                val hot = Uuid.random()
                val total = conc * perWriterHot
                val outcome = runCatching {
                    val d = timed { runBlocking {
                        (0 until conc).map { async(Dispatchers.IO) { appendEvents(pool, v, hot, perWriterHot) } }.awaitAll()
                    } }
                    pool.connection.use { c -> c.createStatement().use { s ->
                        fun count(sql: String) = s.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) }
                        if (v != "plain") {
                            assertEquals(total.toLong(), count("SELECT count(*) FROM ${history(v)}"), "$v history rows")
                            assertEquals(0L, count(gapQuery(nodes(v), history(v))), "$v history gaps or version drift")
                            assertEquals(total.toLong(), count("SELECT max(version) FROM ${nodes(v)}"), "$v node version")
                        }
                    } }
                    d
                }
                outcome.onSuccess { d -> println("HHOT %-8s %d writers x %d on one id: %.0f ms, %.1f events/s".format(v, conc, perWriterHot, d, total * 1000.0 / d)) }
                outcome.onFailure { e -> failures[v] = e.message ?: e.toString(); println("HHOT %-8s FAILED: %s".format(v, e.message)) }
            }
            assertEquals(emptyMap(), failures, "variants that failed under one-id contention")
        } finally {
            pool.connection.use { c -> c.createStatement().use { s -> hotVariants.forEach { dropTables(s, it) } } }
            pool.close()
        }
    }

    // Every entity's history is exactly versions 1..n with no gaps, and the node sits on version n.
    private fun gapQuery(nodes: String, history: String) = """SELECT count(*) FROM (SELECT node_id, min(version) lo,
        max(version) hi, count(*) n FROM $history GROUP BY node_id) g JOIN $nodes x ON x.id = g.node_id
        WHERE g.lo <> 1 OR g.hi <> g.n OR x.version <> g.hi"""

    private fun dropTables(s: java.sql.Statement, v: String) {
        s.execute("DROP TABLE IF EXISTS ${history(v)}"); s.execute("DROP TABLE IF EXISTS ${edges(v)}"); s.execute("DROP TABLE IF EXISTS ${nodes(v)}")
        s.execute("DROP FUNCTION IF EXISTS ${historyFn(v)}()")
    }

    // Production DDL (ysql-schema.sql) plus the version column, and node_history as decided in the idea.
    private fun createTables(s: java.sql.Statement, v: String) {
        dropTables(s, v)
        s.execute("""CREATE TABLE ${nodes(v)} (id BYTEA PRIMARY KEY, type TEXT NOT NULL, data JSONB NOT NULL,
            tags TEXT[] NOT NULL DEFAULT '{}', created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
            version BIGINT NOT NULL DEFAULT 1, history BOOLEAN NOT NULL DEFAULT false)""")
        s.execute("CREATE INDEX hist_spike_${v}_nodes_type ON ${nodes(v)} (type)")
        s.execute("CREATE INDEX hist_spike_${v}_nodes_tags ON ${nodes(v)} USING GIN (tags)")
        s.execute("""CREATE TABLE ${edges(v)} (from_id BYTEA NOT NULL, to_id BYTEA NOT NULL, type TEXT NOT NULL,
            data JSONB NOT NULL, tags TEXT[] NOT NULL DEFAULT '{}', created_at TIMESTAMPTZ NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL, PRIMARY KEY (from_id, to_id, type))""")
        s.execute("CREATE INDEX hist_spike_${v}_edges_to ON ${edges(v)} (to_id)")
        s.execute("CREATE INDEX hist_spike_${v}_edges_type ON ${edges(v)} (type)")
        s.execute("CREATE INDEX hist_spike_${v}_edges_tags ON ${edges(v)} USING GIN (tags)")
        s.execute("""CREATE TABLE ${history(v)} (node_id BYTEA NOT NULL, version BIGINT NOT NULL, type TEXT NOT NULL,
            data JSONB NOT NULL, updated_at TIMESTAMPTZ NOT NULL, PRIMARY KEY (node_id HASH, version ASC))""")
        if (v == "trigger") createHistoryTrigger(s, nodes(v), history(v), historyFn(v))
    }

    private fun timed(block: () -> Unit): Double { val s = System.nanoTime(); block(); return (System.nanoTime() - s) / 1e6 }

    private fun bindNode(c: Connection, st: PreparedStatement, id: Uuid, type: String, seq: Int) {
        val now = Timestamp(System.currentTimeMillis())
        st.setBytes(1, UuidKeyAdapter.toNodeId(id).bytes)
        st.setString(2, type)
        st.setObject(3, """{"type":"$type","id":"$id","seq":$seq,"payload":"$payload"}""", Types.OTHER)
        st.setArray(4, c.createArrayOf("text", emptyArray<String>()))
        st.setTimestamp(5, now); st.setTimestamp(6, now)
    }

    private fun bindEdge(c: Connection, st: PreparedStatement, from: Uuid, to: Uuid, type: String) {
        val now = Timestamp(System.currentTimeMillis())
        st.setBytes(1, UuidKeyAdapter.toNodeId(from).bytes)
        st.setBytes(2, UuidKeyAdapter.toNodeId(to).bytes)
        st.setString(3, type)
        st.setObject(4, """{"type":"$type"}""", Types.OTHER)
        st.setArray(5, c.createArrayOf("text", emptyArray<String>()))
        st.setTimestamp(6, now); st.setTimestamp(7, now)
    }

    // One transaction per event, as commitYsql runs it: one executeUpdate() per op.
    private fun appendEvents(pool: HikariDataSource, v: String, entity: Uuid, n: Int) = pool.connection.use { c ->
        c.autoCommit = false
        val live = c.prepareStatement(liveStatement(v))
        val addNode = c.prepareStatement(upsertNode(nodes(v)))
        val addEdge = c.prepareStatement(upsertEdge(edges(v)))
        val delEdge = c.prepareStatement(deleteEdge(edges(v)))
        var tail = entity // the chain starts at the entity itself
        for (seq in 1..n) {
            bindNode(c, live, entity, "device", seq); live.executeUpdate()
            if (v == "chain") {
                val event = Uuid.random()
                bindNode(c, addNode, event, "event", seq); addNode.executeUpdate()
                bindEdge(c, addEdge, tail, event, "has-next"); addEdge.executeUpdate()
                if (tail != entity) {
                    delEdge.setBytes(1, UuidKeyAdapter.toNodeId(entity).bytes)
                    delEdge.setBytes(2, UuidKeyAdapter.toNodeId(tail).bytes)
                    delEdge.setString(3, "tail"); delEdge.executeUpdate()
                }
                bindEdge(c, addEdge, entity, event, "tail"); addEdge.executeUpdate()
                tail = event
            }
            c.commit()
        }
        listOf(live, addNode, addEdge, delEdge).forEach { it.close() }
    }
}

// The trigger variant of nodes history (shared with HistoryBatchRewriteTest): an AFTER row trigger copies
// each new row state of a flagged node into history. NEW.version is the value this row's own write just
// set, after the ON CONFLICT path locked the row, so nothing is re-read.
internal fun createHistoryTrigger(s: java.sql.Statement, nodes: String, history: String, fn: String) {
    s.execute(
        "CREATE OR REPLACE FUNCTION $fn() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN " +
            "INSERT INTO $history (node_id, version, type, data, updated_at) " +
            "VALUES (NEW.id, NEW.version, NEW.type, NEW.data, NEW.updated_at); RETURN NULL; END \$\$"
    )
    s.execute("CREATE TRIGGER ${fn.substringAfter('.')} AFTER INSERT OR UPDATE ON $nodes FOR EACH ROW WHEN (NEW.history) EXECUTE FUNCTION $fn()")
}
