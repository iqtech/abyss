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
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.sql.DriverManager
import java.util.Arrays
import java.util.concurrent.CyclicBarrier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Baseline harness for IoT.md finding 1 ("multi-op transactions with inconsistent key ordering
 * -> deadlock flood, non-retryable") and its fix, the deterministic op sort in commitYsql.
 *
 * Reproduces TEST 3 and TEST 4 from the IoT.md transcript through the real
 * YugabytePersistentStore.transaction {} API rather than raw SQL, so it measures what the fix
 * actually has to change, plus TEST 5 for the secondary-index question the node-only tests can't
 * reach. Requires the live YugabyteDB container (localhost:5433).
 *
 * Pre-fix: TEST 3 fails with a pile of "deadlock detected". Post-fix: all pass.
 */
class CommitOrderDeadlockTest {

    // Dedicated pool, one connection per worker. On the default size (20) Hikari queues the
    // surplus workers, which serializes the transactions and hides the very contention we
    // are trying to measure.
    private val store by lazy {
        YugabytePersistentStore.create(
            ysqlUrl = "jdbc:yugabytedb://localhost:5433/abyss_test_graph",
            ysqlUser = "abyss",
            ysqlPassword = "abyss",
            module = SerializersModule {
                polymorphic(NodeLike::class) { subclass(YbTestNode::class) }
                polymorphic(EdgeLike::class) { subclass(YbTestEdge::class) }
            },
            ysqlMaxPoolSize = 64
        )
    }

    private fun key(n: Int): Uuid = Uuid.parse("0000000$n-0000-4000-8000-00000000dead")

    private fun nid(u: Uuid): NodeId = UuidKeyAdapter.toNodeId(u)

    private fun node(u: Uuid, name: String) =
        YbTestNode(id = u, createdAt = Instant.fromEpochSeconds(0), updatedAt = Instant.fromEpochSeconds(0), name = name)

    private fun edge(from: Uuid, to: Uuid, label: String) =
        YbTestEdge(fromId = from, toId = to, createdAt = Instant.fromEpochSeconds(0), updatedAt = Instant.fromEpochSeconds(0), label = label)

    private fun sql(statement: String, bind: (java.sql.PreparedStatement) -> Unit) {
        DriverManager.getConnection("jdbc:postgresql://localhost:5433/abyss_test_graph", "abyss", "abyss").use { conn ->
            conn.prepareStatement(statement).use { stmt -> bind(stmt) }
        }
    }

    private fun purgeNodes(keys: List<Uuid>) =
        sql("DELETE FROM abyss.nodes WHERE id = ?") { stmt ->
            keys.forEach { stmt.setBytes(1, nid(it).bytes); stmt.executeUpdate() }
        }

    private fun purgeEdges(fromKeys: List<Uuid>) =
        sql("DELETE FROM abyss.edges WHERE from_id = ?") { stmt ->
            fromKeys.forEach { stmt.setBytes(1, nid(it).bytes); stmt.executeUpdate() }
        }

    /**
     * Runs [workers] concurrent transactions, each staging its ops via [stage]. A barrier releases
     * them together so the lock windows actually overlap. Returns (failures, deadlocks, sample msg).
     */
    private fun run(workers: Int, stage: AbyssStoreTransactionLike.(Int) -> Unit): Triple<Int, Int, String?> {
        val barrier = CyclicBarrier(workers)
        val results = runBlocking {
            (0 until workers).map { i ->
                async(Dispatchers.IO) {
                    barrier.await()
                    store.transaction { stage(i) }
                }
            }.awaitAll()
        }
        val errors = results.filterIsInstance<Either.Left<AbyssError>>()
            .map { (it.value as? AbyssError.Unexpected)?.cause?.message ?: it.value.toString() }
        return Triple(errors.size, errors.count { it.contains("deadlock", ignoreCase = true) }, errors.firstOrNull())
    }

    @Test
    fun `TEST 3 - opposite key order across concurrent 2-op transactions`() {
        val keys = listOf(key(1), key(2))
        purgeNodes(keys)
        val (failures, deadlocks, sample) = run(workers = 50) { i ->
            (if (i % 2 == 0) keys else keys.reversed()).forEach { k -> saveNode(nid(k), node(k, "w$i"), emptySet()) }
        }
        println("[baseline] nodes, opposite order, 50 workers x 2 ops: $failures failure(s), $deadlocks deadlock(s). sample=$sample")
        purgeNodes(keys)
        assertEquals(0, deadlocks, "$deadlocks of 50 transactions deadlocked on inconsistent key staging order")
        assertEquals(0, failures, "$failures of 50 transactions failed; sample: $sample")
    }

    @Test
    fun `TEST 4 - same key order across concurrent 3-op transactions is the control`() {
        val keys = listOf(key(3), key(4), key(5))
        purgeNodes(keys)
        val (failures, deadlocks, sample) = run(workers = 40) { i ->
            keys.forEach { k -> saveNode(nid(k), node(k, "w$i"), emptySet()) }
        }
        println("[baseline] nodes, same order, 40 workers x 3 ops: $failures failure(s), $deadlocks deadlock(s). sample=$sample")
        purgeNodes(keys)
        assertEquals(0, failures, "control run should not fail; $failures did, sample: $sample")
    }

    /**
     * The secondary-index question the node tests can't reach. commitYsql orders row locks by the
     * base-table key (from_id, to_id); `edges` also carries idx_edges_to_id (lsm, to_id HASH), whose
     * row order does NOT follow the base key. The two edges below are picked so the orders invert:
     *
     *   e1 = a -> z, e2 = b -> y,  with a < b and y < z
     *   base order (from_id, to_id): e1 then e2
     *   idx_edges_to_id order (to_id): e2 then e1
     *
     * If index-row locks can re-open the cycle that the base-key sort closed, this is where it shows.
     * Tags are empty (the default write path), so the ybgin tag indexes hold no entries and stay out
     * of it. No endpoint nodes are created: the schema has no foreign keys, so the edges dangle
     * harmlessly and the test stays confined to `edges`.
     */
    @Test
    fun `TEST 5 - edges whose secondary-index order inverts the base-key order`() {
        val a = key(6); val b = key(7); val y = key(8); val z = key(9)

        // Guard the premise: if key encoding ever changes these orderings, the test silently stops
        // testing what it claims to.
        assertTrue(Arrays.compareUnsigned(nid(a).bytes, nid(b).bytes) < 0, "precondition: from-side a < b")
        assertTrue(Arrays.compareUnsigned(nid(y).bytes, nid(z).bytes) < 0, "precondition: to-side y < z")

        val e1 = edge(a, z, "e1")
        val e2 = edge(b, y, "e2")
        purgeEdges(listOf(a, b))
        val (failures, deadlocks, sample) = run(workers = 50) { i ->
            val staged = if (i % 2 == 0) listOf(e1, e2) else listOf(e2, e1)
            staged.forEach { e -> saveEdge(nid(e.fromId), nid(e.toId), e, emptySet()) }
        }
        println("[baseline] edges, inverted index order, 50 workers x 2 ops: $failures failure(s), $deadlocks deadlock(s). sample=$sample")
        purgeEdges(listOf(a, b))
        assertEquals(0, deadlocks, "$deadlocks of 50 edge transactions deadlocked; base-key sort does not cover secondary-index locks")
        assertEquals(0, failures, "$failures of 50 edge transactions failed; sample: $sample")
    }
}
