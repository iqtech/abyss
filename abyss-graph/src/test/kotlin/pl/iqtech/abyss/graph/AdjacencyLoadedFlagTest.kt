package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.right
import com.hazelcast.internal.serialization.Data
import com.hazelcast.internal.serialization.SerializationService
import com.hazelcast.nio.serialization.genericrecord.GenericRecord
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder
import com.hazelcast.spi.impl.SerializationServiceSupport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.StoredEdge
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.uuid.Uuid

private class PartialProbeStore : AbyssStoreLike {
    val edges = java.util.concurrent.CopyOnWriteArrayList<StoredEdge>()
    @Volatile var failLoads = false
    private fun fail() = Either.Left(AbyssError.Unexpected(IllegalStateException("store down")))
    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> = Either.Right(null to null)
    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> =
        Either.Right(edges.find { it.fromId == fromId && it.toId == toId }?.let { it.edge to null } ?: (null to null))
    override suspend fun loadEdges(fromId: NodeId): Either<AbyssError, List<StoredEdge>> = if (failLoads) fail() else Either.Right(edges.filter { it.fromId == fromId })
    override suspend fun loadInEdges(toId: NodeId): Either<AbyssError, List<StoredEdge>> = if (failLoads) fail() else Either.Right(edges.filter { it.toId == toId })
    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        block(object : AbyssStoreTransactionLike {
            override fun saveNode(id: NodeId, node: NodeLike<*>, tags: Set<String>) {}
            override fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, tags: Set<String>) { edges += StoredEdge(fromId, toId, edge, null) }
            override fun deleteNode(id: NodeId) {}
            override fun deleteEdge(fromId: NodeId, toId: NodeId, type: String) { edges.removeAll { it.fromId == fromId && it.toId == toId } }
        })
        return Unit.right()
    }
}

// The adjacency loaded flag. Before it, adjacencyHopFlow.readEntries took "non-empty index" for "complete": after
// a cold start (index empty, store full), ONE write made the node's index non-empty and the store was never
// consulted again — outEdges/inEdges returned 1 of 11, and a node delete left 10 edge rows in the store.
class AdjacencyLoadedFlagTest {

