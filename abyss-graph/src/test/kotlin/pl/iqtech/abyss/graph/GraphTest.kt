package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import pl.iqtech.abyss.store.api.EdgeConstraint
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.Path
import pl.iqtech.abyss.dsl.edge
import pl.iqtech.abyss.dsl.edgeExists
import pl.iqtech.abyss.dsl.ensureSubgraph
import pl.iqtech.abyss.dsl.inEdges
import pl.iqtech.abyss.dsl.node
import pl.iqtech.abyss.dsl.outEdges
import pl.iqtech.abyss.dsl.removeEdge
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreLike
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreTransactionLike
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.StoredEdge
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.TypeTag
import pl.iqtech.abyss.store.api.HeaderlessKeyAdapter
import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.StringKeyAdapter
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable @SerialName("other_node") @TypeTag(2)
data class OtherNode(
    override val id: Uuid,
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
) : NodeLike<Uuid>

@Serializable @SerialName("typed_edge") @TypeTag(2)
@EdgeConstraint(fromTypes = [TestNode::class], toTypes = [TestNode::class])
data class TypedEdge(
    override val fromId: Uuid,
    override val toId: Uuid,
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
) : EdgeLike<Uuid, Uuid>

val graphTestModule = SerializersModule {
    polymorphic(NodeLike::class) {
        subclass(TestNode::class); subclass(OtherNode::class)
        subclass(LongTestNode::class); subclass(StrTestNode::class)
    }
    polymorphic(EdgeLike::class) {
        subclass(TestEdge::class); subclass(TypedEdge::class)
        subclass(LongTestEdge::class); subclass(StrTestEdge::class)
        subclass(CrossRefEdge::class)
        subclass(LivesIn::class); subclass(TenantLink::class); subclass(SameTenantLink::class)
    }
}

// TODO 1.19: AbyssGraphSchema's standalone (single-schema) constructor is headerless, so the
// Compact serializer config and any test code building/decoding a NodeId outside the facade must
// go through the same HeaderlessKeyAdapter wrapper, not the bare (headered) domain adapter. Shared
// (not file-private) since several other test files pre-seed graphTestHz's maps directly.
val huid = HeaderlessKeyAdapter(UuidKeyAdapter)

val graphTestHz by lazy {
    System.setProperty("hazelcast.logging.type", "none")
    Hazelcast.newHazelcastInstance(
        Config().setClusterName("graph-test-uuid").registerAbyssSerializers(huid, graphTestModule)
    )
}

// EdgeKey compact serialization is bound to one KeyAdapter per HazelcastInstance,
// so Long/String performance tests get their own instances rather than sharing graphTestHz.
val longTestHz by lazy {
    System.setProperty("hazelcast.logging.type", "none")
    Hazelcast.newHazelcastInstance(
        Config().setClusterName("graph-test-long").registerAbyssSerializers(HeaderlessKeyAdapter(LongKeyAdapter), graphTestModule)
    )
}

val stringTestHz by lazy {
    System.setProperty("hazelcast.logging.type", "none")
    Hazelcast.newHazelcastInstance(
        Config().setClusterName("graph-test-string").registerAbyssSerializers(HeaderlessKeyAdapter(StringKeyAdapter), graphTestModule)
    )
}

val graphTest by lazy { AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "g-nodes", "g-edges", module = graphTestModule) }

private fun Uuid.toNodeId() = huid.toNodeId(this)
private fun edgeKey(fromId: Uuid, toId: Uuid, type: String): EdgeKey {
    val fromNid = fromId.toNodeId()
    return EdgeKey(fromNid, toId.toNodeId(), type, huid.partitionKey(fromNid))
}

class GraphTest {

    @BeforeTest fun clear() {
        graphTestHz.getMap<Any, Any>("g-nodes").clear()
        graphTestHz.getMap<Any, Any>("g-edges").clear()
        graphTestHz.getMap<Any, Any>("g-edges-adjacency").clear()
    }

    // ── node ─────────────────────────────────────────────────────────────────

    @Test fun `node() returns Right when present`() {
        runBlocking {
            val node = TestNode(id = Uuid.random(), name = "rifle")
            graphTestHz.getMap<NodeId, NodeLike<*>>("g-nodes")[node.id.toNodeId()] = node
            val result = graphTest.node(node.id)
            assertIs<Either.Right<NodeLike<*>>>(result)
            assertEquals(node, result.value)
        }
    }

    @Test fun `node() returns NodeNotFound when absent`() {
        runBlocking {
            val result = graphTest.node(Uuid.random())
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.NodeNotFound>(result.value)
        }
    }

    @Test fun `reified node() casts to concrete type`() {
        runBlocking {
            val node = TestNode(id = Uuid.random(), name = "scope")
            graphTestHz.getMap<NodeId, NodeLike<*>>("g-nodes")[node.id.toNodeId()] = node
            val result = graphTest.node<TestNode>(node.id)
            assertIs<Either.Right<TestNode>>(result)
            assertEquals("scope", result.value.name)
        }
    }

    // ── nodeExists ────────────────────────────────────────────────────────────

