package pl.iqtech.abyss.graph

import arrow.core.Either
import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.collectNodes
import pl.iqtech.abyss.dsl.inEdges
import pl.iqtech.abyss.dsl.nodes
import pl.iqtech.abyss.dsl.outEdges
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.store.api.HeaderlessKeyAdapter
import pl.iqtech.abyss.store.api.LongKeyAdapter
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// TODO 2.1: every other Hazelcast-backed test in this module runs against exactly one embedded
// member, where PartitionAware co-location and partition-scoped predicate reads (outAt's fast
// path, outEdgeFlow, adjacencyRead, cascadeEdgeRemovals — AbyssSchemaWorker.kt) are trivially
// "correct" because every partition is local. This starts a genuine 3-member in-process cluster
// (same cluster name, real newHazelcastInstance() x3) so those code paths are actually exercised
// with cross-member routing. Gated behind -Pcluster (mirrors the -Pperf gate): starting 3 real
// members and waiting for the partition table to settle is much slower than the rest of the suite.
class MultiMemberClusterTest {

    companion object {
        private const val MEMBER_COUNT = 3
        private const val NODE_COUNT = 60
        private const val EDGES_PER_NODE = 4
        private val clusterName = "cluster-test-${System.nanoTime()}"
        private val hlong = HeaderlessKeyAdapter(LongKeyAdapter)

        private val membersLazy = lazy {
            System.setProperty("hazelcast.logging.type", "none")
            val instances = (1..MEMBER_COUNT).map {
                Hazelcast.newHazelcastInstance(
                    Config().setClusterName(clusterName).registerAbyssSerializers(hlong, graphTestModule)
                )
            }
            awaitSafeCluster(instances)
            instances
        }
        private val members: List<HazelcastInstance> by membersLazy

        // Any member is a valid handle: IMap ops issued through it route to whichever member
        // actually owns the target partition — using a fixed member here is what genuinely
        // exercises cross-member routing, rather than a client pinned to one peer.
        private val hz: HazelcastInstance get() = members[0]

        private val graph: AbyssGraphSchema<Long> by lazy {
            SingleSchemaGraph(LongKeyAdapter, hz, "cluster-nodes", "cluster-edges", module = graphTestModule)
        }

        // Hand-rolled wait loop: TestHazelcastInstanceFactory/TestUtil.waitAllForSafeState aren't on
        // the classpath (they live in a Hazelcast test-jar this repo doesn't depend on). Poll until
        // every member reports full membership, every partition has a non-null owner, and the
        // cluster-wide safe-state check passes (no pending migrations / under-replicated backups).
        private fun awaitSafeCluster(instances: List<HazelcastInstance>, timeoutMs: Long = 60_000) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val fullMembership = instances.all { it.cluster.members.size == instances.size }
                val noNullOwners = instances[0].partitionService.partitions.all { it.owner != null }
                val clusterSafe = instances.all { it.partitionService.isClusterSafe }
                if (fullMembership && noNullOwners && clusterSafe) return
                Thread.sleep(200)
            }
            error("Cluster of ${instances.size} members did not reach a safe partition state within ${timeoutMs}ms")
        }

        // Guarded by isInitialized() so a run where every @Test short-circuited on the -Pcluster
        // gate never starts (and thus never needs to stop) any real Hazelcast members.
        @JvmStatic
        @AfterClass
        fun shutdownCluster() {
            if (membersLazy.isInitialized()) members.forEach { it.shutdown() }
        }
    }

    @BeforeTest fun clear() {
        if (System.getProperty("cluster") == null) return
        hz.getMap<Any, Any>("cluster-nodes").clear()
        hz.getMap<Any, Any>("cluster-edges").clear()
        hz.getMap<Any, Any>("cluster-edges-adjacency").clear()
    }

    @Test fun `multi-hop traversal, incoming edges, and cascade delete are correct across a real 3-member cluster`() {
        if (System.getProperty("cluster") == null) return
        runBlocking {
            // hub(1) -> mid(2) -> leaf(3); other(4) -> hub(1)
            graph.transaction {
                addNode(LongTestNode(1L, name = "hub")); addNode(LongTestNode(2L, name = "mid"))
                addNode(LongTestNode(3L, name = "leaf")); addNode(LongTestNode(4L, name = "other"))
                addEdge(LongTestEdge(fromId = 1L, toId = 2L))
                addEdge(LongTestEdge(fromId = 2L, toId = 3L))
                addEdge(LongTestEdge(fromId = 4L, toId = 1L))
            }

            // outEdgeFlow's partition-predicate scan (AbyssSchemaWorker.kt:187-191).
            val out1 = graph.outEdges(1L, "long_test_edge").toList()
            assertEquals(listOf(2L), out1.map { it.toId })

            // outAt's needValue=true fast path (AbyssSchemaWorker.kt:131-143) — the predicate form
            // of outgoing<E> sets needValue=true; the no-predicate overload does not.
            val twoHop = graph.from(1L) {
                outgoing<LongTestEdge> { true }
                outgoing<LongTestEdge> { true }
                nodes<LongTestNode>()
                collectNodes<LongTestNode>().toList()
            }
            assertIs<Either.Right<List<LongTestNode>>>(twoHop)
            assertEquals(listOf(3L), twoHop.value.map { it.id })

            // adjacencyRead's batched cross-partition getAll (AbyssSchemaWorker.kt:154-169).
            val in1 = graph.inEdges(1L).toList()
            assertEquals(listOf(4L), in1.map { it.fromId })

            // cascadeEdgeRemovals: OUT via partition-predicate scan, IN via adjacencyRead
            // (AbyssSchemaWorker.kt:311-323), both against the real cluster.
            assertIs<Either.Right<Unit>>(graph.transaction { removeNode(1L) })
            assertIs<Either.Left<*>>(graph.node(1L))
            assertIs<Either.Left<*>>(graph.edge(1L, 2L, "long_test_edge"))
            assertIs<Either.Left<*>>(graph.edge(4L, 1L, "long_test_edge"))
            assertIs<Either.Right<*>>(graph.node(2L)) // mid survives — only its inbound edge is gone
        }
    }

    @Test fun `EdgeKey and AdjacencyKey partitions co-locate per node and are genuinely spread across cluster members`() {
        if (System.getProperty("cluster") == null) return
        runBlocking {
            graph.transaction {
                (1L..NODE_COUNT).forEach { addNode(LongTestNode(it, name = "n$it")) }
                (1L..NODE_COUNT).forEach { from ->
                    (1..EDGES_PER_NODE).forEach { k ->
                        val to = ((from - 1 + k) % NODE_COUNT) + 1
                        addEdge(LongTestEdge(fromId = from, toId = to))
                    }
                }
            }
        }

        // Real stored keys only — EdgeKey/AdjacencyKey's default constructor pk (fromId.toString())
        // is never what production actually stamps on the key (see AbyssSchemaWorker.edgeKey/
        // outKeyFor/inKeyFor), so hand-reconstructing keys here would query the wrong partition.
        // equals()/hashCode() on both classes ignore pk, so filtering the real key set is safe.
        // IMap's keySet()/entrySet() overload set (inherited from both ConcurrentMap and BaseMap,
        // the latter also declaring a Predicate-taking overload) trips up Kotlin overload
        // resolution, and IMap being simultaneously a Map and an Iterable<Map.Entry<K,V>> makes
        // even `.map { }` ambiguous. A plain for-loop over the Iterable side sidesteps all of it.
        val edgeKeys = buildSet { for (e in hz.getMap<EdgeKey, Any>("cluster-edges")) add(e.key) }
        val adjKeys = buildSet { for (e in hz.getMap<AdjacencyKey, AdjacencyValue>("cluster-edges-adjacency")) add(e.key) }
        val ps = hz.partitionService

        val edgeOwners = mutableSetOf<Any>()
        (1L..NODE_COUNT).forEach { nid0 ->
            val nid = hlong.toNodeId(nid0)
            val forNode = edgeKeys.filter { it.fromId == nid }
            assertTrue(forNode.size >= 2, "expected >=2 EdgeKeys for node $nid0 to make co-location meaningful")
            val partitionIds = forNode.map { ps.getPartition(it).partitionId }.toSet()
            assertEquals(1, partitionIds.size, "EdgeKeys for node $nid0 span multiple partitions: $partitionIds")
            edgeOwners += ps.getPartition(forNode.first()).owner!!.uuid

            val adjForNode = adjKeys.filter { it.nodeId == nid }
            assertTrue(adjForNode.size >= 2, "expected >=2 AdjacencyKeys for node $nid0")
            val adjPartitionIds = adjForNode.map { ps.getPartition(it).partitionId }.toSet()
            assertEquals(1, adjPartitionIds.size, "AdjacencyKeys for node $nid0 span multiple partitions: $adjPartitionIds")
        }

        // The assertion that proves this ran on a genuine multi-member cluster, not a degenerate
        // single-partition-owner case: with 60 nodes over 3 members and 271 default partitions,
        // seeing only 1 distinct owner would mean the cluster never actually spread.
        assertTrue(edgeOwners.size >= 2, "all sampled nodes' edges landed on one member — cluster did not spread: $edgeOwners")
    }
}
