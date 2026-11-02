package pl.iqtech.abyss.store.yugabyte

import arrow.core.Either
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import pl.iqtech.abyss.store.api.WithHistory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
@SerialName("yb_hist_node")
@WithHistory
data class YbHistNode(
    override val id: Uuid,
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
    val name: String
) : NodeLike<Uuid>

// nodes-history plan.md, cases 1-11: the real store on its own schema (LIKE abyss.* INCLUDING ALL), so
// counts are exact and the shared rows stay untouched. Requires the live YugabyteDB container
// (localhost:5433). YbHistNode is @WithHistory, YbTestNode is plain.
class NodeHistoryTest {

    private val url = "jdbc:yugabytedb://localhost:5433/abyss_test_graph"
    private val schema = "abyss_nodehist"

    private val module = SerializersModule {
        polymorphic(NodeLike::class) { subclass(YbHistNode::class); subclass(YbTestNode::class) }
        polymorphic(EdgeLike::class) { subclass(YbTestEdge::class) }
    }

    private fun <T> conn(block: (Connection) -> T): T = DriverManager.getConnection(url, "abyss", "abyss").use(block)
    private fun ddl(vararg sql: String) = conn { c -> c.createStatement().use { s -> sql.forEach(s::execute) } }

    @BeforeTest fun createSchema() = ddl(
        "DROP SCHEMA IF EXISTS $schema CASCADE",
        "CREATE SCHEMA $schema",
        "CREATE TABLE $schema.nodes (LIKE abyss.nodes INCLUDING ALL)",
        "CREATE TABLE $schema.edges (LIKE abyss.edges INCLUDING ALL)",
        "CREATE TABLE $schema.node_history (LIKE abyss.node_history INCLUDING ALL)",
    )

    // Lazy: property initializers run before @BeforeTest, and case 10 sets the zone before connecting.
    private val store by lazy {
        YugabytePersistentStore.create(
            ysqlUrl = url, ysqlUser = "abyss", ysqlPassword = "abyss", ysqlSchema = schema, module = module, ysqlMaxPoolSize = 16,
        )
    }

    @AfterTest fun dropSchema() {
        store.close()
        ddl("DROP SCHEMA IF EXISTS $schema CASCADE")
    }

    private enum class CommitPath { SINGLE, BATCHED }

    private suspend fun commit(path: CommitPath, batchSize: Int = 4, block: suspend AbyssStoreTransactionLike.() -> Unit) =
        ok(if (path == CommitPath.SINGLE) store.transaction(block) else store.batchTransaction(batchSize, block))

    private fun ok(result: Either<AbyssError, *>) = assertEquals(null, result.leftOrNull()?.toString(), "commit failed")

    private suspend fun AbyssStoreTransactionLike.saveH(id: Uuid, name: String, tags: Set<String> = emptySet()) =
        saveNode(UuidKeyAdapter.toNodeId(id), YbHistNode(id = id, name = name), tags)

    private suspend fun AbyssStoreTransactionLike.saveP(id: Uuid, name: String) =
        saveNode(UuidKeyAdapter.toNodeId(id), YbTestNode(id = id, name = name), emptySet())

    private suspend fun AbyssStoreTransactionLike.delete(id: Uuid) = deleteNode(UuidKeyAdapter.toNodeId(id))

