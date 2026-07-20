package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.right
import kotlinx.coroutines.delay
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
import kotlin.time.Duration
import kotlin.time.measureTime
import kotlin.uuid.Uuid

// Isolates preloadOut's per-call store cost from real YSQL latency: a fake persistent store that
// charges a fixed delay() per loadEdges call and counts invocations — mirrors PathToPerformanceTest's
// DelayedFakeEngine, but at the AbyssSchemaWorker/store boundary (preloadOut's own caching) rather
// than the NodeIdEngine/pathTo boundary.
private class DelayedFakeStore(
    private val latencyMs: Long,
    private val outEdges: List<EdgeLike<Uuid, Uuid>> = emptyList(),
) : AbyssStoreLike {
    var loadEdgesCalls = 0
        private set

    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> = Either.Right(null to null)
    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> = Either.Right(null to null)
    override suspend fun loadEdges(fromId: NodeId): Either<AbyssError, List<StoredEdge>> {
        loadEdgesCalls++
        delay(latencyMs)
        return Either.Right(outEdges.filter { huid.toNodeId(it.fromId) == fromId }.map { StoredEdge(fromId, huid.toNodeId(it.toId), it, null) })
    }

    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        val tx = object : AbyssStoreTransactionLike {
            override fun saveNode(id: NodeId, node: NodeLike<*>, tags: Set<String>) {}
            override fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, tags: Set<String>) {}
            override fun deleteNode(id: NodeId) {}
            override fun deleteEdge(fromId: NodeId, toId: NodeId, type: String) {}
        }
        tx.block()
        return Unit.right()
    }
}

class AdjacencyPreloadPerformanceTest {

    companion object {
        private const val LATENCY_MS = 3L
        private const val CALLS = 500
    }

    @Test fun `repeated outEdges on a warm node pays the store's latency once, not once per call`() {
        if (System.getProperty("perf") == null) return
        val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "warm")
        val store = DelayedFakeStore(LATENCY_MS, outEdges = listOf(edge))
        val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "adj-preload-perf-nodes", "adj-preload-perf-edges", persistentStore = store, module = graphTestModule)

        val elapsed = measureTime { runBlocking { repeat(CALLS) { g.outEdges(edge.fromId).toList() } } }
        val msEach = elapsed.inWholeMilliseconds.toDouble() / CALLS
        println("\nrepeated outEdges on a warm node (latency=${LATENCY_MS}ms/call, calls=$CALLS): " +
            "${"%.3f".format(msEach)}ms avg, ${store.loadEdgesCalls} store hit(s) (${elapsed.inWholeMilliseconds}ms total)")

        graphTestHz.getMap<Any, Any>("adj-preload-perf-nodes").clear()
        graphTestHz.getMap<Any, Any>("adj-preload-perf-edges").clear()
        graphTestHz.getMap<Any, Any>("adj-preload-perf-edges-adjacency").clear()
    }
}
