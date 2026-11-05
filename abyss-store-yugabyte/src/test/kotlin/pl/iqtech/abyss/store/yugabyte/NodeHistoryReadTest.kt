package pl.iqtech.abyss.store.yugabyte

import arrow.core.Either
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.NodeVersion
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant
import kotlin.uuid.Uuid

// nodes-history plan.md phase E, cases 13-19: loadNodeHistory through the real store, on its own schema
// (LIKE abyss.* INCLUDING ALL) as NodeHistoryTest does. Requires the live YugabyteDB container.
class NodeHistoryReadTest {

    private val url = "jdbc:yugabytedb://localhost:5433/abyss_test_graph"
    private val schema = "abyss_histread"
    private val poolSize = 2

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

    // Pool of 2: case 19 leaks a connection per early stop if the cursor isn't closed on cancel.
    private val store by lazy {
        YugabytePersistentStore.create(
            ysqlUrl = url, ysqlUser = "abyss", ysqlPassword = "abyss", ysqlSchema = schema, module = module, ysqlMaxPoolSize = poolSize,
        )
    }

    @AfterTest fun dropSchema() {
        store.close()
        ddl("DROP SCHEMA IF EXISTS $schema CASCADE")
    }

    private fun ok(result: Either<AbyssError, *>) = assertEquals(null, result.leftOrNull()?.toString(), "commit failed")
    private suspend fun commit(block: suspend AbyssStoreTransactionLike.() -> Unit) = ok(store.transaction(block))

    // Distinct, non-epoch, whole-millisecond instants: a column mix-up or a default shows.
    private fun at(n: Int) = Instant.fromEpochMilliseconds(1_760_000_000_000L + n * 1_001L)

    private suspend fun saveH(id: Uuid, n: Int) =
        commit { saveNode(UuidKeyAdapter.toNodeId(id), YbHistNode(id = id, updatedAt = at(n), name = "s$n"), emptySet()) }

    private suspend fun saveP(id: Uuid, n: Int) =
        commit { saveNode(UuidKeyAdapter.toNodeId(id), YbTestNode(id = id, name = "p$n"), emptySet()) }

    private suspend fun read(id: Uuid, from: Long = 1, limit: Int = Int.MAX_VALUE): List<NodeVersion<NodeLike<*>>> {
        val r = store.loadNodeHistory(UuidKeyAdapter.toNodeId(id), from, limit)
        return (r.getOrNull() ?: error("loadNodeHistory is Left: ${r.leftOrNull()}")).toList()
    }

    private fun List<NodeVersion<NodeLike<*>>>.versions() = map { it.version }
    private fun List<NodeVersion<NodeLike<*>>>.names() = map { (it.node as YbHistNode).name }

    // A node row at `version` with no history rows: state from before the migration.
    private fun plant(id: Uuid, version: Long) = conn { c ->
        c.prepareStatement("INSERT INTO $schema.nodes (id, type, data, tags, created_at, updated_at, version) VALUES (?, 'yb_hist_node', ?::jsonb, '{}', now(), now(), ?)").use { st ->
            st.setBytes(1, UuidKeyAdapter.toNodeId(id).bytes)
            st.setString(2, """{"type":"yb_hist_node","id":"$id","name":"planted"}""")
            st.setLong(3, version)
            st.executeUpdate()
        }
    }

    // ── 13: every version, ascending, as saved ───────────────────────────────────────────────────────

    // Planted at version 4 with no history, so 7 saves are versions 5..11: from = 1 must return them by
    // their stored version, not by position.
    @Test fun `13 seven saves from a non-zero start - defaults return versions 5 to 11 as saved`() = runBlocking<Unit> {
        val id = Uuid.random()
        plant(id, 4)
        (1..7).forEach { saveH(id, it) }

        val all = read(id)

        assertEquals((5L..11L).toList(), all.versions())
        assertEquals((1..7).map { "s$it" }, all.names())
        assertEquals((1..7).map { at(it) }, all.map { it.updatedAt })
        assertEquals(List(7) { id }, all.map { (it.node as YbHistNode).id })
    }

