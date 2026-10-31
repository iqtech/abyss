package pl.iqtech.abyss.store.yugabyte

import arrow.core.Either
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.sql.BatchUpdateException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Timestamp
import java.sql.Types
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

// ysql-commit-retry-on-conflict: both commit paths retry a transaction aborted with SQLState 40001
// (serialization) or 40P01 (deadlock), up to 3 attempts, and fail everything else as before. Aborts are
// injected at conn.commit() through FailAfterNCommitsDataSource (LoadTest.kt), so the cases are
// deterministic. Requires the live YugabyteDB container (localhost:5433).
class CommitRetryTest {

    private val module = SerializersModule {
        polymorphic(NodeLike::class) { subclass(YbTestNode::class) }
        polymorphic(EdgeLike::class) { subclass(YbTestEdge::class) }
    }

    private fun abort(state: String): (Int) -> SQLException = { n -> SQLException("simulated $state at commit #$n", state) }

    // Runs `block` against a store whose commits fail per FailAfterNCommitsDataSource; the wrapper only
    // delegates DataSource, so the raw pool is closed here, as in LoadTest.
    private fun <T> withFailingStore(
        failAtCommit: Int, failCount: Int, error: (Int) -> SQLException,
        block: suspend (YugabytePersistentStore, FailAfterNCommitsDataSource) -> T,
    ): T {
        val raw = rawYsqlDataSource()
        val ds = FailAfterNCommitsDataSource(raw, failAtCommit, failCount, error)
        return try { runBlocking { block(YugabytePersistentStore(ds, module), ds) } } finally { raw.close() }
    }

    private fun <T> withStore(block: suspend (YugabytePersistentStore) -> T): T {
        val raw = rawYsqlDataSource()
        return try { runBlocking { block(YugabytePersistentStore(raw, module)) } } finally { raw.close() }
    }

    private fun AbyssStoreTransactionLike.save(id: Uuid, name: String, tags: Set<String> = emptySet()) =
        saveNode(UuidKeyAdapter.toNodeId(id), YbTestNode(id = id, name = name), tags)

    private suspend fun YugabytePersistentStore.name(id: Uuid): String? =
        (loadNode(UuidKeyAdapter.toNodeId(id)).getOrNull()?.first as YbTestNode?)?.name

    private fun cleanup(ids: List<Uuid>) = withStore { s -> s.transaction { ids.forEach { deleteNode(UuidKeyAdapter.toNodeId(it)) } } }

    // ── commitYsql ──────────────────────────────────────────────────────────────────────────────────

    @Test fun `transaction retries 40001 once and commits`() = retriedTransaction("40001")
    @Test fun `transaction retries 40P01 once and commits`() = retriedTransaction("40P01")

    private fun retriedTransaction(state: String) {
        val id = Uuid.random()
        try {
            withFailingStore(failAtCommit = 1, failCount = 1, error = abort(state)) { store, ds ->
                val result = store.transaction { save(id, "retried-$state") }
                assertEquals(null, result.leftOrNull()?.toString(), "transaction must succeed after one $state")
                assertEquals(2, ds.commitCount, "one failed commit, one retry")
                assertEquals("retried-$state", store.name(id))
            }
        } finally { cleanup(listOf(id)) }
    }

    @Test fun `transaction does not retry another SQLState`() {
        val id = Uuid.random()
        try {
            withFailingStore(failAtCommit = 1, failCount = 1, error = abort("08006")) { store, ds ->
                assertIs<Either.Left<AbyssError.Unexpected>>(store.transaction { save(id, "lost") })
                assertEquals(1, ds.commitCount, "08006 must not be retried")
                assertEquals(null, store.name(id))
            }
        } finally { cleanup(listOf(id)) }
    }

    @Test fun `transaction gives up after 3 attempts`() {
        val id = Uuid.random()
        try {
            withFailingStore(failAtCommit = 1, failCount = Int.MAX_VALUE, error = abort("40001")) { store, ds ->
                assertIs<Either.Left<AbyssError.Unexpected>>(store.transaction { save(id, "never") })
                assertEquals(3, ds.commitCount, "bounded at 3 attempts")
                assertEquals(null, store.name(id))
            }
        } finally { cleanup(listOf(id)) }
    }

    // ── commitYsqlBatched: batch 4 over 7 ops -> chunks [0..3] [4..6]; commit #2 is chunk 2 ─────────

    @Test fun `batchTransaction retries a chunk aborted with 40001`() =
        retriedBatch(abort("40001"))

    // The shape the driver really produces (see the driver test below): the BatchUpdateException itself
    // carries the abort's SQLState.
    @Test fun `batchTransaction retries a BatchUpdateException carrying 40P01`() =
        retriedBatch { n -> BatchUpdateException("simulated batch abort at commit #$n", "40P01", IntArray(0)) }

    private fun retriedBatch(error: (Int) -> SQLException) {
        val ids = List(7) { Uuid.random() }
        try {
            withFailingStore(failAtCommit = 2, failCount = 1, error = error) { store, ds ->
                val result = store.batchTransaction(4) { ids.forEachIndexed { i, id -> save(id, "b$i") } }
                assertEquals(null, result.leftOrNull()?.toString(), "batchTransaction must succeed after one abort")
                assertEquals(3, ds.commitCount, "chunk 1 once, chunk 2 failed then retried")
                assertEquals(ids.indices.map { "b$it" }, ids.map { store.name(it) })
            }
        } finally { cleanup(ids) }
    }

