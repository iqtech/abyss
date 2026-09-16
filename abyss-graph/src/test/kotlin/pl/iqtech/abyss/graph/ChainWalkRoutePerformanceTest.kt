package pl.iqtech.abyss.graph

import com.hazelcast.core.HazelcastInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.EdgeTraversalDirection
import pl.iqtech.abyss.dsl.Evaluation
import pl.iqtech.abyss.dsl.TraversalStrategy
import pl.iqtech.abyss.dsl.typeTag
import pl.iqtech.abyss.store.api.EdgeLike
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
internal fun seedChain(hz: HazelcastInstance, nodesName: String, edgesName: String, length: Int, others: Int, shards: Int = 16): List<Uuid> {
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
    hz.getMap<Any, EdgeLike<*, *>>(edgesName).putAll(edgeValues)
    hz.getMap<AdjacencyKey, AdjacencyValue>("$edgesName-adjacency").putAll(adjacency.mapValues { AdjacencyValue(it.value) })
    return chain
}

// TODO 2.32 Phase 0 (route): Hazelcast map ops per link for today's paths() over a warm degree-1 chain,
// counted by MapOpCounter. Single in-JVM member, so wall-clock is meaningless here — the op count is
// the metric. Seeded by seedChain above.
//
// `others` at 0 the type push-down a typed
// walk buys is invisible (there is nothing to filter); the second variant is what makes it show.
class ChainWalkRoutePerformanceTest {

    private val length = 2_000        // per-link averages are length-independent; DFS depth is not (reported)
    private val nodesName = "cwr-nodes"
    private val edgesName = "cwr-edges"

    @Test fun `chain walk baseline - paths map ops per link`() {
        if (System.getProperty("perf") == null) return
        for (others in listOf(0, 16)) {
            val chain = seedChain(graphTestHz, nodesName, edgesName, length, others)
            for (strategy in TraversalStrategy.entries) for (direction in listOf(EdgeTraversalDirection.OUT, EdgeTraversalDirection.IN)) {
                val counter = MapOpCounter(graphTestHz)
                val g = AbyssGraphSchema(UuidKeyAdapter, counter.hz, nodesName, edgesName, module = graphTestModule)
                val origin = if (direction == EdgeTraversalDirection.OUT) chain.first() else chain.last()
                val outcome = try {
                    val paths = runBlocking {
                        g.from(origin) {
                            paths(strategy, direction,
                                edgeVisitor = { _, e -> e is TestEdge },
                                nodeEvaluator = { _, _ -> Evaluation.INCLUDE_AND_CONTINUE })
                        }.getOrNull()!!.toList()
                    }
                    assertEquals(listOf(length), paths.map { it.nodes.size }, "walk must cover the whole chain (else the seed is wrong)")
                    "ok"
                } catch (e: StackOverflowError) { "DNF: StackOverflowError" }
                val snap = counter.snapshot()
                val total = snap.keys.sumOf { counter.total(it) }
                println("\nchain paths $strategy $direction (length=$length, others=$others): $outcome, " +
                        "${"%.2f".format(total.toDouble() / (length - 1))} map ops/link ($total total) $snap")
            }
        }
    }
}
