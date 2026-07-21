package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.right
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.nodes
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.StoredEdge
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
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

// Serves a hub's out-edges already carrying neighborType (as a YSQL JOIN would), and counts loadNode
// so a cold warm's per-neighbor node reads are observable. loadNode returns a real node so the
// null-tag fallback path stays correct.
private class HubFakeStore(
    private val latencyMs: Long,
    private val out: List<StoredEdge>,
    private val nodesById: Map<NodeId, NodeLike<*>>,
) : AbyssStoreLike {
    val loadNodeCalls = AtomicInteger(0)

    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> {
        loadNodeCalls.incrementAndGet()
        delay(latencyMs)
        return Either.Right((nodesById[id]) to null)
    }
    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> = Either.Right(null to null)
    override suspend fun loadEdges(fromId: NodeId): Either<AbyssError, List<StoredEdge>> = Either.Right(out.filter { it.fromId == fromId })
    override suspend fun loadInEdges(toId: NodeId): Either<AbyssError, List<StoredEdge>> = Either.Right(emptyList())
    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        block(object : AbyssStoreTransactionLike {
            override fun saveNode(id: NodeId, node: NodeLike<*>, tags: Set<String>) {}
            override fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, tags: Set<String>) {}
            override fun deleteNode(id: NodeId) {}
            override fun deleteEdge(fromId: NodeId, toId: NodeId, type: String) {}
        })
        return Unit.right()
    }
}

class AdjacencyPreloadPerformanceTest {

    companion object {
        private const val LATENCY_MS = 3L
        private const val CALLS = 500
        private const val HUB_EDGES = 300
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

    // A hub whose out-edges carry neighborType (Solution 1: type rides the edge scan). Cold warming
    // must resolve every neighbor's tag from that string, not a per-neighbor loadNode. Baseline before
    // the fix: loadNodeCalls == HUB_EDGES.
    private fun hub(danglingCount: Int = 0): Triple<HubFakeStore, Uuid, Int> {
        val hub = Uuid.random()
        val out = ArrayList<StoredEdge>(HUB_EDGES)
        val nodesById = HashMap<NodeId, NodeLike<*>>()
        repeat(HUB_EDGES) { i ->
            val to = Uuid.random()
            val toNid = huid.toNodeId(to)
            nodesById[toNid] = TestNode(id = to, name = "n$i")
            // First `danglingCount` edges arrive without a resolved type (dangling / non-relational) -> null.
            val neighborType = if (i < danglingCount) null else "test_node"
            out += StoredEdge(huid.toNodeId(hub), toNid, TestEdge(fromId = hub, toId = to, label = "e$i"), null, neighborType)
        }
        return Triple(HubFakeStore(LATENCY_MS, out, nodesById), hub, HUB_EDGES)
    }

    @Test fun `cold hub warm resolves neighbor tags from the edge scan- zero loadNode`() {
        if (System.getProperty("perf") == null) return
        val (store, hubId, n) = hub()
        val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "adj-hub-perf-nodes", "adj-hub-perf-edges", persistentStore = store, module = graphTestModule)

        val elapsed = measureTime { runBlocking { g.outEdges(hubId).toList() } }
        println("\ncold hub warm (edges=$n, latency=${LATENCY_MS}ms/node-read): " +
            "${store.loadNodeCalls.get()} node read(s) (${elapsed.inWholeMilliseconds}ms total)")

        assertEquals(0, store.loadNodeCalls.get(), "neighbor tags come from the edge scan, not per-neighbor loadNode")

        graphTestHz.getMap<Any, Any>("adj-hub-perf-nodes").clear()
        graphTestHz.getMap<Any, Any>("adj-hub-perf-edges").clear()
        graphTestHz.getMap<Any, Any>("adj-hub-perf-edges-adjacency").clear()
    }

    // Correctness through the real graph: after a cold warm, a typed hop+filter keeps ALL neighbors
    // (count == N) AND fetches nothing (loadNode == 0). count==N proves the tag is present *and*
    // correct (a wrong tag would drop them -> count 0); loadNode==0 proves it came from the scan (a
    // null tag would fetch each -> loadNode N).
    @Test fun `typed traversal after cold warm is exact and fetch-free`() {
        val (store, hubId, n) = hub()
        val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "adj-hub-fn-nodes", "adj-hub-fn-edges", persistentStore = store, module = graphTestModule)

        val kept = runBlocking { g.from(hubId) { outgoing<TestEdge>(); nodes<TestNode>(); count() } }
        assertEquals(Either.Right(n), kept, "every TestNode neighbour kept via tag match")
        assertEquals(0, store.loadNodeCalls.get(), "tag resolved from the scan; typed filter fetched nothing")

        clear("adj-hub-fn")
    }

    // Null neighborType (dangling / non-relational store) must fall back to a fetch and still be exact.
    @Test fun `null neighborType falls back to a fetch and stays correct`() {
        val (store, hubId, n) = hub(danglingCount = 5)
        val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "adj-hub-dg-nodes", "adj-hub-dg-edges", persistentStore = store, module = graphTestModule)

        val kept = runBlocking { g.from(hubId) { outgoing<TestEdge>(); nodes<TestNode>(); count() } }
        assertEquals(Either.Right(n), kept, "all neighbours kept, dangling ones via the fetch fallback")
        assertEquals(5, store.loadNodeCalls.get(), "exactly the 5 null-tag neighbours were fetched")

        clear("adj-hub-dg")
    }

    private fun clear(prefix: String) {
        graphTestHz.getMap<Any, Any>("$prefix-nodes").clear()
        graphTestHz.getMap<Any, Any>("$prefix-edges").clear()
        graphTestHz.getMap<Any, Any>("$prefix-edges-adjacency").clear()
    }
}
