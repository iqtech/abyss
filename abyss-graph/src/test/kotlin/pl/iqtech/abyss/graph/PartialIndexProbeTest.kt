package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.right
import kotlinx.coroutines.flow.toList
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
import kotlin.time.Duration
import kotlin.uuid.Uuid

private class PartialProbeStore : AbyssStoreLike {
    val edges = java.util.concurrent.CopyOnWriteArrayList<StoredEdge>()
    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> = Either.Right(null to null)
    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> =
        Either.Right(edges.find { it.fromId == fromId && it.toId == toId }?.let { it.edge to null } ?: (null to null))
    override suspend fun loadEdges(fromId: NodeId): Either<AbyssError, List<StoredEdge>> = Either.Right(edges.filter { it.fromId == fromId })
    override suspend fun loadInEdges(toId: NodeId): Either<AbyssError, List<StoredEdge>> = Either.Right(edges.filter { it.toId == toId })
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

// Probe for the "non-empty index == complete" gate in adjacencyHopFlow.readEntries: after a cold start
// (index empty, store full), ONE write makes the node's index non-empty and the store is never consulted again.
class PartialIndexProbeTest {

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
}
