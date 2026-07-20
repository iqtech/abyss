package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pl.iqtech.abyss.dsl.collectNodes
import pl.iqtech.abyss.dsl.incoming
import pl.iqtech.abyss.dsl.node
import pl.iqtech.abyss.dsl.outEdges
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.dsl.removeCrossEdge
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreLike
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreTransactionLike
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.CrossSchemaEdge
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.HeaderlessMultiSchemaAdapter
import pl.iqtech.abyss.store.api.HeaderlessSchemaKeyAdapter
import pl.iqtech.abyss.store.api.IntKeyAdapter
import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.adapter
import pl.iqtech.abyss.store.api.MultiSchemaAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeKey
import pl.iqtech.abyss.store.api.NodeKeyKind
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.SchemaDescriptor
import pl.iqtech.abyss.store.api.SchemaKeyAdapter
import pl.iqtech.abyss.store.api.SchemaTag
import pl.iqtech.abyss.store.api.SchemaTagWidth
import pl.iqtech.abyss.store.api.StoredEdge
import pl.iqtech.abyss.store.api.TypeTag
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable @SerialName("cross_ref") @TypeTag(5)
data class CrossRefEdge(
    override val fromId: NodeId,
    override val toId: NodeId,
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
    val note: String = "",
) : EdgeLike<NodeId, NodeId>

private val LONG_TAG = SchemaTag(1L)
private val UUID_TAG = SchemaTag(2L)
private val LONG_TAG_B = SchemaTag(3L)

// Annotation-driven cross edge (O3): declared with real domain types, no manual NodeId construction —
// addCrossEdge resolves both endpoints from the annotation alone. Tags match newContainer()'s LONG_TAG/UUID_TAG.
@Serializable @SerialName("lives_in") @TypeTag(6)
@CrossSchemaEdge(
    fromTag = 2L, fromAdapter = UuidKeyAdapter::class,
    toTag = 1L, toAdapter = LongKeyAdapter::class,
)
data class LivesIn(
    override val fromId: Uuid,
    override val toId: Long,
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
) : EdgeLike<Uuid, Long>

// HomogeneousSchemaGraph fixtures: one ID type (Long) shared by every tag in that container.
@Serializable @SerialName("tenant_link") @TypeTag(7)
@CrossSchemaEdge(
    fromTag = 501L, fromAdapter = LongKeyAdapter::class,
    toTag = 502L, toAdapter = LongKeyAdapter::class,
)
data class TenantLink(
    override val fromId: Long,
    override val toId: Long,
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
) : EdgeLike<Long, Long>

@Serializable @SerialName("same_tenant_link") @TypeTag(8)
@CrossSchemaEdge(
    fromTag = 501L, fromAdapter = LongKeyAdapter::class,
    toTag = 501L, toAdapter = LongKeyAdapter::class,
)
data class SameTenantLink(
    override val fromId: Long,
    override val toId: Long,
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
) : EdgeLike<Long, Long>

// One HazelcastInstance, tag+native edge encoding, shared maps for all schemas. The registry must
// cover every tag any test registers (two Long schemas exercise fromTag disambiguation).
val multiSchemaHz by lazy {
    System.setProperty("hazelcast.logging.type", "none")
    Hazelcast.newHazelcastInstance(
        Config().setClusterName("graph-test-multi").registerAbyssSerializers(
            MultiSchemaAdapter(SchemaTagWidth.BYTE),
            graphTestModule
        )
    )
}

// HomogeneousSchemaGraph's headerless keys need their own dedicated instance (see
// HomogeneousSchemaTest.kt) — incompatible with multiSchemaHz's headered Compact adapter.
private val homogeneousCrossHz by lazy {
    System.setProperty("hazelcast.logging.type", "none")
    Hazelcast.newHazelcastInstance(
        Config().setClusterName("graph-test-homog-cross").registerAbyssSerializers(
            HeaderlessMultiSchemaAdapter(SchemaTagWidth.BYTE, NodeKeyKind.INT64),
            graphTestModule,
        )
    )
}

class MultiSchemaTest {

    // No tag→schema registry any more: the caller holds the typed facade `register` hands back.
    private class Ctr(val g: HeterogeneousSchemaGraph, val longS: AbyssGraphSchema<Long>, val uuidS: AbyssGraphSchema<Uuid>)

    private fun newContainer(allowCross: Boolean = false): Ctr {
        val g = HeterogeneousSchemaGraph(multiSchemaHz, SchemaTagWidth.BYTE, "ms-nodes", "ms-edges", allowCrossSchemaEdges = allowCross, module = graphTestModule)
        return Ctr(g, g.register(LONG_TAG, LongKeyAdapter), g.register(UUID_TAG, UuidKeyAdapter))
    }

    private fun longNodeId(id: Long) = SchemaKeyAdapter(LONG_TAG, SchemaTagWidth.BYTE, LongKeyAdapter).toNodeId(id)
    private fun uuidNodeId(id: Uuid) = SchemaKeyAdapter(UUID_TAG, SchemaTagWidth.BYTE, UuidKeyAdapter).toNodeId(id)

    @BeforeTest fun clear() {
        listOf("ms-nodes", "ms-edges", "ms-edges-adjacency", "ms-edges-cross", "ms-edges-cross-adjacency")
            .forEach { multiSchemaHz.getMap<Any, Any>(it).clear() }
    }