    // ── 14-15: slice boundaries ──────────────────────────────────────────────────────────────────────

    @Test fun `14 slices of seven versions - middle, tail past the end, past the newest`() = runBlocking<Unit> {
        val id = Uuid.random()
        (1..7).forEach { saveH(id, it) }

        assertEquals(listOf(4L, 5L, 6L), read(id, from = 4, limit = 3).versions())
        assertEquals(listOf(7L), read(id, from = 7, limit = 5).versions())
        assertEquals(emptyList(), read(id, from = 8).versions())
    }

    @Test fun `15 from 1 limit 1 is exactly the first version`() = runBlocking<Unit> {
        val id = Uuid.random()
        (1..7).forEach { saveH(id, it) }

        val first = read(id, from = 1, limit = 1)

        assertEquals(listOf(1L), first.versions())
        assertEquals(listOf("s1"), first.names())
    }

    // ── 16: ids with no history ──────────────────────────────────────────────────────────────────────

    @Test fun `16 unknown id, plain type and re-created id`() = runBlocking<Unit> {
        val unknown = Uuid.random()
        val plain = Uuid.random()
        val recreated = Uuid.random()
        repeat(3) { saveP(plain, it) }
        (1..3).forEach { saveH(recreated, it) }
        commit { deleteNode(UuidKeyAdapter.toNodeId(recreated)) }
        (4..5).forEach { saveH(recreated, it) }

        assertEquals(emptyList(), read(unknown))
        assertEquals(emptyList(), read(plain))
        val after = read(recreated)
        assertEquals(listOf(1L, 2L), after.versions())
        assertEquals(listOf("s4", "s5"), after.names())
    }

    // ── 17: arguments the read can't accept ──────────────────────────────────────────────────────────

    @Test fun `17 limit 0, limit -1 and from 0 are InvalidArgument`() {
        val nid = UuidKeyAdapter.toNodeId(Uuid.random())

        assertIs<AbyssError.InvalidArgument>(store.loadNodeHistory(nid, 1, 0).leftOrNull())
        assertIs<AbyssError.InvalidArgument>(store.loadNodeHistory(nid, 1, -1).leftOrNull())
        assertIs<AbyssError.InvalidArgument>(store.loadNodeHistory(nid, 0, 10).leftOrNull())
    }

    // ── 18: one id's rows only ───────────────────────────────────────────────────────────────────────

    @Test fun `18 two ids with interleaved saves, 5 and 3 - each reads only its own`() = runBlocking<Unit> {
        val a = Uuid.random()
        val b = Uuid.random()
        saveH(a, 1); saveH(b, 101); saveH(a, 2); saveH(a, 3); saveH(b, 102); saveH(a, 4); saveH(b, 103); saveH(a, 5)

        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), read(a).names())
        assertEquals(listOf("s101", "s102", "s103"), read(b).names())
    }

    // ── 19: an early stop gives the connection back ──────────────────────────────────────────────────

    // poolSize + 1 early stops in a row: a cursor left open on cancel holds its connection, and the
    // last call times out on the pool instead of returning.
    @Test fun `19 take 2 of 7, pool size plus one times - each returns 1 and 2`() = runBlocking<Unit> {
        val id = Uuid.random()
        (1..7).forEach { saveH(id, it) }

        repeat(poolSize + 1) {
            val r = store.loadNodeHistory(UuidKeyAdapter.toNodeId(id), 1, Int.MAX_VALUE)
            val firstTwo = (r.getOrNull() ?: error("loadNodeHistory is Left: ${r.leftOrNull()}")).take(2).toList()
            assertEquals(listOf(1L, 2L), firstTwo.map { it.version }, "early stop #$it")
        }
    }
}