    private fun <T> column(query: String, ids: List<Uuid>, read: (ResultSet) -> T): List<T> = conn { c ->
        c.prepareStatement(query).use { st ->
            ids.forEachIndexed { i, id -> st.setBytes(i + 1, UuidKeyAdapter.toNodeId(id).bytes) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        }
    }

    private fun count(query: String): Long = column(query, emptyList()) { it.getLong(1) }.single()
    private fun history(id: Uuid) = column("SELECT version FROM $schema.node_history WHERE node_id = ? ORDER BY version", listOf(id)) { it.getLong(1) }
    private fun version(id: Uuid) = column("SELECT version FROM $schema.nodes WHERE id = ?", listOf(id)) { it.getLong(1) }.single()
    private fun nodeExists(id: Uuid) = column("SELECT 1 FROM $schema.nodes WHERE id = ?", listOf(id)) { it.getLong(1) }.isNotEmpty()
    private fun historyNames(id: Uuid) = column("SELECT data->>'name' FROM $schema.node_history WHERE node_id = ? ORDER BY version", listOf(id)) { it.getString(1) }
    private fun nodeName(id: Uuid) = column("SELECT data->>'name' FROM $schema.nodes WHERE id = ?", listOf(id)) { it.getString(1) }.single()

    // A row the store didn't write, at `version`, with history rows for `historyVersions`.
    private fun plant(id: Uuid, type: String, version: Long, historyVersions: List<Long>) = conn { c ->
        c.prepareStatement("INSERT INTO $schema.nodes (id, type, data, tags, created_at, updated_at, version) VALUES (?, ?, '{}', '{}', now(), now(), ?)").use { st ->
            st.setBytes(1, UuidKeyAdapter.toNodeId(id).bytes); st.setString(2, type); st.setLong(3, version); st.executeUpdate()
        }
        historyVersions.forEach { v ->
            c.prepareStatement("INSERT INTO $schema.node_history (node_id, version, type, data, updated_at) VALUES (?, ?, ?, '{}', now())").use { st ->
                st.setBytes(1, UuidKeyAdapter.toNodeId(id).bytes); st.setLong(2, v); st.setString(3, type); st.executeUpdate()
            }
        }
    }

    // ── 1-4: every save is one history row ──────────────────────────────────────────────────────────

    // 16 x 21 on one id: the upsert's row lock orders the writers, so history is exact under contention.
    @Test fun `1 contended saves of one id - history is exactly 1 to 336, last row equals the node`() = runBlocking<Unit> {
        val id = Uuid.random()
        coroutineScope {
            repeat(16) { w ->
                launch(Dispatchers.Default) { repeat(21) { n -> ok(store.transaction { saveH(id, "w$w-$n") }) } }
            }
        }
        assertEquals((1L..336L).toList(), history(id))
        assertEquals(336L, version(id))
        assertEquals(nodeName(id), historyNames(id).last(), "last history row is the node's state")
    }

    // Saves per id are 3/1/5, ids start at 5/0/2 with no history (rows from before the migration).
    @Test fun `2 distinct ids from non-zero versions - single path`() = distinctIds(CommitPath.SINGLE)
    @Test fun `2 distinct ids from non-zero versions - batched path`() = distinctIds(CommitPath.BATCHED)

    private fun distinctIds(path: CommitPath) = runBlocking<Unit> {
        val (a, b, c) = List(3) { Uuid.random() }
        plant(a, "yb_hist_node", 5, emptyList())
        plant(c, "yb_hist_node", 2, emptyList())
        val order = listOf(a, c, b, a, c, c, a, c, c)
        commit(path) { order.forEachIndexed { pos, id -> saveH(id, "n$pos") } }
        assertEquals(listOf(listOf(6L, 7L, 8L), listOf(1L), listOf(3L, 4L, 5L, 6L, 7L)), listOf(a, b, c).map(::history))
        assertEquals(listOf(8L, 1L, 7L), listOf(a, b, c).map(::version))
    }

    @Test fun `3 plain type writes no history, history type one row per save`() = runBlocking<Unit> {
        val (h, p) = List(2) { Uuid.random() }
        ok(store.transaction { saveH(h, "h0"); saveH(h, "h1"); saveP(p, "p0"); saveP(p, "p1") })
        assertEquals(listOf(1L, 2L), history(h))
        assertEquals(listOf("h0", "h1"), historyNames(h))
        assertEquals(emptyList(), history(p))
        assertEquals(2L, version(p), "plain still counts saves")
    }

    // Batch 4 over 7 ops: chunks [a b a c] [a b a]; a repeats inside chunk 1 and crosses the boundary.
    @Test fun `4 batch crossing a chunk boundary with a repeated id`() = runBlocking<Unit> {
        val (a, b, c) = List(3) { Uuid.random() }
        val order = listOf(a, b, a, c, a, b, a)
        commit(CommitPath.BATCHED) { order.forEachIndexed { pos, id -> saveH(id, "b$pos") } }
        assertEquals(listOf((1L..4L).toList(), listOf(1L, 2L), listOf(1L)), listOf(a, b, c).map(::history))
        assertEquals(listOf("b0", "b2", "b4", "b6"), historyNames(a), "states in op order")
    }

    // ── 5-6: mixed batches ──────────────────────────────────────────────────────────────────────────

    // The delete splits the chunk: moving S(x) across D(x) leaves x deleted, or with a stale history row.
    @Test fun `5 mixed chunk keeps per-row order across the statement split`() = runBlocking<Unit> {
        val (x, p, q) = List(3) { Uuid.random() }
        commit(CommitPath.BATCHED, batchSize = 8) { saveH(x, "old"); saveP(p, "p"); delete(x); saveH(x, "new"); saveP(q, "q") }
        assertEquals(1L, version(x))
        assertEquals(listOf("new"), historyNames(x))
        assertEquals(listOf(true, true), listOf(p, q).map(::nodeExists))
        assertEquals(listOf(emptyList(), emptyList()), listOf(p, q).map(::history), "plain saves took the plain statement")
    }

    // Alternating types over 10007 ops: even op index = history id, 1000 ids per type, so ids repeat.
    @Test fun `6 mixed history and plain over 10007 ops loses nothing on either statement`() = runBlocking<Unit> {
        val n = 10_007
        val ids = List(2000) { Uuid.random() }
        commit(CommitPath.BATCHED, batchSize = 1000) {
            repeat(n) { i -> if (i % 2 == 0) saveH(ids[i % 2000], "h$i") else saveP(ids[i % 2000], "p$i") }
        }
        val historyOps = (0 until n).count { it % 2 == 0 }.toLong()
        assertEquals(2000L, count("SELECT count(*) FROM $schema.nodes"))
        assertEquals(n.toLong(), count("SELECT sum(version) FROM $schema.nodes"), "version sum = saves")
        assertEquals(historyOps, count("SELECT count(*) FROM $schema.node_history"))
        assertEquals(0L, count("SELECT count(*) FROM $schema.node_history WHERE type <> 'yb_hist_node'"))
        assertEquals(0L, count("""SELECT count(*) FROM (SELECT node_id, min(version) lo, max(version) hi, count(*) k
            FROM $schema.node_history GROUP BY node_id) g JOIN $schema.nodes x ON x.id = g.node_id
            WHERE g.lo <> 1 OR g.hi <> g.k OR x.version <> g.hi"""), "history gaps or version drift")
    }

    // ── 7-9: delete cascade ─────────────────────────────────────────────────────────────────────────

    @Test fun `7 delete then re-create starts at v1 - single path`() = recreate(CommitPath.SINGLE)
    @Test fun `7 delete then re-create starts at v1 - batched path`() = recreate(CommitPath.BATCHED)

    private fun recreate(path: CommitPath) = runBlocking<Unit> {
        val x = Uuid.random()
        commit(path) { saveH(x, "v1"); saveH(x, "v2") }
        commit(path) { delete(x) }
        assertEquals(emptyList(), history(x), "delete leaves no history")
        commit(path) { saveH(x, "again") }
        assertEquals(listOf("again"), historyNames(x))
        assertEquals(1L, version(x))
    }

    @Test fun `8 delete of an unknown type cascades - single path`() = unknownType(CommitPath.SINGLE)
    @Test fun `8 delete of an unknown type cascades - batched path`() = unknownType(CommitPath.BATCHED)

    private fun unknownType(path: CommitPath) = runBlocking<Unit> {
        val x = Uuid.random()
        plant(x, "yb_gone_from_module", 3, listOf(1L, 2L, 3L))
        commit(path) { delete(x) }
        assertEquals(emptyList(), history(x))
    }

    // History on a plain id can't happen in production; here it shows a cascade that ignored the type.
    @Test fun `9 plain delete leaves history alone - single path`() = plainDelete(CommitPath.SINGLE)
    @Test fun `9 plain delete leaves history alone - batched path`() = plainDelete(CommitPath.BATCHED)

    private fun plainDelete(path: CommitPath) = runBlocking<Unit> {
        val (p, h) = List(2) { Uuid.random() }
        plant(p, "yb_test_node", 2, listOf(1L, 2L))
        commit(path) { saveH(h, "h") }
        commit(path) { delete(p) }
        assertEquals(listOf(1L, 2L), history(p), "plain type: no cascade")
        assertEquals(listOf(1L), history(h), "other ids untouched")
        assertEquals(false, nodeExists(p))
    }

    // ── 10: values through the multi-row statement ──────────────────────────────────────────────────

    // Both paths must store the same bytes. The instants sit in Warsaw's fall-back hour, when 02:30 local
    // happens twice, so a value sent as local time without its offset lands an hour off on one of them.
    @Test fun `10 tags, data and timestamps land verbatim on both paths`() = runBlocking<Unit> {
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Warsaw"))
        try {
            val first = setOf("a,b", "\"q\"", "{x}", "back\\slash", " sp ", "")
            val second = setOf("{x}", "NULL", "c")
            val name = "q\"uo\\te {a,b} NULL ż"
            val created = Instant.parse("2026-10-25T00:30:00.123456Z")
            val updated = Instant.parse("2026-10-25T01:30:00.654321Z")
            val (single, batched) = List(2) { Uuid.random() }
            suspend fun AbyssStoreTransactionLike.save(id: Uuid, tags: Set<String>) =
                saveNode(UuidKeyAdapter.toNodeId(id), YbHistNode(id, created, updated, name), tags)
            commit(CommitPath.SINGLE) { save(single, first) }
            commit(CommitPath.SINGLE) { save(single, second) }
            commit(CommitPath.BATCHED) { save(batched, first) }
            commit(CommitPath.BATCHED) { save(batched, second) }
            listOf(single, batched).forEach { id ->
                @Suppress("UNCHECKED_CAST")
                val tags = column("SELECT tags FROM $schema.nodes WHERE id = ?", listOf(id)) { (it.getArray(1).array as Array<String?>).toList() }.single()
                assertEquals((first + second).sorted(), tags.map { it ?: "<SQL NULL>" }.sorted(), "tags union")
                assertEquals(listOf(name, name), historyNames(id), "history data")
                assertEquals(name, nodeName(id), "node data")
                val micros = column(
                    "SELECT (extract(epoch FROM created_at) * 1000000)::bigint, (extract(epoch FROM updated_at) * 1000000)::bigint FROM $schema.nodes WHERE id = ?",
                    listOf(id)) { it.getLong(1) to it.getLong(2) }.single()
                assertEquals(created.toEpochMilliseconds() * 1000 + 456 to updated.toEpochMilliseconds() * 1000 + 321, micros, "instants")
            }
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    // ── 11: the history row is part of the transaction ──────────────────────────────────────────────

    @Test fun `11 aborted commit leaves no extra history row - single path`() = abortedCommit(CommitPath.SINGLE)
    @Test fun `11 aborted commit leaves no extra history row - batched path`() = abortedCommit(CommitPath.BATCHED)

    private fun abortedCommit(path: CommitPath) = runBlocking<Unit> {
        val x = Uuid.random()
        val raw = rawYsqlDataSource()
        try {
            val ds = FailAfterNCommitsDataSource(raw, failAtCommit = 1, failCount = 1) { n -> SQLException("simulated 40001 at commit #$n", "40001") }
            val failing = YugabytePersistentStore(ds, module, schema)
            ok(if (path == CommitPath.SINGLE) failing.transaction { saveH(x, "once") } else failing.batchTransaction(4) { saveH(x, "once") })
            assertEquals(2, ds.commitCount, "one abort, one retry")
        } finally { raw.close() }
        assertEquals(listOf(1L), history(x))
        assertEquals(1L, version(x))
    }
}
