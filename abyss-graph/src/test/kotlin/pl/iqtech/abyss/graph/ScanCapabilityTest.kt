package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.right
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreLike
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreTransactionLike
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.graph.serialization.AbyssJsonLinesCodec
import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.SchemaKeyAdapter
import pl.iqtech.abyss.store.api.SchemaTag
import pl.iqtech.abyss.store.api.SchemaTagWidth
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.uuid.Uuid

// TODO 1.23: admin/orphan-sweep scan capability. Exercised through the public container facade
// (HeterogeneousSchemaGraph) rather than AbyssSchemaWorker directly — the container is a pure 1:1
// delegation (HeterogeneousSchemaGraph.kt), so this covers the worker's channelFlow merge/fail-loudly
// logic and the container's unscoped-by-tag contract in one pass, matching how every other test in
// this file set exercises the worker (through a public facade, not the internal class directly).
private fun randomNodeId(): NodeId = huid.toNodeId(Uuid.random())

private class ScanFakeStore(
    private val nodeIds: List<NodeId> = emptyList(),
    private val edgePairs: List<Pair<NodeId, NodeId>> = emptyList(),
    private val nodesById: Map<NodeId, NodeLike<*>> = emptyMap(),
) : AbyssStoreLike {
    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> = Either.Right(nodesById[id] to null)
    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> = Either.Right(null to null)
    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> = Unit.right()
    override fun scanNodeIds(tag: String?, parallelism: Int): Flow<NodeId> = nodeIds.asFlow()
    override fun scanEdgeIds(parallelism: Int): Flow<Pair<NodeId, NodeId>> = edgePairs.asFlow()
}

private class ScanFakeEphemeralStore(private val nodeIds: List<NodeId> = emptyList()) : AbyssEphemeralStoreLike {
    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> = Either.Right(null to null)
    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> = Either.Right(null to null)
    override suspend fun transaction(block: suspend AbyssEphemeralStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> = Unit.right()
    override fun scanNodeIds(tag: String?, parallelism: Int): Flow<NodeId> = nodeIds.asFlow()
}

class ScanCapabilityTest {

    @Test fun `scanNodeIds merges persistent and ephemeral stores with no duplicates or drops`() = runBlocking {
        val persistentIds = List(5) { randomNodeId() }
        val ephemeralIds = List(3) { randomNodeId() }
        val g = HeterogeneousSchemaGraph(
            multiSchemaHz, SchemaTagWidth.BYTE, "scan1-nodes", "scan1-edges",
            persistentStore = ScanFakeStore(nodeIds = persistentIds),
            ephemeralStore = ScanFakeEphemeralStore(ephemeralIds),
            module = graphTestModule,
        )

        val found = g.scanNodeIds().toList()

        assertEquals((persistentIds + ephemeralIds).toSet(), found.toSet())
        assertEquals(persistentIds.size + ephemeralIds.size, found.size, "no duplicates expected")
    }

    @Test fun `scanNodeIds with no persistentStore still returns the ephemeral contribution untagged`() = runBlocking {
        val ephemeralIds = List(4) { randomNodeId() }
        val g = HeterogeneousSchemaGraph(
            multiSchemaHz, SchemaTagWidth.BYTE, "scan2-nodes", "scan2-edges",
            ephemeralStore = ScanFakeEphemeralStore(ephemeralIds),
            module = graphTestModule,
        )

        assertEquals(ephemeralIds.toSet(), g.scanNodeIds().toList().toSet())
    }

    @Test fun `scanNodeIds with a tag and no persistentStore fails loudly rather than silently dropping the filter`() = runBlocking {
        val g = HeterogeneousSchemaGraph(
            multiSchemaHz, SchemaTagWidth.BYTE, "scan3-nodes", "scan3-edges",
            ephemeralStore = ScanFakeEphemeralStore(emptyList()),
            module = graphTestModule,
        )

        assertFails { g.scanNodeIds(tag = "orphan").toList() }
        Unit
    }

    @Test fun `scanEdgeIds delegates to persistentStore only and is empty when none is configured`() = runBlocking {
        val a = randomNodeId()
        val b = randomNodeId()
        val withStore = HeterogeneousSchemaGraph(
            multiSchemaHz, SchemaTagWidth.BYTE, "scan4-nodes", "scan4-edges",
            persistentStore = ScanFakeStore(edgePairs = listOf(a to b)),
            module = graphTestModule,
        )
        assertEquals(listOf(a to b), withStore.scanEdgeIds().toList())

        val withoutStore = HeterogeneousSchemaGraph(multiSchemaHz, SchemaTagWidth.BYTE, "scan5-nodes", "scan5-edges", module = graphTestModule)
        assertEquals(emptyList(), withoutStore.scanEdgeIds().toList())
    }

    // Contrast with allNodeIdsRaw()/per-schema allNodeIds(): scanNodeIds is deliberately unscoped by
    // schema tag (cross-tenant admin sweep use case) — registering schemas on the container has no
    // bearing on what scanNodeIds returns, since it never touches tag-aware routing at all.
    @Test fun `scanNodeIds is unscoped by schema tag regardless of how many schemas are registered`() = runBlocking {
        val persistentIds = List(6) { randomNodeId() }
        val g = HeterogeneousSchemaGraph(
            multiSchemaHz, SchemaTagWidth.BYTE, "scan6-nodes", "scan6-edges",
            persistentStore = ScanFakeStore(nodeIds = persistentIds),
            ephemeralStore = ScanFakeEphemeralStore(emptyList()),
            module = graphTestModule,
        )
        g.register(SchemaTag(90L), LongKeyAdapter)
        g.register(SchemaTag(91L), UuidKeyAdapter)

        assertEquals(persistentIds.toSet(), g.scanNodeIds().toList().toSet())
    }

    // The actual point of wiring exportGraphLines to scanNodeIds (TODO 1.23): a node that was
    // written straight to the store and never went through this container's own cache (the
    // register()/transaction() path) — i.e. genuinely cold, not just evicted-and-reloadable — is
    // still recovered. allNodeIds() alone (Hazelcast-cache-only) would silently miss it.
    @Test fun `exportGraphLines recovers a node that exists only in the store, never warmed into this container's cache`() = runBlocking {
        val tag = SchemaTag(70L)
        val coldId = 99L
        val coldNodeId = SchemaKeyAdapter(tag, SchemaTagWidth.BYTE, LongKeyAdapter).toNodeId(coldId)
        val coldNode = LongTestNode(id = coldId, name = "cold")

        val g = HeterogeneousSchemaGraph(
            multiSchemaHz, SchemaTagWidth.BYTE, "scan7-nodes", "scan7-edges",
            persistentStore = ScanFakeStore(nodeIds = listOf(coldNodeId), nodesById = mapOf(coldNodeId to coldNode)),
            module = graphTestModule,
        )
        val schema = g.register(tag, LongKeyAdapter)
        val warmNode = LongTestNode(id = 1L, name = "warm")
        schema.transaction { addNode(warmNode) }

        val lines = schema.exportGraphLines(AbyssJsonLinesCodec(graphTestModule)).toList()

        assertTrue(lines.any { it.contains("\"name\":\"cold\"") }, "expected the store-only node to be exported: $lines")
        assertTrue(lines.any { it.contains("\"name\":\"warm\"") }, "expected the cache-warm node to still be exported: $lines")
    }
}