    @Test fun `nodeExists() returns true when present`() {
        runBlocking {
            val node = TestNode(id = Uuid.random(), name = "ammo")
            graphTestHz.getMap<NodeId, NodeLike<*>>("g-nodes")[node.id.toNodeId()] = node
            assertEquals(Either.Right(true), graphTest.nodeExists(node.id))
        }
    }

    @Test fun `nodeExists() returns false when absent`() {
        runBlocking {
            assertEquals(Either.Right(false), graphTest.nodeExists(Uuid.random()))
        }
    }

    // ── edge ─────────────────────────────────────────────────────────────────

    @Test fun `edge() returns Right when present`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "has_rifle")
            graphTestHz.getMap<EdgeKey, EdgeLike<*, *>>("g-edges")[edgeKey(edge.fromId, edge.toId, "test_edge")] = edge
            val result = graphTest.edge(edge.fromId, edge.toId, "test_edge")
            assertIs<Either.Right<EdgeLike<*, *>>>(result)
            assertEquals(edge, result.value)
        }
    }

    @Test fun `edge() returns EdgeNotFound when absent`() {
        runBlocking {
            val result = graphTest.edge(Uuid.random(), Uuid.random(), "test_edge")
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.EdgeNotFound>(result.value)
        }
    }

    @Test fun `reified edge() resolves type from SerialName`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "owns")
            graphTestHz.getMap<EdgeKey, EdgeLike<*, *>>("g-edges")[edgeKey(edge.fromId, edge.toId, "test_edge")] = edge
            val result = graphTest.edge<TestEdge>(edge.fromId, edge.toId)
            assertIs<Either.Right<TestEdge>>(result)
            assertEquals("owns", result.value.label)
        }
    }

    // ── edgeExists ────────────────────────────────────────────────────────────

    @Test fun `edgeExists() returns true when present`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "link")
            graphTestHz.getMap<EdgeKey, EdgeLike<*, *>>("g-edges")[edgeKey(edge.fromId, edge.toId, "test_edge")] = edge
            assertEquals(Either.Right(true), graphTest.edgeExists<TestEdge>(edge.fromId, edge.toId))
        }
    }

    @Test fun `edgeExists() returns false when absent`() {
        runBlocking {
            assertEquals(Either.Right(false), graphTest.edgeExists<TestEdge>(Uuid.random(), Uuid.random()))
        }
    }

    // ── outEdges ──────────────────────────────────────────────────────────────

    @Test fun `outEdges() returns all edges from a node`() {
        runBlocking {
            val from = Uuid.random()
            val edges = (1..3).map { TestEdge(fromId = from, toId = Uuid.random(), label = "e$it") }
            graphTest.transaction(checkIntegrity = false) { edges.forEach { addEdge(it) } }

            val result = graphTest.outEdges(from).toList()
            assertEquals(3, result.size)
        }
    }

    @Test fun `outEdges() with type filters correctly`() {
        runBlocking {
            val from = Uuid.random()
            val edges = (1..3).map { TestEdge(fromId = from, toId = Uuid.random(), label = "e$it") }
            graphTest.transaction(checkIntegrity = false) { edges.forEach { addEdge(it) } }
            // decoy with a different edge type
            graphTest.transaction(checkIntegrity = false) { addEdge(TypedEdge(fromId = from, toId = Uuid.random())) }

            val result = graphTest.outEdges(from, "test_edge").toList()
            assertEquals(3, result.size)
        }
    }

    @Test fun `reified outEdges() emits typed edges`() {
        runBlocking {
            val from = Uuid.random()
            val edges = (1..2).map { TestEdge(fromId = from, toId = Uuid.random(), label = "e$it") }
            graphTest.transaction(checkIntegrity = false) { edges.forEach { addEdge(it) } }

            val result = graphTest.outEdges<TestEdge>(from).toList()
            assertEquals(2, result.size)
            result.forEach { assertIs<TestEdge>(it) }
        }
    }

    @Test fun `outEdges() pages correctly`() {
        runBlocking {
            val from = Uuid.random()
            val edges = (1..5).map { TestEdge(fromId = from, toId = Uuid.random(), label = "e$it") }
            graphTest.transaction(checkIntegrity = false) { edges.forEach { addEdge(it) } }

            val result = graphTest.outEdges(from, pageSize = 2).toList()
            assertEquals(5, result.size)
        }
    }

    // ── inEdges ───────────────────────────────────────────────────────────────

    @Test fun `inEdges() returns all edges to a node`() {
        runBlocking {
            val to = Uuid.random()
            val edges = (1..3).map { TestEdge(fromId = Uuid.random(), toId = to, label = "e$it") }
            edges.forEach { graphTest.transaction(checkIntegrity = false) { addEdge(it) } }

            val result = graphTest.inEdges(to).toList()
            assertEquals(3, result.size)
        }
    }

    @Test fun `reified inEdges() emits typed edges`() {
        runBlocking {
            val to = Uuid.random()
            val edges = (1..2).map { TestEdge(fromId = Uuid.random(), toId = to, label = "e$it") }
            edges.forEach { graphTest.transaction(checkIntegrity = false) { addEdge(it) } }

            val result = graphTest.inEdges<TestEdge>(to).toList()
            assertEquals(2, result.size)
            result.forEach { assertIs<TestEdge>(it) }
        }
    }

    // ── transaction ───────────────────────────────────────────────────────────

    @Test fun `transaction addNode makes node retrievable`() {
        runBlocking {
            val node = TestNode(id = Uuid.random(), name = "tx-node")
            graphTest.transaction { addNode(node) }
            assertIs<Either.Right<NodeLike<*>>>(graphTest.node(node.id))
        }
    }

    @Test fun `transaction addEdge makes edge retrievable`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "tx-edge")
            graphTest.transaction(checkIntegrity = false) { addEdge(edge) }
            assertIs<Either.Right<EdgeLike<*, *>>>(graphTest.edge(edge.fromId, edge.toId, "test_edge"))
        }
    }

    @Test fun `transaction modifyEdge retargets toId`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")
            val c = TestNode(id = Uuid.random(), name = "c")
            val ab = TestEdge(fromId = a.id, toId = b.id, label = "ab")
            val ac = ab.copy(toId = c.id, label = "ac")
            graphTest.transaction { addNode(a); addNode(b); addNode(c); addEdge(ab) }
            graphTest.transaction { modifyEdge(a.id, b.id, "test_edge") { _ -> ac } }
            assertIs<Either.Right<EdgeLike<*, *>>>(graphTest.edge(a.id, c.id, "test_edge"))
            assertIs<Either.Left<AbyssError>>(graphTest.edge(a.id, b.id, "test_edge"))
            assertEquals(1, graphTest.inEdges(c.id).toList().size)
            assertEquals(0, graphTest.inEdges(b.id).toList().size)
        }
    }

    @Test fun `transaction modifyEdge retargets fromId`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")
            val c = TestNode(id = Uuid.random(), name = "c")
            val ac = TestEdge(fromId = a.id, toId = c.id, label = "ac")
            val bc = ac.copy(fromId = b.id, label = "bc")
            graphTest.transaction { addNode(a); addNode(b); addNode(c); addEdge(ac) }
            graphTest.transaction { modifyEdge(a.id, c.id, "test_edge") { _ -> bc } }
            assertIs<Either.Right<EdgeLike<*, *>>>(graphTest.edge(b.id, c.id, "test_edge"))
            assertIs<Either.Left<AbyssError>>(graphTest.edge(a.id, c.id, "test_edge"))
        }
    }

    @Test fun `transaction modifyEdge returns IntegrityError when new endpoint absent`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")
            val ab = TestEdge(fromId = a.id, toId = b.id, label = "ab")
            graphTest.transaction { addNode(a); addNode(b); addEdge(ab) }
            val result = graphTest.transaction { modifyEdge(a.id, b.id, "test_edge") { ab.copy(toId = Uuid.random()) } }
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.IntegrityError>(result.value)
        }
    }

    @Test fun `transaction modifyEdge old endpoint not integrity-checked`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")
            val ghost = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "ghost")
            graphTest.transaction(checkIntegrity = false) { addNode(a); addNode(b); addEdge(ghost) }
            val result = graphTest.transaction { modifyEdge(ghost.fromId, ghost.toId, "test_edge") { _ -> TestEdge(fromId = a.id, toId = b.id, label = "real") } }
            assertIs<Either.Right<Unit>>(result)
        }
    }

    @Test fun `transaction modifyNode transforms existing node`() {
        runBlocking {
            val node = TestNode(id = Uuid.random(), name = "before")
            graphTest.transaction { addNode(node) }
            graphTest.transaction { modifyNode(node.id) { old -> (old as TestNode).copy(name = "after") } }
            val result = graphTest.node<TestNode>(node.id)
            assertIs<Either.Right<TestNode>>(result)
            assertEquals("after", result.value.name)
        }
    }

    @Test fun `transaction modifyNode receives null when node absent`() {
        runBlocking {
            val id = Uuid.random()
            var sawNull = false
            graphTest.transaction { modifyNode(id) { old -> sawNull = old == null; TestNode(id = id, name = "created") } }
            assertTrue(sawNull)
            assertIs<Either.Right<TestNode>>(graphTest.node<TestNode>(id))
        }
    }

    @Test fun `transaction addEdge returns IntegrityError when fromId node absent`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "dangling")
            val result = graphTest.transaction { addEdge(edge) }
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.IntegrityError>(result.value)
        }
    }

    @Test fun `transaction addEdge returns IntegrityError when toId node absent`() {
        runBlocking {
            val from = TestNode(id = Uuid.random(), name = "from")
            graphTest.transaction(checkIntegrity = false) { addNode(from) }
            val edge = TestEdge(fromId = from.id, toId = Uuid.random(), label = "dangling")
            val result = graphTest.transaction { addEdge(edge) }
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.IntegrityError>(result.value)
        }
    }

    @Test fun `transaction addEdge with checkIntegrity=false skips node existence check`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "bulk")
            val result = graphTest.transaction(checkIntegrity = false) { addEdge(edge) }
            assertIs<Either.Right<Unit>>(result)
        }
    }

    @Test fun `transaction addNode overwrites existing node`() {
        runBlocking {
            val node = TestNode(id = Uuid.random(), name = "before")
            graphTest.transaction { addNode(node) }
            graphTest.transaction { addNode(node.copy(name = "after")) }
            val result = graphTest.node<TestNode>(node.id)
            assertEquals("after", (result as Either.Right).value.name)
        }
    }

    @Test fun `transaction removeNode removes from cache`() {
        runBlocking {
            val node = TestNode(id = Uuid.random(), name = "ephemeral")
            graphTest.transaction { addNode(node) }
            graphTest.transaction { removeNode(node.id) }
            assertIs<Either.Left<AbyssError>>(graphTest.node(node.id))
        }
    }

    @Test fun `transaction removeEdge removes from cache`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "bye")
            graphTest.transaction(checkIntegrity = false) { addEdge(edge) }
            graphTest.transaction { removeEdge(edge.fromId, edge.toId, "test_edge") }
            assertIs<Either.Left<AbyssError>>(graphTest.edge(edge.fromId, edge.toId, "test_edge"))
        }
    }

    @Test fun `transaction removeEdge with edge instance removes edge`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "bye")
            graphTest.transaction(checkIntegrity = false) { addEdge(edge) }
            graphTest.transaction { removeEdge(edge) }
            assertIs<Either.Left<AbyssError>>(graphTest.edge(edge.fromId, edge.toId, "test_edge"))
        }
    }

    @Test fun `transaction removeNode cascades to connected edges`() {
        runBlocking {
            val node  = TestNode(id = Uuid.random(), name = "hub")
            val other = TestNode(id = Uuid.random(), name = "other")
            val out = TestEdge(fromId = node.id,  toId = other.id, label = "out")
            val inc = TestEdge(fromId = other.id, toId = node.id,  label = "in")
            graphTest.transaction { addNode(node); addNode(other); addEdge(out); addEdge(inc) }

            graphTest.transaction { removeNode(node.id) }

            assertIs<Either.Left<AbyssError>>(graphTest.node(node.id))
            assertIs<Either.Left<AbyssError>>(graphTest.edge(node.id,  other.id, "test_edge"))
            assertIs<Either.Left<AbyssError>>(graphTest.edge(other.id, node.id,  "test_edge"))
        }
    }

    // ── ephemeral ─────────────────────────────────────────────────────────────

    @Test fun `ephemeral addNode makes node retrievable`() {
        runBlocking {
            val node = TestNode(id = Uuid.random(), name = "eph-node")
            graphTest.ephemeral(60.seconds) { addNode(node) }
            assertIs<Either.Right<NodeLike<*>>>(graphTest.node(node.id))
        }
    }

    // fable.md 1.2: sub-second TTL floors to USING TTL 0 (YCQL) / setAsync ttl=0 (Hazelcast), both of
    // which mean "never expire" — so anything <1s must be rejected rather than silently made immortal.
    @Test fun `ephemeral rejects sub-second TTL`() {
        runBlocking {
            assertFailsWith<IllegalArgumentException> {
                graphTest.ephemeral(500.milliseconds) { addNode(TestNode(id = Uuid.random(), name = "x")) }
            }
        }
    }

    // TODO 1.27: ephemeral edges are store-only — retrievable via the ephemeral store (readEdge
    // self-heals from it), not the cache. Requires an ephemeral store (cache-only is unsupported).
    @Test fun `ephemeral addEdge makes edge retrievable from the store`() {
        runBlocking {
            val fake = FakeEphemeralStore()
            val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "eph-e-nodes", "eph-e-edges", ephemeralStore = fake, module = graphTestModule)
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "eph-edge")
            g.ephemeral(60.seconds, checkIntegrity = false) { addEdge(edge) }
            assertIs<Either.Right<EdgeLike<*, *>>>(g.edge(edge.fromId, edge.toId, "test_edge"))
            listOf("eph-e-nodes", "eph-e-edges", "eph-e-edges-adjacency").forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        }
    }

    // TODO 1.13/1.27: ephemeral edges are outgoing-only and store-only — reached via includeEphemeral
    // (reads the store, survives cache eviction), invisible to the default read and to inEdges.
    @Test fun `ephemeral addEdge is outgoing-only and store-sourced via includeEphemeral`() {
        runBlocking {
            val fake = FakeEphemeralStore()
            val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "eph-o-nodes", "eph-o-edges", ephemeralStore = fake, module = graphTestModule)
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "eph-out-only")
            g.ephemeral(60.seconds, checkIntegrity = false) { addEdge(edge) }

            assertEquals(0, g.outEdges(edge.fromId).toList().size, "default excludes ephemeral")
            assertEquals(1, g.outEdges(edge.fromId, includeEphemeral = true).toList().size, "includeEphemeral reads it from the store")
            assertEquals(0, g.inEdges(edge.toId).toList().size, "ephemeral is outgoing-only")
            listOf("eph-o-nodes", "eph-o-edges", "eph-o-edges-adjacency").forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        }
    }

    @Test fun `ephemeral addEdge returns IntegrityError when fromId node absent`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "eph-dangling")
            val result = graphTest.ephemeral(60.seconds) { addEdge(edge) }
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.IntegrityError>(result.value)
        }
    }

    @Test fun `ephemeral addEdge with checkIntegrity=false skips node existence check`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "eph-bulk")
            val result = graphTest.ephemeral(60.seconds, checkIntegrity = false) { addEdge(edge) }
            assertIs<Either.Right<Unit>>(result)
        }
    }

    @Test fun `ephemeral with store commits to store with ttl`() {
        runBlocking {
            val fake = FakeEphemeralStore()
            val storeGraph = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "eph-s-nodes", "eph-s-edges", ephemeralStore = fake, module = graphTestModule)
            val node = TestNode(id = Uuid.random(), name = "eph-stored")

            storeGraph.ephemeral(60.seconds) { addNode(node) }

            assertTrue(fake.saveNodeCalls.contains(node.id))
            assertIs<Either.Right<NodeLike<*>>>(storeGraph.node(node.id))

            graphTestHz.getMap<Any, Any>("eph-s-nodes").clear()
            graphTestHz.getMap<Any, Any>("eph-s-edges").clear()
        }
    }

    // ── ensureSubgraph (TODO 2.15) ──────────────────────────────────────────────

    @Test fun `ensureSubgraph creates all missing nodes and edges of a path`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")
            val c = TestNode(id = Uuid.random(), name = "c")
            val ab = TestEdge(fromId = a.id, toId = b.id, label = "ab")
            val bc = TestEdge(fromId = b.id, toId = c.id, label = "bc")

            val result = graphTest.ensureSubgraph(Path(listOf(a, b, c), listOf(ab, bc)))

            assertIs<Either.Right<Unit>>(result)
            assertIs<Either.Right<NodeLike<*>>>(graphTest.node(a.id))
            assertIs<Either.Right<NodeLike<*>>>(graphTest.node(b.id))
            assertIs<Either.Right<NodeLike<*>>>(graphTest.node(c.id))
            assertEquals(Either.Right(true), graphTest.edgeExists<TestEdge>(a.id, b.id))
            assertEquals(Either.Right(true), graphTest.edgeExists<TestEdge>(b.id, c.id))
        }
    }

    @Test fun `ensureSubgraph is idempotent - no duplication on re-run`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")
            val ab = TestEdge(fromId = a.id, toId = b.id, label = "ab")
            val path = Path(listOf(a, b), listOf(ab))

            graphTest.ensureSubgraph(path)
            val second = graphTest.ensureSubgraph(path)

            assertIs<Either.Right<Unit>>(second)
            assertEquals(1, graphTest.outEdges(a.id).toList().size)
        }
    }

    @Test fun `ensureSubgraph leaves existing nodes untouched (create-if-missing)`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            graphTest.ensureSubgraph(Path(listOf(a), emptyList()))
            graphTest.transaction { modifyNode(a.id) { (it as TestNode).copy(name = "mutated") } }

            // Re-ensure with the original node payload — must not overwrite the mutated one.
            graphTest.ensureSubgraph(Path(listOf(a), emptyList()))

            val result = graphTest.node<TestNode>(a.id)
            assertEquals("mutated", (result as Either.Right).value.name)
        }
    }

    @Test fun `ensureSubgraph enforces integrity for an edge to an absent node`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            val dangling = TestEdge(fromId = a.id, toId = Uuid.random(), label = "dangling")

            val result = graphTest.ensureSubgraph(Path(listOf(a), listOf(dangling)))

            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.IntegrityError>(result.value)
        }
    }

    // ── schema enforcement ────────────────────────────────────────────────────

    @Test fun `transaction addEdge with constrained edge and correct node types succeeds`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")
            graphTest.transaction { addNode(a); addNode(b) }
            val result = graphTest.transaction { addEdge(TypedEdge(fromId = a.id, toId = b.id)) }
            assertIs<Either.Right<Unit>>(result)
        }
    }

    @Test fun `transaction addEdge returns SchemaError when fromId has wrong node type`() {
        runBlocking {
            val bad  = OtherNode(id = Uuid.random())
            val good = TestNode(id = Uuid.random(), name = "good")
            graphTest.transaction(checkIntegrity = false) { addNode(bad); addNode(good) }
            val result = graphTest.transaction { addEdge(TypedEdge(fromId = bad.id, toId = good.id)) }
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.SchemaError>(result.value)
        }
    }

    @Test fun `transaction addEdge returns SchemaError when toId has wrong node type`() {
        runBlocking {
            val good = TestNode(id = Uuid.random(), name = "good")
            val bad  = OtherNode(id = Uuid.random())
            graphTest.transaction(checkIntegrity = false) { addNode(good); addNode(bad) }
            val result = graphTest.transaction { addEdge(TypedEdge(fromId = good.id, toId = bad.id)) }
            assertIs<Either.Left<AbyssError>>(result)
            assertIs<AbyssError.SchemaError>(result.value)
        }
    }

    @Test fun `transaction addEdge with constrained edge and in-tx nodes passes schema check`() {
        runBlocking {
            val a = TestNode(id = Uuid.random(), name = "inline-a")
            val b = TestNode(id = Uuid.random(), name = "inline-b")
            val result = graphTest.transaction {
                addNode(a)
                addNode(b)
                addEdge(TypedEdge(fromId = a.id, toId = b.id))
            }
            assertIs<Either.Right<Unit>>(result)
        }
    }

    @Test fun `transaction addEdge with checkIntegrity=false bypasses schema check`() {
        runBlocking {
            val bad  = OtherNode(id = Uuid.random())
            val good = TestNode(id = Uuid.random(), name = "good")
            graphTest.transaction(checkIntegrity = false) { addNode(bad); addNode(good) }
            val result = graphTest.transaction(checkIntegrity = false) {
                addEdge(TypedEdge(fromId = bad.id, toId = good.id))
            }
            assertIs<Either.Right<Unit>>(result)
        }
    }

    @Test fun `outEdges warms cold cache from store`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "warm-out")
            val fake = WarmingFakeStore(outEdges = listOf(edge))
            val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "w-out-nodes", "w-out-edges", persistentStore = fake, module = graphTestModule)
            val result = g.outEdges(edge.fromId).toList()
            assertEquals(1, result.size)
            graphTestHz.getMap<Any, Any>("w-out-nodes").clear()
            graphTestHz.getMap<Any, Any>("w-out-edges").clear()
        }
    }

    @Test fun `inEdges warms cold cache from store`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "warm-in")
            val fake = WarmingFakeStore(inEdges = listOf(edge))
            val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "w-in-nodes", "w-in-edges", persistentStore = fake, module = graphTestModule)
            val result = g.inEdges(edge.toId).toList()
            assertEquals(1, result.size)
            graphTestHz.getMap<Any, Any>("w-in-nodes").clear()
            graphTestHz.getMap<Any, Any>("w-in-edges").clear()
            graphTestHz.getMap<Any, Any>("w-in-edges-adjacency").clear()
        }
    }

    // TODO 1.20 (durability audit finding #4): removeNode's cascade delete used to scan only the
    // cache, so an edge that was never queried since a restart/eviction (store-only, cold cache) was
    // invisible to it and left dangling in the store. hub/other are added via transaction{} (so the
    // cache learns about the NODES), but outEdge itself is seeded only in the fake store, never added
    // through transaction{} — the cache never learns about it, exactly like a cold restart would.
    @Test fun `removeNode cascades store-only edges not yet cache-resident`() {
        runBlocking {
            val hub = TestNode(id = Uuid.random(), name = "hub")
            val other = TestNode(id = Uuid.random(), name = "other")
            val outEdge = TestEdge(fromId = hub.id, toId = other.id, label = "cold")
            val fake = WarmingFakeStore(outEdges = listOf(outEdge))
            val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "cd-nodes", "cd-edges", persistentStore = fake, module = graphTestModule)
            g.transaction { addNode(hub); addNode(other) }

            val result = g.transaction { removeNode(hub.id) }

            assertTrue(result.isRight())
            assertEquals(listOf(huid.toNodeId(hub.id) to huid.toNodeId(other.id)), fake.deletedEdges)
            graphTestHz.getMap<Any, Any>("cd-nodes").clear()
            graphTestHz.getMap<Any, Any>("cd-edges").clear()
            graphTestHz.getMap<Any, Any>("cd-edges-adjacency").clear()
        }
    }

    // TODO 1.20 (durability audit finding #5): addEdge's integrity check used to read the cache
    // directly, so a genuinely-existing node that just hadn't been queried yet (cold restart, evicted
    // partition) spuriously failed with IntegrityError. from/to exist only in the fake store, never
    // added through transaction{} — the cache never learns about them.
    @Test fun `addEdge integrity check self-heals from store on cache-cold node`() {
        runBlocking {
            val from = TestNode(id = Uuid.random(), name = "from")
            val to = TestNode(id = Uuid.random(), name = "to")
            val fake = WarmingFakeStore(nodes = listOf(from, to))
            val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "ic-nodes", "ic-edges", persistentStore = fake, module = graphTestModule)

            val result = g.transaction { addEdge(TestEdge(fromId = from.id, toId = to.id, label = "ok")) }

            assertTrue(result.isRight())
            graphTestHz.getMap<Any, Any>("ic-nodes").clear()
            graphTestHz.getMap<Any, Any>("ic-edges").clear()
        }
    }

    // TODO 1.22: preloadOut/preloadIn used to hit the persistent store on every outAt/inAt call, even
    // when the adjacency cache was already warm for that node+direction — a self-heal mechanism with
    // no "only heal on an actual miss" guard.
    @Test fun `outEdges hits the persistent store once per node, not once per call`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "warm-repeat")
            val fake = WarmingFakeStore(outEdges = listOf(edge))
            val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "wr-nodes", "wr-edges", persistentStore = fake, module = graphTestModule)

            repeat(5) { g.outEdges(edge.fromId).toList() }

            assertEquals(1, fake.loadEdgesCalls)
            graphTestHz.getMap<Any, Any>("wr-nodes").clear()
            graphTestHz.getMap<Any, Any>("wr-edges").clear()
            graphTestHz.getMap<Any, Any>("wr-edges-adjacency").clear()
        }
    }

    @Test fun `inEdges hits the persistent store once per node, not once per call`() {
        runBlocking {
            val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "warm-repeat-in")
            val fake = WarmingFakeStore(inEdges = listOf(edge))
            val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "wri-nodes", "wri-edges", persistentStore = fake, module = graphTestModule)

            repeat(5) { g.inEdges(edge.toId).toList() }

            assertEquals(1, fake.loadInEdgesCalls)
            graphTestHz.getMap<Any, Any>("wri-nodes").clear()
            graphTestHz.getMap<Any, Any>("wri-edges").clear()
            graphTestHz.getMap<Any, Any>("wri-edges-adjacency").clear()
        }
    }

    // Accepted ceiling (see AbyssSchemaWorker.adjacencyRead): a genuinely edgeless node can't be told
    // apart from a never-preloaded one without a dedicated warm marker, so it retries the store on
    // every call. Locked in here so a future change to that behavior is a deliberate decision.
    @Test fun `outEdges retries the store on every call for a node with no adjacency data`() {
        runBlocking {
            val fake = WarmingFakeStore()
            val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "wempty-nodes", "wempty-edges", persistentStore = fake, module = graphTestModule)
            val nid = Uuid.random()

            repeat(3) { g.outEdges(nid).toList() }

            assertEquals(3, fake.loadEdgesCalls)
            graphTestHz.getMap<Any, Any>("wempty-nodes").clear()
            graphTestHz.getMap<Any, Any>("wempty-edges").clear()
            graphTestHz.getMap<Any, Any>("wempty-edges-adjacency").clear()
        }
    }

    @Test fun `transaction with store commits to store before cache`() {
        runBlocking {
            val fake = FakeStore()
            val storeGraph = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "s-nodes", "s-edges", persistentStore = fake, module = graphTestModule)
            val node = TestNode(id = Uuid.random(), name = "stored")

            storeGraph.transaction { addNode(node) }

            assertTrue(fake.saveNodeCalls.contains(node.id))
            assertIs<Either.Right<NodeLike<*>>>(storeGraph.node(node.id))

            graphTestHz.getMap<Any, Any>("s-nodes").clear()
            graphTestHz.getMap<Any, Any>("s-edges").clear()
        }
    }

    @Test fun `transaction with store returns Left when store fails`() {
        runBlocking {
            val fake = FakeStore(failTx = true)
            val storeGraph = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "f-nodes", "f-edges", persistentStore = fake, module = graphTestModule)
            val result = storeGraph.transaction { addNode(TestNode(id = Uuid.random(), name = "x")) }
            assertIs<Either.Left<AbyssError>>(result)
        }
    }

    // ── tags (TODO 1.24) ─────────────────────────────────────────────────────────

    @Test fun `transaction addNode passes tags through to the store`() {
        runBlocking {
            val fake = FakeStore()
            val storeGraph = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "tag-nodes", "tag-edges", persistentStore = fake, module = graphTestModule)
            val node = TestNode(id = Uuid.random(), name = "tagged")

            storeGraph.transaction { addNode(node, tags = setOf("a", "b")) }

            assertEquals(setOf("a", "b"), fake.savedNodeTags[node.id])
            graphTestHz.getMap<Any, Any>("tag-nodes").clear()
            graphTestHz.getMap<Any, Any>("tag-edges").clear()
        }
    }

    @Test fun `transaction addNode with no tags argument saves an empty tag set`() {
        runBlocking {
            val fake = FakeStore()
            val storeGraph = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "notag-nodes", "notag-edges", persistentStore = fake, module = graphTestModule)
            val node = TestNode(id = Uuid.random(), name = "untagged")

            storeGraph.transaction { addNode(node) }

            assertEquals(emptySet(), fake.savedNodeTags[node.id])
            graphTestHz.getMap<Any, Any>("notag-nodes").clear()
            graphTestHz.getMap<Any, Any>("notag-edges").clear()
        }
    }

    @Test fun `transaction addEdge passes tags through to the store`() {
        runBlocking {
            val fake = FakeStore()
            val storeGraph = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "tage-nodes", "tage-edges", persistentStore = fake, module = graphTestModule)
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")

            storeGraph.transaction {
                addNode(a); addNode(b)
                addEdge(TestEdge(fromId = a.id, toId = b.id, label = "x"), tags = setOf("edge-tag"))
            }

            assertEquals(setOf("edge-tag"), fake.savedEdgeTags[a.id to b.id])
            graphTestHz.getMap<Any, Any>("tage-nodes").clear()
            graphTestHz.getMap<Any, Any>("tage-edges").clear()
            graphTestHz.getMap<Any, Any>("tage-edges-adjacency").clear()
        }
    }

    @Test fun `transaction modifyNode's replacement AddNode carries the given tags`() {
        runBlocking {
            val fake = FakeStore()
            val storeGraph = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "tagm-nodes", "tagm-edges", persistentStore = fake, module = graphTestModule)
            val node = TestNode(id = Uuid.random(), name = "orig")
            storeGraph.transaction { addNode(node) }

            storeGraph.transaction {
                modifyNode(node.id, tags = setOf("modified")) { (it as TestNode).copy(name = "changed") }
            }

            assertEquals(setOf("modified"), fake.savedNodeTags[node.id])
            graphTestHz.getMap<Any, Any>("tagm-nodes").clear()
            graphTestHz.getMap<Any, Any>("tagm-edges").clear()
        }
    }

    @Test fun `transaction modifyEdge's replacement AddEdge carries the given tags`() {
        runBlocking {
            val fake = FakeStore()
            val storeGraph = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "tagme-nodes", "tagme-edges", persistentStore = fake, module = graphTestModule)
            val a = TestNode(id = Uuid.random(), name = "a")
            val b = TestNode(id = Uuid.random(), name = "b")
            storeGraph.transaction {
                addNode(a); addNode(b)
                addEdge(TestEdge(fromId = a.id, toId = b.id, label = "x"))
            }

            storeGraph.transaction {
                modifyEdge(a.id, b.id, "test_edge", tags = setOf("retag")) { (it as TestEdge).copy(label = "y") }
            }

            assertEquals(setOf("retag"), fake.savedEdgeTags[a.id to b.id])
            graphTestHz.getMap<Any, Any>("tagme-nodes").clear()
            graphTestHz.getMap<Any, Any>("tagme-edges").clear()
            graphTestHz.getMap<Any, Any>("tagme-edges-adjacency").clear()
        }
    }
}

