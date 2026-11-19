package pl.iqtech.abyss.graph

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.TraversalScope
import pl.iqtech.abyss.dsl.walkIn
import pl.iqtech.abyss.dsl.walkOut
import pl.iqtech.abyss.graph.traversal.TraversalBuilder
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.SchemaKeyAdapter
import pl.iqtech.abyss.store.api.SchemaTag
import pl.iqtech.abyss.store.api.SchemaTagWidth
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// TODO 2.32: walkOut<E>() / walkIn<E>(). Plan: ai/flow/7. Done/typed-chain-walk/impl-plan.md (§Tests).
// Inputs break symmetry: odd chain length (7), non-head origins, unrelated TypedEdges in BOTH directions
// on every chain node (an OUT walk can't pass through IN entries by accident, and vice versa).
class ChainWalkTest {

    private fun schema(prefix: String, store: AbyssStoreLike? = null, eph: AbyssEphemeralStoreLike? = null): AbyssGraphSchema<Uuid> {
        listOf("$prefix-nodes", "$prefix-edges", "$prefix-edges-adjacency").forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        return AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "$prefix-nodes", "$prefix-edges",
            persistentStore = store, ephemeralStore = eph, module = graphTestModule)
    }

    // n[0..6] = n1..n7 linked by TestEdge, except links listed in `skip` (by index of the from-node).
    private suspend fun seed(g: AbyssGraphSchema<Uuid>, skip: Set<Int> = emptySet(), noise: Int = 3): List<Uuid> {
        val n = List(7) { Uuid.random() }
        check(g.transaction(checkIntegrity = false) {
            n.forEachIndexed { i, id -> addNode(TestNode(id, name = "n${i + 1}")) }
            n.zipWithNext().forEachIndexed { i, (a, b) -> if (i !in skip) addEdge(TestEdge(fromId = a, toId = b, label = "next")) }
            n.forEach { id -> repeat(noise) { addEdge(TypedEdge(fromId = id, toId = Uuid.random())); addEdge(TypedEdge(fromId = Uuid.random(), toId = id)) } }
        }.isRight())
        return n
    }

    private suspend fun Flow<NodeLike<*>>.ids() = toList().map { (it as TestNode).id }

    private suspend fun collectUntilError(flow: Flow<NodeLike<*>>, into: MutableList<Uuid>): Throwable? =
        try { flow.collect { into += (it as TestNode).id }; null } catch (e: Throwable) { e }

    @Test fun `walkOut emits the far nodes in chain order, origin excluded`() = runBlocking {
        val g = schema("cw1"); val n = seed(g)
        assertEquals(n.subList(2, 7), g.from(n[1]) { walkOut<TestEdge>() }.getOrNull()!!.ids())
    }

    @Test fun `walkIn emits in reverse chain order, origin excluded`() = runBlocking {
        val g = schema("cw2"); val n = seed(g)
        assertEquals(n.subList(0, 5).reversed(), g.from(n[5]) { walkIn<TestEdge>() }.getOrNull()!!.ids())
    }

    @Test fun `maxLength truncates after that many links`() = runBlocking {
        val g = schema("cw3"); val n = seed(g)
        assertEquals(n.subList(2, 5), g.from(n[1]) { walkOut<TestEdge>(maxLength = 3) }.getOrNull()!!.ids())
    }

    @Test fun `fork out of a node throws after emitting up to it`() = runBlocking {
        val g = schema("cw4"); val n = seed(g)
        // Fork at n5: off a window boundary (windows 1, 2 flushed after n2, n4; n5 sits alone in the window of 4),
        // so dropping the flush-before-throw loses n5 and this goes red.
        check(g.transaction(checkIntegrity = false) { addEdge(TestEdge(fromId = n[4], toId = Uuid.random(), label = "fork")) }.isRight())
        val seen = mutableListOf<Uuid>()
        val e = collectUntilError(g.from(n[0]) { walkOut<TestEdge>() }.getOrNull()!!, seen)
        assertIs<IllegalStateException>(e)
        assertTrue(e.message!!.contains("fork") && e.message!!.contains("test_edge") && e.message!!.contains("OUTGOING") &&
            e.message!!.contains(huid.toNodeId(n[4]).toString()), e.message)
        assertEquals(n.subList(1, 5), seen, "every node up to the fork is emitted before the throw")
    }

    @Test fun `fork into a node throws on walkIn`() = runBlocking {
        val g = schema("cw5"); val n = seed(g)
        // Fork into n3: off a window boundary, mirroring the OUT test.
        check(g.transaction(checkIntegrity = false) { addEdge(TestEdge(fromId = Uuid.random(), toId = n[2], label = "fork")) }.isRight())
        val seen = mutableListOf<Uuid>()
        val e = collectUntilError(g.from(n[6]) { walkIn<TestEdge>() }.getOrNull()!!, seen)
        assertIs<IllegalStateException>(e)
        assertTrue(e.message!!.contains("INCOMING"), e.message)
        assertEquals(listOf(n[5], n[4], n[3], n[2]), seen)
    }

    @Test fun `cycle throws after emitting the whole loop once`() = runBlocking {
        val g = schema("cw6"); val n = seed(g)
        check(g.transaction(checkIntegrity = false) { addEdge(TestEdge(fromId = n[6], toId = n[2], label = "loop")) }.isRight())
        val seen = mutableListOf<Uuid>()
        val e = collectUntilError(g.from(n[0]) { walkOut<TestEdge>() }.getOrNull()!!, seen)
        assertIs<IllegalStateException>(e)
        assertTrue(e.message!!.contains("cycle"), e.message)
        assertEquals(n.subList(1, 7), seen)
    }

    @Test fun `multi-origin frontier is rejected`() = runBlocking<Unit> {
        val g = schema("cw7"); val n = seed(g)
        val e = collectUntilError(g.from(setOf(n[0], n[1])) { walkOut<TestEdge>() }.getOrNull()!!, mutableListOf())
        assertIs<IllegalArgumentException>(e)
    }

    @Test fun `broken link is followed but not emitted`() = runBlocking {
        val g = schema("cw8"); val n = seed(g)
        graphTestHz.getMap<NodeId, Any>("cw8-nodes").delete(huid.toNodeId(n[3]))
        assertEquals(listOf(n[1], n[2], n[4], n[5], n[6]), g.from(n[0]) { walkOut<TestEdge>() }.getOrNull()!!.ids())
    }

    @Test fun `end of chain is an empty flow, not an error`() = runBlocking {
        val g = schema("cw9"); val n = seed(g)
        assertEquals(emptyList(), g.from(n[6]) { walkOut<TestEdge>() }.getOrNull()!!.ids())
        assertEquals(emptyList(), g.from(n[0]) { walkIn<TestEdge>() }.getOrNull()!!.ids())
    }

    @Test fun `cold chain heals from the store in both directions`() = runBlocking {
        val g = schema("cw10", store = PartialProbeStore()); val n = seed(g)
        // "Restart": edge values + adjacency gone, store intact. Nodes stay: PartialProbeStore.loadNode returns null.
        listOf("cw10-edges", "cw10-edges-adjacency").forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        assertEquals(n.subList(1, 7), g.from(n[0]) { walkOut<TestEdge>() }.getOrNull()!!.ids())
        graphTestHz.getMap<Any, Any>("cw10-edges-adjacency").clear()
        assertEquals(n.subList(0, 6).reversed(), g.from(n[6]) { walkIn<TestEdge>() }.getOrNull()!!.ids())
    }

    // n4 -> n5 is ephemeral (store-only, outgoing-only — TODO 1.13/1.27).
    private suspend fun ephemeralChain(prefix: String): Pair<AbyssGraphSchema<Uuid>, List<Uuid>> {
        val g = schema(prefix, eph = FakeEphemeralStore()); val n = seed(g, skip = setOf(3))
        check(g.ephemeral(60.seconds, checkIntegrity = false) { addEdge(TestEdge(fromId = n[3], toId = n[4], label = "eph")) }.isRight())
        return g to n
    }

    @Test fun `walkOut follows an ephemeral link only on includeEphemeral`() = runBlocking {
        val (g, n) = ephemeralChain("cw11")
        assertEquals(n.subList(1, 4), g.from(n[0]) { walkOut<TestEdge>() }.getOrNull()!!.ids())
        assertEquals(n.subList(1, 7), g.from(n[0]) { walkOut<TestEdge>(includeEphemeral = true) }.getOrNull()!!.ids())
    }

    @Test fun `persistent plus ephemeral link out of one node is a fork under includeEphemeral`() = runBlocking<Unit> {
        val (g, n) = ephemeralChain("cw12")
        check(g.transaction(checkIntegrity = false) { addEdge(TestEdge(fromId = n[3], toId = Uuid.random(), label = "persistent")) }.isRight())
        val e = collectUntilError(g.from(n[0]) { walkOut<TestEdge>(includeEphemeral = true) }.getOrNull()!!, mutableListOf())
        assertIs<IllegalStateException>(e)
    }

    @Test fun `walkIn cannot see an ephemeral link and ends there without error`() = runBlocking {
        val (g, n) = ephemeralChain("cw13")
        assertEquals(listOf(n[5], n[4]), g.from(n[6]) { walkIn<TestEdge>() }.getOrNull()!!.ids())
    }

    @Test fun `walk follows a cross-schema link in a container`() = runBlocking {
        listOf("cw-ms-nodes", "cw-ms-edges", "cw-ms-edges-adjacency").forEach { multiSchemaHz.getMap<Any, Any>(it).clear() }
        val c = HeterogeneousSchemaGraph(multiSchemaHz, SchemaTagWidth.BYTE, "cw-ms-nodes", "cw-ms-edges", allowCrossSchemaEdges = true, module = graphTestModule)
        val longS = c.register(SchemaTag(1L), LongKeyAdapter)
        val uuidS = c.register(SchemaTag(2L), UuidKeyAdapter)
        fun longNid(id: Long) = SchemaKeyAdapter(SchemaTag(1L), SchemaTagWidth.BYTE, LongKeyAdapter).toNodeId(id)
        fun uuidNid(id: Uuid) = SchemaKeyAdapter(SchemaTag(2L), SchemaTagWidth.BYTE, UuidKeyAdapter).toNodeId(id)
        val u = Uuid.random()
        check(longS.transaction { addNode(LongTestNode(1L)); addNode(LongTestNode(2L)) }.isRight())
        check(uuidS.transaction { addNode(TestNode(u, name = "mid")) }.isRight())
        check(c.addCrossEdge(CrossRefEdge(longNid(1L), uuidNid(u))).isRight())
        check(c.addCrossEdge(CrossRefEdge(uuidNid(u), longNid(2L))).isRight())

        val walked = longS.from(1L) { walkOut<CrossRefEdge>() }.getOrNull()!!.toList()
        assertEquals(listOf<Any?>(u, 2L), walked.map { it.id })
    }

    // CountingFakeEngine: one round trip per hop call and per nodesAt — the shape tests, no Hazelcast.
    private fun fakeChain(links: Int): Pair<CountingFakeEngine, NodeId> {
        val ids = List(links + 1) { huid.toNodeId(Uuid.random()) }
        return CountingFakeEngine(ids.zipWithNext().associate { (a, b) -> a to listOf(b) }, latencyMs = 0) to ids.first()
    }

    @Test fun `take(2) stops after a handful of round trips`() = runBlocking {
        val (engine, origin) = fakeChain(1_000)
        val got = TraversalScope(TraversalBuilder(engine, setOf(origin), UuidKeyAdapter)).walkOut<TestEdge>().take(2).toList()
        assertEquals(2, got.size)
        // Doubling window: hop, nodesAt(1), hop, hop, nodesAt(2) = 5. A fixed 128 window would cost 129.
        assertTrue(engine.roundTrips.get() <= 6, "round trips ${engine.roundTrips.get()}")
    }

    @Test fun `full walk batches node fetches`() = runBlocking {
        val links = 999
        val (engine, origin) = fakeChain(links)
        val got = TraversalScope(TraversalBuilder(engine, setOf(origin), UuidKeyAdapter)).walkOut<TestEdge>().toList()
        assertEquals(links, got.size)
        val hopCalls = links + 1                                          // every link, plus the empty read at the end
        val maxFetches = ceil(log2(128.0)).toInt() + ceil(links / 128.0).toInt() + 1
        assertTrue(engine.roundTrips.get() - hopCalls <= maxFetches, "nodesAt calls ${engine.roundTrips.get() - hopCalls} > $maxFetches")
    }

    // The permanent guard on the hop route (needValue = false): store-backed warm chain, 16 unrelated edges per
    // node per direction. needValue = true would add an edges-map op per link (OUT entrySet / IN resolveEdges).
    @Test fun `map-op guard - one adjacency read per link and no edge-value reads`() = runBlocking {
        val links = 257
        val chain = seedChain(graphTestHz, "cwg-nodes", "cwg-edges", links + 1, others = 16, loaded = true)
        for (out in listOf(true, false)) {
            val counter = MapOpCounter(graphTestHz)
            val g = AbyssGraphSchema(UuidKeyAdapter, counter.hz, "cwg-nodes", "cwg-edges", persistentStore = PartialProbeStore(), module = graphTestModule)
            val walked = (if (out) g.from(chain.first()) { walkOut<TestEdge>() } else g.from(chain.last()) { walkIn<TestEdge>() }).getOrNull()!!.toList()
            assertEquals(links, walked.size)
            val dir = if (out) "OUT" else "IN"
            assertEquals(0, counter.total("cwg-edges"), "$dir: edge-value reads on the walk: ${counter.snapshot()}")
            assertTrue(counter.total("cwg-edges-adjacency") <= links * 1.05, "$dir: adjacency ops ${counter.snapshot()}")
            assertTrue(counter.total("cwg-nodes") <= ceil(log2(128.0)) + ceil(links / 128.0) + 1, "$dir: node fetches ${counter.snapshot()}")
        }
    }
}
