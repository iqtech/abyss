package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.right
import com.hazelcast.client.HazelcastClient
import com.hazelcast.client.config.ClientConfig
import com.hazelcast.config.Config
import com.hazelcast.config.EvictionPolicy
import com.hazelcast.core.Hazelcast
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.StoredEdge
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.uuid.Uuid

// Persistent store that actually holds edges, so an evicted value can be reloaded (TODO 1.27 Phase 2).
private class SelfHealStore : AbyssStoreLike {
    private val edges = mutableListOf<StoredEdge>()
    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> = Either.Right(null to null)
    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> =
        Either.Right(edges.find { it.fromId == fromId && it.toId == toId }?.let { it.edge to null } ?: (null to null))
    override suspend fun loadEdges(fromId: NodeId): Either<AbyssError, List<StoredEdge>> = Either.Right(edges.filter { it.fromId == fromId })
    override suspend fun loadInEdges(toId: NodeId): Either<AbyssError, List<StoredEdge>> = Either.Right(edges.filter { it.toId == toId })
    override suspend fun transaction(block: suspend AbyssStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        block(object : AbyssStoreTransactionLike {
            override fun saveNode(id: NodeId, node: NodeLike<*>, tags: Set<String>) {}
            override fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, tags: Set<String>) { edges += StoredEdge(fromId, toId, edge, null) }
            override fun deleteNode(id: NodeId) {}
            override fun deleteEdge(fromId: NodeId, toId: NodeId, type: String) { edges.removeAll { it.fromId == fromId && it.toId == toId } }
        })
        return Unit.right()
    }
}

class IndexAlwaysAliveTest {

    // Phase 2: the adjacency index is authoritative. A value evicted from edgesMap (but present in the
    // never-evicted index) reads back null → self-heal from the store, not "removed → skip".
    @Test fun `evicted persistent edge value self-heals from the store`() = runBlocking {
        val store = SelfHealStore()
        val g = AbyssGraphSchema(UuidKeyAdapter, graphTestHz, "iaa-nodes", "iaa-edges", persistentStore = store, module = graphTestModule)
        val to = Uuid.random()
        val edges = (1..3).map { TestEdge(fromId = Uuid.random(), toId = to, label = "e$it") }
        g.transaction(checkIntegrity = false) { edges.forEach { addEdge(it) } }   // adjacency + edgesMap + store

        graphTestHz.getMap<Any, Any>("iaa-edges").clear()   // evict values; the adjacency topology survives

        val result = g.inEdges(to).toList()
        assertEquals(3, result.size, "evicted values are reloaded from the store because the index is authoritative")

        listOf("iaa-nodes", "iaa-edges", "iaa-edges-adjacency").forEach { graphTestHz.getMap<Any, Any>(it).clear() }
    }

    // Phase 2: eviction on the adjacency map silently corrupts traversal (partial eviction is invisible
    // to the per-node warm-check), so construction must fail fast.
    @Test fun `construction fails fast if the adjacency map has eviction configured`() {
        val cfg = Config().setClusterName("iaa-guard-test").registerAbyssSerializers(huid, graphTestModule)
        cfg.getMapConfig("guard-edges-adjacency").evictionConfig.evictionPolicy = EvictionPolicy.LRU   // forbidden
        val hz = Hazelcast.newHazelcastInstance(cfg)
        try {
            assertFailsWith<IllegalArgumentException> {
                AbyssGraphSchema(UuidKeyAdapter, hz, "guard-nodes", "guard-edges", module = graphTestModule)
            }
        } finally {
            hz.shutdown()
        }
    }

    // TODO 1.29 item 3: findMapConfig always throws UnsupportedOperationException on a Hazelcast
    // client instance (verified against 5.6.0 sources — ClientDynamicClusterConfig's read methods are
    // unconditionally unsupported), so the guard above used to be silently skipped on a client
    // connection instead of failing fast. Point a real client at a member whose adjacency map has
    // eviction configured, by the member's own bound address (not multicast, for a deterministic test).
    @Test fun `construction fails fast on a client connection when eviction can't be verified`() {
        val cfg = Config().setClusterName("iaa-client-test").registerAbyssSerializers(huid, graphTestModule)
        cfg.getMapConfig("guard-client-edges-adjacency").evictionConfig.evictionPolicy = EvictionPolicy.LRU
        val member = Hazelcast.newHazelcastInstance(cfg)
        val addr = member.cluster.localMember.address
        val client = HazelcastClient.newHazelcastClient(
            ClientConfig().setClusterName("iaa-client-test").apply { networkConfig.addAddress("${addr.host}:${addr.port}") }
        )
        try {
            assertFailsWith<IllegalArgumentException> {
                AbyssGraphSchema(UuidKeyAdapter, client, "guard-client-nodes", "guard-client-edges", module = graphTestModule)
            }
        } finally {
            client.shutdown()
            member.shutdown()
        }
    }

    @Test fun `evictionVerifiedExternally lets client-mode construction proceed`() {
        val cfg = Config().setClusterName("iaa-client-test-2").registerAbyssSerializers(huid, graphTestModule)
        cfg.getMapConfig("guard-client2-edges-adjacency").evictionConfig.evictionPolicy = EvictionPolicy.LRU
        val member = Hazelcast.newHazelcastInstance(cfg)
        val addr = member.cluster.localMember.address
        val client = HazelcastClient.newHazelcastClient(
            ClientConfig().setClusterName("iaa-client-test-2").apply { networkConfig.addAddress("${addr.host}:${addr.port}") }
        )
        try {
            AbyssGraphSchema(
                UuidKeyAdapter, client, "guard-client2-nodes", "guard-client2-edges",
                module = graphTestModule, evictionVerifiedExternally = true,
            )
        } finally {
            client.shutdown()
            member.shutdown()
        }
    }

    // TODO 1.29 item 3: in cache-only mode (no persistentStore, README-documented as supported), an
    // evicted edge has nothing to self-heal from — it's silently dropped from traversal results with
    // no error. Construction must now fail fast the same way the adjacency-map guard already does.
    @Test fun `cache-only mode fails fast if the edges map has eviction configured`() {
        val cfg = Config().setClusterName("iaa-cacheonly-guard-test").registerAbyssSerializers(huid, graphTestModule)
        cfg.getMapConfig("guard2-edges").evictionConfig.evictionPolicy = EvictionPolicy.LRU
        val hz = Hazelcast.newHazelcastInstance(cfg)
        try {
            assertFailsWith<IllegalArgumentException> {
                AbyssGraphSchema(UuidKeyAdapter, hz, "guard2-nodes", "guard2-edges", module = graphTestModule)
            }
        } finally {
            hz.shutdown()
        }
    }

    // Same eviction config on the edges map, but WITH a persistentStore: self-heals per TODO 1.27
    // Phase 2 (the earlier test in this file), so construction must NOT throw. Guards against
    // over-tightening the new cache-only-mode check above.
    @Test fun `edges map eviction is fine when a persistentStore is configured`() {
        val cfg = Config().setClusterName("iaa-withstore-guard-test").registerAbyssSerializers(huid, graphTestModule)
        cfg.getMapConfig("guard3-edges").evictionConfig.evictionPolicy = EvictionPolicy.LRU
        val hz = Hazelcast.newHazelcastInstance(cfg)
        try {
            AbyssGraphSchema(UuidKeyAdapter, hz, "guard3-nodes", "guard3-edges", persistentStore = SelfHealStore(), module = graphTestModule)
        } finally {
            hz.shutdown()
        }
    }
}
