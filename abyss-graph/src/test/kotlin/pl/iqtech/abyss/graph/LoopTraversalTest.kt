package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.EdgeTraversalDirection
import pl.iqtech.abyss.dsl.Evaluation
import pl.iqtech.abyss.dsl.Path
import pl.iqtech.abyss.dsl.TraversalStrategy
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

class LoopTraversalTest {

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

    private fun putEdge(from: Uuid, to: Uuid): TestEdge {
        val edge = TestEdge(fromId = from, toId = to, label = "$from→$to")
        runBlocking { graphTest.transaction { addEdge(edge) } }
        return edge
    }

    private fun includeAll(path: Path, node: NodeLike) = Evaluation.INCLUDE_AND_PRUNE
    private fun followAll(path: Path, edge: EdgeLike) = true

    // ── basic path finding ────────────────────────────────────────────────────

    @Test fun `loop DFS emits path for single hop`() = runBlocking {
        val a = putNode("a"); val b = putNode("b")
        putEdge(a.id, b.id)
        val paths = graphTest.from(a.id) {
            loop(TraversalStrategy.DFS, edgeVisitor = ::followAll, nodeEvaluator = ::includeAll).toList()
        }
        val result = (paths as Either.Right).value
        assertEquals(1, result.size)
        assertEquals(listOf(a, b), result[0].nodes)
    }

    @Test fun `loop DFS emits both branches from a fork`() = runBlocking {
        val a = putNode("a"); val b = putNode("b"); val c = putNode("c")
        putEdge(a.id, b.id); putEdge(a.id, c.id)
        val paths = (graphTest.from(a.id) {
            loop(TraversalStrategy.DFS, edgeVisitor = ::followAll, nodeEvaluator = ::includeAll).toList()
        } as Either.Right).value
        assertEquals(2, paths.size)
        val terminals = paths.map { it.nodes.last() }.toSet()
        assertEquals(setOf(b, c), terminals)
    }

    @Test fun `loop DFS path contains correct edge`() = runBlocking {
        val a = putNode("a"); val b = putNode("b")
        val edge = putEdge(a.id, b.id)
        val paths = (graphTest.from(a.id) {
            loop(TraversalStrategy.DFS, edgeVisitor = ::followAll, nodeEvaluator = ::includeAll).toList()
        } as Either.Right).value
        assertEquals(1, paths[0].edges.size)
        assertEquals(edge.fromId to edge.toId, paths[0].edges[0].fromId to paths[0].edges[0].toId)
    }

    // ── EXCLUDE_AND_CONTINUE ──────────────────────────────────────────────────

    @Test fun `EXCLUDE_AND_CONTINUE skips intermediate node but continues traversal`() = runBlocking {
        val a = putNode("a"); val b = putNode("b"); val c = putNode("c")
        putEdge(a.id, b.id); putEdge(b.id, c.id)
        val paths = (graphTest.from(a.id) {
            loop(TraversalStrategy.DFS,
                edgeVisitor = ::followAll,
                nodeEvaluator = { _, node ->
                    if (node == b) Evaluation.EXCLUDE_AND_CONTINUE else Evaluation.INCLUDE_AND_PRUNE
                }
            ).toList()
        } as Either.Right).value
        // b excluded from path, c is terminal — path is [a, c] with no edge (b was a pass-through)
        assertEquals(1, paths.size)
        assertEquals(listOf(a, c), paths[0].nodes)
        assertTrue(paths[0].edges.isEmpty())
    }

    // ── EXCLUDE_AND_PRUNE ────────────────────────────────────────────────────

    @Test fun `EXCLUDE_AND_PRUNE stops branch without emitting`() = runBlocking {
        val a = putNode("a"); val b = putNode("b"); val c = putNode("c")
        putEdge(a.id, b.id); putEdge(b.id, c.id)
        val paths = (graphTest.from(a.id) {
            loop(TraversalStrategy.DFS,
                edgeVisitor = ::followAll,
                nodeEvaluator = { _, node ->
                    if (node == b) Evaluation.EXCLUDE_AND_PRUNE else Evaluation.INCLUDE_AND_PRUNE
                }
            ).toList()
        } as Either.Right).value
        assertTrue(paths.isEmpty())
    }

    // ── maxDepth ─────────────────────────────────────────────────────────────

