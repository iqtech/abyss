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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Real multi-schema traversals against the shared UniverseFixture. Each test proves the engine
// discriminates correctly (exact expected sets), not merely "non-empty".
class UniverseTraversalTest {

    private val universeHz by lazy {
        System.setProperty("hazelcast.logging.type", "none")
        Hazelcast.newHazelcastInstance(
            Config().setClusterName("graph-test-universe")
                .registerAbyssSerializers(MultiSchemaAdapter(SchemaTagWidth.BYTE), universeModule)
        )
    }

    private lateinit var universe: UniverseGraph
    private lateinit var data: UniverseData

    @BeforeTest fun setup() = runBlocking {
        clearUniverseMaps(universeHz)
        universe = UniverseGraph(universeHz)
        data = universe.build()
    }

    @Test fun usersInterestedInArtWhoLiveOnEarth() = runBlocking {
        // Art subtree derived from the graph, not hardcoded: incoming SubdomainOf closure from Art.
        val artSubtree = universe.interests.from(data.interestsByName.getValue("Art").id) {
            allReachable { incoming<SubdomainOf>() }
        }.getOrNull()!!.resolve<Interest>().map { it.name }.toSet()
        assertEquals(setOf("Art", "Music", "Singing", "PlayingInstrument"), artSubtree)
        val artIds = data.interestsByName.filterKeys { it in artSubtree }.values.map { it.id }.toSet()

        // Two independent per-user checks — the frontier ID type differs across the two hops
        // (Interest is Uuid, Astronomy is Long), so reaches/hasOutgoing(toId) can't span both.
        val result = data.users.filter { user ->
            val likesArt = universe.users.from(user.id) {
                outgoing<InterestedIn>()
                nodes<Interest> { it.id in artIds }
                collectNodes<Interest>().toList()
            }.getOrNull()!!.isNotEmpty()
            val onEarth = universe.users.from(user.id) {
                outgoing<LivesOn>()
                nodes<Planet> { it.name == "Earth" }
                collectNodes<Planet>().toList()
            }.getOrNull()!!.isNotEmpty()
            likesArt && onEarth
        }.map { it.id }.toSet()

        // alice lives on Earth but likes Astronomy/Chemistry (under Science), so she's excluded.
        assertEquals(setOf("bob", "frank"), result)
    }

    @Test fun subdomainReachesTransitively() = runBlocking {
        val math = data.interestsByName.getValue("Math").id
        val singing = data.interestsByName.getValue("Singing").id
        val science = data.interestsByName.getValue("Science").id
        assertTrue(universe.interests.from(math) { reaches(science) { outgoing<SubdomainOf>() } }.getOrNull()!!)
        assertFalse(universe.interests.from(singing) { reaches(science) { outgoing<SubdomainOf>() } }.getOrNull()!!)
    }

    @Test fun moonOrbitsPlanetOrbitsStarOrbitsSingularity() = runBlocking {
        val luna = (data.astroByName.getValue("Luna")).id
        val reached = universe.astronomy.from(luna) {
            outgoing<Orbits>(); outgoing<Orbits>(); outgoing<Orbits>()
            collectNodes<Singularity>().toList()
        }.getOrNull()!!
        assertEquals(listOf("Sagittarius A*"), reached.map { it.name })
    }

    @Test fun usersLivingOnAnyMoon() = runBlocking {
        val onMoon = data.users.filter { user ->
            universe.users.from(user.id) {
                hasOutgoing<LivesOn, Moon>()
                collectNodes<User>().toList()
            }.getOrNull()!!.isNotEmpty()
        }.map { it.id }.toSet()
        assertEquals(setOf("carol", "heidi"), onMoon)
    }
}