    @Test fun twoSchemasCoexistInSharedMaps() = runBlocking {
        val (longS, uuidS) = newContainer().let { it.longS to it.uuidS }
        val u1 = Uuid.random()

        longS.transaction { addNode(LongTestNode(1L, name = "long-one")) }
        uuidS.transaction { addNode(TestNode(u1, name = "uuid-one")) }

        assertEquals("long-one", longS.node<LongTestNode>(1L).getOrNull()?.name)
        assertEquals("uuid-one", uuidS.node<TestNode>(u1).getOrNull()?.name)

        // Both live in the same shared nodes map, distinguished only by their tag prefix.
        assertEquals(2, multiSchemaHz.getMap<Any, Any>("ms-nodes").size)
    }

    @Test fun intraSchemaEdgesStayScoped() = runBlocking {
        val (longS, uuidS) = newContainer().let { it.longS to it.uuidS }
        val ua = Uuid.random(); val ub = Uuid.random()

        longS.transaction {
            addNode(LongTestNode(1L)); addNode(LongTestNode(2L))
            addEdge(LongTestEdge(1L, 2L))
        }
        uuidS.transaction {
            addNode(TestNode(ua, name = "a")); addNode(TestNode(ub, name = "b"))
            addEdge(TestEdge(ua, ub, label = "x"))
        }

        val longOut = longS.outEdges<LongTestEdge>(1L).toList()
        val uuidOut = uuidS.outEdges<TestEdge>(ua).toList()
        assertEquals(1, longOut.size)
        assertEquals(2L, longOut.single().toId)
        assertEquals(1, uuidOut.size)
        assertEquals(ub, uuidOut.single().toId)
    }

    @Test fun resolveSchemaRoutesByTagPrefix() = runBlocking {
        val c = newContainer()
        val u = Uuid.random()
        c.longS.transaction { addNode(LongTestNode(42L, name = "L")) }
        c.uuidS.transaction { addNode(TestNode(u, name = "U")) }
        val longNid = longNodeId(42L)
        val uuidNid = uuidNodeId(u)

        // No registry: the container routes each self-describing NodeId to the right schema's data,
        // and SchemaDescriptor recovers the routing (tag) from the key bytes alone.
        assertEquals("L", (c.g.nodeAt(longNid) as LongTestNode).name)
        assertEquals("U", (c.g.nodeAt(uuidNid) as TestNode).name)
        assertEquals(LONG_TAG, SchemaDescriptor.of(longNid).tag)
        assertEquals(UUID_TAG, SchemaDescriptor.of(uuidNid).tag)
    }

    @Test fun crossEdgeReachableAsOrdinaryHopAcrossSchemas() = runBlocking {
        val c = newContainer(allowCross = true)
        val g = c.g; val longS = c.longS; val uuidS = c.uuidS
        val u = Uuid.random()

        longS.transaction { addNode(LongTestNode(1L)) }
        uuidS.transaction { addNode(TestNode(u, name = "target")) }
        assertTrue(g.addCrossEdge(CrossRefEdge(longNodeId(1L), uuidNodeId(u), note = "link")).isRight())

        // outgoing<CrossRefEdge>() is an ordinary hop that lands in the Uuid schema.
        val reached = longS.from(1L) { outgoing<CrossRefEdge>(); collectNodes<TestNode>().toList() }.getOrNull()!!
        assertEquals(setOf("target"), reached.map { it.name }.toSet())

        // incoming<CrossRefEdge>() from the Uuid side reaches back into the Long schema.
        val back = uuidS.from(u) { incoming<CrossRefEdge>(); collectNodes<LongTestNode>().toList() }.getOrNull()!!
        assertEquals(setOf(1L), back.map { it.id }.toSet())

        // The cross-edge (type cross_ref) does not appear in a typed intra query for long_test_edge.
        assertEquals(0, longS.outEdges<LongTestEdge>(1L).toList().size)
    }

    @Test fun intraThenCrossHopInOneExpression() = runBlocking {
        val c = newContainer(allowCross = true)
        val g = c.g; val longS = c.longS; val uuidS = c.uuidS
        val a = Uuid.random(); val b = Uuid.random()

        uuidS.transaction { addNode(TestNode(a, name = "a")); addNode(TestNode(b, name = "b")); addEdge(TestEdge(a, b, label = "x")) }
        longS.transaction { addNode(LongTestNode(7L)) }
        g.addCrossEdge(CrossRefEdge(uuidNodeId(b), longNodeId(7L)))

        // One expression: intra Uuid hop (a→b), then cross hop (b→long 7), collect in the Long schema.
        val reached = uuidS.from(a) {
            outgoing<TestEdge>(); outgoing<CrossRefEdge>(); collectNodes<LongTestNode>().toList()
        }.getOrNull()!!
        assertEquals(setOf(7L), reached.map { it.id }.toSet())
    }

    @Test fun integrityRejectsMissingEndpoint() = runBlocking {
        val c = newContainer(allowCross = true)
        c.longS.transaction { addNode(LongTestNode(1L)) }
        // toId points at a Uuid node that was never created.
        val result = c.g.addCrossEdge(CrossRefEdge(longNodeId(1L), uuidNodeId(Uuid.random())))
        assertTrue(result.isLeft())
    }

    @Test fun crossEdgesDisabledByDefault() = runBlocking {
        val c = newContainer() // allowCrossSchemaEdges = false
        c.longS.transaction { addNode(LongTestNode(1L)) }
        c.uuidS.transaction { addNode(TestNode(Uuid.random(), name = "t")) }
        val result = c.g.addCrossEdge(CrossRefEdge(longNodeId(1L), uuidNodeId(Uuid.random())))
        assertTrue(result.isLeft())
        // No cross edge was created, so a cross hop yields nothing.
        val reached = c.longS.from(1L) { outgoing<CrossRefEdge>(); collectNodes<TestNode>().toList() }.getOrNull()!!
        assertEquals(emptyList(), reached)
    }

    // --- @CrossSchemaEdge annotation + transaction-composable addCrossEdge -------------------------