// Fakes speak the untyped NodeId-keyed store API; they convert via UuidKeyAdapter so the tests can
// still assert against domain Uuids and construct domain edges.
private class FakeStore(private val failTx: Boolean = false) : AbyssStoreLike {
    val saveNodeCalls = mutableSetOf<Uuid>()
    val savedNodeTags = mutableMapOf<Uuid, Set<String>>()
    val savedEdgeTags = mutableMapOf<Pair<Uuid, Uuid>, Set<String>>()

    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> = Either.Right(null to null)
    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> = Either.Right(null to null)

    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        if (failTx) return AbyssError.Unexpected(RuntimeException("store down")).left()
        val tx = object : AbyssStoreTransactionLike {
            override fun saveNode(id: NodeId, node: NodeLike<*>, tags: Set<String>) {
                saveNodeCalls += huid.fromNodeId(id)
                savedNodeTags[huid.fromNodeId(id)] = tags
            }
            override fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, tags: Set<String>) {
                savedEdgeTags[huid.fromNodeId(fromId) to huid.fromNodeId(toId)] = tags
            }
            override fun deleteNode(id: NodeId) { saveNodeCalls -= huid.fromNodeId(id) }
            override fun deleteEdge(fromId: NodeId, toId: NodeId, type: String) {}
        }
        tx.block()
        return Unit.right()
    }
}

