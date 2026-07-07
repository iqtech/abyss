package pl.iqtech.abyss.graph

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.collectNodes
import pl.iqtech.abyss.dsl.nodes
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.dsl.typeTag
import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.SchemaKeyAdapter
import pl.iqtech.abyss.store.api.SchemaTag
import pl.iqtech.abyss.store.api.SchemaTagWidth
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

// RFC §4 verification #3: a Long schema registered INSIDE a multi-schema container now encodes edge
// keys as tag + native Int64 (MultiSchemaAdapter) rather than hex. Throughput should be within noise
// of the standalone LongPerformanceTest numbers — i.e. the container adds no hex tax.
class MultiSchemaPerformanceTest {

    companion object {
        private const val NODE_COUNT = 10_000
        private const val EDGES_PER_NODE = 5
        private val TAG = SchemaTag(1L)
        private val tagged = SchemaKeyAdapter(TAG, SchemaTagWidth.BYTE, LongKeyAdapter)

        private val perfGraph: AbyssGraphSchema<Long> by lazy {
            HeterogeneousSchemaGraph(multiSchemaHz, SchemaTagWidth.BYTE, "perf-ms-nodes", "perf-ms-edges", module = graphTestModule)
                .register(TAG, LongKeyAdapter)
        }

        // Matches AbyssSchemaWorker's default adjacencyShardCount (perfGraph doesn't override it).
        private const val ADJACENCY_SHARD_COUNT = 16

        private val nodeIds: List<Long> by lazy {
            perfGraph // force registration
            val ids = (1L..NODE_COUNT.toLong()).toList()
            val nodesMap = multiSchemaHz.getMap<NodeId, NodeLike<*>>("perf-ms-nodes")
            val edgesMap = multiSchemaHz.getMap<EdgeKey, Any>("perf-ms-edges")
            val adjacencyMap = multiSchemaHz.getMap<AdjacencyKey, AdjacencyValue>("perf-ms-edges-adjacency")
            ids.forEach { id -> nodesMap[tagged.toNodeId(id)] = LongTestNode(id = id, name = id.toString()) }
            val nodeTag = LongTestNode::class.typeTag()
            val edgeTag = LongTestEdge::class.typeTag()
            val adjacency = mutableMapOf<AdjacencyKey, MutableSet<AdjacencyEntry>>()
            ids.forEachIndexed { i, fromId ->
                repeat(EDGES_PER_NODE) { j ->
                    val toId = ids[(i + j + 1) % NODE_COUNT]
                    val fromNid = tagged.toNodeId(fromId); val toNid = tagged.toNodeId(toId)
                    edgesMap[EdgeKey(fromNid, toNid, "test_edge", tagged.partitionKey(fromNid))] =
                        LongTestEdge(fromId = fromId, toId = toId)
                    val inKey = AdjacencyKey(toNid, packShard(AdjacencyDirection.IN, shardIndexOf(fromNid, ADJACENCY_SHARD_COUNT)), tagged.partitionKey(toNid))
                    adjacency.getOrPut(inKey) { mutableSetOf() } += AdjacencyEntry(fromNid, nodeTag, edgeTag)
                }
            }
            adjacencyMap.putAll(adjacency.mapValues { AdjacencyValue(it.value) })
            ids
        }
    }

    @Test fun `container outEdges throughput`() {
        if (System.getProperty("perf") == null) return
        val ids = nodeIds
        runBlocking { repeat(200) { perfGraph.outEdges(ids.random()).toList() } }
        val n = 2_000
        val elapsed = measureTime { runBlocking { repeat(n) { perfGraph.outEdges(ids.random()).toList() } } }
        val opsPerSec = n * 1000.0 / elapsed.inWholeMilliseconds
        println("\ncontainer outEdges (tag+native): ${opsPerSec.toInt()} ops/sec  ($n queries, ${elapsed.inWholeMilliseconds}ms)")
        assertTrue(opsPerSec > 500)
    }

    @Test fun `container inEdges throughput`() {
        if (System.getProperty("perf") == null) return
        val ids = nodeIds
        runBlocking { repeat(200) { perfGraph.inEdges(ids.random()).toList() } }
        val n = 2_000
        val elapsed = measureTime { runBlocking { repeat(n) { perfGraph.inEdges(ids.random()).toList() } } }
        val opsPerSec = n * 1000.0 / elapsed.inWholeMilliseconds
        println("\ncontainer inEdges (tag+native): ${opsPerSec.toInt()} ops/sec  ($n queries, ${elapsed.inWholeMilliseconds}ms)")
        assertTrue(opsPerSec > 500)
    }

    @Test fun `container 3-hop traversal throughput`() {
        if (System.getProperty("perf") == null) return
        val ids = nodeIds
        runBlocking {
            repeat(20) {
                perfGraph.from(ids.random()) {
                    outgoing<LongTestEdge>(); outgoing<LongTestEdge>(); outgoing<LongTestEdge>()
                    nodes<LongTestNode>(); collectNodes<LongTestNode>().toList()
                }
            }
        }
        val n = 200
        val elapsed = measureTime {
            runBlocking {
                repeat(n) {
                    perfGraph.from(ids.random()) {
                        outgoing<LongTestEdge>(); outgoing<LongTestEdge>(); outgoing<LongTestEdge>()
                        nodes<LongTestNode>(); collectNodes<LongTestNode>().toList()
                    }
                }
            }
        }
        val msEach = elapsed.inWholeMilliseconds.toDouble() / n
        println("\ncontainer 3-hop traversal (tag+native): ${"%.1f".format(msEach)}ms avg  ($n traversals, ${elapsed.inWholeMilliseconds}ms)")
        assertTrue(msEach < 500)
    }
}
