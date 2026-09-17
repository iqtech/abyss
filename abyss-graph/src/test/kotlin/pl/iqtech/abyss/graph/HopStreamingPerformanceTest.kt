package pl.iqtech.abyss.graph

import com.hazelcast.map.IMap
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.typeTag
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

// Perf test (gated by -Pperf) for TODO 1.26 (b): a short-circuiting lookup over a supernode (the shape
// hasOutgoing -> filterFrontierByOutEdgeTo uses: hops(...).firstOrNull { it.target == endpoint }) must stop at
// the shard window holding the match instead of materializing every window.
// Index-level since TODO 4.14: ShardedAdjacencyIndex now defaults to ONE window (every shard per getAll), where
// there is nothing to stop at, and the worker doesn't expose readWindow — so this pins the window mechanism
// itself with an explicit readWindow = 8, the pre-4.14 default. The match sits in shard 0 (window 0), so a
// short-circuiting read must never touch window 1 (shards >= readWindow).
class HopStreamingPerformanceTest {

    private val shards = 16
    private val readWindow = 8

    private class WindowProbe(private val readWindow: Int) {
        var laterWindowGetAlls = 0   // getAll calls that touch a shard >= readWindow (i.e. window 1+)
        var totalEntries = 0

        @Suppress("UNCHECKED_CAST")
        fun proxy(real: IMap<AdjacencyKey, AdjacencyValue>): IMap<AdjacencyKey, AdjacencyValue> =
            Proxy.newProxyInstance(IMap::class.java.classLoader, arrayOf(IMap::class.java)) { _, m, a ->
                val r = m.invoke(real, *(a ?: emptyArray()))
                if (m.name == "getAll") {
                    val keys = a?.getOrNull(0) as? Set<AdjacencyKey>
                    if (keys != null && keys.any { it.shard.shardIndex() >= readWindow }) laterWindowGetAlls++
                    if (r is Map<*, *>) totalEntries += r.values.sumOf { (it as? AdjacencyValue)?.entries?.size ?: 0 }
                }
                r
            } as IMap<AdjacencyKey, AdjacencyValue>
    }

    @Test fun `short-circuit lookup over a supernode stops at the match's window (readWindow = 8)`() {
        if (System.getProperty("perf") == null) return
        val n = 2000
        val real = graphTestHz.getMap<AdjacencyKey, AdjacencyValue>("hs-edges-adjacency").also { it.clear() }
        val probe = WindowProbe(readWindow)
        val index = ShardedAdjacencyIndex(probe.proxy(real), shards, readWindow) { it.toString() }

        val hub = UuidKeyAdapter.toNodeId(Uuid.random())
        // Match in shard 0 (window 0) so a short-circuiting read never needs window 1.
        val target = generateSequence { UuidKeyAdapter.toNodeId(Uuid.random()) }.first { shardIndexOf(it, shards) == 0 }
        val edgeTag = TestEdge::class.typeTag()
        val neighbors = listOf(target) + (1 until n).map { UuidKeyAdapter.toNodeId(Uuid.random()) }
        runBlocking { neighbors.forEach { index.addAsync(hub, AdjacencyDirection.OUT, AdjacencyEntry(it, null, edgeTag)).toCompletableFuture().await() } }

        probe.laterWindowGetAlls = 0; probe.totalEntries = 0
        val found = runBlocking { index.read(hub, AdjacencyDirection.OUT).firstOrNull { it.neighborId == target } }

        println("\nshort-circuit lookup over a $n-entry hub (readWindow = $readWindow, match in shard 0, window 0):")
        println("  found = ${found != null}, adjacency entries materialized = ${probe.totalEntries}, " +
            "window-1 getAll calls = ${probe.laterWindowGetAlls}")

        real.clear()

        assertTrue(found != null, "the match is found")
        assertEquals(0, probe.laterWindowGetAlls, "short-circuits at the match's window; never reads window 1")
        assertTrue(probe.totalEntries < n, "materializes only window 0, not all $n entries")
    }
}
