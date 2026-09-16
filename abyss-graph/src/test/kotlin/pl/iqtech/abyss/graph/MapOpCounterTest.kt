package pl.iqtech.abyss.graph

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class MapOpCounterTest {

    // One in-edge b <- a, priced on real Hazelcast. The IN entry lives in shard shardIndexOf(a): window 0
    // (shards 0-7) lets isEmpty stop after one getAll; shards 8-15 force the second. read then walks both
    // windows regardless. Pins TypedChainWalkPlan's ~1.5-op warm-check correction with measured counts.
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

    @Test fun `in-edge in window 0 costs isEmpty 1 + read 2 adjacency getAlls`() {
        val (getAlls, counter) = inEdgeAdjacencyGetAlls(laterWindow = false)
        assertEquals(3, getAlls, "${counter.snapshot()}")
    }

    @Test fun `in-edge in shards 8-15 costs isEmpty 2 + read 2 adjacency getAlls`() {
        val (getAlls, counter) = inEdgeAdjacencyGetAlls(laterWindow = true)
        assertEquals(4, getAlls, "${counter.snapshot()}")
    }

    @Test fun `proxied map rethrows the real exception, not UndeclaredThrowableException`() {
        val counter = MapOpCounter(graphTestHz)
        assertFailsWith<NullPointerException> { counter.hz.getMap<Any, Any>("moc-throw").get(null) }
    }
}
