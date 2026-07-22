package pl.iqtech.abyss.graph

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

// Perf test (gated by -Pperf) for TODO 1.26 (a): how outEdges materializes a hub's neighbor set.
// Metric = the largest single edges-map Hazelcast call during one outEdges consumption — a faithful
// proxy for peak in-flight edges (heap). Before: outEdgeFlow's values(predicate) coughs up all N at
// once, so even take(1) drags the whole set into heap. After: adjacencyEdgeFlow's getAll runs in
// <=pageSize batches.
class OutEdgePagingPerformanceTest {

    private class EdgesCounter {
        val callSizes = mutableListOf<Int>()
        fun reset() = callSizes.clear()
        val max get() = callSizes.maxOrNull() ?: 0
        val total get() = callSizes.sum()
    }

    // Wraps the edges IMap so values()/getAll() record their result size; everything else delegates.
    private fun countingHz(real: HazelcastInstance, edgesMapName: String, counter: EdgesCounter): HazelcastInstance =
        Proxy.newProxyInstance(HazelcastInstance::class.java.classLoader, arrayOf(HazelcastInstance::class.java)) { _, method, args ->
            val res = method.invoke(real, *(args ?: emptyArray()))
            if (method.name == "getMap" && (args?.getOrNull(0) as? String) == edgesMapName) {
                @Suppress("UNCHECKED_CAST")
                val realMap = res as IMap<Any, Any>
                Proxy.newProxyInstance(IMap::class.java.classLoader, arrayOf(IMap::class.java)) { _, m, a ->
                    val r = m.invoke(realMap, *(a ?: emptyArray()))
                    when (m.name) {
                        "values" -> (r as? Collection<*>)?.let { counter.callSizes += it.size }
                        "getAll" -> (r as? Map<*, *>)?.let { counter.callSizes += it.size }
                    }
                    r
                }
            } else res
        } as HazelcastInstance

    @Test fun `outEdges materializes the whole hub at once (before) vs pageSize batches (after)`() {
        if (System.getProperty("perf") == null) return
        val n = 2000; val k = 100
        val counter = EdgesCounter()
        val hz = countingHz(graphTestHz, "oep-edges", counter)
        val g = AbyssGraphSchema(UuidKeyAdapter, hz, "oep-nodes", "oep-edges", module = graphTestModule)

        val hub = Uuid.random()
        val edges = (1..n).map { TestEdge(fromId = hub, toId = Uuid.random(), label = "e$it") }
        runBlocking { g.transaction(checkIntegrity = false) { edges.forEach { addEdge(it) } } }

        counter.reset()
        val all = runBlocking { g.outEdges(hub, pageSize = k).toList() }
        val maxSingle = counter.max
        counter.reset()
        val one = runBlocking { g.outEdges(hub, pageSize = k).take(1).toList() }
        val takeTotal = counter.total

        println("\noutEdges over a $n-edge hub (pageSize=$k):")
        println("  full read -> ${all.size} edges, largest single edges-map materialization = $maxSingle")
        println("  take(1)   -> materialized $takeTotal edge(s) to yield ${one.size}")

        graphTestHz.getMap<Any, Any>("oep-nodes").clear()
        graphTestHz.getMap<Any, Any>("oep-edges").clear()
        graphTestHz.getMap<Any, Any>("oep-edges-adjacency").clear()

        assertEquals(n, all.size, "returns every out-edge")
        assertTrue(maxSingle <= k, "peak single materialization bounded by pageSize=$k (was $maxSingle, n=$n)")
        assertTrue(takeTotal <= k, "take(1) must not materialize the whole set (was $takeTotal, n=$n)")
    }
}
