package pl.iqtech.abyss.graph

import arrow.core.Either
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.allReachable
import pl.iqtech.abyss.dsl.collectNodes
import pl.iqtech.abyss.dsl.hasCycle
import pl.iqtech.abyss.dsl.hasTraversal
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.dsl.reaches
import pl.iqtech.abyss.dsl.resolve
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

// Regression coverage for the TraversalScope facade (TODO 1.28): every DSL block callers write
// (from, hasTraversal, reaches/checkReaches, allReachable/exhaustReachable, hasCycle/detectCycle,
// pathTo) now receives a TraversalScope<ID> instead of the raw TraversalBuilderLike<ID> — these
// tests prove that swap didn't change traversal behavior. The complementary guarantee (that
// addHop/addNodeHop/filterFrontierBy*/flushFrontierNodes/flushHopEdges/countEdges/collectSubgraph
// are no longer reachable from a DSL block) is a compile-time property, not something a runtime
// test can assert — verified separately by a scratch file that must fail to compile.
class TraversalScopeTest {

    @BeforeTest fun clear() {
        graphTestHz.getMap<Any, Any>("g-nodes").clear()
        graphTestHz.getMap<Any, Any>("g-edges").clear()
        graphTestHz.getMap<Any, Any>("g-edges-adjacency").clear()
    }

    private fun putNode(name: String): TestNode {
        val node = TestNode(id = Uuid.random(), name = name)
        graphTestHz.getMap<NodeId, NodeLike<*>>("g-nodes")[huid.toNodeId(node.id)] = node
        return node
    }

    private fun putEdge(from: Uuid, to: Uuid): TestEdge {
        val edge = TestEdge(fromId = from, toId = to, label = "$from->$to")
        runBlocking { graphTest.transaction { addEdge(edge) } }
        return edge
    }

    @Test fun `from block sugar still resolves through TraversalScope`() = runBlocking {
        val a = putNode("a"); val b = putNode("b")
        putEdge(a.id, b.id)
        val result = (graphTest.from(a.id) {
            outgoing<TestEdge>()
            collectNodes<TestNode>().toList()
        } as Either.Right).value
        assertEquals(listOf(b), result)
    }

    @Test fun `hasTraversal filters the frontier via TraversalScope`() = runBlocking {
        val a = putNode("a"); val b = putNode("b"); val c = putNode("c")
        putEdge(a.id, b.id) // b has an outgoing edge, c doesn't
        putEdge(b.id, c.id)
        val survivors = (graphTest.from(setOf(a.id, c.id)) {
            hasTraversal { outgoing<TestEdge>() }
            collectNodes<TestNode>().toList()
        } as Either.Right).value
        assertEquals(listOf(a), survivors, "only a has an outgoing TestEdge; c is a dead end")
    }

    @Test fun `reaches (checkReaches) walks nested TraversalScope blocks`() = runBlocking {
        val a = putNode("a"); val b = putNode("b"); val c = putNode("c")
        putEdge(a.id, b.id); putEdge(b.id, c.id)
        val canReachC = (graphTest.from(a.id) {
            reaches(c.id) { outgoing<TestEdge>() }
        } as Either.Right).value
        assertTrue(canReachC)
        val cannotReachSelf = (graphTest.from(a.id) {
            reaches(a.id) { outgoing<TestEdge>() }
        } as Either.Right).value
        assertFalse(cannotReachSelf, "starting frontier doesn't count as reached")
    }

    @Test fun `pathTo returns the discovered path through TraversalScope`() = runBlocking {
        val a = putNode("a"); val b = putNode("b"); val c = putNode("c")
        putEdge(a.id, b.id); putEdge(b.id, c.id)
        val path = (graphTest.from(a.id) {
            pathTo(c.id) { outgoing<TestEdge>() }
        } as Either.Right).value
        assertEquals(listOf(a, b, c), path?.resolve<TestNode>())
    }

    @Test fun `allReachable (exhaustReachable) collects the whole reachable subgraph`() = runBlocking {
        val a = putNode("a"); val b = putNode("b"); val c = putNode("c")
        putEdge(a.id, b.id); putEdge(a.id, c.id)
        val subgraph = (graphTest.from(a.id) {
            allReachable { outgoing<TestEdge>() }
        } as Either.Right).value
        assertEquals(setOf(a, b, c), subgraph.resolve<TestNode>().toSet())
    }

    @Test fun `hasCycle (detectCycle) finds a cycle through TraversalScope`() = runBlocking {
        val a = putNode("a"); val b = putNode("b")
        putEdge(a.id, b.id); putEdge(b.id, a.id)
        val found = (graphTest.from(a.id) {
            hasCycle { outgoing<TestEdge>() }
        } as Either.Right).value
        assertTrue(found)
    }
}