    @Test fun `maxDepth limits path length`() = runBlocking {
        val a = putNode("a"); val b = putNode("b"); val c = putNode("c")
        putEdge(a.id, b.id); putEdge(b.id, c.id)
        val paths = (graphTest.from(a.id) {
            loop(TraversalStrategy.DFS, maxDepth = 1,
                edgeVisitor = ::followAll,
                nodeEvaluator = { _, _ -> Evaluation.INCLUDE_AND_CONTINUE }
            ).toList()
        } as Either.Right).value
        // INCLUDE_AND_CONTINUE at maxDepth=1 emits the path [a, b], c is unreachable
        assertEquals(1, paths.size)
        assertEquals(listOf(a, b), paths[0].nodes)
    }

    // ── cycle guard ───────────────────────────────────────────────────────────

    @Test fun `loop does not loop on cyclic graph`() = runBlocking {
        val a = putNode("a"); val b = putNode("b")
        putEdge(a.id, b.id); putEdge(b.id, a.id)
        val paths = (graphTest.from(a.id) {
            loop(TraversalStrategy.DFS, edgeVisitor = ::followAll, nodeEvaluator = ::includeAll).toList()
        } as Either.Right).value
        // b is visited, a is already in visited — no infinite loop
        assertEquals(1, paths.size)
        assertEquals(b, paths[0].nodes.last())
    }

    // ── BFS shortest paths first ──────────────────────────────────────────────

    @Test fun `loop BFS emits shorter paths before longer ones`() = runBlocking {
        // a → b → d  (2 hops)
        // a → c → d  (2 hops, same length — order within same depth is unspecified)
        // a → d      (1 hop — should come first)
        val a = putNode("a"); val b = putNode("b")
        val c = putNode("c"); val d = putNode("d")
        putEdge(a.id, d.id)   // direct path
        putEdge(a.id, b.id); putEdge(b.id, d.id)
        putEdge(a.id, c.id); putEdge(c.id, d.id)
        val paths = (graphTest.from(a.id) {
            loop(TraversalStrategy.BFS, edgeVisitor = ::followAll, nodeEvaluator = ::includeAll).toList()
        } as Either.Right).value
        // shortest path (depth 1) must come first
        assertEquals(1, paths[0].depth)
        assertEquals(d, paths[0].nodes.last())
    }

    // ── EdgeTraversalDirection ───────────────────────────────────────────────

    @Test fun `OUT direction does not follow incoming edges`() = runBlocking {
        val a = putNode("a"); val b = putNode("b")
        putEdge(b.id, a.id)  // b → a (incoming to a)
        val paths = (graphTest.from(a.id) {
            loop(direction = EdgeTraversalDirection.OUT,
                edgeVisitor = ::followAll, nodeEvaluator = ::includeAll).toList()
        } as Either.Right).value
        assertTrue(paths.isEmpty())
    }

    @Test fun `IN direction follows only incoming edges`() = runBlocking {
        val a = putNode("a"); val b = putNode("b"); val c = putNode("c")
        putEdge(b.id, a.id)  // b → a (incoming to a)
        putEdge(a.id, c.id)  // a → c (outgoing from a, should be ignored)
        val paths = (graphTest.from(a.id) {
            loop(direction = EdgeTraversalDirection.IN,
                edgeVisitor = ::followAll, nodeEvaluator = ::includeAll).toList()
        } as Either.Right).value
        assertEquals(1, paths.size)
        assertEquals(b, paths[0].nodes.last())
    }

    // ── toEitherList ─────────────────────────────────────────────────────────

    @Test fun `toEitherList interleaves nodes and edges in traversal order`() = runBlocking {
        val a = putNode("a"); val b = putNode("b")
        val edge = putEdge(a.id, b.id)
        val paths = (graphTest.from(a.id) {
            loop(TraversalStrategy.DFS, edgeVisitor = ::followAll, nodeEvaluator = ::includeAll).toList()
        } as Either.Right).value
        val list = paths[0].toEitherList()
        assertEquals(3, list.size)
        assertEquals(Right(a), list[0])
        assertEquals(Left(edge.fromId to edge.toId), (list[1] as Left).value.let { Left(it.fromId to it.toId) })
        assertEquals(Right(b), list[2])
    }
}
