package pl.iqtech.abyss.graph

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.hasOutgoing
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

// Perf test (gated by -Pperf) for TODO 1.26 (b): a short-circuit frontier filter (hasOutgoing ->
// filterFrontierByOutEdgeTo -> hops(...).any { ... }) over a supernode. Before: hops() is a List, so
// outAt materializes the WHOLE neighbor set (every shard window) before .any looks. After: hops() is
// a Flow, so .any stops at the window holding the match. The match is placed in shard 0 (window 0), so
// a short-circuiting read must never touch window 1 (shards >= readWindow). That "did it read window 1"
// signal is independent of the isEmpty warm-check (which only ever reads window 0).
class HopStreamingPerformanceTest {

    private val shards = 16
    private val readWindow = 8   // ShardedAdjacencyIndex default

    private class WindowProbe(private val readWindow: Int) {
        var laterWindowGetAlls = 0   // getAll calls that touch a shard >= readWindow (i.e. window 1+)
        var totalEntries = 0
    }

    private fun countingHz(real: HazelcastInstance, adjMapName: String, probe: WindowProbe): HazelcastInstance =
        Proxy.newProxyInstance(HazelcastInstance::class.java.classLoader, arrayOf(HazelcastInstance::class.java)) { _, method, args ->
            val res = method.invoke(real, *(args ?: emptyArray()))
            if (method.name == "getMap" && (args?.getOrNull(0) as? String) == adjMapName) {
                @Suppress("UNCHECKED_CAST")
                val realMap = res as IMap<Any, Any>
                Proxy.newProxyInstance(IMap::class.java.classLoader, arrayOf(IMap::class.java)) { _, m, a ->
                    val r = m.invoke(realMap, *(a ?: emptyArray()))
                    if (m.name == "getAll") {
                        @Suppress("UNCHECKED_CAST")
                        val keys = a?.getOrNull(0) as? Set<AdjacencyKey>
                        if (keys != null && keys.any { it.shard.shardIndex() >= readWindow }) probe.laterWindowGetAlls++
                        if (r is Map<*, *>) probe.totalEntries += r.values.sumOf { (it as? AdjacencyValue)?.entries?.size ?: 0 }
                    }
                    r
                }
            } else res
        } as HazelcastInstance

    @Test fun `hasOutgoing over a supernode reads every window (before) vs stops at the match's window (after)`() {
        if (System.getProperty("perf") == null) return
        val n = 2000
        val probe = WindowProbe(readWindow)
        val hz = countingHz(graphTestHz, "hs-edges-adjacency", probe)
        val g = AbyssGraphSchema(UuidKeyAdapter, hz, "hs-nodes", "hs-edges", module = graphTestModule)

        val hub = Uuid.random()
        // Match in shard 0 (window 0) so a short-circuiting read never needs window 1.
        val target = generateSequence { Uuid.random() }.first { shardIndexOf(huid.toNodeId(it), shards) == 0 }
        val edges = (listOf(target) + (1 until n).map { Uuid.random() }).map { TestEdge(fromId = hub, toId = it, label = "e") }
        runBlocking { g.transaction(checkIntegrity = false) { edges.forEach { addEdge(it) } } }

        probe.laterWindowGetAlls = 0; probe.totalEntries = 0
        val reached = runBlocking { g.from(hub) { hasOutgoing<TestEdge, Uuid>(target); count() } }.getOrNull()

        println("\nhasOutgoing over a $n-edge hub (match in shard 0, window 0):")
        println("  frontier retained = $reached, adjacency entries materialized = ${probe.totalEntries}, " +
            "window-1 getAll calls = ${probe.laterWindowGetAlls}")

        graphTestHz.getMap<Any, Any>("hs-nodes").clear()
        graphTestHz.getMap<Any, Any>("hs-edges").clear()
        graphTestHz.getMap<Any, Any>("hs-edges-adjacency").clear()

        assertEquals(1, reached, "hub is retained (it has the out-edge)")
        assertEquals(0, probe.laterWindowGetAlls, "short-circuits at the match's window; never reads window 1")
    }
}
