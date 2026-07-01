package pl.iqtech.abyss.graph

import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.StringKeyAdapter
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime
import kotlin.uuid.Uuid

// TODO 3.6: isolates hashCode/equals + HashMap/HashSet get/put cost for NodeId(Uuid) vs
// NodeId(Long) vs NodeId(String) (proxy for IMap key handling), and separately for the
// domain-ID (Uuid/Long/String) HashSet rebuild cost TraversalBuilder's frontier/visited sets
// actually exercise in production -- independent of Hazelcast/serde (plain JVM HashMap, no IMap).
class NodeIdHashPerformanceTest {

    companion object {
        private const val BACKING_SIZE = 10_000
        private val chars = ('a'..'z') + ('0'..'9')
        private fun randomStr() = (10 + Random.nextInt(41)).let { len ->
            (1..len).map { chars[Random.nextInt(chars.size)] }.joinToString("")
        }
    }

    @Test fun `NodeId HashMap get cost per ID type`() {
        if (System.getProperty("perf") == null) return
        val n = 200_000

        val uuidKeys = (1..BACKING_SIZE).map { UuidKeyAdapter.toNodeId(Uuid.random()) }
        val longKeys = (1L..BACKING_SIZE.toLong()).map { LongKeyAdapter.toNodeId(it) }
        val strKeys = (1..BACKING_SIZE).map { StringKeyAdapter.toNodeId(randomStr()) }

        val uuidMap = HashMap<NodeId, String>(BACKING_SIZE * 2).apply { uuidKeys.forEach { put(it, "v") } }
        val longMap = HashMap<NodeId, String>(BACKING_SIZE * 2).apply { longKeys.forEach { put(it, "v") } }
        val strMap = HashMap<NodeId, String>(BACKING_SIZE * 2).apply { strKeys.forEach { put(it, "v") } }

        fun getNanosEach(map: HashMap<NodeId, String>, keys: List<NodeId>): Double {
            repeat(2_000) { map[keys.random()] } // warm-up
            val elapsed = measureTime { repeat(n) { map[keys.random()] } }
            return elapsed.inWholeNanoseconds.toDouble() / n
        }

        val uuidNs = getNanosEach(uuidMap, uuidKeys)
        val longNs = getNanosEach(longMap, longKeys)
        val strNs = getNanosEach(strMap, strKeys)

        println("\nNodeId HashMap.get cost, plain JVM HashMap, $BACKING_SIZE entries ($n gets each):")
        println("  Uuid: ${"%.0f".format(uuidNs)} ns/op   Long: ${"%.0f".format(longNs)} ns/op   String: ${"%.0f".format(strNs)} ns/op")
        println("  Uuid/Long ratio: ${"%.1f".format(uuidNs / longNs)}x   Uuid/String ratio: ${"%.1f".format(uuidNs / strNs)}x")
        // Measured: NodeId(Uuid) HashMap.get is not slower than NodeId(Long) -- real bucket
        // lookup cost doesn't track raw hashCode+equals cost. Floor check only, no direction claim.
        assertTrue(uuidNs > 0 && longNs > 0 && strNs > 0, "expected positive per-op timings")
    }

    @Test fun `NodeId HashMap put cost per ID type`() {
        if (System.getProperty("perf") == null) return
        val n = 200_000

        fun putNanosEach(keysFactory: () -> List<NodeId>): Double {
            val warmupMap = HashMap<NodeId, String>()
            keysFactory().take(2_000).forEach { warmupMap[it] = "v" } // warm-up

            val keys = keysFactory()
            val map = HashMap<NodeId, String>()
            val elapsed = measureTime { keys.forEach { map[it] = "v" } }
            return elapsed.inWholeNanoseconds.toDouble() / keys.size
        }

        val uuidNs = putNanosEach { (1..n).map { UuidKeyAdapter.toNodeId(Uuid.random()) } }
        val longNs = putNanosEach { (1L..n.toLong()).map { LongKeyAdapter.toNodeId(it) } }
        val strNs = putNanosEach { (1..n).map { StringKeyAdapter.toNodeId(randomStr()) } }

        println("\nNodeId HashMap.put cost, plain JVM HashMap, growing to $n entries:")
        println("  Uuid: ${"%.0f".format(uuidNs)} ns/op   Long: ${"%.0f".format(longNs)} ns/op   String: ${"%.0f".format(strNs)} ns/op")
        println("  Uuid/Long ratio: ${"%.1f".format(uuidNs / longNs)}x   Uuid/String ratio: ${"%.1f".format(uuidNs / strNs)}x")
        // Measured: NodeId(Uuid) HashMap.put is not slower than NodeId(Long) -- same finding as get.
        assertTrue(uuidNs > 0 && longNs > 0 && strNs > 0, "expected positive per-op timings")
    }

