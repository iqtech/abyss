package pl.iqtech.abyss.graph

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.collectNodes
import pl.iqtech.abyss.dsl.nodes
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.dsl.typeTag
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.HeaderlessKeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime
import kotlin.uuid.Uuid

class UuidPerformanceTest {

    companion object {
        private const val NODE_COUNT = 10_000
        private const val EDGES_PER_NODE = 5

        // AbyssGraphSchema's standalone constructor is headerless (TODO 1.19); pre-seeded maps must
        // use the same HeaderlessKeyAdapter wrapper, not the bare (headered) UuidKeyAdapter.
        private val huid = HeaderlessKeyAdapter(UuidKeyAdapter)

        private val perfGraph: AbyssGraphSchema<Uuid> by lazy {
            AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "perf-uuid-nodes", "perf-uuid-edges", module = graphTestModule)
        }

        // Matches AbyssSchemaWorker's default adjacencyShardCount (perfGraph doesn't override it).
        private const val ADJACENCY_SHARD_COUNT = 16

        private val nodeIds: List<Uuid> by lazy {
            val ids = (1..NODE_COUNT).map { Uuid.random() }
            val nodesMap = graphTestHz.getMap<NodeId, NodeLike<*>>("perf-uuid-nodes")
            val edgesMap = graphTestHz.getMap<EdgeKey, EdgeLike<*, *>>("perf-uuid-edges")
            val adjacencyMap = graphTestHz.getMap<AdjacencyKey, AdjacencyValue>("perf-uuid-edges-adjacency")
            ids.forEach { id ->
                nodesMap[huid.toNodeId(id)] = TestNode(id = id, name = id.toString())
            }
            val nodeTag = TestNode::class.typeTag()
            val edgeTag = TestEdge::class.typeTag()
            val adjacency = mutableMapOf<AdjacencyKey, MutableSet<AdjacencyEntry>>()
            ids.forEachIndexed { i, fromId ->
                repeat(EDGES_PER_NODE) { j ->
                    val toId = ids[(i + j + 1) % NODE_COUNT]
                    val fromNid = huid.toNodeId(fromId)
                    val toNid   = huid.toNodeId(toId)
                    edgesMap[EdgeKey(fromNid, toNid, "test_edge", huid.partitionKey(fromNid))] =
                        TestEdge(fromId = fromId, toId = toId, label = "")
                    // Only inEdges() needs the adjacency index seeded here (outEdges/outgoing<E>() scan
                    // edgesMap directly, unaffected by 2.21's index).
                    val inKey = AdjacencyKey(toNid, packShard(AdjacencyDirection.IN, shardIndexOf(fromNid, ADJACENCY_SHARD_COUNT)), huid.partitionKey(toNid))
                    adjacency.getOrPut(inKey) { mutableSetOf() } += AdjacencyEntry(fromNid, nodeTag, edgeTag)
                }
            }
            adjacencyMap.putAll(adjacency.mapValues { AdjacencyValue(it.value) })
            ids
        }
    }

    @Test fun `outEdges throughput`() {
        if (System.getProperty("perf") == null) return
        val ids = nodeIds
        runBlocking { repeat(200) { perfGraph.outEdges(ids.random()).toList() } }

        val n = 2_000
        val elapsed = measureTime { runBlocking { repeat(n) { perfGraph.outEdges(ids.random()).toList() } } }
        val opsPerSec = n * 1000.0 / elapsed.inWholeMilliseconds
        println("\noutEdges: ${opsPerSec.toInt()} ops/sec  ($n queries, ${elapsed.inWholeMilliseconds}ms)")
        assertTrue(opsPerSec > 500)
    }

    @Test fun `inEdges throughput`() {
        if (System.getProperty("perf") == null) return
        val ids = nodeIds
        runBlocking { repeat(200) { perfGraph.inEdges(ids.random()).toList() } }

        val n = 2_000
        val elapsed = measureTime { runBlocking { repeat(n) { perfGraph.inEdges(ids.random()).toList() } } }
        val opsPerSec = n * 1000.0 / elapsed.inWholeMilliseconds
        println("\ninEdges: ${opsPerSec.toInt()} ops/sec  ($n queries, ${elapsed.inWholeMilliseconds}ms)")
        assertTrue(opsPerSec > 500)
    }

    @Test fun `3-hop traversal throughput`() {
        if (System.getProperty("perf") == null) return
        val ids = nodeIds
        runBlocking {
            repeat(20) {
                perfGraph.from(ids.random()) {
                    outgoing<TestEdge>(); outgoing<TestEdge>(); outgoing<TestEdge>()
                    nodes<TestNode>(); collectNodes<TestNode>().toList()
                }
            }
        }

        val n = 200
        val elapsed = measureTime {
            runBlocking {
                repeat(n) {
                    perfGraph.from(ids.random()) {
                        outgoing<TestEdge>(); outgoing<TestEdge>(); outgoing<TestEdge>()
                        nodes<TestNode>(); collectNodes<TestNode>().toList()
                    }
                }
            }
        }
        val msEach = elapsed.inWholeMilliseconds.toDouble() / n
        println("\n3-hop traversal: ${"%.1f".format(msEach)}ms avg  ($n traversals, ${elapsed.inWholeMilliseconds}ms)")
        assertTrue(msEach < 500)
    }
}
