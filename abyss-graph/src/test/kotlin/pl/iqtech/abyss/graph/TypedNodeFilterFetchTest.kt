package pl.iqtech.abyss.graph

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.collectNodes
import pl.iqtech.abyss.dsl.hasOutgoing
import pl.iqtech.abyss.dsl.nodes
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.graph.traversal.TraversalBuilder
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

// Proves typed node filtering reads the carried @TypeTag instead of fetching each frontier node.
// A hand-built NodeIdEngine returns canned hops with nodeTypeTag set (simulating a warm adjacency
// index) and counts every nodeAt call. Driving TraversalBuilder directly is the only way to isolate
// the fetch count from Hazelcast/store I/O. See TypedTraversalTagFilterPlan.md.
private class CountingEngine(
    private val nodes: Map<NodeId, NodeLike<*>>,
    private val outHops: Map<NodeId, List<Hop>>,
) : NodeIdEngine {
    val nodeAtCalls = AtomicInteger(0)

    override suspend fun nodeAt(nid: NodeId): NodeLike<*>? { nodeAtCalls.incrementAndGet(); return nodes[nid] }
    override fun outAt(nid: NodeId, type: String?, needValue: Boolean, includeEphemeral: Boolean): Flow<Hop> =
        outHops[nid].orEmpty().filter { type == null || it.type == type }.asFlow()
    override fun inAt(nid: NodeId, type: String?, needValue: Boolean): Flow<Hop> = emptyFlow()
    override suspend fun resolveEdges(hops: List<Hop>): Map<Hop, EdgeLike<*, *>> = emptyMap()
    override fun allNodeIdsRaw(): Flow<NodeId> = emptyFlow()
    override val hopDispatcher = Dispatchers.IO
}

class TypedNodeFilterFetchTest {

    // TestNode @TypeTag(1)/"test_node", OtherNode @TypeTag(2)/"other_node", TestEdge "test_edge" (from GraphTest.kt).
    private fun scenario(withNullTagTarget: Boolean): Pair<CountingEngine, NodeId> {
        val origin = huid.toNodeId(Uuid.random())
        val nodesMap = mutableMapOf<NodeId, NodeLike<*>>()
        val hops = mutableListOf<Hop>()

        fun add(node: NodeLike<*>, tag: Short?) {
            val nid = huid.toNodeId(Uuid.random())
            nodesMap[nid] = node
            hops += Hop(origin, nid, "test_edge", null, tag)
        }
        repeat(3) { add(TestNode(id = Uuid.random(), name = "person$it"), 1) } // TestNode, tag 1
        repeat(2) { add(OtherNode(id = Uuid.random()), 2) }                    // OtherNode, tag 2
        // A TestNode whose tag wasn't resolved at write time (dangling / cold preload) -> fetch fallback.
        if (withNullTagTarget) add(TestNode(id = Uuid.random(), name = "dangling"), null)
        return CountingEngine(nodesMap, mapOf(origin to hops)) to origin
    }

    @Test fun `nodes filter reads the tag - zero fetches when all tags present`() = runBlocking {
        val (engine, origin) = scenario(withNullTagTarget = false)
        val result = TraversalBuilder(engine, setOf(origin), huid).run {
            outgoing<TestEdge>(); nodes<TestNode>(); collectNodes<TestNode>().toList()
        }
        assertEquals(3, result.size, "keeps only the 3 TestNode targets")
        assertEquals(3, engine.nodeAtCalls.get(), "collectNodes materializes the 3 survivors; the type filter itself fetched nothing")
    }

    @Test fun `nodes filter falls back to fetch only for null-tag targets`() = runBlocking {
        val (engine, origin) = scenario(withNullTagTarget = true)
        val builder = TraversalBuilder(engine, setOf(origin), huid)
        builder.outgoing<TestEdge>()
        builder.nodes<TestNode>()
        // 4 TestNode targets survive (3 tagged + 1 null-tag). The type filter fetched exactly the 1
        // null-tag target (tagged ones classified in-memory); tagged OtherNodes were dropped fetch-free.
        assertEquals(1, engine.nodeAtCalls.get(), "only the null-tag target is fetched for the type check")
        assertEquals(4, builder.frontier.size, "3 tagged + 1 fallback TestNode survive")
    }

    // Part B: typed node hop (addNodeHop). Wrong-type targets are dropped fetch-free by tag; only the
    // matching-type survivors are fetched to run the predicate.
    @Test fun `typed node hop fetches only matching-type targets for the predicate`() = runBlocking {
        val (engine, origin) = scenario(withNullTagTarget = false)
        val builder = TraversalBuilder(engine, setOf(origin), huid)
        builder.outgoing<Uuid, TestEdge, TestNode> { true }
        assertEquals(3, builder.frontier.size, "keeps the 3 TestNode targets")
        assertEquals(3, engine.nodeAtCalls.get(), "2 OtherNode targets skipped fetch-free; 3 TestNodes fetched for the predicate")
    }

    // Part B: hasOutgoing<E,N>() (filterFrontierByEdgeType) — pure type check on hop targets, fully
    // fetch-free when the hop tags are present.
    @Test fun `hasOutgoing type filter reads hop tags with zero fetches`() = runBlocking {
        val a = huid.toNodeId(Uuid.random()); val b = huid.toNodeId(Uuid.random())
        val toPerson = huid.toNodeId(Uuid.random()); val toOther = huid.toNodeId(Uuid.random())
        val engine = CountingEngine(
            nodes = mapOf(toPerson to TestNode(id = Uuid.random(), name = "p"), toOther to OtherNode(id = Uuid.random())),
            outHops = mapOf(
                a to listOf(Hop(a, toPerson, "test_edge", null, 1)),  // A -> TestNode (tag 1)
                b to listOf(Hop(b, toOther, "test_edge", null, 2)),   // B -> OtherNode (tag 2)
            ),
        )
        val builder = TraversalBuilder(engine, setOf(a, b), huid)
        builder.hasOutgoing<TestEdge, TestNode>()
        assertEquals(setOf(a), builder.frontier, "only A has an outgoing edge to a TestNode")
        assertEquals(0, engine.nodeAtCalls.get(), "type gate decided by hop tag, no node fetched")
    }
}