    @Test fun crossSchemaEndpointsMatchHandBuiltAdapters() {
        val u = Uuid.random()
        val (fromNid, toNid) = crossSchemaEndpoints(LivesIn(u, 7L), SchemaTagWidth.BYTE, headerless = false)
        assertEquals(SchemaKeyAdapter(UUID_TAG, SchemaTagWidth.BYTE, UuidKeyAdapter).toNodeId(u), fromNid)
        assertEquals(SchemaKeyAdapter(LONG_TAG, SchemaTagWidth.BYTE, LongKeyAdapter).toNodeId(7L), toNid)
    }

    @Test fun addCrossEdgeComposesWithAddNodeInOnePerSchemaTransaction() = runBlocking {
        val c = newContainer(allowCross = true)
        c.longS.transaction { addNode(LongTestNode(1L)) }
        val u = Uuid.random()

        // One atomic transaction on the Uuid schema: a brand-new node plus the cross edge to it.
        val result = c.uuidS.transaction {
            addNode(TestNode(u, name = "new-uuid-node"))
            addCrossEdge(LivesIn(u, 1L))
        }
        assertTrue(result.isRight())

        assertEquals("new-uuid-node", c.uuidS.node<TestNode>(u).getOrNull()?.name)
        val reached = c.uuidS.from(u) { outgoing<LivesIn>(); collectNodes<LongTestNode>().toList() }.getOrNull()!!
        assertEquals(setOf(1L), reached.map { it.id }.toSet())
    }

    @Test fun `transaction with addNode and addCrossEdge is all-or-nothing on store failure`() = runBlocking {
        val store = RecordingStore()
        val g = HeterogeneousSchemaGraph(multiSchemaHz, SchemaTagWidth.BYTE, "cx7-nodes", "cx7-edges", allowCrossSchemaEdges = true, persistentStore = store, module = graphTestModule)
        val longS = g.register(LONG_TAG, LongKeyAdapter)
        val uuidS = g.register(UUID_TAG, UuidKeyAdapter)
        try {
            longS.transaction { addNode(LongTestNode(1L)) }
            val u = Uuid.random()

            store.failTx = true
            val result = uuidS.transaction {
                addNode(TestNode(u, name = "frank"))
                addCrossEdge(LivesIn(u, 1L))
            }
            assertIs<Either.Left<AbyssError>>(result)

            // Neither the node nor the cross edge landed — all-or-nothing.
            assertTrue(uuidS.node<TestNode>(u).isLeft())
            val reached = longS.from(1L) { incoming<LivesIn>(); collectNodes<TestNode>().toList() }.getOrNull()!!
            assertEquals(emptyList(), reached)
        } finally {
            listOf("cx7-nodes", "cx7-edges", "cx7-edges-adjacency").forEach { multiSchemaHz.getMap<Any, Any>(it).clear() }
        }
    }

    @Test fun removeCrossEdgeReifiedInsideTransaction() = runBlocking {
        val c = newContainer(allowCross = true)
        val u = Uuid.random()
        c.longS.transaction { addNode(LongTestNode(1L)) }
        c.uuidS.transaction { addNode(TestNode(u, name = "g")); addCrossEdge(LivesIn(u, 1L)) }

        val before = c.uuidS.from(u) { outgoing<LivesIn>(); collectNodes<LongTestNode>().toList() }.getOrNull()!!
        assertEquals(setOf(1L), before.map { it.id }.toSet())

        val result = c.uuidS.transaction { removeCrossEdge<LivesIn>(u, 1L) }
        assertTrue(result.isRight())

        val after = c.uuidS.from(u) { outgoing<LivesIn>(); collectNodes<LongTestNode>().toList() }.getOrNull()!!
        assertEquals(emptyList(), after)
    }

    // Cascade-delete used to filter cascaded edges by sameSchema(), silently excluding cross-schema
    // edges and leaving them dangling after their endpoint was removed. Verified via outAt directly
    // (not a node-resolving traversal): once the deleted node is gone, collectNodes<N>() can't
    // materialize it whether the edge is dangling or properly cascaded, so it can't distinguish the
    // two — it would pass either way and prove nothing.

    @Test fun removeNodeCascadesCrossSchemaEdgeWhenFromNodeDeleted() = runBlocking {
        val c = newContainer(allowCross = true)
        val u = Uuid.random()
        c.longS.transaction { addNode(LongTestNode(1L)) }
        c.uuidS.transaction { addNode(TestNode(u, name = "m")); addCrossEdge(LivesIn(u, 1L)) }

        assertEquals(1, c.g.outAt(uuidNodeId(u), "lives_in", needValue = false).size)

        c.uuidS.transaction { removeNode(u) }

        assertEquals(0, c.g.outAt(uuidNodeId(u), "lives_in", needValue = false).size)
    }

    @Test fun removeNodeCascadesCrossSchemaEdgeWhenToNodeDeleted() = runBlocking {
        val c = newContainer(allowCross = true)
        val u = Uuid.random()
        c.uuidS.transaction { addNode(TestNode(u, name = "n")) }
        c.longS.transaction { addNode(LongTestNode(1L)) }
        c.uuidS.transaction { addCrossEdge(LivesIn(u, 1L)) }

        assertEquals(1, c.g.outAt(uuidNodeId(u), "lives_in", needValue = false).size)

        // Delete the TARGET this time — exercises the adjacency-index-driven (incoming) cascade branch.
        c.longS.transaction { removeNode(1L) }

        assertEquals(0, c.g.outAt(uuidNodeId(u), "lives_in", needValue = false).size)
    }

