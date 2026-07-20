package pl.iqtech.abyss.graph

import com.hazelcast.core.HazelcastInstance
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.LongKeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.SchemaKeyAdapter
import pl.iqtech.abyss.store.api.SchemaTag
import pl.iqtech.abyss.store.api.SchemaTagWidth
import pl.iqtech.abyss.store.api.StringKeyAdapter
import pl.iqtech.abyss.store.api.TypeTag
import pl.iqtech.abyss.store.api.UuidKeyAdapter
import kotlin.time.Instant
import kotlin.uuid.Uuid

// A reusable, non-toy multi-schema fixture for the TODO 1.14 engine: three real schemas on one
// HeterogeneousSchemaGraph container (users / astronomy / interests), a real type hierarchy in
// astronomy, and
// cross-schema edges whose target type varies per instance. Same-package convention as
// GraphTest.kt/MultiSchemaTest.kt: plain @Serializable data classes + a SerializersModule + helpers,
// no new abstraction.

// ── Users (String id) ─────────────────────────────────────────────────────────
@Serializable @SerialName("uni_user") @TypeTag(1)
data class User(
    override val id: String,   // the username doubles as the key
    val age: Int = 0,
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
) : NodeLike<String>

// ── Astronomy (Long id, one shared keyspace across all four node types) ─────────
@Serializable @SerialName("uni_star") @TypeTag(2)
data class Star(override val id: Long, val name: String, val mass: Double = 0.0, override val createdAt: Instant = Instant.fromEpochSeconds(0), override val updatedAt: Instant = Instant.fromEpochSeconds(0)) : NodeLike<Long>

@Serializable @SerialName("uni_planet") @TypeTag(3)
data class Planet(override val id: Long, val name: String, val mass: Double = 0.0, override val createdAt: Instant = Instant.fromEpochSeconds(0), override val updatedAt: Instant = Instant.fromEpochSeconds(0)) : NodeLike<Long>

@Serializable @SerialName("uni_moon") @TypeTag(4)
data class Moon(override val id: Long, val name: String, val mass: Double = 0.0, override val createdAt: Instant = Instant.fromEpochSeconds(0), override val updatedAt: Instant = Instant.fromEpochSeconds(0)) : NodeLike<Long>

@Serializable @SerialName("uni_singularity") @TypeTag(5)
data class Singularity(override val id: Long, val name: String, val mass: Double = 0.0, override val createdAt: Instant = Instant.fromEpochSeconds(0), override val updatedAt: Instant = Instant.fromEpochSeconds(0)) : NodeLike<Long>

// ── Interests (Uuid id, self-referencing hierarchy) ─────────────────────────────
@Serializable @SerialName("uni_interest") @TypeTag(6)
data class Interest(override val id: Uuid, val name: String, override val createdAt: Instant = Instant.fromEpochSeconds(0), override val updatedAt: Instant = Instant.fromEpochSeconds(0)) : NodeLike<Uuid>

// ── Edges ───────────────────────────────────────────────────────────────────────
@Serializable @SerialName("uni_orbits") @TypeTag(1)   // orbiter → orbited (Moon→Planet, Planet→Star, Star→Singularity)
data class Orbits(override val fromId: Long, override val toId: Long, override val createdAt: Instant = Instant.fromEpochSeconds(0), override val updatedAt: Instant = Instant.fromEpochSeconds(0)) : EdgeLike<Long, Long>

@Serializable @SerialName("uni_subdomain_of") @TypeTag(2)   // child → parent
data class SubdomainOf(override val fromId: Uuid, override val toId: Uuid, override val createdAt: Instant = Instant.fromEpochSeconds(0), override val updatedAt: Instant = Instant.fromEpochSeconds(0)) : EdgeLike<Uuid, Uuid>

@Serializable @SerialName("uni_interested_in") @TypeTag(3)   // User → Interest
data class InterestedIn(override val fromId: NodeId, override val toId: NodeId, override val createdAt: Instant = Instant.fromEpochSeconds(0), override val updatedAt: Instant = Instant.fromEpochSeconds(0)) : EdgeLike<NodeId, NodeId>

@Serializable @SerialName("uni_lives_on") @TypeTag(4)   // User → Astronomy (target is a Planet or a Moon)
data class LivesOn(override val fromId: NodeId, override val toId: NodeId, override val createdAt: Instant = Instant.fromEpochSeconds(0), override val updatedAt: Instant = Instant.fromEpochSeconds(0)) : EdgeLike<NodeId, NodeId>

object UniverseTags { val USERS = SchemaTag(1L); val ASTRONOMY = SchemaTag(2L); val INTERESTS = SchemaTag(3L) }

