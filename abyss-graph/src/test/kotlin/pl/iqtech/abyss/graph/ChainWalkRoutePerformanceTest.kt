package pl.iqtech.abyss.graph

import com.hazelcast.core.HazelcastInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.EdgeTraversalDirection
import pl.iqtech.abyss.dsl.Evaluation
import pl.iqtech.abyss.dsl.TraversalStrategy
import pl.iqtech.abyss.dsl.typeTag
import pl.iqtech.abyss.dsl.walkIn
import pl.iqtech.abyss.dsl.walkOut
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.EdgeLike
import java.util.concurrent.atomic.AtomicInteger
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

// Seeds a warm chain straight into the maps (uncounted) the way SupernodeTraversalTest.seedHub does,
// but with BOTH adjacency directions, mirroring AbyssSchemaWorker's write path: OUT entry keyed by
// from, sharded by shardIndexOf(to); IN entry keyed by to, sharded by shardIndexOf(from), carrying the
// from node's tag. Missing IN entries would silently measure the cold preload path instead.
// `others` = unrelated TypedEdges per chain node, in each direction.
// `loaded` marks every chain node's head shard (shard 0) loaded in both directions, as a completed preload
// would. Without it a store-backed schema sees every chain node as cold and measures the preload path.
internal fun seedChain(hz: HazelcastInstance, nodesName: String, edgesName: String, length: Int, others: Int, shards: Int = 16, loaded: Boolean = false): List<Uuid> {
    listOf(nodesName, edgesName, "$edgesName-adjacency").forEach { hz.getMap<Any, Any>(it).clear() }
    val chain = List(length) { Uuid.random() }
    hz.getMap<NodeId, NodeLike<*>>(nodesName)
        .putAll(chain.associate { huid.toNodeId(it) to TestNode(id = it, name = "c") })

    val nodeTag = TestNode::class.typeTag()
    val edges = buildList<EdgeLike<*, *>> {
        chain.zipWithNext().forEach { (a, b) -> add(TestEdge(fromId = a, toId = b, label = "next")) }
        chain.forEach { c -> repeat(others) { add(TypedEdge(fromId = c, toId = Uuid.random())); add(TypedEdge(fromId = Uuid.random(), toId = c)) } }
    }
    val edgeValues = mutableMapOf<Any, EdgeLike<*, *>>()
    val adjacency = mutableMapOf<AdjacencyKey, MutableSet<AdjacencyEntry>>()
    for (e in edges) {
        val from = huid.toNodeId(e.fromId as Uuid)
        val to = huid.toNodeId(e.toId as Uuid)
        val type = if (e is TestEdge) "test_edge" else "typed_edge"
        val edgeTag = e::class.typeTag()
        edgeValues[EdgeKey(from, to, type, huid.partitionKey(from))] = e
        adjacency.getOrPut(AdjacencyKey(from, packShard(AdjacencyDirection.OUT, shardIndexOf(to, shards)), huid.partitionKey(from))) { mutableSetOf() } +=
            AdjacencyEntry(to, nodeTag, edgeTag)
        adjacency.getOrPut(AdjacencyKey(to, packShard(AdjacencyDirection.IN, shardIndexOf(from, shards)), huid.partitionKey(to))) { mutableSetOf() } +=
            AdjacencyEntry(from, nodeTag, edgeTag)
    }
    val heads = if (!loaded) emptySet() else chain.flatMap { c ->
        val nid = huid.toNodeId(c)
        AdjacencyDirection.entries.map { d -> AdjacencyKey(nid, packShard(d, 0), huid.partitionKey(nid)).also { adjacency.getOrPut(it) { mutableSetOf() } } }
    }.toSet()
    hz.getMap<Any, EdgeLike<*, *>>(edgesName).putAll(edgeValues)
    hz.getMap<AdjacencyKey, AdjacencyValue>("$edgesName-adjacency").putAll(adjacency.mapValues { AdjacencyValue(it.value, loaded = it.key in heads) })
    return chain
}

// TODO 2.32 Phase 0 (route): Hazelcast map ops per link for today's paths() over a warm degree-1 chain,
// counted by MapOpCounter. Single in-JVM member, so wall-clock is meaningless here — the op count is
// the metric. Seeded by seedChain above.
//
// `others` at 0 the type push-down a typed
// walk buys is invisible (there is nothing to filter); the second variant is what makes it show.
//
// Phase 0′ (2026-11-19): two modes. CACHE_ONLY (no store) skips the loaded check and cachedOutScan's count,
// so it cannot tell needValue=true from false; STORE_WARM (a store, chain seeded loaded) is the production
// warm path. STORE_WARM asserts zero store loads — a load means the seed was cold and the numbers are wrong.
class ChainWalkRoutePerformanceTest {

    private val length = 2_000        // per-link averages are length-independent
    private val nodesName = "cwr-nodes"
    private val edgesName = "cwr-edges"

    private enum class Mode { CACHE_ONLY, STORE_WARM }

