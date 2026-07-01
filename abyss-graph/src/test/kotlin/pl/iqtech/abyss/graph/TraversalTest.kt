package pl.iqtech.abyss.graph

import arrow.core.Either
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.Subgraph
import pl.iqtech.abyss.dsl.collectNodes
import pl.iqtech.abyss.dsl.hasIncoming
import pl.iqtech.abyss.dsl.hasOutgoing
import pl.iqtech.abyss.dsl.hasTraversal
import pl.iqtech.abyss.dsl.incoming
import pl.iqtech.abyss.dsl.nodes
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.dsl.reaches
import pl.iqtech.abyss.dsl.subgraph
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

class TraversalTest {

    @BeforeTest fun clear() {
        graphTestHz.getMap<Any, Any>("g-nodes").clear()
        graphTestHz.getMap<Any, Any>("g-edges").clear()
        graphTestHz.getMap<Any, Any>("g-edges-reverse").clear()
    }

    // helpers

    private fun putNode(name: String): TestNode {
        val node = TestNode(id = Uuid.random(), name = name)
        graphTestHz.getMap<NodeId, NodeLike<*>>("g-nodes")[UuidKeyAdapter.toNodeId(node.id)] = node
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
            val result = graphTest.from(a.id) { nodes<TestNode>(); collectNodes<TestNode>().toList() }
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
                nodes<TestNode>()
                collectNodes<TestNode>().toList()
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
                nodes<TestNode>()
                collectNodes<TestNode>().toList()
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
                nodes<TestNode>()
                collectNodes<TestNode>().toList()
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
                val fromNid = UuidKeyAdapter.toNodeId(a.id)
                graphTestHz.getMap<Any, EdgeLike<*>>("g-edges")[EdgeKey(fromNid, UuidKeyAdapter.toNodeId(b.id), "test_edge", UuidKeyAdapter.partitionKey(fromNid))] =
                    it.copy(label = "keep")
            }
            putEdge(a.id, c.id).also {
                val fromNid = UuidKeyAdapter.toNodeId(a.id)
                graphTestHz.getMap<Any, EdgeLike<*>>("g-edges")[EdgeKey(fromNid, UuidKeyAdapter.toNodeId(c.id), "test_edge", UuidKeyAdapter.partitionKey(fromNid))] =
                    it.copy(label = "drop")
            }

