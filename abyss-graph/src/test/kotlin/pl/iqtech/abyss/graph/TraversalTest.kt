package pl.iqtech.abyss.graph

import arrow.core.Either
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.Subgraph
import pl.iqtech.abyss.dsl.incoming
import pl.iqtech.abyss.dsl.nodes
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.dsl.reaches
import pl.iqtech.abyss.dsl.subgraph
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

class TraversalTest {

    @BeforeTest fun clear() {
        graphTestHz.getMap<Any, Any>("g-nodes").clear()
        graphTestHz.getMap<Any, Any>("g-edges").clear()
        graphTestHz.getMap<Any, Any>("g-edges-reverse").clear()
    }

    // helpers

    private fun putNode(name: String): TestNode {
        val node = TestNode(id = Uuid.random(), name = name)
        graphTestHz.getMap<java.util.UUID, NodeLike>("g-nodes")[node.id.toJavaUuid()] = node
        return node
    }

    private fun putEdge(from: Uuid, to: Uuid): TestEdge {
        val edge = TestEdge(fromId = from, toId = to, label = "$from→$to")
        runBlocking { graphTest.transaction { addEdge(edge) } }
        return edge
    }

    // ── collectNodes from starting node ──────────────────────────────────────

    @Test fun `from - no hops returns starting node`() {
        runBlocking {
            val a = putNode("a")
            val result = graphTest.from(a.id) { nodes<TestNode>().toList() }
            assertIs<Either.Right<List<TestNode>>>(result)
            assertEquals(listOf(a), result.value)
        }
    }

    // ── one hop outgoing ──────────────────────────────────────────────────────

    @Test fun `from - outgoing hop then collect`() {
        runBlocking {
            val a = putNode("a")
            val b = putNode("b")
            val c = putNode("c")
            putEdge(a.id, b.id)
            putEdge(a.id, c.id)

            val result = graphTest.from(a.id) {
                outgoing<TestEdge>()
                nodes<TestNode>().toList()
            }
            assertIs<Either.Right<List<TestNode>>>(result)
            assertEquals(setOf(b, c), result.value.toSet())
        }
    }

    // ── two hops ──────────────────────────────────────────────────────────────

    @Test fun `from - two hops chains frontier correctly`() {
        runBlocking {
            val a = putNode("a")
            val b = putNode("b")
            val c = putNode("c")
            putEdge(a.id, b.id)
            putEdge(b.id, c.id)

            val result = graphTest.from(a.id) {
                outgoing<TestEdge>()
                outgoing<TestEdge>()
                nodes<TestNode>().toList()
            }
            assertIs<Either.Right<List<TestNode>>>(result)
            assertEquals(listOf(c), result.value)
        }
    }

    // ── incoming hop ─────────────────────────────────────────────────────────

    @Test fun `from - incoming hop follows edges in reverse`() {
        runBlocking {
            val a = putNode("a")
            val b = putNode("b")
            putEdge(a.id, b.id)  // a → b

            val result = graphTest.from(b.id) {
                incoming<TestEdge>()
                nodes<TestNode>().toList()
            }
            assertIs<Either.Right<List<TestNode>>>(result)
            assertEquals(listOf(a), result.value)
        }
    }

    // ── edge predicate ────────────────────────────────────────────────────────

    @Test fun `from - edge predicate filters which edges are followed`() {
        runBlocking {
            val a = putNode("a")
            val b = putNode("b")
            val c = putNode("c")
            putEdge(a.id, b.id).also {
                graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(a.id, b.id, "test_edge")] =
                    it.copy(label = "keep")
            }
            putEdge(a.id, c.id).also {
                graphTestHz.getMap<Any, EdgeLike>("g-edges")[EdgeKey(a.id, c.id, "test_edge")] =
                    it.copy(label = "drop")
            }

            val result = graphTest.from(a.id) {
                outgoing<TestEdge>({ it.label == "keep" })
                nodes<TestNode>().toList()
            }
            assertIs<Either.Right<List<TestNode>>>(result)
            assertEquals(listOf(b), result.value)
        }
    }

    // ── reaches ───────────────────────────────────────────────────────────────

    @Test fun `reaches returns true when target reachable in one hop`() {
        runBlocking {
            val a = putNode("a")
            val b = putNode("b")
            putEdge(a.id, b.id)

            val result = graphTest.from(a.id) {
                reaches(b.id) { outgoing<TestEdge>() }
            }
            assertEquals(Either.Right(true), result)
        }
    }

    @Test fun `reaches returns true when target reachable in multiple hops`() {
        runBlocking {
            val a = putNode("a")
            val b = putNode("b")
            val c = putNode("c")
            putEdge(a.id, b.id)
            putEdge(b.id, c.id)

            val result = graphTest.from(a.id) {
                reaches(c.id) { outgoing<TestEdge>() }
            }
            assertEquals(Either.Right(true), result)
        }
    }

    @Test fun `reaches returns false when target not reachable`() {
        runBlocking {
            val a = putNode("a")
            val b = putNode("b")
            val unreachable = putNode("unreachable")
            putEdge(a.id, b.id)

            val result = graphTest.from(a.id) {
                reaches(unreachable.id) { outgoing<TestEdge>() }
            }
            assertEquals(Either.Right(false), result)
        }
    }

    // ── subgraph ──────────────────────────────────────────────────────────────

    @Test fun `subgraph with no hops returns start node and no edges`() {
        runBlocking {
            val a = putNode("a")
            val sg = assertIs<Either.Right<Subgraph>>(
                graphTest.from(a.id) { subgraph<TestNode>() }
            ).value
            assertEquals(listOf(a), sg.nodes)
            assertEquals(emptyList<EdgeLike>(), sg.edges)
        }
    }

    @Test fun `subgraph returns all nodes across two hops`() {
        runBlocking {
            val a = putNode("a")
            val b = putNode("b")
            val c = putNode("c")
            putEdge(a.id, b.id)
            putEdge(b.id, c.id)

            val sg = assertIs<Either.Right<Subgraph>>(graphTest.from(a.id) {
                outgoing<TestEdge>()
                outgoing<TestEdge>()
                subgraph<TestNode>()
            }).value
            assertEquals(setOf(a, b, c), sg.nodes.toSet())
        }
    }

    @Test fun `subgraph returns all traversed edges across two hops`() {
        runBlocking {
            val a = putNode("a")
            val b = putNode("b")
            val c = putNode("c")
            val ab = putEdge(a.id, b.id)
            val bc = putEdge(b.id, c.id)

            val sg = assertIs<Either.Right<Subgraph>>(graphTest.from(a.id) {
                outgoing<TestEdge>()
                outgoing<TestEdge>()
                subgraph<TestNode>()
            }).value
            assertEquals(setOf(ab.fromId to ab.toId, bc.fromId to bc.toId),
                sg.edges.map { it.fromId to it.toId }.toSet())
        }
    }

    @Test fun `reaches handles cycles without infinite loop`() {
        runBlocking {
            val a = putNode("a")
            val b = putNode("b")
            val target = putNode("target")
            putEdge(a.id, b.id)
            putEdge(b.id, a.id)  // cycle

            val result = graphTest.from(a.id) {
                reaches(target.id) { outgoing<TestEdge>() }
            }
            assertEquals(Either.Right(false), result)
        }
    }
}