    @Test fun containerLevelTransactionAddsCrossEdgeAtomically() = runBlocking {
        val c = newContainer(allowCross = true)
        c.longS.transaction { addNode(LongTestNode(1L)) }
        val u = Uuid.random()
        c.uuidS.transaction { addNode(TestNode(u, name = "h")) }

        val result = c.g.transaction { addCrossEdge(LivesIn(u, 1L)) }
        assertTrue(result.isRight())
        val reached = c.uuidS.from(u) { outgoing<LivesIn>(); collectNodes<LongTestNode>().toList() }.getOrNull()!!
        assertEquals(setOf(1L), reached.map { it.id }.toSet())
    }

    @Test fun containerLevelTransactionRejectsUnregisteredTag() = runBlocking {
        // A container that never registered the Long tag LivesIn's annotation points at.
        val g = HeterogeneousSchemaGraph(multiSchemaHz, SchemaTagWidth.BYTE, "cx8-nodes", "cx8-edges", allowCrossSchemaEdges = true, module = graphTestModule)
        g.register(UUID_TAG, UuidKeyAdapter)
        try {
            val result = g.transaction { addCrossEdge(LivesIn(Uuid.random(), 1L)) }
            assertTrue(result.isLeft())
        } finally {
            listOf("cx8-nodes", "cx8-edges", "cx8-edges-adjacency").forEach { multiSchemaHz.getMap<Any, Any>(it).clear() }
        }
    }

    @Test fun containerLevelTransactionRejectsWhenCrossEdgesDisabled() = runBlocking {
        val c = newContainer() // allowCrossSchemaEdges = false
        c.longS.transaction { addNode(LongTestNode(1L)) }
        val u = Uuid.random()
        c.uuidS.transaction { addNode(TestNode(u, name = "i")) }

        val result = c.g.transaction { addCrossEdge(LivesIn(u, 1L)) }
        assertTrue(result.isLeft())
    }

    // --- container.transaction { on(schema)... } — multi-schema node/edge ops plus cross edges, all
    // committed atomically through the same worker.transaction as the cross-edge-only case above. ---

    @Test fun containerLevelTransactionSpansMultipleSchemasWithOnAtomically() = runBlocking {
        val c = newContainer(allowCross = true)
        val u = Uuid.random()

        val result = c.g.transaction {
            on(c.longS).addNode(LongTestNode(1L, name = "L"))
            on(c.uuidS).addNode(TestNode(u, name = "U"))
            addCrossEdge(LivesIn(u, 1L))
        }
        assertTrue(result.isRight())

        assertEquals("L", c.longS.node<LongTestNode>(1L).getOrNull()?.name)
        assertEquals("U", c.uuidS.node<TestNode>(u).getOrNull()?.name)
        val reached = c.uuidS.from(u) { outgoing<LivesIn>(); collectNodes<LongTestNode>().toList() }.getOrNull()!!
        assertEquals(setOf(1L), reached.map { it.id }.toSet())
    }

    @Test fun `container-level on(schema) writes are all-or-nothing on store failure`() = runBlocking {
        val store = RecordingStore()
        val g = HeterogeneousSchemaGraph(multiSchemaHz, SchemaTagWidth.BYTE, "cx9-nodes", "cx9-edges", allowCrossSchemaEdges = true, persistentStore = store, module = graphTestModule)
        val longS = g.register(LONG_TAG, LongKeyAdapter)
        val uuidS = g.register(UUID_TAG, UuidKeyAdapter)
        try {
            val u = Uuid.random()
            store.failTx = true
            val result = g.transaction {
                on(longS).addNode(LongTestNode(1L))
                on(uuidS).addNode(TestNode(u, name = "frank"))
                addCrossEdge(LivesIn(u, 1L))
            }
            assertIs<Either.Left<AbyssError>>(result)

            assertTrue(longS.node<LongTestNode>(1L).isLeft())
            assertTrue(uuidS.node<TestNode>(u).isLeft())
        } finally {
            listOf("cx9-nodes", "cx9-edges", "cx9-edges-adjacency").forEach { multiSchemaHz.getMap<Any, Any>(it).clear() }
        }
    }

    @Test fun containerLevelTransactionOnRejectsSchemaFromDifferentContainer() = runBlocking {
        val c = newContainer(allowCross = true)
        val other = HeterogeneousSchemaGraph(multiSchemaHz, SchemaTagWidth.BYTE, "cx10-nodes", "cx10-edges", allowCrossSchemaEdges = true, module = graphTestModule)
        val foreignLongS = other.register(LONG_TAG_B, LongKeyAdapter)
        try {
            val result = c.g.transaction { on(foreignLongS).addNode(LongTestNode(1L)) }
            assertTrue(result.isLeft())
        } finally {
            listOf("cx10-nodes", "cx10-edges", "cx10-edges-adjacency").forEach { multiSchemaHz.getMap<Any, Any>(it).clear() }
        }
    }

    @Test fun containerLevelTransactionAllowsMultiSchemaWritesWithoutCrossEdgeWhenDisabled() = runBlocking {
        val c = newContainer() // allowCrossSchemaEdges = false
        val u = Uuid.random()

        // No addCrossEdge in this block — spanning two schemas' own node ops shouldn't need the
        // cross-edge gate at all.
        val result = c.g.transaction {
            on(c.longS).addNode(LongTestNode(1L))
            on(c.uuidS).addNode(TestNode(u, name = "j"))
        }
        assertTrue(result.isRight())
        assertEquals(1L, c.longS.node<LongTestNode>(1L).getOrNull()?.id)
        assertEquals(u, c.uuidS.node<TestNode>(u).getOrNull()?.id)
    }

    // HomogeneousSchemaGraph had zero cross-edge test coverage before this change — its gating logic
    // (same-tag always allowed; different-tag needs allowCrossSchemaEdges, only when checkIntegrity)
    // differs from HeterogeneousSchemaGraph's registeredTags check, so it needs its own cases.