    @Test fun `NodeId raw hashCode+equals cost per ID type`() {
        if (System.getProperty("perf") == null) return
        val n = 200_000

        val uuidA = UuidKeyAdapter.toNodeId(Uuid.random())
        val uuidB = UuidKeyAdapter.toNodeId(Uuid.random())
        val longA = LongKeyAdapter.toNodeId(1L)
        val longB = LongKeyAdapter.toNodeId(2L)
        val strA = StringKeyAdapter.toNodeId(randomStr())
        val strB = StringKeyAdapter.toNodeId(randomStr())

        fun hashEqualsNanosEach(a: NodeId, b: NodeId): Double {
            repeat(2_000) { a.hashCode(); a.equals(b) } // warm-up
            val elapsed = measureTime { repeat(n) { a.hashCode(); a.equals(b) } }
            return elapsed.inWholeNanoseconds.toDouble() / n
        }

        val uuidNs = hashEqualsNanosEach(uuidA, uuidB)
        val longNs = hashEqualsNanosEach(longA, longB)
        val strNs = hashEqualsNanosEach(strA, strB)

        println("\nNodeId raw hashCode()+equals() cost, no HashMap ($n calls each):")
        println("  Uuid: ${"%.0f".format(uuidNs)} ns/op   Long: ${"%.0f".format(longNs)} ns/op   String: ${"%.0f".format(strNs)} ns/op")
        println("  Uuid/Long ratio: ${"%.1f".format(uuidNs / longNs)}x   Uuid/String ratio: ${"%.1f".format(uuidNs / strNs)}x")
        assertTrue(uuidNs > longNs, "expected NodeId(Uuid) raw hashCode+equals to cost more than NodeId(Long)")
    }

    @Test fun `domain-ID frontier Set rebuild cost at 3-hop volumes`() {
        if (System.getProperty("perf") == null) return
        val reps = 50_000
        val hopSizes = listOf(5, 25, 125) // fanout^hop for a 3-hop x 5-fanout traversal

        fun <ID> toSetNanosEach(ids: List<ID>): Double {
            repeat(2_000) { ids.toSet() } // warm-up
            val elapsed = measureTime { repeat(reps) { ids.toSet() } }
            return elapsed.inWholeNanoseconds.toDouble() / reps
        }

        println("\nDomain-ID frontier Set rebuild cost (list.toSet(), as TraversalBuilder.addHop does, $reps reps each):")
        var uuidNs125 = 0.0
        var longNs125 = 0.0
        for (size in hopSizes) {
            val uuidIds = (1..size).map { Uuid.random() }
            val longIds = (1L..size.toLong()).toList()
            val strIds = (1..size).map { randomStr() }

            val uuidNs = toSetNanosEach(uuidIds)
            val longNs = toSetNanosEach(longIds)
            val strNs = toSetNanosEach(strIds)
            if (size == 125) {
                uuidNs125 = uuidNs
                longNs125 = longNs
            }

            println(
                "  frontier size $size:  Uuid: ${"%.0f".format(uuidNs)} ns/op   Long: ${"%.0f".format(longNs)} ns/op   String: ${"%.0f".format(strNs)} ns/op" +
                    "   (Uuid/Long: ${"%.1f".format(uuidNs / longNs)}x, Uuid/String: ${"%.1f".format(uuidNs / strNs)}x)"
            )
        }
        assertTrue(uuidNs125 > longNs125, "expected Uuid frontier Set rebuild at size 125 to cost more than Long")
    }
}
