package pl.iqtech.abyss.graph

import arrow.core.Either
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.collectNodes
import pl.iqtech.abyss.dsl.nodes
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.dsl.typeTag
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

// TODO 2.19: addHop/collectSubgraph fan out one coroutine per frontier node through a shared,
// bounded dispatcher (TraversalBuilder.hopDispatcher) instead of an unbounded wave. These tests
// exercise a wide fan-out (a hub with 1500 outgoing edges) to catch any batching bug that would
// drop or duplicate hops across the dispatcher's concurrency limit, plus concurrent-traversal
// isolation across the shared dispatcher.
class SupernodeTraversalTest {

    @BeforeTest fun clear() {
        graphTestHz.getMap<Any, Any>("g-nodes").clear()
        graphTestHz.getMap<Any, Any>("g-edges").clear()
        graphTestHz.getMap<Any, Any>("g-edges-adjacency").clear()
    }

    private val nodesMap get() = graphTestHz.getMap<NodeId, NodeLike<*>>("g-nodes")
    private val edgesMap get() = graphTestHz.getMap<Any, EdgeLike<*, *>>("g-edges")
    private val adjacencyMap get() = graphTestHz.getMap<AdjacencyKey, AdjacencyValue>("g-edges-adjacency")

    // Matches AbyssSchemaWorker's default adjacencyShardCount (graphTest doesn't override it).
    private val ADJACENCY_SHARD_COUNT = 16

    // Writes directly into the cache maps (bypassing transaction()) so seeding 1500 edges is one
    // batch put, not 1500 sequential commits — this test is about fan-out width, not write path.
    // Existence-only outgoing<TestEdge>() reads through the adjacency index (2.21), so the OUT-side
    // entries need seeding too, same as the write path would produce.
    private fun seedHub(edgeCount: Int): Pair<TestNode, List<TestNode>> {
        val hub = TestNode(id = Uuid.random(), name = "hub")
        val targets = List(edgeCount) { i -> TestNode(id = Uuid.random(), name = "t$i") }
        nodesMap.putAll((listOf(hub) + targets).associateBy { huid.toNodeId(it.id) })
        val hubNid = huid.toNodeId(hub.id)
        val edges = targets.associate { t ->
            EdgeKey(hubNid, huid.toNodeId(t.id), "test_edge", huid.partitionKey(hubNid)) as Any to
                TestEdge(fromId = hub.id, toId = t.id, label = "L") as EdgeLike<*, *>
        }
        edgesMap.putAll(edges)
        val nodeTag = TestNode::class.typeTag()
        val edgeTag = TestEdge::class.typeTag()
        val adjacency = mutableMapOf<AdjacencyKey, MutableSet<AdjacencyEntry>>()
        targets.forEach { t ->
            val toNid = huid.toNodeId(t.id)
            val outKey = AdjacencyKey(hubNid, packShard(AdjacencyDirection.OUT, shardIndexOf(toNid, ADJACENCY_SHARD_COUNT)), huid.partitionKey(hubNid))
            adjacency.getOrPut(outKey) { mutableSetOf() } += AdjacencyEntry(toNid, nodeTag, edgeTag)
        }
        adjacencyMap.putAll(adjacency.mapValues { AdjacencyValue(it.value) })
        return hub to targets
    }

    @Test fun `wide fan-out returns every target exactly once`() {
        runBlocking {
            val (hub, targets) = seedHub(1500)
            val result = graphTest.from(hub.id) {
                outgoing<TestEdge>()
                nodes<TestNode>()
                collectNodes<TestNode>().toList()
            }
            assertIs<Either.Right<List<TestNode>>>(result)
            assertEquals(targets.size, result.value.size)
            assertEquals(targets.toSet(), result.value.toSet())
        }
    }

    @Test fun `wide fan-out with value-needed predicate returns every matching target exactly once`() {
        runBlocking {
            val (hub, targets) = seedHub(1500)
            val result = graphTest.from(hub.id) {
                outgoing<TestEdge> { it.label == "L" }
                nodes<TestNode>()
                collectNodes<TestNode>().toList()
            }
            assertIs<Either.Right<List<TestNode>>>(result)
            assertEquals(targets.size, result.value.size)
            assertEquals(targets.toSet(), result.value.toSet())
        }
    }

    @Test fun `two concurrent supernode traversals do not cross-contaminate results`() {
        runBlocking {
            val (hubA, targetsA) = seedHub(800)
            val (hubB, targetsB) = seedHub(800)

            val (resultA, resultB) = coroutineScope {
                val a = async { graphTest.from(hubA.id) { outgoing<TestEdge>(); nodes<TestNode>(); collectNodes<TestNode>().toList() } }
                val b = async { graphTest.from(hubB.id) { outgoing<TestEdge>(); nodes<TestNode>(); collectNodes<TestNode>().toList() } }
                a.await() to b.await()
            }

            assertIs<Either.Right<List<TestNode>>>(resultA)
            assertIs<Either.Right<List<TestNode>>>(resultB)
            assertEquals(targetsA.toSet(), resultA.value.toSet())
            assertEquals(targetsB.toSet(), resultB.value.toSet())
        }
    }
}
