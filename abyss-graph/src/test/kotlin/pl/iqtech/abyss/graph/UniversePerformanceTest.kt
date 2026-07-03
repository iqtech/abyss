package pl.iqtech.abyss.graph

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.allReachable
import pl.iqtech.abyss.dsl.collectNodes
import pl.iqtech.abyss.dsl.hasOutgoing
import pl.iqtech.abyss.dsl.incoming
import pl.iqtech.abyss.dsl.nodes
import pl.iqtech.abyss.dsl.outgoing
import pl.iqtech.abyss.dsl.reaches
import pl.iqtech.abyss.dsl.resolve
import pl.iqtech.abyss.store.api.MultiSchemaAdapter
import pl.iqtech.abyss.store.api.SchemaTagWidth
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

// Times the four UniverseTraversalTest queries against the shared UniverseFixture. Perf-gated the same
// way as the other *PerformanceTest classes (skipped unless -Dperf is set) so normal CI stays fast.
// The graph is tiny (30 nodes / 36 edges, cache-only), so these are latency-per-query numbers, not a
// throughput benchmark — the point is a repeatable per-traversal cost, warmed and averaged.
class UniversePerformanceTest {

    companion object {
        private const val WARMUP = 50
        private const val RUNS = 500

        private val perfHz by lazy {
            System.setProperty("hazelcast.logging.type", "none")
            Hazelcast.newHazelcastInstance(
                Config().setClusterName("graph-test-universe-perf")
                    .registerAbyssSerializers(MultiSchemaAdapter(SchemaTagWidth.BYTE), universeModule)
            )
        }

        private val universe: UniverseGraph by lazy {
            UniverseGraph(perfHz, "perf-uni-nodes", "perf-uni-edges")
        }
        private val data: UniverseData by lazy { runBlocking { universe.build() } }
    }

    private fun bench(name: String, warmup: suspend () -> Unit, body: suspend () -> Unit) {
        runBlocking { repeat(WARMUP) { warmup() } }
        val elapsed = measureTime { runBlocking { repeat(RUNS) { body() } } }
        val msEach = elapsed.inWholeMicroseconds.toDouble() / RUNS / 1000.0
        println("\n$name: ${"%.3f".format(msEach)}ms avg  ($RUNS runs, ${elapsed.inWholeMilliseconds}ms)")
        assertTrue(msEach < 500, "$name regressed: ${msEach}ms")
    }

    @Test fun `usersInterestedInArtWhoLiveOnEarth timing`() {
        if (System.getProperty("perf") == null) return
        val artId = data.interestsByName.getValue("Art").id
        val query: suspend () -> Unit = {
            val artIds = universe.interests.from(artId) { allReachable { incoming<SubdomainOf>() } }
                .getOrNull()!!.resolve<Interest>().map { it.id }.toSet()
            data.users.filter { user ->
                val likesArt = universe.users.from(user.id) {
                    outgoing<InterestedIn>(); nodes<Interest> { it.id in artIds }; collectNodes<Interest>().toList()
                }.getOrNull()!!.isNotEmpty()
                val onEarth = universe.users.from(user.id) {
                    outgoing<LivesOn>(); nodes<Planet> { it.name == "Earth" }; collectNodes<Planet>().toList()
                }.getOrNull()!!.isNotEmpty()
                likesArt && onEarth
            }
        }
        bench("usersInterestedInArtWhoLiveOnEarth", query, query)
    }

    @Test fun `subdomainReachesTransitively timing`() {
        if (System.getProperty("perf") == null) return
        val math = data.interestsByName.getValue("Math").id
        val science = data.interestsByName.getValue("Science").id
        val query: suspend () -> Unit = {
            universe.interests.from(math) { reaches(science) { outgoing<SubdomainOf>() } }.getOrNull()!!
        }
        bench("subdomainReachesTransitively", query, query)
    }

    @Test fun `moonOrbitsPlanetOrbitsStarOrbitsSingularity timing`() {
        if (System.getProperty("perf") == null) return
        val luna = data.astroByName.getValue("Luna").id
        val query: suspend () -> Unit = {
            universe.astronomy.from(luna) {
                outgoing<Orbits>(); outgoing<Orbits>(); outgoing<Orbits>(); collectNodes<Singularity>().toList()
            }.getOrNull()!!
        }
        bench("moonOrbitsPlanetOrbitsStarOrbitsSingularity", query, query)
    }

    @Test fun `usersLivingOnAnyMoon timing`() {
        if (System.getProperty("perf") == null) return
        val query: suspend () -> Unit = {
            data.users.filter { user ->
                universe.users.from(user.id) { hasOutgoing<LivesOn, Moon>(); collectNodes<User>().toList() }
                    .getOrNull()!!.isNotEmpty()
            }
        }
        bench("usersLivingOnAnyMoon", query, query)
    }
}
