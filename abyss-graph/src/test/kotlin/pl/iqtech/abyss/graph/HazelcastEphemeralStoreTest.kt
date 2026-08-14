package pl.iqtech.abyss.graph

import arrow.core.Either
import com.hazelcast.config.Config
import com.hazelcast.config.MapStoreConfig
import com.hazelcast.core.Hazelcast
import com.hazelcast.map.MapStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.store.api.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// The Hazelcast-backed default ephemeral store (RFC: ai-scripts/HazelcastEphemeralStoreRFC.md).
// Uses a real embedded HazelcastInstance throughout (graphTestHz, same fixture as
// IndexAlwaysAliveTest) rather than a fake — TTL decay via getEntryView is new usage in this
// codebase and needs to be verified against the real thing, not assumed.
class HazelcastEphemeralStoreTest {

    @Test fun `saveEdge then loadEdge round-trips with decaying remaining TTL`() {
        runBlocking {
            val store = HazelcastEphemeralStore(graphTestHz, "hes-edges", "hes-nodes")
            val from = huid.toNodeId(Uuid.random())
            val to = huid.toNodeId(Uuid.random())
            val edge = TestEdge(fromId = huid.fromNodeId(from), toId = huid.fromNodeId(to), label = "hes-edge")

            store.transaction { saveEdge(from, to, edge, 5.seconds, emptySet()) }

            val (loaded1, remaining1) = store.loadEdge(from, to, "test_edge").shouldBeRight()
            assertNotNull(loaded1)
            assertNotNull(remaining1)
            delay(200)
            val (loaded2, remaining2) = store.loadEdge(from, to, "test_edge").shouldBeRight()
            assertNotNull(loaded2)
            assertTrue(remaining2!! < remaining1!!, "remaining TTL decreases across reads: $remaining1 -> $remaining2")

            listOf("hes-edges", "hes-nodes").forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        }
    }

    @Test fun `edge is gone after TTL expires`() {
        runBlocking {
            val store = HazelcastEphemeralStore(graphTestHz, "hes-edges-exp", "hes-nodes-exp")
            val from = huid.toNodeId(Uuid.random())
            val to = huid.toNodeId(Uuid.random())
            val edge = TestEdge(fromId = huid.fromNodeId(from), toId = huid.fromNodeId(to), label = "hes-expiring")

            store.transaction { saveEdge(from, to, edge, 1.seconds, emptySet()) }
            assertNotNull(store.loadEdge(from, to, "test_edge").shouldBeRight().first)

            delay(1300)
            assertNull(store.loadEdge(from, to, "test_edge").shouldBeRight().first, "expired entry reads as a miss")

            listOf("hes-edges-exp", "hes-nodes-exp").forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        }
    }

    @Test fun `loadEdges scans by fromId and loadInEdges is always empty`() {
        runBlocking {
            val store = HazelcastEphemeralStore(graphTestHz, "hes-edges-scan", "hes-nodes-scan")
            val from = huid.toNodeId(Uuid.random())
            val to1 = huid.toNodeId(Uuid.random())
            val to2 = huid.toNodeId(Uuid.random())
            val other = huid.toNodeId(Uuid.random())

            store.transaction {
                saveEdge(from, to1, TestEdge(fromId = huid.fromNodeId(from), toId = huid.fromNodeId(to1), label = "a"), 60.seconds, emptySet())
                saveEdge(from, to2, TestEdge(fromId = huid.fromNodeId(from), toId = huid.fromNodeId(to2), label = "b"), 60.seconds, emptySet())
                saveEdge(other, to1, TestEdge(fromId = huid.fromNodeId(other), toId = huid.fromNodeId(to1), label = "c"), 60.seconds, emptySet())
            }

            val loaded = store.loadEdges(from).shouldBeRight()
            assertEquals(2, loaded.size)
            assertTrue(loaded.all { it.fromId == from })

            assertEquals(emptyList(), store.loadInEdges(to1).shouldBeRight())

            listOf("hes-edges-scan", "hes-nodes-scan").forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        }
    }

