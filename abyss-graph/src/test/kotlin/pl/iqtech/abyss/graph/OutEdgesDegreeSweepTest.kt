package pl.iqtech.abyss.graph

import com.hazelcast.core.DistributedObject
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.store.api.HeaderlessKeyAdapter
import pl.iqtech.abyss.store.api.LongKeyAdapter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test

// TODO 4.14: outEdges cost by hub out-degree, A/B across commits. Uses ONLY API common to df76b70 and dev
// (standalone AbyssGraphSchema ctor, transaction(checkIntegrity = false) { addEdge }, outEdges(id)) so this
// exact file drops unchanged into both `git archive` exports. Seeded through the transaction path, never
// direct map puts — the adjacency layout differs between the two commits.
// Each hub sits on its own partition: the old path scans the hub's whole edgesMap partition, so a
// d=10k hub sharing a partition with the d=1 hub would poison the d=1 number.
// ponytail: one in-JVM member — every getAll is a local call, so this UNDERSTATES the extra round trips
// of the new path; the ops/call column is the cluster-relevant evidence.
class OutEdgesDegreeSweepTest {

    private val degrees = listOf(1, 5, 20, 100, 500, 2_000, 10_000)
    private val hlong = HeaderlessKeyAdapter(LongKeyAdapter)

    @Test fun `outEdges by hub degree`() {
        if (System.getProperty("perf") == null) return
        val out = StringBuilder()
        listOf("none" to 0, "10k x 5" to 10_000).forEach { (label, bgNodes) -> out.append(sweep(label, bgNodes)) }
        println(out)
        System.getProperty("sweepOut")?.let { java.io.File(it).appendText(out.toString()) }
    }

    private fun sweep(label: String, bgNodes: Int): String = runBlocking {
        val prefix = "sweep-$bgNodes"
        val graph = AbyssGraphSchema(LongKeyAdapter, longTestHz, "$prefix-nodes", "$prefix-edges", module = graphTestModule)

        // Background: bgNodes nodes, 5 out-edges each (~185 edges per partition at 10k), chunked transactions.
        (1L..bgNodes).chunked(1_000).forEach { chunk ->
            check(graph.transaction(checkIntegrity = false) {
                chunk.forEach { id ->
                    addNode(LongTestNode(id = id, name = "$id"))
                    repeat(5) { j -> addEdge(LongTestEdge(fromId = id, toId = (id + j) % bgNodes + 1)) }
                }
            }.isRight())
        }

        // Hubs on distinct partitions, targets in a disjoint id range.
        val usedPartitions = HashSet<Int>()
        val hubs = degrees.associateWith { d ->
            generateSequence(100_000_000L + d) { it + 1 }
                .first { usedPartitions.add(longTestHz.partitionService.getPartition(hlong.partitionKey(hlong.toNodeId(it))).partitionId) }
                .also { hub ->
                    check(graph.transaction(checkIntegrity = false) { addNode(LongTestNode(id = hub, name = "hub")) }.isRight())
                    (0 until d).chunked(1_000).forEach { chunk ->
                        check(graph.transaction(checkIntegrity = false) {
                            chunk.forEach { j -> addEdge(LongTestEdge(fromId = hub, toId = hub * 100_000 + j)) }
                        }.isRight())
                    }
                }
        }

        val counter = SweepOpCounter(longTestHz)
        val counted = AbyssGraphSchema(LongKeyAdapter, counter.hz, "$prefix-nodes", "$prefix-edges", module = graphTestModule)

        val sb = StringBuilder("\noutEdges by degree — background: $label\n")
        sb.append("%8s %12s %12s %10s %8s  %s\n".format("degree", "us/op", "us/edge", "ops/sec", "mapOps", "ops (returned)"))
        for (d in degrees) {
            val hub = hubs.getValue(d)
            repeat(maxOf(20, 20_000 / d)) { graph.outEdges(hub).toList() }            // warm-up
            var calls = 0
            val t0 = System.nanoTime()
            while (calls < 30 || System.nanoTime() - t0 < 1_500_000_000L) {
                val n = graph.outEdges(hub).toList().size
                check(n == d) { "degree $d returned $n edges" }
                calls++
            }
            val usOp = (System.nanoTime() - t0) / 1_000.0 / calls

            counter.reset()
            counted.outEdges(hub).toList()
            val snap = counter.snapshot()
            val total = snap.values.sumOf { ops -> ops.filterKeys { !it.endsWith(".returned") }.values.sum() }
            val detail = snap.entries.joinToString("; ") { (map, ops) ->
                map.removePrefix("$prefix-") + ": " + ops.filterKeys { !it.endsWith(".returned") }
                    .entries.joinToString(", ") { (op, c) -> "$op=$c(${ops["$op.returned"] ?: "-"})" }
            }
            sb.append("%8d %12.1f %12.3f %10d %8d  %s\n".format(d, usOp, usOp / d, (1_000_000 / usOp).toLong(), total, detail))
        }
        sb.toString()
    }
}

// Copy of MapOpCounter (dev test sources, TODO 2.32) — duplicated so this file stands alone in the df76b70
// export, where MapOpCounter does not exist. Private to avoid clashing with it on dev.
private class SweepOpCounter(real: HazelcastInstance) {
    private val counts = ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>>()

    val hz: HazelcastInstance = proxy(real) { name, args, res ->
        if (name == "getMap") countingMap(args!![0] as String, res as IMap<*, *>) else res
    }

    private fun countingMap(mapName: String, map: IMap<*, *>): IMap<*, *> = proxy(map) { name, _, res ->
        val ops = counts.computeIfAbsent(mapName) { ConcurrentHashMap() }
        ops.computeIfAbsent(name) { AtomicLong() }.incrementAndGet()
        val values = when (res) { is Map<*, *> -> res.values; is Collection<*> -> res; else -> null }
        if (values != null) ops.computeIfAbsent("$name.returned") { AtomicLong() }
            .addAndGet(values.sumOf { v -> ((v as? Map.Entry<*, *>)?.value ?: v).let { (it as? AdjacencyValue)?.entries?.size ?: 1 }.toLong() })
        res
    }

    fun snapshot(): Map<String, Map<String, Long>> = counts.mapValues { (_, ops) -> ops.mapValues { it.value.get() }.toSortedMap() }.toSortedMap()
    fun reset() = counts.clear()

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : Any> proxy(target: T, crossinline after: (String, Array<Any?>?, Any?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            val res = try { method.invoke(target, *(args ?: emptyArray())) }
                      catch (e: InvocationTargetException) { throw e.targetException }
            if (method.declaringClass == Any::class.java || method.declaringClass == DistributedObject::class.java) res
            else after(method.name, args, res)
        } as T
}