    private class LoadCountingStore(private val inner: AbyssStoreLike = PartialProbeStore()) : AbyssStoreLike by inner {
        val loads = AtomicInteger()
        override suspend fun loadNode(id: NodeId) = inner.loadNode(id).also { loads.incrementAndGet() }
        override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String) = inner.loadEdge(fromId, toId, type).also { loads.incrementAndGet() }
        override suspend fun loadEdges(fromId: NodeId) = inner.loadEdges(fromId).also { loads.incrementAndGet() }
        override suspend fun loadInEdges(toId: NodeId) = inner.loadInEdges(toId).also { loads.incrementAndGet() }
    }

    private fun schema(counter: MapOpCounter, mode: Mode, store: LoadCountingStore) =
        if (mode == Mode.CACHE_ONLY) AbyssGraphSchema(UuidKeyAdapter, counter.hz, nodesName, edgesName, module = graphTestModule)
        else AbyssGraphSchema(UuidKeyAdapter, counter.hz, nodesName, edgesName, persistentStore = store, module = graphTestModule)

    private fun report(label: String, counter: MapOpCounter, links: Int) {
        val snap = counter.snapshot()
        val total = snap.keys.sumOf { counter.total(it) }
        val perMap = snap.mapValues { (_, ops) -> ops.mapValues { "%.2f".format(it.value.toDouble() / links) } }
        println("\n$label: ${"%.2f".format(total.toDouble() / links)} map ops/link ($total total) per-link $perMap")
    }

    @Test fun `chain walk baseline - paths map ops per link`() {
        if (System.getProperty("perf") == null) return
        for (others in listOf(0, 16)) {
            val chain = seedChain(graphTestHz, nodesName, edgesName, length, others, loaded = true)
            for (mode in Mode.entries) for (strategy in TraversalStrategy.entries) for (direction in listOf(EdgeTraversalDirection.OUT, EdgeTraversalDirection.IN)) {
                val counter = MapOpCounter(graphTestHz)
                val store = LoadCountingStore()
                val g = schema(counter, mode, store)
                val origin = if (direction == EdgeTraversalDirection.OUT) chain.first() else chain.last()
                val paths = runBlocking {
                    g.from(origin) {
                        paths(strategy, direction,
                            edgeVisitor = { _, e -> e is TestEdge },
                            nodeEvaluator = { _, _ -> Evaluation.INCLUDE_AND_CONTINUE })
                    }.getOrNull()!!.toList()
                }
                assertEquals(listOf(length), paths.map { it.nodes.size }, "walk must cover the whole chain (else the seed is wrong)")
                assertEquals(0, store.loads.get(), "store loads during a warm walk: the seed is cold, numbers invalid")
                report("chain paths $mode $strategy $direction (length=$length, others=$others)", counter, length - 1)
            }
        }
    }

    // TODO 2.32 Phase 1 post: the walk itself, same chain, modes and report as the paths() baseline above.
    @Test fun `chain walk post - walkChain map ops per link`() {
        if (System.getProperty("perf") == null) return
        for (others in listOf(0, 16)) {
            val chain = seedChain(graphTestHz, nodesName, edgesName, length, others, loaded = true)
            for (mode in Mode.entries) for (out in listOf(true, false)) {
                val counter = MapOpCounter(graphTestHz)
                val store = LoadCountingStore()
                val g = schema(counter, mode, store)
                val walked = runBlocking {
                    (if (out) g.from(chain.first()) { walkOut<TestEdge>() } else g.from(chain.last()) { walkIn<TestEdge>() }).getOrNull()!!.toList()
                }
                assertEquals(length - 1, walked.size, "walk must cover the whole chain")
                assertEquals(0, store.loads.get(), "store loads during a warm walk: the seed is cold, numbers invalid")
                report("chain walk $mode ${if (out) "OUT" else "IN"} (length=$length, others=$others)", counter, length - 1)
            }
        }
    }

    // Decision probe for the plan's "hop call": a bare id chase over the engine, no node fetches, so the
    // count is the hop route alone. Expected (read from source): STORE_WARM OUT needValue=true 2 ops/link
    // (cachedOutScan: entrySet + count), false 1 (readIfLoaded); IN true 2 (index + resolveEdges), false 1.
    @Test fun `chain walk probe - hop route ops per link by needValue`() {
        if (System.getProperty("perf") == null) return
        for (others in listOf(0, 16)) {
            val chain = seedChain(graphTestHz, nodesName, edgesName, length, others, loaded = true)
            for (mode in Mode.entries) for (out in listOf(true, false)) for (needValue in listOf(true, false)) {
                val counter = MapOpCounter(graphTestHz)
                val store = LoadCountingStore()
                val engine = schema(counter, mode, store).traversalEngine
                var cur = huid.toNodeId(if (out) chain.first() else chain.last())
                var links = 0
                runBlocking {
                    while (true) {
                        val hops = (if (out) engine.outAt(cur, "test_edge", needValue) else engine.inAt(cur, "test_edge", needValue)).toList()
                        if (hops.isEmpty()) break
                        cur = hops.single().let { if (out) it.toId else it.fromId }
                        links++
                    }
                }
                assertEquals(length - 1, links, "probe must chase the whole chain")
                assertEquals(0, store.loads.get(), "store loads during a warm chase: the seed is cold, numbers invalid")
                report("hop probe $mode ${if (out) "OUT" else "IN"} needValue=$needValue (others=$others)", counter, links)
            }
        }
    }
}