val universeModule = SerializersModule {
    polymorphic(NodeLike::class) {
        subclass(User::class)
        subclass(Star::class); subclass(Planet::class); subclass(Moon::class); subclass(Singularity::class)
        subclass(Interest::class)
    }
    polymorphic(EdgeLike::class) {
        subclass(Orbits::class); subclass(SubdomainOf::class)
        subclass(InterestedIn::class); subclass(LivesOn::class)
    }
}

private val userKeys = SchemaKeyAdapter(UniverseTags.USERS, SchemaTagWidth.BYTE, StringKeyAdapter)
private val astroKeys = SchemaKeyAdapter(UniverseTags.ASTRONOMY, SchemaTagWidth.BYTE, LongKeyAdapter)
private val interestKeys = SchemaKeyAdapter(UniverseTags.INTERESTS, SchemaTagWidth.BYTE, UuidKeyAdapter)

/** Node lookups keyed by name so tests reference nodes without knowing the generated ids. */
data class UniverseData(
    val interestsByName: Map<String, Interest>,
    val astroByName: Map<String, NodeLike<Long>>,
    val users: List<User>,
)

/** One HeterogeneousSchemaGraph container fronting the three schemas; cross-schema edges enabled. */
class UniverseGraph(hz: HazelcastInstance, nodesMapName: String = "uni-nodes", edgesMapName: String = "uni-edges") {
    val container = HeterogeneousSchemaGraph(hz, SchemaTagWidth.BYTE, nodesMapName, edgesMapName, allowCrossSchemaEdges = true, module = universeModule)
    val users = container.register(UniverseTags.USERS, StringKeyAdapter)
    val astronomy = container.register(UniverseTags.ASTRONOMY, LongKeyAdapter)
    val interests = container.register(UniverseTags.INTERESTS, UuidKeyAdapter)
}