            val result = graphTest.from(a.id) {
                outgoing<TestEdge>({ it.label == "keep" })
                nodes<TestNode>()
                collectNodes<TestNode>().toList()
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
            val sg = assertIs<Either.Right<Subgraph<Uuid>>>(
                graphTest.from(a.id) { subgraph<TestNode, Uuid>() }
            ).value
            assertEquals(listOf(a), sg.nodes)
            assertEquals(emptyList(), sg.edges)
        }
    }

    @Test fun `subgraph returns all nodes across two hops`() {
        runBlocking {
            val a = putNode("a")
            val b = putNode("b")
            val c = putNode("c")
            putEdge(a.id, b.id)
            putEdge(b.id, c.id)

            val sg = assertIs<Either.Right<Subgraph<Uuid>>>(graphTest.from(a.id) {
                outgoing<TestEdge>()
                outgoing<TestEdge>()
                subgraph<TestNode, Uuid>()
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

            val sg = assertIs<Either.Right<Subgraph<Uuid>>>(graphTest.from(a.id) {
                outgoing<TestEdge>()
                outgoing<TestEdge>()
                subgraph<TestNode, Uuid>()
            }).value
            assertEquals(setOf(ab.fromId to ab.toId, bc.fromId to bc.toId),
                sg.edges.map { it.fromId to it.toId }.toSet())
        }
    }

    // ── multi-type node filter (astronomers pattern) ──────────────────────────
    //
    // alice →[recent]→ bob, charlie    alice →[old]→ dave
    // bob, charlie, dave →[likes]→ astronomy (OtherNode)
    //
    // edge filter "recent" drops dave; node filter name≠"charlie" drops charlie;
    // subgraph<TestNode, Uuid>() collects intermediate TestNodes = {alice, bob}

    @Test fun `nodes predicate narrows frontier and subgraph collects surviving intermediate nodes`() {
        runBlocking {
            val alice   = putNode("alice")
            val bob     = putNode("bob")
            val charlie = putNode("charlie")
            val dave    = putNode("dave")
            val astronomy = OtherNode(id = Uuid.random())
            graphTest.transaction { addNode(astronomy) }

            graphTest.transaction {
                addEdge(TestEdge(fromId = alice.id,   toId = bob.id,       label = "recent"))
                addEdge(TestEdge(fromId = alice.id,   toId = charlie.id,   label = "recent"))
                addEdge(TestEdge(fromId = alice.id,   toId = dave.id,      label = "old"))
                addEdge(TestEdge(fromId = bob.id,     toId = astronomy.id, label = "likes"))
                addEdge(TestEdge(fromId = charlie.id, toId = astronomy.id, label = "likes"))
                addEdge(TestEdge(fromId = dave.id,    toId = astronomy.id, label = "likes"))
            }

            val sg = assertIs<Either.Right<Subgraph<Uuid>>>(graphTest.from(alice.id) {
                outgoing<TestEdge> { it.label == "recent" }  // drops dave
                nodes<TestNode> { it.name != "charlie" }      // drops charlie, removes from visited
                outgoing<TestEdge> { it.label == "likes" }    // bob → astronomy
                nodes<OtherNode>()                            // confirm interest type
                subgraph<TestNode, Uuid>()                          // intermediate TestNodes: alice + bob
            }).value

            val testNodes = sg.nodes.filterIsInstance<TestNode>().toSet()
            assertEquals(setOf(alice, bob), testNodes)
        }
    }

    // ── hasOutgoing / hasIncoming ─────────────────────────────────────────────

    @Test fun `hasOutgoing specific target filters frontier`() {
        runBlocking {
            val root   = putNode("root")
            val a      = putNode("a")
            val b      = putNode("b")
            val target = putNode("target")
            putEdge(root.id, a.id)
            putEdge(root.id, b.id)
            putEdge(a.id, target.id)   // only a has edge to target

            val result = graphTest.from(root.id) {
                outgoing<TestEdge>()
                hasOutgoing<TestEdge, Uuid>(target.id)
                collectNodes<TestNode>().toList()
            }
            assertIs<Either.Right<List<TestNode>>>(result)
            assertEquals(listOf(a), result.value)
        }
    }

    @Test fun `hasOutgoing type-based filters frontier`() {
        runBlocking {
            val root  = putNode("root")
            val a     = putNode("a")
            val b     = putNode("b")
            val other = OtherNode(id = Uuid.random())
            graphTest.transaction { addNode(other) }
            putEdge(root.id, a.id)
            putEdge(root.id, b.id)
            putEdge(a.id, other.id)   // only a has edge to an OtherNode

            val result = graphTest.from(root.id) {
                outgoing<TestEdge>()
                hasOutgoing<TestEdge, OtherNode>()
                collectNodes<TestNode>().toList()
            }
            assertIs<Either.Right<List<TestNode>>>(result)
            assertEquals(listOf(a), result.value)
        }
    }

    @Test fun `hasIncoming specific source filters frontier`() {
        runBlocking {
            val root   = putNode("root")
            val source = putNode("source")
            val a      = putNode("a")
            val b      = putNode("b")
            putEdge(root.id,   a.id)   // root → a, root → b (to build frontier)
            putEdge(root.id,   b.id)
            putEdge(source.id, a.id)   // only a has incoming from source

            val result = graphTest.from(root.id) {
                outgoing<TestEdge>()
                hasIncoming<TestEdge, Uuid>(source.id)
                collectNodes<TestNode>().toList()
            }
            assertIs<Either.Right<List<TestNode>>>(result)
            assertEquals(listOf(a), result.value)
        }
    }

    @Test fun `hasOutgoing conjunction - AND two independent edge conditions`() {
        runBlocking {
            val alice   = putNode("alice")
            val bob     = putNode("bob")
            val charlie = putNode("charlie")
            val newYork = putNode("NewYork")
            val astronomy = OtherNode(id = Uuid.random())
            graphTest.transaction { addNode(astronomy) }

            graphTest.transaction {
                addEdge(TestEdge(fromId = alice.id,   toId = bob.id,       label = "knows"))
                addEdge(TestEdge(fromId = alice.id,   toId = charlie.id,   label = "knows"))
                addEdge(TestEdge(fromId = bob.id,     toId = newYork.id,   label = "livesIn"))
                // charlie has no livesIn edge
                addEdge(TestEdge(fromId = bob.id,     toId = astronomy.id, label = "likes"))
                addEdge(TestEdge(fromId = charlie.id, toId = astronomy.id, label = "likes"))
            }

            val result = graphTest.from(alice.id) {
                outgoing<TestEdge>()                       // {bob, charlie}
                hasOutgoing<TestEdge, Uuid>(newYork.id)          // {bob} — charlie not in NY
                hasOutgoing<TestEdge, OtherNode>()         // {bob} — bob likes astronomy
                collectNodes<TestNode>().toList()
            }
            assertIs<Either.Right<List<TestNode>>>(result)
            assertEquals(listOf(bob), result.value)
        }
    }

    @Test fun `hasTraversal multi-hop filters frontier`() {
        runBlocking {
            val root   = putNode("root")
            val a      = putNode("a")
            val b      = putNode("b")
            val target = OtherNode(id = Uuid.random())
            graphTest.transaction { addNode(target) }
            putEdge(root.id, a.id)
            putEdge(root.id, b.id)
            putEdge(a.id, target.id)   // only a has a path to an OtherNode

            val result = graphTest.from(root.id) {
                outgoing<TestEdge>()
                hasTraversal { outgoing<TestEdge>(); nodes<OtherNode>() }
                collectNodes<TestNode>().toList()
            }
            assertIs<Either.Right<List<TestNode>>>(result)
            assertEquals(listOf(a), result.value)
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
