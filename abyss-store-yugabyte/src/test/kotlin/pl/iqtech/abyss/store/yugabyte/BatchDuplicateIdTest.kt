package pl.iqtech.abyss.store.yugabyte

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

// commitYsqlBatched puts consecutive same-class ops in one executeBatch(), and production sets
// reWriteBatchedInserts=true, which turns that batch into one multi-row INSERT ... ON CONFLICT DO UPDATE.
// Saving the same id twice in one chunk must still behave like commitYsql's op-by-op execution:
// the call succeeds and the last write wins. Requires the live YugabyteDB container (localhost:5433).
class BatchDuplicateIdTest {

    // One instance per test method: a small pool, closed after each test. The default pool (20, all idle
    // and held open) per instance exhausted the container's connection slots in the full suite.
    private val store = YugabytePersistentStore.create(
        ysqlUrl = "jdbc:yugabytedb://localhost:5433/abyss_test_graph",
        ysqlUser = "abyss",
        ysqlPassword = "abyss",
        module = SerializersModule {
            polymorphic(NodeLike::class) { subclass(YbTestNode::class) }
            polymorphic(EdgeLike::class) { subclass(YbTestEdge::class) }
        },
        ysqlMaxPoolSize = 2,
    )

    @AfterTest fun closeStore() = store.close()

    // pgjdbc sends a rewritten batch as multi-row blocks sized in powers of two, so where the duplicate
    // lands decides whether both copies share one INSERT: [A,B,A] splits as (A,B)+(A), [A,A] and
    // [A,B,C,A] are one block each.
    @Test fun `A B A - duplicate split across blocks`() = saveAndCheck(listOf(0, 1, 0))
    @Test fun `A A - duplicate in one block`() = saveAndCheck(listOf(0, 0))
    @Test fun `A B C A - duplicate in one block of four`() = saveAndCheck(listOf(0, 1, 2, 0))

    // Edge key is (from, to, type): a->c and c->a share a node with a->b but are different rows.
    @Test fun `edge a-b saved twice in one block of four`() = runBlocking {
        val (a, b, c) = List(3) { Uuid.random() }
        val order = listOf(a to b, a to c, c to a, a to b)
        val result = store.batchTransaction(1000) {
            order.forEachIndexed { pos, (f, t) ->
                saveEdge(UuidKeyAdapter.toNodeId(f), UuidKeyAdapter.toNodeId(t), YbTestEdge(fromId = f, toId = t, label = "e$pos"), emptySet())
            }
        }
        try {
            assertEquals(null, result.leftOrNull()?.toString(), "batchTransaction failed")
            val loaded = store.loadEdge(UuidKeyAdapter.toNodeId(a), UuidKeyAdapter.toNodeId(b), "yb_test_edge").getOrNull()?.first as YbTestEdge?
            assertEquals("e3", loaded?.label, "last write must win")
        } finally {
            store.transaction { order.forEach { (f, t) -> deleteEdge(UuidKeyAdapter.toNodeId(f), UuidKeyAdapter.toNodeId(t), "yb_test_edge") } }
        }
        Unit
    }

    // Saves node `ids[i]` named "n<position>" for each position; the last save of id 0 must win.
    private fun saveAndCheck(order: List<Int>) = runBlocking {
        val ids = List(order.max() + 1) { Uuid.random() }
        val result = store.batchTransaction(1000) {
            order.forEachIndexed { pos, i -> saveNode(UuidKeyAdapter.toNodeId(ids[i]), YbTestNode(id = ids[i], name = "n$pos"), emptySet()) }
        }
        try {
            assertEquals(null, result.leftOrNull()?.toString(), "batchTransaction failed")
            val loaded = store.loadNode(UuidKeyAdapter.toNodeId(ids[0])).getOrNull()?.first as YbTestNode?
            assertEquals("n${order.lastIndexOf(0)}", loaded?.name, "last write must win")
        } finally {
            store.transaction { ids.forEach { deleteNode(UuidKeyAdapter.toNodeId(it)) } }
        }
        Unit
    }
}