/** Populates all three schemas plus the cross-schema edges, then returns name→node lookups. */
suspend fun UniverseGraph.build(): UniverseData {
    // Interests: two roots, one subtree under each. Art subtree is {Art, Music, Singing, PlayingInstrument}.
    val science = Interest(Uuid.random(), "Science")
    val art = Interest(Uuid.random(), "Art")
    val music = Interest(Uuid.random(), "Music")
    val astronomyI = Interest(Uuid.random(), "Astronomy")
    val math = Interest(Uuid.random(), "Math")
    val chemistry = Interest(Uuid.random(), "Chemistry")
    val singing = Interest(Uuid.random(), "Singing")
    val playing = Interest(Uuid.random(), "PlayingInstrument")
    val interestNodes = listOf(science, art, music, astronomyI, math, chemistry, singing, playing)
    interests.transaction {
        interestNodes.forEach { addNode(it) }
        addEdge(SubdomainOf(music.id, art.id))
        addEdge(SubdomainOf(astronomyI.id, science.id))
        addEdge(SubdomainOf(math.id, science.id))
        addEdge(SubdomainOf(chemistry.id, science.id))
        addEdge(SubdomainOf(singing.id, music.id))
        addEdge(SubdomainOf(playing.id, music.id))
    }

    // Astronomy: one sequential counter across all four types (shared keyspace).
    var seq = 0L
    val sagA = Singularity(++seq, "Sagittarius A*", 4.1e6)
    val sun = Star(++seq, "Sun", 1.0)
    val mercury = Planet(++seq, "Mercury", 0.055)
    val venus = Planet(++seq, "Venus", 0.815)
    val earth = Planet(++seq, "Earth", 1.0)
    val mars = Planet(++seq, "Mars", 0.107)
    val luna = Moon(++seq, "Luna", 0.0123)
    val phobos = Moon(++seq, "Phobos", 1.8e-9)
    val deimos = Moon(++seq, "Deimos", 2.4e-10)
    val kepler186 = Star(++seq, "Kepler-186", 0.54)
    val kepler186f = Planet(++seq, "Kepler-186f", 1.4)
    val trappist1 = Star(++seq, "TRAPPIST-1", 0.089)
    val trappist1e = Planet(++seq, "TRAPPIST-1e", 0.69)
    val trappist1f = Planet(++seq, "TRAPPIST-1f", 1.04)

    // TODO 3.4: a second, same-shaped system (1 singularity + 3 stars + 7 planets + 3 moons,
    // mirroring Sol/Kepler/TRAPPIST above) roughly doubling the astronomy graph for the concurrency
    // benchmark. Entirely new names — existing lookups by name (e.g. "Luna", "Earth") are untouched.
    val m87 = Singularity(++seq, "M87*", 6.5e9)
    val alphaCenA = Star(++seq, "Alpha Centauri A", 1.1)
    val alphaCenAb = Planet(++seq, "Alpha Centauri Ab", 0.5)
    val alphaCenBb = Planet(++seq, "Alpha Centauri Bb", 0.9)
    val proximaB = Planet(++seq, "Proxima b", 1.3)
    val toi700d = Planet(++seq, "TOI-700 d", 1.7)
    val titan = Moon(++seq, "Titan", 0.0225)
    val europa = Moon(++seq, "Europa", 0.008)
    val ganymede = Moon(++seq, "Ganymede", 0.025)
    val pegasi51 = Star(++seq, "51 Pegasi", 1.06)
    val pegasi51b = Planet(++seq, "51 Pegasi b", 0.47)
    val gliese581 = Star(++seq, "Gliese 581", 0.31)
    val gliese581c = Planet(++seq, "Gliese 581c", 5.6)
    val gliese581d = Planet(++seq, "Gliese 581d", 7.7)

    val astroByName: Map<String, NodeLike<Long>> = listOf(
        sagA, sun, mercury, venus, earth, mars, luna, phobos, deimos,
        kepler186, kepler186f, trappist1, trappist1e, trappist1f,
        m87, alphaCenA, alphaCenAb, alphaCenBb, proximaB, toi700d, titan, europa, ganymede,
        pegasi51, pegasi51b, gliese581, gliese581c, gliese581d,
    ).associateBy {
        when (it) { is Star -> it.name; is Planet -> it.name; is Moon -> it.name; is Singularity -> it.name; else -> error("unreachable") }
    }
    astronomy.transaction {
        astroByName.values.forEach { addNode(it) }
        // Sol system
        addEdge(Orbits(sun.id, sagA.id))
        listOf(mercury, venus, earth, mars).forEach { addEdge(Orbits(it.id, sun.id)) }
        addEdge(Orbits(luna.id, earth.id))
        addEdge(Orbits(phobos.id, mars.id)); addEdge(Orbits(deimos.id, mars.id))
        // Kepler — shares the singularity with Sol (fan-in on Sagittarius A*)
        addEdge(Orbits(kepler186.id, sagA.id))
        addEdge(Orbits(kepler186f.id, kepler186.id))
        // TRAPPIST
        addEdge(Orbits(trappist1e.id, trappist1.id)); addEdge(Orbits(trappist1f.id, trappist1.id))

        // Second system (TODO 3.4), same shape as above
        addEdge(Orbits(alphaCenA.id, m87.id))
        listOf(alphaCenAb, alphaCenBb, proximaB, toi700d).forEach { addEdge(Orbits(it.id, alphaCenA.id)) }
        addEdge(Orbits(titan.id, proximaB.id))
        addEdge(Orbits(europa.id, toi700d.id)); addEdge(Orbits(ganymede.id, toi700d.id))
        addEdge(Orbits(pegasi51.id, m87.id))
        addEdge(Orbits(pegasi51b.id, pegasi51.id))
        addEdge(Orbits(gliese581c.id, gliese581.id)); addEdge(Orbits(gliese581d.id, gliese581.id))
    }

    // Users
    val users = listOf(
        User("alice", 30), User("bob", 25), User("carol", 41), User("dave", 33),
        User("eve", 28), User("frank", 37), User("grace", 22), User("heidi", 45),
    )
    this.users.transaction { users.forEach { addNode(it) } }

    // Cross-schema edges (endpoints must already exist — all committed above).
    fun interestedIn(user: String, interest: Interest) =
        InterestedIn(userKeys.toNodeId(user), interestKeys.toNodeId(interest.id))
    fun livesOn(user: String, body: String) =
        LivesOn(userKeys.toNodeId(user), astroKeys.toNodeId(astroByName.getValue(body).id))

    listOf(
        interestedIn("alice", astronomyI), interestedIn("alice", chemistry),
        interestedIn("bob", music), interestedIn("bob", astronomyI),
        interestedIn("carol", math),
        interestedIn("dave", astronomyI),
        interestedIn("eve", art),
        interestedIn("frank", singing),
        interestedIn("grace", chemistry),
        interestedIn("heidi", playing),
    ).forEach { container.addCrossEdge(it) }

    listOf(
        livesOn("alice", "Earth"), livesOn("bob", "Earth"), livesOn("frank", "Earth"),
        livesOn("carol", "Luna"), livesOn("dave", "Mars"), livesOn("heidi", "Phobos"),
        livesOn("eve", "Kepler-186f"), livesOn("grace", "TRAPPIST-1e"),
    ).forEach { container.addCrossEdge(it) }

    return UniverseData(interestNodes.associateBy { it.name }, astroByName, users)
}

/** Clears the shared node/edge/adjacency maps (cross edges share these) — call from @BeforeTest. */
fun clearUniverseMaps(hz: HazelcastInstance) {
    listOf("uni-nodes", "uni-edges", "uni-edges-adjacency").forEach { hz.getMap<Any, Any>(it).clear() }
}