private class FakeEphemeralStore : AbyssEphemeralStoreLike {
    val saveNodeCalls = mutableSetOf<Uuid>()
    private val edges = mutableListOf<StoredEdge>()   // durable ephemeral out-edges (TODO 1.27: store is their only home)

    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> = Either.Right(null to null)
    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> =
        Either.Right(edges.find { it.fromId == fromId && it.toId == toId }?.let { it.edge to it.remaining } ?: (null to null))
    override suspend fun loadEdges(fromId: NodeId): Either<AbyssError, List<StoredEdge>> =
        Either.Right(edges.filter { it.fromId == fromId })

    override suspend fun transaction(block: suspend AbyssEphemeralStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        val tx = object : AbyssEphemeralStoreTransactionLike {
            override fun saveNode(id: NodeId, node: NodeLike<*>, ttl: Duration, tags: Set<String>) { saveNodeCalls += huid.fromNodeId(id) }
            override fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, ttl: Duration, tags: Set<String>) { edges += StoredEdge(fromId, toId, edge, ttl) }
            override fun deleteNode(id: NodeId) { saveNodeCalls -= huid.fromNodeId(id) }
            override fun deleteEdge(fromId: NodeId, toId: NodeId, type: String) { edges.removeAll { it.fromId == fromId && it.toId == toId } }
        }
        tx.block()
        return Unit.right()
    }
}