    @Test fun homogeneousSameTagCrossEdgeAlwaysAllowed() = runBlocking {
        val g = HomogeneousSchemaGraph(homogeneousCrossHz, SchemaTagWidth.BYTE, LongKeyAdapter, "hg1-nodes", "hg1-edges", module = graphTestModule)
        val s = g.forTag(SchemaTag(501L))
        try {
            s.transaction { addNode(LongTestNode(1L)); addNode(LongTestNode(2L)) }
            // allowCrossSchemaEdges defaults to false — a same-tag "cross" edge must still succeed.
            val result = s.transaction { addCrossEdge(SameTenantLink(1L, 2L)) }
            assertTrue(result.isRight())
            val reached = s.from(1L) { outgoing<SameTenantLink>(); collectNodes<LongTestNode>().toList() }.getOrNull()!!
            assertEquals(setOf(2L), reached.map { it.id }.toSet())
        } finally {
            listOf("hg1-nodes", "hg1-edges", "hg1-edges-adjacency").forEach { homogeneousCrossHz.getMap<Any, Any>(it).clear() }
        }
    }

    @Test fun homogeneousDifferentTagCrossEdgeNeedsAllowFlag() = runBlocking {
        val g = HomogeneousSchemaGraph(homogeneousCrossHz, SchemaTagWidth.BYTE, LongKeyAdapter, "hg2-nodes", "hg2-edges", allowCrossSchemaEdges = false, module = graphTestModule)
        val a = g.forTag(SchemaTag(501L))
        val b = g.forTag(SchemaTag(502L))
        try {
            a.transaction { addNode(LongTestNode(1L)) }
            b.transaction { addNode(LongTestNode(2L)) }

            val rejected = a.transaction { addCrossEdge(TenantLink(1L, 2L)) }
            assertTrue(rejected.isLeft())
        } finally {
            listOf("hg2-nodes", "hg2-edges", "hg2-edges-adjacency").forEach { homogeneousCrossHz.getMap<Any, Any>(it).clear() }
        }
    }

    @Test fun homogeneousDifferentTagCrossEdgeAllowedWithFlag() = runBlocking {
        val g = HomogeneousSchemaGraph(homogeneousCrossHz, SchemaTagWidth.BYTE, LongKeyAdapter, "hg3-nodes", "hg3-edges", allowCrossSchemaEdges = true, module = graphTestModule)
        val a = g.forTag(SchemaTag(501L))
        val b = g.forTag(SchemaTag(502L))
        try {
            a.transaction { addNode(LongTestNode(1L)) }
            b.transaction { addNode(LongTestNode(2L)) }

            // Container-level transaction{} this time, not the per-schema one.
            val result = g.transaction { addCrossEdge(TenantLink(1L, 2L)) }
            assertTrue(result.isRight())
            val reached = a.from(1L) { outgoing<TenantLink>(); collectNodes<LongTestNode>().toList() }.getOrNull()!!
            assertEquals(setOf(2L), reached.map { it.id }.toSet())
        } finally {
            listOf("hg3-nodes", "hg3-edges", "hg3-edges-adjacency").forEach { homogeneousCrossHz.getMap<Any, Any>(it).clear() }
        }
    }

    @Test fun homogeneousRemoveNodeCascadesDifferentTagCrossEdge() = runBlocking {
        val g = HomogeneousSchemaGraph(homogeneousCrossHz, SchemaTagWidth.BYTE, LongKeyAdapter, "hg4-nodes", "hg4-edges", allowCrossSchemaEdges = true, module = graphTestModule)
        val a = g.forTag(SchemaTag(501L))
        val b = g.forTag(SchemaTag(502L))
        try {
            a.transaction { addNode(LongTestNode(1L)) }
            b.transaction { addNode(LongTestNode(2L)) }
            a.transaction { addCrossEdge(TenantLink(1L, 2L)) }

            val fromNid = HeaderlessSchemaKeyAdapter(SchemaTag(501L), SchemaTagWidth.BYTE, LongKeyAdapter).toNodeId(1L)
            assertEquals(1, g.outAt(fromNid, "tenant_link", needValue = false).size)

            b.transaction { removeNode(2L) }

            assertEquals(0, g.outAt(fromNid, "tenant_link", needValue = false).size)
        } finally {
            listOf("hg4-nodes", "hg4-edges", "hg4-edges-adjacency").forEach { homogeneousCrossHz.getMap<Any, Any>(it).clear() }
        }
    }

    // Regression coverage for lifting 1.12's cache-only limitation (TODO 4.5): AbyssStoreLike is
    // already NodeId-keyed/untyped, so a cross-schema edge persists exactly like an ordinary one.

    @Test fun `addCrossEdge persists through the persistent store`() = runBlocking {
        val store = RecordingStore()
        val g = HeterogeneousSchemaGraph(multiSchemaHz, SchemaTagWidth.BYTE, "cx-nodes", "cx-edges", allowCrossSchemaEdges = true, persistentStore = store, module = graphTestModule)
        val longS = g.register(SchemaTag(201L), LongKeyAdapter)
        val uuidS = g.register(SchemaTag(202L), UuidKeyAdapter)
        try {
            longS.transaction { addNode(LongTestNode(1L)) }
            val u = Uuid.random()
            uuidS.transaction { addNode(TestNode(id = u, name = "alice")) }

            val fromNid = SchemaKeyAdapter(SchemaTag(201L), SchemaTagWidth.BYTE, LongKeyAdapter).toNodeId(1L)
            val toNid = SchemaKeyAdapter(SchemaTag(202L), SchemaTagWidth.BYTE, UuidKeyAdapter).toNodeId(u)
            val edge = CrossRefEdge(fromNid, toNid, note = "x")
            assertTrue(g.addCrossEdge(edge).isRight())

            assertEquals(listOf(Triple(fromNid, toNid, edge as EdgeLike<*, *>)), store.savedEdges)

            val reached = longS.from(1L) { outgoing<CrossRefEdge>(); collectNodes<TestNode>().toList() }.getOrNull()!!
            assertEquals(setOf(u), reached.map { it.id }.toSet())
        } finally {
            listOf("cx-nodes", "cx-edges", "cx-edges-adjacency").forEach { multiSchemaHz.getMap<Any, Any>(it).clear() }
        }
    }