    @Test fun `batchTransaction gives up on a chunk after 3 attempts, earlier chunk stays committed`() {
        val ids = List(7) { Uuid.random() }
        try {
            withFailingStore(failAtCommit = 2, failCount = Int.MAX_VALUE, error = abort("40001")) { store, ds ->
                val result = store.batchTransaction(4) { ids.forEachIndexed { i, id -> save(id, "b$i") } }
                assertIs<Either.Left<AbyssError.BatchPartiallyCommitted>>(result)
                assertEquals(4, result.value.committedOps)
                assertEquals(4, ds.commitCount, "chunk 1 once, chunk 2 three times")
                assertEquals(List(4) { "b$it" } + List(3) { null }, ids.map { store.name(it) })
            }
        } finally { cleanup(ids) }
    }

    // ── the driver's real shape ─────────────────────────────────────────────────────────────────────

    // Guards the classifier's assumption: a real abort out of executeBatch (rewritten, as in production)
    // arrives as a BatchUpdateException whose own SQLState is the abort's, so the store needs no cause
    // chain walk. Two transactions lock x and y, then each batch-upserts the other's row; YB must kill
    // one with 40P01. Rolled back, nothing persists.
    @Test fun `driver - deadlock out of executeBatch carries 40P01 on the BatchUpdateException`() {
        val url = "jdbc:yugabytedb://localhost:5433/abyss_test_graph?reWriteBatchedInserts=true&load-balance=false"
        val upsert = "INSERT INTO abyss.nodes AS n (id, type, data, tags, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data, updated_at = EXCLUDED.updated_at"
        fun bind(c: Connection, st: java.sql.PreparedStatement, id: Uuid) {
            val now = Timestamp(System.currentTimeMillis())
            st.setBytes(1, UuidKeyAdapter.toNodeId(id).bytes); st.setString(2, "probe")
            st.setObject(3, """{"type":"probe"}""", Types.OTHER)
            st.setArray(4, c.createArrayOf("text", emptyArray<String>()))
            st.setTimestamp(5, now); st.setTimestamp(6, now)
        }
        val (x, y) = List(2) { Uuid.random() }
        val a = DriverManager.getConnection(url, "abyss", "abyss").apply { autoCommit = false }
        val b = DriverManager.getConnection(url, "abyss", "abyss").apply { autoCommit = false }
        try {
            a.prepareStatement(upsert).use { bind(a, it, x); it.executeUpdate() }
            b.prepareStatement(upsert).use { bind(b, it, y); it.executeUpdate() }
            fun cross(c: Connection, id: Uuid) = CompletableFuture.supplyAsync {
                try { c.prepareStatement(upsert).use { bind(c, it, id); it.addBatch(); it.executeBatch() }; null }
                catch (e: Throwable) { e }
            }
            val errors = listOf(cross(a, y), cross(b, x)).map { it.get() }
            val failed = errors.filterNotNull()
            assertEquals(1, failed.size, "exactly one side is killed: $errors")
            assertEquals("40P01", assertIs<BatchUpdateException>(failed.single()).sqlState)
        } finally {
            runCatching { a.rollback() }; runCatching { b.rollback() }
            a.close(); b.close()
        }
    }

    // ── stochastic confirmation, not a red-first guard (real aborts are 0.1–0.5% at 16 writers) ──────

    // 16 writers × 21 saves on one id, each writer adding its own tag: every call must succeed and the
    // row's tags must be the union of all 16, on both commit paths. -Pperf only: ~62 s on the dev box
    // (11 commits/s, the one-id contention cost). A unique tag per save took 206 s: the growing array is
    // re-unioned and its GIN entries rewritten on every update.
    @Test fun `16 writers on one id all succeed, tags are the full union`() {
        if (System.getProperty("perf") == null) return
        for (batched in listOf(false, true)) {
            val id = Uuid.random()
            val store = YugabytePersistentStore.create(
                ysqlUrl = "jdbc:yugabytedb://localhost:5433/abyss_test_graph", ysqlUser = "abyss", ysqlPassword = "abyss",
                module = module, ysqlMaxPoolSize = 16, ysqlLoadBalance = false,
            )
            try {
                val expected = (0 until 16).map { w -> "w$w" }.toSet()
                val results = runBlocking(Dispatchers.IO) {
                    (0 until 16).map { w ->
                        async {
                            (0 until 21).map { i ->
                                val tag = setOf("w$w")
                                if (batched) store.batchTransaction(4) { save(id, "w$w-$i", tag) }
                                else store.transaction { save(id, "w$w-$i", tag) }
                            }
                        }
                    }.awaitAll().flatten()
                }
                assertEquals(emptyList(), results.mapNotNull { it.leftOrNull()?.toString() }, "batched=$batched")
                assertEquals(expected, tagsOf(id), "batched=$batched")
            } finally {
                runBlocking { store.transaction { deleteNode(UuidKeyAdapter.toNodeId(id)) } }
                store.close()
            }
        }
    }

    private fun tagsOf(id: Uuid): Set<String> =
        DriverManager.getConnection("jdbc:yugabytedb://localhost:5433/abyss_test_graph", "abyss", "abyss").use { c ->
            c.prepareStatement("SELECT tags FROM abyss.nodes WHERE id = ?").use { st ->
                st.setBytes(1, UuidKeyAdapter.toNodeId(id).bytes)
                st.executeQuery().use { rs ->
                    check(rs.next()) { "node $id not stored" }
                    @Suppress("UNCHECKED_CAST") (rs.getArray(1).array as Array<String>).toSet()
                }
            }
        }
}