private class WarmingFakeStore(
    private val outEdges: List<EdgeLike<Uuid, Uuid>> = emptyList(),
    private val inEdges: List<EdgeLike<Uuid, Uuid>> = emptyList(),
    private val nodes: List<NodeLike<Uuid>> = emptyList(),
) : AbyssStoreLike {
    val deletedEdges = mutableListOf<Pair<NodeId, NodeId>>()
    var loadEdgesCalls = 0
        private set
    var loadInEdgesCalls = 0
        private set

    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> =
        Either.Right(nodes.find { huid.toNodeId(it.id) == id } to null)
    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> = Either.Right(null to null)
    override suspend fun loadEdges(fromId: NodeId): Either<AbyssError, List<StoredEdge>> {
        loadEdgesCalls++
        return Either.Right(outEdges.filter { huid.toNodeId(it.fromId) == fromId }.map { StoredEdge(fromId, huid.toNodeId(it.toId), it, null) })
    }
    override suspend fun loadInEdges(toId: NodeId): Either<AbyssError, List<StoredEdge>> {
        loadInEdgesCalls++
        return Either.Right(inEdges.filter { huid.toNodeId(it.toId) == toId }.map { StoredEdge(huid.toNodeId(it.fromId), toId, it, null) })
    }

    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        val tx = object : AbyssStoreTransactionLike {
            override fun saveNode(id: NodeId, node: NodeLike<*>, tags: Set<String>) {}
            override fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, tags: Set<String>) {}
            override fun deleteNode(id: NodeId) {}
            override fun deleteEdge(fromId: NodeId, toId: NodeId, type: String) { deletedEdges += fromId to toId }
        }
        tx.block()
        return Unit.right()
    }
}

private fun AbyssError.left(): Either<AbyssError, Nothing> = Either.Left(this)