    @Test fun `addCrossEdge with ttl persists through the ephemeral store, not the persistent one`() = runBlocking {
        val persistent = RecordingStore()
        val ephemeral = RecordingEphemeralStore()
        val g = HeterogeneousSchemaGraph(multiSchemaHz, SchemaTagWidth.BYTE, "cx2-nodes", "cx2-edges", allowCrossSchemaEdges = true, persistentStore = persistent, ephemeralStore = ephemeral, module = graphTestModule)
        val longS = g.register(SchemaTag(211L), LongKeyAdapter)
        val uuidS = g.register(SchemaTag(212L), UuidKeyAdapter)
        try {
            longS.transaction { addNode(LongTestNode(1L)) }
            val u = Uuid.random()
            uuidS.transaction { addNode(TestNode(id = u, name = "bob")) }

            val fromNid = SchemaKeyAdapter(SchemaTag(211L), SchemaTagWidth.BYTE, LongKeyAdapter).toNodeId(1L)
            val toNid = SchemaKeyAdapter(SchemaTag(212L), SchemaTagWidth.BYTE, UuidKeyAdapter).toNodeId(u)
            assertTrue(g.addCrossEdge(CrossRefEdge(fromNid, toNid), ttl = 60.seconds).isRight())

            assertEquals(1, ephemeral.savedEdges.size)
            assertEquals(0, persistent.savedEdges.size)
        } finally {
            listOf("cx2-nodes", "cx2-edges", "cx2-edges-adjacency").forEach { multiSchemaHz.getMap<Any, Any>(it).clear() }
        }
    }

    @Test fun `removeCrossEdge fans the delete out to both stores`() = runBlocking {
        val persistent = RecordingStore()
        val ephemeral = RecordingEphemeralStore()
        val g = HeterogeneousSchemaGraph(multiSchemaHz, SchemaTagWidth.BYTE, "cx3-nodes", "cx3-edges", allowCrossSchemaEdges = true, persistentStore = persistent, ephemeralStore = ephemeral, module = graphTestModule)
        val longS = g.register(SchemaTag(221L), LongKeyAdapter)
        val uuidS = g.register(SchemaTag(222L), UuidKeyAdapter)
        try {
            longS.transaction { addNode(LongTestNode(1L)) }
            val u = Uuid.random()
            uuidS.transaction { addNode(TestNode(id = u, name = "carol")) }

            val fromNid = SchemaKeyAdapter(SchemaTag(221L), SchemaTagWidth.BYTE, LongKeyAdapter).toNodeId(1L)
            val toNid = SchemaKeyAdapter(SchemaTag(222L), SchemaTagWidth.BYTE, UuidKeyAdapter).toNodeId(u)
            g.addCrossEdge(CrossRefEdge(fromNid, toNid))

            assertTrue(g.removeCrossEdge(fromNid, toNid, "cross_ref").isRight())
            assertEquals(1, persistent.deletedEdges.size)
            assertEquals(1, ephemeral.deletedEdges.size)

            val reached = longS.from(1L) { outgoing<CrossRefEdge>(); collectNodes<TestNode>().toList() }.getOrNull()!!
            assertEquals(emptyList(), reached)
        } finally {
            listOf("cx3-nodes", "cx3-edges", "cx3-edges-adjacency").forEach { multiSchemaHz.getMap<Any, Any>(it).clear() }
        }
    }

    @Test fun `addCrossEdge failure leaves the cache untouched`() = runBlocking {
        val store = RecordingStore(failTx = true)
        val g = HeterogeneousSchemaGraph(multiSchemaHz, SchemaTagWidth.BYTE, "cx4-nodes", "cx4-edges", allowCrossSchemaEdges = true, persistentStore = store, module = graphTestModule)
        val longS = g.register(SchemaTag(231L), LongKeyAdapter)
        val uuidS = g.register(SchemaTag(232L), UuidKeyAdapter)
        try {
            longS.transaction { addNode(LongTestNode(1L)) }
            val u = Uuid.random()
            uuidS.transaction { addNode(TestNode(id = u, name = "dave")) }

            val fromNid = SchemaKeyAdapter(SchemaTag(231L), SchemaTagWidth.BYTE, LongKeyAdapter).toNodeId(1L)
            val toNid = SchemaKeyAdapter(SchemaTag(232L), SchemaTagWidth.BYTE, UuidKeyAdapter).toNodeId(u)
            val result = g.addCrossEdge(CrossRefEdge(fromNid, toNid))
            assertIs<Either.Left<AbyssError>>(result)

            val reached = longS.from(1L) { outgoing<CrossRefEdge>(); collectNodes<TestNode>().toList() }.getOrNull()!!
            assertEquals(emptyList(), reached)
        } finally {
            listOf("cx4-nodes", "cx4-edges", "cx4-edges-adjacency").forEach { multiSchemaHz.getMap<Any, Any>(it).clear() }
        }
    }

