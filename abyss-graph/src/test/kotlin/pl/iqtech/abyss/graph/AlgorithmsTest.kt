package pl.iqtech.abyss.graph

import arrow.core.Either
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.Subgraph
import pl.iqtech.abyss.dsl.allReachable
import pl.iqtech.abyss.dsl.connectedComponents
import pl.iqtech.abyss.dsl.hasCycle
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AlgorithmsTest {

    @BeforeTest fun clear() {
        graphTestHz.getMap<Any, Any>("g-nodes").clear()
        graphTestHz.getMap<Any, Any>("g-edges").clear()
        graphTestHz.getMap<Any, Any>("g-edges-reverse").clear()
    }

    private fun putNode(name: String): TestNode {
        val node = TestNode(id = Uuid.random(), name = name)
        graphTestHz.getMap<NodeId, NodeLike<*>>("g-nodes")[huid.toNodeId(node.id)] = node
        return node
    }

    private fun putEdge(from: Uuid, to: Uuid) = runBlocking {
        graphTest.transaction { addEdge(TestEdge(fromId = from, toId = to, label = "$from→$to")) }
    }

    // ── exhaustReachable / allReachable ───────────────────────────────────────

    @Test fun `allReachable on isolated node returns just that node`() {
        runBlocking {
            val a = putNode("a")
            val result = graphTest.from(a.id) { allReachable { outgoing<TestEdge>() } }
            assertIs<Either.Right<Subgraph>>(result)
            val subgraph = result.value
            assertEquals(setOf(a.id), subgraph.nodes.map { it.id }.toSet())
            assertEquals(0, subgraph.edges.size)
        }
    }

    @Test fun `allReachable on linear chain collects all nodes and edges`() {
        // a → b → c
        runBlocking {
            val a = putNode("a"); val b = putNode("b"); val c = putNode("c")
            putEdge(a.id, b.id); putEdge(b.id, c.id)

            val result = graphTest.from(a.id) { allReachable { outgoing<TestEdge>() } }
            assertIs<Either.Right<Subgraph>>(result)
            val subgraph = result.value
            assertEquals(setOf(a.id, b.id, c.id), subgraph.nodes.map { it.id }.toSet())
            assertEquals(2, subgraph.edges.size)
        }
    }

    @Test fun `allReachable on diamond graph visits each node once`() {
        //     a
        //    / \
        //   b   c
        //    \ /
        //     d
        runBlocking {
            val a = putNode("a"); val b = putNode("b")
            val c = putNode("c"); val d = putNode("d")
            putEdge(a.id, b.id); putEdge(a.id, c.id)
            putEdge(b.id, d.id); putEdge(c.id, d.id)

            val result = graphTest.from(a.id) { allReachable { outgoing<TestEdge>() } }
            assertIs<Either.Right<Subgraph>>(result)
            val subgraph = result.value
            assertEquals(setOf(a.id, b.id, c.id, d.id), subgraph.nodes.map { it.id }.toSet())
            assertEquals(4, subgraph.edges.size)
        }
    }

    @Test fun `allReachable with cycle does not loop`() {
        // a → b → c → a (cycle)
        runBlocking {
            val a = putNode("a"); val b = putNode("b"); val c = putNode("c")
            putEdge(a.id, b.id); putEdge(b.id, c.id); putEdge(c.id, a.id)

            val result = graphTest.from(a.id) { allReachable { outgoing<TestEdge>() } }
            assertIs<Either.Right<Subgraph>>(result)
            val subgraph = result.value
            assertEquals(setOf(a.id, b.id, c.id), subgraph.nodes.map { it.id }.toSet())
            assertEquals(3, subgraph.edges.size)
        }
    }

    // ── detectCycle / hasCycle ────────────────────────────────────────────────

    @Test fun `hasCycle returns false for isolated node`() {
        runBlocking {
            val a = putNode("a")
            val result = graphTest.from(a.id) { hasCycle { outgoing<TestEdge>() } }
            assertIs<Either.Right<Boolean>>(result)
            assertFalse(result.value)
        }
    }

    @Test fun `hasCycle returns false for linear chain`() {
        // a → b → c
        runBlocking {
            val a = putNode("a"); val b = putNode("b"); val c = putNode("c")
            putEdge(a.id, b.id); putEdge(b.id, c.id)
            val result = graphTest.from(a.id) { hasCycle { outgoing<TestEdge>() } }
            assertIs<Either.Right<Boolean>>(result)
            assertFalse(result.value)
        }
    }

    @Test fun `hasCycle returns false for diamond (convergent paths, no back-edge)`() {
        //     a
        //    / \
        //   b   c
        //    \ /
        //     d
        runBlocking {
            val a = putNode("a"); val b = putNode("b")
            val c = putNode("c"); val d = putNode("d")
            putEdge(a.id, b.id); putEdge(a.id, c.id)
            putEdge(b.id, d.id); putEdge(c.id, d.id)
            val result = graphTest.from(a.id) { hasCycle { outgoing<TestEdge>() } }
            assertIs<Either.Right<Boolean>>(result)
            assertFalse(result.value)
        }
    }

    @Test fun `hasCycle returns true for self-loop`() {
        // a → a
        runBlocking {
            val a = putNode("a")
            putEdge(a.id, a.id)
            val result = graphTest.from(a.id) { hasCycle { outgoing<TestEdge>() } }
            assertIs<Either.Right<Boolean>>(result)
            assertTrue(result.value)
        }
    }

    @Test fun `hasCycle returns true for simple cycle`() {
        // a → b → c → a
        runBlocking {
            val a = putNode("a"); val b = putNode("b"); val c = putNode("c")
            putEdge(a.id, b.id); putEdge(b.id, c.id); putEdge(c.id, a.id)
            val result = graphTest.from(a.id) { hasCycle { outgoing<TestEdge>() } }
            assertIs<Either.Right<Boolean>>(result)
            assertTrue(result.value)
        }
    }

    @Test fun `hasCycle returns true for back-edge to intermediate node`() {
        // a → b → c → b (cycle between b and c only)
        runBlocking {
            val a = putNode("a"); val b = putNode("b"); val c = putNode("c")
            putEdge(a.id, b.id); putEdge(b.id, c.id); putEdge(c.id, b.id)
            val result = graphTest.from(a.id) { hasCycle { outgoing<TestEdge>() } }
            assertIs<Either.Right<Boolean>>(result)
            assertTrue(result.value)
        }
    }

    @Test fun `allReachable does not cross unreachable nodes`() {
        // a → b   c (isolated)
        runBlocking {
            val a = putNode("a"); val b = putNode("b"); putNode("c")
            putEdge(a.id, b.id)

            val result = graphTest.from(a.id) { allReachable { outgoing<TestEdge>() } }
            assertIs<Either.Right<Subgraph>>(result)
            assertEquals(setOf(a.id, b.id), result.value.nodes.map { it.id }.toSet())
        }
    }

    // ── connectedComponents ───────────────────────────────────────────────────

    @Test fun `connectedComponents with empty graph returns empty list`() {
        runBlocking {
            assertEquals(emptyList(), graphTest.connectedComponents())
        }
    }

    @Test fun `connectedComponents with single isolated node returns one component`() {
        runBlocking {
            val a = putNode("a")
            val components = graphTest.connectedComponents()
            assertEquals(1, components.size)
            assertEquals(setOf(a.id), components.first())
        }
    }

    @Test fun `connectedComponents treats edges as undirected`() {
        // a → b: both should be in one component regardless of direction
        runBlocking {
            val a = putNode("a"); val b = putNode("b")
            putEdge(a.id, b.id)
            val components = graphTest.connectedComponents()
            assertEquals(1, components.size)
            assertEquals(setOf(a.id, b.id), components.first())
        }
    }

    @Test fun `connectedComponents returns two components for disjoint pairs`() {
        // a → b   c → d
        runBlocking {
            val a = putNode("a"); val b = putNode("b")
            val c = putNode("c"); val d = putNode("d")
            putEdge(a.id, b.id); putEdge(c.id, d.id)

            val components = graphTest.connectedComponents()
            assertEquals(2, components.size)
            assertEquals(setOf(setOf(a.id, b.id), setOf(c.id, d.id)), components.toSet())
        }
    }

    @Test fun `connectedComponents merges disjoint pairs when bridge is added`() {
        // a → b   c → d, then add b → c
        runBlocking {
            val a = putNode("a"); val b = putNode("b")
            val c = putNode("c"); val d = putNode("d")
            putEdge(a.id, b.id); putEdge(c.id, d.id); putEdge(b.id, c.id)

            val components = graphTest.connectedComponents()
            assertEquals(1, components.size)
            assertEquals(setOf(a.id, b.id, c.id, d.id), components.first())
        }
    }

    @Test fun `connectedComponents handles cycle within a component`() {
        // a → b → c → a, plus isolated d
        runBlocking {
            val a = putNode("a"); val b = putNode("b")
            val c = putNode("c"); val d = putNode("d")
            putEdge(a.id, b.id); putEdge(b.id, c.id); putEdge(c.id, a.id)

            val components = graphTest.connectedComponents()
            assertEquals(2, components.size)
            assertEquals(setOf(setOf(a.id, b.id, c.id), setOf(d.id)), components.toSet())
        }
    }
}
