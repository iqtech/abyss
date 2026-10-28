package pl.iqtech.abyss.store.yugabyte

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

// Cost of the duplicate-key cut in commitYsqlBatched (issue batch-duplicate-id-upsert) on the path it must
// not slow down: distinct keys, so no cut ever fires and only the per-op key bookkeeping is left. Goes
// through the real YugabytePersistentStore.batchTransaction. Run before/after the fix as alternating
// builds; round 0 is warm-up, median of the rest.
class BatchCutCostTest {

    private val ops = 10_007
    private val rounds = 5

    @Test fun `batchTransaction with distinct keys - nodes and edges`() {
        if (System.getProperty("perf") == null) return
        val store = YugabytePersistentStore.create(
            ysqlUrl = "jdbc:yugabytedb://localhost:5433/abyss_test_graph",
            ysqlUser = "abyss",
            ysqlPassword = "abyss",
            module = SerializersModule {
                polymorphic(NodeLike::class) { subclass(YbTestNode::class) }
                polymorphic(EdgeLike::class) { subclass(YbTestEdge::class) }
            },
            ysqlMaxPoolSize = 2,
        )
        try {
            val ms = mutableMapOf("nodes" to mutableListOf<Double>(), "edges" to mutableListOf())
            repeat(rounds + 1) { round ->
                val ids = List(ops) { Uuid.random() }
                val hub = Uuid.random()
                val nodes = timed { runBlocking { assertEquals(null, store.batchTransaction(1000) {
                    ids.forEachIndexed { i, u -> saveNode(UuidKeyAdapter.toNodeId(u), YbTestNode(id = u, name = "n$i"), emptySet()) }
                }.leftOrNull()) } }
                // hub -> each id: from is shared, so only the full (from, to, type) key keeps them distinct
                val edges = timed { runBlocking { assertEquals(null, store.batchTransaction(1000) {
                    ids.forEachIndexed { i, u -> saveEdge(UuidKeyAdapter.toNodeId(hub), UuidKeyAdapter.toNodeId(u), YbTestEdge(fromId = hub, toId = u, label = "e$i"), emptySet()) }
                }.leftOrNull()) } }
                runBlocking { assertEquals(null, store.batchTransaction(1000) {
                    ids.forEach { u ->
                        deleteEdge(UuidKeyAdapter.toNodeId(hub), UuidKeyAdapter.toNodeId(u), "yb_test_edge")
                        deleteNode(UuidKeyAdapter.toNodeId(u))
                    }
                }.leftOrNull()) }
                if (round > 0) { ms.getValue("nodes") += nodes; ms.getValue("edges") += edges }
            }
            ms.forEach { (kind, all) ->
                val s = all.sorted()
                println("BCUT %-5s %6d ops median=%6.0f ms  range=%.0f..%.0f  %6.0f ops/s".format(
                    kind, ops, s[rounds / 2], s.first(), s.last(), ops * 1000.0 / s[rounds / 2]))
            }
        } finally {
            store.close()
        }
    }

    private fun timed(block: () -> Unit): Double { val s = System.nanoTime(); block(); return (System.nanoTime() - s) / 1e6 }
}
