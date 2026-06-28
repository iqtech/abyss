package pl.iqtech.abyss.graph

import arrow.core.Either
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.Subgraph
import pl.iqtech.abyss.dsl.allReachable
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.store.api.NodeLike
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

class AlgorithmsTest {

    @BeforeTest fun clear() {
        graphTestHz.getMap<Any, Any>("g-nodes").clear()
        graphTestHz.getMap<Any, Any>("g-edges").clear()
        graphTestHz.getMap<Any, Any>("g-edges-reverse").clear()
    }

    private fun putNode(name: String): TestNode {
        val node = TestNode(id = Uuid.random(), name = name)
        graphTestHz.getMap<java.util.UUID, NodeLike>("g-nodes")[node.id.toJavaUuid()] = node
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
}