    @Test fun `transaction rolls back all ops when the block throws partway through`() {
        runBlocking {
            val store = HazelcastEphemeralStore(graphTestHz, "hes-edges-rollback", "hes-nodes-rollback")
            val id = huid.toNodeId(Uuid.random())
            val node = TestNode(id = huid.fromNodeId(id), name = "hes-rollback")

            val result = store.transaction {
                saveNode(id, node, 60.seconds, emptySet())
                error("boom")
            }

            assertTrue(result is Either.Left)
            assertNull(store.loadNode(id).shouldBeRight().first, "partial write must not survive a failed transaction")

            listOf("hes-edges-rollback", "hes-nodes-rollback").forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        }
    }

    @Test fun `deleteEdge and deleteNode remove entries`() {
        runBlocking {
            val store = HazelcastEphemeralStore(graphTestHz, "hes-edges-del", "hes-nodes-del")
            val from = huid.toNodeId(Uuid.random())
            val to = huid.toNodeId(Uuid.random())
            val node = TestNode(id = huid.fromNodeId(from), name = "hes-node")

            store.transaction {
                saveNode(from, node, 60.seconds, emptySet())
                saveEdge(from, to, TestEdge(fromId = huid.fromNodeId(from), toId = huid.fromNodeId(to), label = "d"), 60.seconds, emptySet())
            }
            store.transaction {
                deleteNode(from)
                deleteEdge(from, to, "test_edge")
            }

            assertNull(store.loadNode(from).shouldBeRight().first)
            assertNull(store.loadEdge(from, to, "test_edge").shouldBeRight().first)

            listOf("hes-edges-del", "hes-nodes-del").forEach { graphTestHz.getMap<Any, Any>(it).clear() }
        }
    }

    // TODO 1.23: plain ephNodes.keys enumeration, no server-side tag support — NodeLike<ID> carries
    // no tags field (TODO 1.24 moved tags into the DB table only), so a tag filter can't be honored
    // from this cache and must return empty rather than silently ignoring the filter.
    @Test fun `scanNodeIds enumerates cached node ids untagged, and returns empty when a tag is requested`() {
        runBlocking {
            val store = HazelcastEphemeralStore(graphTestHz, "hes-edges-scan2", "hes-nodes-scan2")
            val ids = List(3) { huid.toNodeId(Uuid.random()) }
            store.transaction {
                ids.forEachIndexed { i, id -> saveNode(id, TestNode(id = huid.fromNodeId(id), name = "hes-scan-$i"), 60.seconds, emptySet()) }
            }

            assertEquals(ids.toSet(), store.scanNodeIds().toList().toSet())
            assertEquals(emptyList(), store.scanNodeIds(tag = "anything").toList())

            graphTestHz.getMap<Any, Any>("hes-nodes-scan2").clear()
        }
    }

    // Mandatory guard, not documentation-only: MapStore on an ephemeral map would carry secrets to
    // disk, defeating the whole point of a memory-only store. Mirrors IndexAlwaysAliveTest's
    // "construction fails fast" shape for the adjacency-eviction guard.
    @Test fun `construction fails fast if an ephemeral map has a MapStore configured`() {
        val cfg = Config().setClusterName("hes-guard-test").registerAbyssSerializers(huid, graphTestModule)
        cfg.getMapConfig("guard-eph-edges").mapStoreConfig = MapStoreConfig().setEnabled(true).setImplementation(NoOpMapStore)
        val hz = Hazelcast.newHazelcastInstance(cfg)
        try {
            assertFailsWith<IllegalArgumentException> {
                HazelcastEphemeralStore(hz, "guard-eph-edges", "guard-eph-nodes")
            }
        } finally {
            hz.shutdown()
        }
    }
}

private object NoOpMapStore : MapStore<Any, Any> {
    override fun store(key: Any, value: Any) {}
    override fun storeAll(map: Map<Any, Any>) {}
    override fun delete(key: Any) {}
    override fun deleteAll(keys: MutableCollection<Any>) {}
    override fun load(key: Any): Any? = null
    override fun loadAll(keys: MutableCollection<Any>): MutableMap<Any, Any> = mutableMapOf()
    override fun loadAllKeys(): MutableIterable<Any>? = null
}

private fun <A, B> Either<A, B>.shouldBeRight(): B = when (this) {
    is Either.Right -> value
    is Either.Left -> throw AssertionError("expected Right, got Left($value)")
}
