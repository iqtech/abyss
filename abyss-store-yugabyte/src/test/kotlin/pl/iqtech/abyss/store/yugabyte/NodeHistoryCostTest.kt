package pl.iqtech.abyss.store.yugabyte

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

// nodes-history plan.md Verification 3: the real YugabytePersistentStore on its own schema. plain, history
// and mixed (50/50 alternating) saves, one transaction per op or batches of 1000; 1000 ids per run, so later
// chunks update them and history versions climb. delete: 10007 distinct plain nodes, batched. Counts asserted
// every run, shapes interleaved, round 0 warm-up. Run before/after as alternating builds: HCOST_SHAPES picks
// the shapes an older store can run, as it can't pass the history assertion.
class NodeHistoryCostTest {

    private val url = "jdbc:yugabytedb://localhost:5433/abyss_test_graph"
    private val schema = "abyss_histcost"
    private val batchSize = 1000
    private val rounds = 5
    private val workloads = listOf("single" to 1_001, "batched" to 1_003, "batched" to 10_007)
    private val deletes = 10_007
    private val shapes = System.getenv("HCOST_SHAPES")?.split(",") ?: listOf("plain", "history", "mixed", "delete")
    private val writeShapes = shapes - "delete"
    private val payload = "x".repeat(240)

    private fun ddl(vararg sql: String) = DriverManager.getConnection(url, "abyss", "abyss").use { c ->
        c.createStatement().use { s -> sql.forEach(s::execute) }
    }

    private fun count(table: String): Long = DriverManager.getConnection(url, "abyss", "abyss").use { c ->
        c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM $schema.$table").use { rs -> rs.next(); rs.getLong(1) } }
    }

    private fun isHistory(shape: String, i: Int) = shape == "history" || (shape == "mixed" && i % 2 == 0)

    private fun AbyssStoreTransactionLike.save(shape: String, i: Int, ids: List<Uuid>) {
        val id = ids[i % batchSize]
        val node = if (isHistory(shape, i)) YbHistNode(id = id, name = payload) else YbTestNode(id = id, name = payload)
        saveNode(UuidKeyAdapter.toNodeId(id), node, emptySet())
    }

    @Test fun `real store - plain, history, mixed and delete`() {
        if (System.getProperty("perf") == null) return
        ddl(
            "DROP SCHEMA IF EXISTS $schema CASCADE",
            "CREATE SCHEMA $schema",
            "CREATE TABLE $schema.nodes (LIKE abyss.nodes INCLUDING ALL)",
            "CREATE TABLE $schema.edges (LIKE abyss.edges INCLUDING ALL)",
            "CREATE TABLE $schema.node_history (LIKE abyss.node_history INCLUDING ALL)",
        )
        val store = YugabytePersistentStore.create(
            ysqlUrl = url, ysqlUser = "abyss", ysqlPassword = "abyss", ysqlSchema = schema, ysqlMaxPoolSize = 2,
            module = SerializersModule {
                polymorphic(NodeLike::class) { subclass(YbHistNode::class); subclass(YbTestNode::class) }
                polymorphic(EdgeLike::class) { subclass(YbTestEdge::class) }
            },
        )
        fun commit(batched: Boolean, block: suspend AbyssStoreTransactionLike.() -> Unit) = runBlocking {
            val result = if (batched) store.batchTransaction(batchSize, block) else store.transaction(block)
            assertEquals(null, result.leftOrNull()?.toString(), "commit failed")
        }
        fun reset() = ddl("TRUNCATE $schema.nodes, $schema.node_history")
        try {
            val ms = mutableMapOf<Triple<String, Int, String>, MutableList<Double>>()
            repeat(rounds + 1) { round ->
                for ((mode, n) in workloads) for (k in writeShapes.indices) {
                    val shape = writeShapes[(k + round) % writeShapes.size]
                    reset()
                    val ids = List(batchSize) { Uuid.random() }
                    val d = timed {
                        if (mode == "batched") commit(true) { repeat(n) { save(shape, it, ids) } }
                        else repeat(n) { i -> commit(false) { save(shape, i, ids) } }
                    }
                    assertEquals(batchSize.toLong(), count("nodes"), "$mode $n $shape nodes")
                    assertEquals((0 until n).count { isHistory(shape, it) }.toLong(), count("node_history"), "$mode $n $shape history")
                    if (round > 0) ms.getOrPut(Triple(mode, n, shape)) { mutableListOf() } += d
                }
                if ("delete" in shapes) {
                    reset()
                    val ids = List(deletes) { Uuid.random() }
                    commit(true) { ids.forEach { saveNode(UuidKeyAdapter.toNodeId(it), YbTestNode(id = it, name = payload), emptySet()) } }
                    val d = timed { commit(true) { ids.forEach { deleteNode(UuidKeyAdapter.toNodeId(it)) } } }
                    assertEquals(0L, count("nodes"), "delete: nodes left")
                    if (round > 0) ms.getOrPut(Triple("batched", deletes, "delete")) { mutableListOf() } += d
                }
            }
            ms.forEach { (key, all) ->
                val (mode, n, shape) = key
                val s = all.sorted()
                println("HCOST %-7s %6d ops %-7s median=%7.0f ms  range=%.0f..%.0f  %6.0f ops/s".format(
                    mode, n, shape, s[rounds / 2], s.first(), s.last(), n * 1000.0 / s[rounds / 2]))
            }
        } finally {
            store.close()
            ddl("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun timed(block: () -> Unit): Double { val s = System.nanoTime(); block(); return (System.nanoTime() - s) / 1e6 }
}
