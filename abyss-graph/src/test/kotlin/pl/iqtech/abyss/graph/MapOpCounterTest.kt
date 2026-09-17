package pl.iqtech.abyss.graph

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class MapOpCounterTest {

    // One in-edge b <- a, priced on real Hazelcast. The IN entry lives in shard shardIndexOf(a), in the first
    // or second half of the shards. Since TODO 4.14 a warm read is ONE adjacency getAll over every shard, with
    // no separate warm probe, wherever the entry lives (was isEmpty 1-2 + read windows 2 = 3-4).
    private fun inEdgeAdjacencyGetAlls(laterWindow: Boolean): Pair<Long, MapOpCounter> = runBlocking {
        val counter = MapOpCounter(graphTestHz)
        val g = AbyssGraphSchema(UuidKeyAdapter, counter.hz, "moc-nodes", "moc-edges", module = graphTestModule)
        val aId = generateSequence { Uuid.random() }.first { (shardIndexOf(huid.toNodeId(it), 16) >= 8) == laterWindow }
        val a = TestNode(id = aId, name = "a")
        val b = TestNode(id = Uuid.random(), name = "b")
        g.transaction { addNode(a); addNode(b); addEdge(TestEdge(fromId = a.id, toId = b.id, label = "L")) }
        counter.reset()

        assertEquals(1, g.inEdges(b.id).toList().size)
        println("inEdges laterWindow=$laterWindow: ${counter.snapshot()}")
        counter.count("moc-edges-adjacency", "getAll") to counter
    }

    @Test fun `in-edge in shards 0-7 costs one adjacency getAll`() {
        val (getAlls, counter) = inEdgeAdjacencyGetAlls(laterWindow = false)
        assertEquals(1, getAlls, "${counter.snapshot()}")
    }

    @Test fun `in-edge in shards 8-15 costs one adjacency getAll`() {
        val (getAlls, counter) = inEdgeAdjacencyGetAlls(laterWindow = true)
        assertEquals(1, getAlls, "${counter.snapshot()}")
    }

    // TODO 4.14: cache-only (no persistentStore) — the startup guard forbids edgesMap eviction and nothing reads
    // through, so the cached values are complete: outEdges is ONE partition scan, no adjacency count.
    @Test fun `cache-only outEdges costs one edges op and no adjacency op`() = runBlocking {
        val counter = MapOpCounter(graphTestHz)
        val g = AbyssGraphSchema(UuidKeyAdapter, counter.hz, "moc-co-nodes", "moc-co-edges", module = graphTestModule)
        val hub = Uuid.random()
        g.transaction(checkIntegrity = false) { repeat(5) { addEdge(TestEdge(fromId = hub, toId = Uuid.random(), label = "e$it")) } }
        counter.reset()

        assertEquals(5, g.outEdges(hub).toList().size)
        assertEquals(1, counter.total("moc-co-edges"), "${counter.snapshot()}")
        assertEquals(0, counter.total("moc-co-edges-adjacency"), "${counter.snapshot()}")
    }

    @Test fun `proxied map rethrows the real exception, not UndeclaredThrowableException`() {
        val counter = MapOpCounter(graphTestHz)
        assertFailsWith<NullPointerException> { counter.hz.getMap<Any, Any>("moc-throw").get(null) }
    }
}