    @Test fun `a cross edge already in the persistent store is warmed by preloadOut without being added directly`() = runBlocking {
        val u = Uuid.random()
        val fromNid = SchemaKeyAdapter(SchemaTag(241L), SchemaTagWidth.BYTE, LongKeyAdapter).toNodeId(1L)
        val toNid = SchemaKeyAdapter(SchemaTag(242L), SchemaTagWidth.BYTE, UuidKeyAdapter).toNodeId(u)
        val edge = CrossRefEdge(fromNid, toNid, note = "seeded")
        val store = RecordingStore().apply { seededEdges = listOf(StoredEdge(fromNid, toNid, edge, null)) }

        val g = HeterogeneousSchemaGraph(multiSchemaHz, SchemaTagWidth.BYTE, "cx5-nodes", "cx5-edges", allowCrossSchemaEdges = true, persistentStore = store, module = graphTestModule)
        val longS = g.register(SchemaTag(241L), LongKeyAdapter)
        val uuidS = g.register(SchemaTag(242L), UuidKeyAdapter)
        try {
            longS.transaction { addNode(LongTestNode(1L)) }
            uuidS.transaction { addNode(TestNode(id = u, name = "erin")) }
            // No addCrossEdge call — the edge exists only in the (fake) persistent store.

            val reached = longS.from(1L) { outgoing<CrossRefEdge>(); collectNodes<TestNode>().toList() }.getOrNull()!!
            assertEquals(setOf(u), reached.map { it.id }.toSet())
        } finally {
            listOf("cx5-nodes", "cx5-edges", "cx5-edges-adjacency").forEach { multiSchemaHz.getMap<Any, Any>(it).clear() }
        }
    }

    // TODO 1.20 (durability audit finding #5, cross-schema instance): addCrossEdge's integrity check
    // used to read worker.containsNodeInCache — a raw cache read — so a genuinely-existing node not
    // yet cache-warmed spuriously failed with IntegrityError. Both endpoints exist only in the (fake)
    // store here, never added through transaction{} — the cache never learns about them, exactly like
    // a cold restart would.
    @Test fun `addCrossEdge integrity check self-heals from store on cache-cold node`() = runBlocking {
        val store = RecordingStore()
        val g = HeterogeneousSchemaGraph(multiSchemaHz, SchemaTagWidth.BYTE, "cx6-nodes", "cx6-edges", allowCrossSchemaEdges = true, persistentStore = store, module = graphTestModule)
        g.register(SchemaTag(251L), LongKeyAdapter)
        g.register(SchemaTag(252L), UuidKeyAdapter)
        try {
            val u = Uuid.random()
            val fromNid = SchemaKeyAdapter(SchemaTag(251L), SchemaTagWidth.BYTE, LongKeyAdapter).toNodeId(1L)
            val toNid = SchemaKeyAdapter(SchemaTag(252L), SchemaTagWidth.BYTE, UuidKeyAdapter).toNodeId(u)
            store.seededNodes = mapOf(fromNid to LongTestNode(1L), toNid to TestNode(id = u, name = "cold"))

            val result = g.addCrossEdge(CrossRefEdge(fromNid, toNid))
            assertTrue(result.isRight())
        } finally {
            listOf("cx6-nodes", "cx6-edges", "cx6-edges-adjacency").forEach { multiSchemaHz.getMap<Any, Any>(it).clear() }
        }
    }

    @Test fun twoLongSchemasDisambiguateByTag() = runBlocking {
        val g = HeterogeneousSchemaGraph(multiSchemaHz, SchemaTagWidth.BYTE, "ms-nodes", "ms-edges", module = graphTestModule)
        val a = g.register(LONG_TAG, LongKeyAdapter)
        val b = g.register(LONG_TAG_B, LongKeyAdapter)
        // Identical numeric ids in both schemas — only the schema tag distinguishes their edge keys.
        a.transaction { addNode(LongTestNode(1L)); addNode(LongTestNode(2L)); addEdge(LongTestEdge(1L, 2L)) }
        b.transaction { addNode(LongTestNode(1L)); addNode(LongTestNode(9L)); addEdge(LongTestEdge(1L, 9L)) }

        // The fromTag clause keeps schema A's query from matching schema B's fromId==1 edge.
        assertEquals(setOf(2L), a.outEdges<LongTestEdge>(1L).toList().map { it.toId }.toSet())
        assertEquals(setOf(9L), b.outEdges<LongTestEdge>(1L).toList().map { it.toId }.toSet())
    }

    @Test fun demo() {
        // Tag round-trips through the schema adapter, and the header reads back to its tag.
        val adapter = SchemaKeyAdapter(LONG_TAG, SchemaTagWidth.BYTE, LongKeyAdapter)
        val nid = adapter.toNodeId(7L)
        assert(adapter.fromNodeId(nid) == 7L)
        assert(NodeKey.tag(nid) == LONG_TAG)
        // Wider tag widths preserve the value too.
        val wide = SchemaKeyAdapter(SchemaTag(300L), SchemaTagWidth.SHORT, LongKeyAdapter)
        assert(NodeKey.tag(wide.toNodeId(9L)) == SchemaTag(300L))

        // Self-decoding: recover (width, kind, tag, rawId) from the bytes alone — no adapter, no graph.
        assert(NodeKey.width(nid) == SchemaTagWidth.BYTE)
        assert(NodeKey.kind(nid) == NodeKeyKind.INT64)
        assert(LongKeyAdapter.decodeIdBytes(NodeKey.rawId(nid)) == 7L)
        // A bare (untagged) key still carries a NONE-width header and decodes standalone.
        val bare = LongKeyAdapter.toNodeId(42L)
        assert(NodeKey.width(bare) == SchemaTagWidth.NONE)
        assert(NodeKey.kind(bare) == NodeKeyKind.INT64)
        assert(LongKeyAdapter.fromNodeId(bare) == 42L)
    }

