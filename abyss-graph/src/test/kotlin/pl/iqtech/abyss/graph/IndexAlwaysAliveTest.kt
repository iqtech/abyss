package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.right
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
}
