package pl.iqtech.abyss.graph

import com.hazelcast.map.IMap
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.lang.reflect.Proxy
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

// Proves the ShardedAdjacencyIndex read is bounded: it pulls shards in windows (each getAll capped at
// readWindow keys) instead of one getAll over every shard, and a downstream take short-circuits before
// walking the rest — the core of TODO 1.26's fallback strategy.
class AdjacencyIndexTest {

    private val SHARDS = 16
    private val WINDOW = 8
    private val EDGE_TAG: Short = 1

    // Records the key-set size of every getAll; delegates everything to the real map.
    private class CountingMap(private val real: IMap<AdjacencyKey, AdjacencyValue>) {
        val getAllSizes = mutableListOf<Int>()
        @Suppress("UNCHECKED_CAST")
        val proxy: IMap<AdjacencyKey, AdjacencyValue> = Proxy.newProxyInstance(
            IMap::class.java.classLoader, arrayOf(IMap::class.java)
        ) { _, method, args ->
            if (method.name == "getAll") getAllSizes += (args[0] as Set<*>).size
            method.invoke(real, *(args ?: emptyArray()))
        } as IMap<AdjacencyKey, AdjacencyValue>
    }

    private lateinit var counting: CountingMap
    private lateinit var index: ShardedAdjacencyIndex
    private val owner = UuidKeyAdapter.toNodeId(Uuid.random())

    @BeforeTest fun setUp() {
        graphTestHz.getMap<AdjacencyKey, AdjacencyValue>("adj-index-test").clear()
        counting = CountingMap(graphTestHz.getMap("adj-index-test"))
        index = ShardedAdjacencyIndex(counting.proxy, SHARDS, WINDOW) { it.toString() }
    }

    private fun neighborInWindow0(): NodeId {
        while (true) {
            val n = UuidKeyAdapter.toNodeId(Uuid.random())
            if (shardIndexOf(n, SHARDS) < WINDOW) return n
        }
    }

    private fun add(vararg neighbors: NodeId) = runBlocking {
        neighbors.forEach { index.addAsync(owner, AdjacencyDirection.OUT, AdjacencyEntry(it, null, EDGE_TAG)).asDeferred().await() }
    }

    @Test fun `full read walks shards in windows, never one getAll over all shards`() = runBlocking {
        val neighbors = (1..40).map { UuidKeyAdapter.toNodeId(Uuid.random()) }
        add(*neighbors.toTypedArray())
        counting.getAllSizes.clear()

        val read = index.read(owner, AdjacencyDirection.OUT).toList()

        assertEquals(neighbors.size, read.size, "read returns every neighbor")
        assertEquals(neighbors.toSet(), read.map { it.neighborId }.toSet(), "same neighbors, no loss/dup")
        assertEquals(SHARDS / WINDOW, counting.getAllSizes.size, "one getAll per shard-window")
        assertTrue(counting.getAllSizes.all { it <= WINDOW }, "each getAll capped at the window size, not all $SHARDS shards")
    }

    @Test fun `take short-circuits before reading the remaining windows`() = runBlocking {
        add(neighborInWindow0(), neighborInWindow0(), neighborInWindow0())
        counting.getAllSizes.clear()

        val one = index.read(owner, AdjacencyDirection.OUT).take(1).toList()

        assertEquals(1, one.size)
        assertEquals(1, counting.getAllSizes.size, "take(1) reads only the first window, not the rest")
    }

    @Test fun `isEmpty is false once warmed, true for an unknown node, and stays bounded`() = runBlocking {
        assertTrue(index.isEmpty(UuidKeyAdapter.toNodeId(Uuid.random()), AdjacencyDirection.OUT), "unknown node is empty")
        add(neighborInWindow0())
        counting.getAllSizes.clear()
        assertFalse(index.isEmpty(owner, AdjacencyDirection.OUT), "warmed node is not empty")
        assertTrue(counting.getAllSizes.all { it <= WINDOW }, "a non-empty first window answers without loading every shard")
    }
}