    @Test fun schemaDescriptorDerivesFromKeyWithoutRegistry() {
        // Tagged key: width + tag recovered from the bytes; edgeAdapter is the stateless multi-schema one.
        val tagged = SchemaKeyAdapter(LONG_TAG, SchemaTagWidth.BYTE, LongKeyAdapter).toNodeId(7L)
        val dTagged = SchemaDescriptor.of(tagged)
        assertEquals(SchemaTagWidth.BYTE, dTagged.tagWidth)
        assertEquals(LONG_TAG, dTagged.tag)
        assertTrue(dTagged.edgeAdapter is MultiSchemaAdapter)

        // NONE-width native key: tag 0, canonical native adapter (not the multi-schema one).
        val bare = LongKeyAdapter.toNodeId(42L)
        val dBare = SchemaDescriptor.of(bare)
        assertEquals(SchemaTagWidth.NONE, dBare.tagWidth)
        assertEquals(SchemaTag.ZERO, dBare.tag)
        assertTrue(dBare.edgeAdapter === LongKeyAdapter)
    }

    @Test fun taggedContainersRejectNoneTagWidth() {
        assertFails { HeterogeneousSchemaGraph(multiSchemaHz, SchemaTagWidth.NONE, "ms-nodes-x", "ms-edges-x") }
        assertFails { HomogeneousSchemaGraph(multiSchemaHz, SchemaTagWidth.NONE, LongKeyAdapter, "ms-nodes-x", "ms-edges-x") }
    }

    // Fix confirmation (TODO 1.19 follow-up): before widening the tag to SchemaTag(hi, lo), NodeKey's
    // byte-packing loop shifted a single Long by up to 120 bits — on the JVM, Long ushr wraps the shift
    // amount mod 64, so SchemaTagWidth.UUID silently duplicated the low 64 bits instead of encoding a
    // genuine 128-bit value. This proves a real (hi != 0) 128-bit tag now round-trips correctly.
    @Test fun uuidWidthTagRoundTripsFull128Bits() {
        val tag = SchemaTag(hi = -1L, lo = 42L)
        val nid = NodeKey.compose(SchemaTagWidth.UUID, NodeKeyKind.INT64, tag, LongKeyAdapter.encodeIdBytes(7L))
        assertEquals(tag, NodeKey.tag(nid))
        assertEquals(SchemaTagWidth.UUID, NodeKey.width(nid))
        assertEquals(7L, LongKeyAdapter.decodeIdBytes(NodeKey.rawId(nid)))
    }

    @Test fun kindToAdapterIsTotalAndSelfConsistent() {
        // Every kind maps to a canonical adapter that reports the same kind — the 1:1 map is total.
        for (kind in NodeKeyKind.entries) assert(kind.adapter().nodeKeyKind == kind)

        // INT32 round-trips through its new adapter and self-decodes from the bytes alone.
        val nid = IntKeyAdapter.toNodeId(42)
        assert(IntKeyAdapter.fromNodeId(nid) == 42)
        assert(NodeKey.kind(nid) == NodeKeyKind.INT32)
        assert(NodeKey.width(nid) == SchemaTagWidth.NONE)
    }
}

// Cross-edge persistence fakes (TODO 4.5): track saveEdge/deleteEdge calls so a test can assert a
// cross-schema edge actually reached the store, not just the cache. GraphTest.kt's FakeStore only
// tracks node saves and is file-private, so these are separate, edge-focused doubles.
private class RecordingStore(var failTx: Boolean = false) : AbyssStoreLike {
    val savedEdges = mutableListOf<Triple<NodeId, NodeId, EdgeLike<*, *>>>()
    val deletedEdges = mutableListOf<Triple<NodeId, NodeId, String>>()
    var seededEdges: List<StoredEdge> = emptyList()
    var seededNodes: Map<NodeId, NodeLike<*>> = emptyMap()

    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> = Either.Right(seededNodes[id] to null)
    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> = Either.Right(null to null)
    override suspend fun loadEdges(fromId: NodeId): Either<AbyssError, List<StoredEdge>> =
        Either.Right(seededEdges.filter { it.fromId == fromId })

    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        if (failTx) return AbyssError.Unexpected(RuntimeException("store down")).left()
        val tx = object : AbyssStoreTransactionLike {
            override fun saveNode(id: NodeId, node: NodeLike<*>, tags: Set<String>) {}
            override fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, tags: Set<String>) { savedEdges += Triple(fromId, toId, edge) }
            override fun deleteNode(id: NodeId) {}
            override fun deleteEdge(fromId: NodeId, toId: NodeId, type: String) { deletedEdges += Triple(fromId, toId, type) }
        }
        tx.block()
        return Unit.right()
    }
}

private class RecordingEphemeralStore : AbyssEphemeralStoreLike {
    val savedEdges = mutableListOf<Triple<NodeId, NodeId, EdgeLike<*, *>>>()
    val deletedEdges = mutableListOf<Triple<NodeId, NodeId, String>>()

    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> = Either.Right(null to null)
    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> = Either.Right(null to null)

    override suspend fun transaction(block: suspend AbyssEphemeralStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        val tx = object : AbyssEphemeralStoreTransactionLike {
            override fun saveNode(id: NodeId, node: NodeLike<*>, ttl: Duration, tags: Set<String>) {}
            override fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, ttl: Duration, tags: Set<String>) { savedEdges += Triple(fromId, toId, edge) }
            override fun deleteNode(id: NodeId) {}
            override fun deleteEdge(fromId: NodeId, toId: NodeId, type: String) { deletedEdges += Triple(fromId, toId, type) }
        }
        tx.block()
        return Unit.right()
    }
}