    private fun coldAfterOneWrite(prefix: String, inbound: Boolean) = runBlocking {
        val maps = listOf("$prefix-nodes", "$prefix-edges", "$prefix-edges-adjacency")
        maps.forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        val store = PartialProbeStore()
        val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "$prefix-nodes", "$prefix-edges", persistentStore = store, module = graphTestModule)
        val hub = Uuid.random()
        fun e(i: Int) = if (inbound) TestEdge(fromId = Uuid.random(), toId = hub, label = "e$i") else TestEdge(fromId = hub, toId = Uuid.random(), label = "e$i")
        check(g.transaction(checkIntegrity = false) { (1..10).forEach { addEdge(e(it)) } }.isRight())
        maps.forEach { graphTestHz.getMap<Any, Any>(it).clear() }                          // "restart": cache + index gone, store intact
        check(g.transaction(checkIntegrity = false) { addEdge(e(11)) }.isRight())           // ingest touches hub before any read
        Triple(g, hub, store)
    }

    private fun adjacencyOf(prefix: String, owner: Uuid) =
        graphTestHz.getMap<AdjacencyKey, AdjacencyValue>("$prefix-edges-adjacency").entries.filter { it.key.nodeId == huid.toNodeId(owner) }

    @Test fun `outEdges after cold start plus one write`() = runBlocking {
        val (g, hub, _) = coldAfterOneWrite("pip1", inbound = false)
        assertEquals(11, g.outEdges(hub).toList().size)
    }

    @Test fun `inEdges after cold start plus one write`() = runBlocking {
        val (g, hub, _) = coldAfterOneWrite("pip2", inbound = true)
        assertEquals(11, g.inEdges(hub).toList().size)
    }

    @Test fun `node delete after cold start plus one write cascades every stored edge`() = runBlocking {
        val (g, hub, store) = coldAfterOneWrite("pip3", inbound = false)
        check(g.transaction(checkIntegrity = false) { removeNode(hub) }.isRight())
        assertEquals(0, store.edges.size, "edge rows of the deleted node left in the store")
    }

    @Test fun `failed preload does not mark loaded, the next read retries and heals`() = runBlocking {
        val (g, hub, store) = coldAfterOneWrite("pip4", inbound = false)
        store.failLoads = true
        assertEquals(1, g.outEdges(hub).toList().size, "store down: the partial index is served")
        assertTrue(adjacencyOf("pip4", hub).none { it.value.loaded }, "a failed load must not mark")
        store.failLoads = false
        assertEquals(11, g.outEdges(hub).toList().size)
    }

    @Test fun `node delete drops its adjacency - entries, empty shards and loaded flag`() = runBlocking {
        val (g, hub, _) = coldAfterOneWrite("pip5", inbound = false)
        assertEquals(11, g.outEdges(hub).toList().size)
        assertTrue(adjacencyOf("pip5", hub).any { it.value.loaded })
        check(g.transaction(checkIntegrity = false) { removeNode(hub) }.isRight())
        assertEquals(emptyList(), adjacencyOf("pip5", hub).map { it.key })
    }

    @Test fun `cache-only mode never marks`() = runBlocking {
        listOf("pip6-nodes", "pip6-edges", "pip6-edges-adjacency").forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "pip6-nodes", "pip6-edges", module = graphTestModule)
        val hub = Uuid.random()
        check(g.transaction(checkIntegrity = false) { (1..3).forEach { addEdge(TestEdge(fromId = hub, toId = Uuid.random(), label = "e$it")) } }.isRight())
        assertEquals(3, g.outEdges(hub).toList().size)
        assertTrue(adjacencyOf("pip6", hub).none { it.value.loaded })
    }

    @Test fun `mutations keep the flag, mark creates an empty loaded shard`() = runBlocking {
        val map = graphTestHz.getMap<AdjacencyKey, AdjacencyValue>("pip7-adjacency").also { it.clear() }
        val owner = huid.toNodeId(Uuid.random())
        val key = AdjacencyKey(owner, packShard(AdjacencyDirection.OUT, 0), huid.partitionKey(owner))
        map.submitToKey(key, AdjacencyLifecycleProcessor(AdjacencyLifecycle.MARK_LOADED)).await()
        assertEquals(AdjacencyValue(emptySet(), loaded = true), map[key])
        val entry = AdjacencyEntry(huid.toNodeId(Uuid.random()), null, 7)
        map.submitToKey(key, AdjacencyMutationProcessor(AdjacencyMutation.Add(entry))).await()
        assertEquals(AdjacencyValue(setOf(entry), loaded = true), map[key])
        map.submitToKey(key, AdjacencyMutationProcessor(AdjacencyMutation.Remove(entry.neighborId, 7))).await()
        assertEquals(AdjacencyValue(emptySet(), loaded = true), map[key])
        map.submitToKey(key, AdjacencyLifecycleProcessor(AdjacencyLifecycle.DROP)).await()
        assertFalse(map.containsKey(key))
    }

    // The flag rides the read's own getAll: a warm store-backed hop is still ONE adjacency op; a cold preload pays
    // one extra submitToKey (the mark). Measured pre/post on this shape: cold-in adjacency getAll=2 submitToKey 10 → 11,
    // warm-in getAll=1 both; cold-out getAll=3 both, warm-out getAll=1 both.
    @Test fun `flag costs no warm-read op, one mark per cold preload`() = runBlocking {
        val maps = listOf("pip8-nodes", "pip8-edges", "pip8-edges-adjacency")
        maps.forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        val counter = MapOpCounter(graphTestHz)
        val g = AbyssGraphSchema(UuidKeyAdapter, counter.hz, "pip8-nodes", "pip8-edges", persistentStore = PartialProbeStore(), module = graphTestModule)
        val hub = Uuid.random()
        check(g.transaction(checkIntegrity = false) { (1..10).forEach { addEdge(TestEdge(fromId = Uuid.random(), toId = hub, label = "e$it")) } }.isRight())
        maps.forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        counter.reset()
        assertEquals(10, g.inEdges(hub).toList().size)
        assertEquals(mapOf("getAll" to 2L, "getAll.returned" to 10L, "submitToKey" to 11L), counter.snapshot()["pip8-edges-adjacency"])
        counter.reset()
        assertEquals(10, g.inEdges(hub).toList().size)
        assertEquals(mapOf("getAll" to 1L, "getAll.returned" to 10L), counter.snapshot()["pip8-edges-adjacency"])
    }

    private val ss = (graphTestHz as SerializationServiceSupport).serializationService as SerializationService

    @Test fun `flag round-trips, a pre-flag value reads not loaded`() {
        val v = AdjacencyValue(setOf(AdjacencyEntry(huid.toNodeId(Uuid.random()), 3, 7)), loaded = true)
        assertEquals(v, ss.toObject<AdjacencyValue>(ss.toData<Data>(v)))
        // Old schema: same type name, no `loaded` field (what a pre-flag member writes).
        val old = GenericRecordBuilder.compact("AdjacencyValue").setArrayOfGenericRecord("entries", arrayOf<GenericRecord>()).build()
        assertEquals(AdjacencyValue(emptySet(), loaded = false), ss.toObject<AdjacencyValue>(ss.toData<Data>(old)))
        val proc = ss.toObject<AdjacencyLifecycleProcessor>(ss.toData<Data>(AdjacencyLifecycleProcessor(AdjacencyLifecycle.DROP)))
        assertEquals(AdjacencyLifecycle.DROP, proc.action)
    }
}
