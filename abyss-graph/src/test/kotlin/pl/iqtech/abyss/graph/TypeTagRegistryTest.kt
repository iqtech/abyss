package pl.iqtech.abyss.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import pl.iqtech.abyss.dsl.serialName
import pl.iqtech.abyss.dsl.typeTag
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.TypeTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Dedicated to TypeTagRegistry's Short <-> String <-> Short bijection alone: edgeTagOf/edgeNameOf
// must be true inverses of each other, and the two independent routes production code uses to reach
// the same tag (direct @TypeTag read at AddEdge time vs name-indexed lookup at RemoveEdge time) must
// agree — no Hazelcast instance needed, this is pure-function coverage over the registry itself.
class TypeTagRegistryTest {

    private val module = SerializersModule {
        polymorphic(NodeLike::class) { subclass(RtNode::class) }
        polymorphic(EdgeLike::class) { subclass(RtEdgeA::class); subclass(RtEdgeB::class) }
    }
    private val registry = TypeTagRegistry.of(module)

    @Test fun `edgeNameOf undoes edgeTagOf for every registered type`() {
        assertEquals("rt_edge_a", registry.edgeNameOf(registry.edgeTagOf("rt_edge_a")))
        assertEquals("rt_edge_b", registry.edgeNameOf(registry.edgeTagOf("rt_edge_b")))
    }

    @Test fun `edgeTagOf undoes edgeNameOf for every registered tag`() {
        val tagA = RtEdgeA::class.typeTag()
        val tagB = RtEdgeB::class.typeTag()
        assertEquals(tagA, registry.edgeTagOf(registry.edgeNameOf(tagA)))
        assertEquals(tagB, registry.edgeTagOf(registry.edgeNameOf(tagB)))
    }

    @Test fun `edgeTagOf agrees with the class's own @TypeTag annotation`() {
        // Production code reaches the same tag two different ways: AddEdge reads @TypeTag directly
        // off the live edge instance's class; RemoveEdge only has a type string and must resolve it
        // through this name-indexed map. Both must land on the same Short for the same class, or an
        // edge added under one path would never match itself for removal under the other.
        assertEquals(RtEdgeA::class.typeTag(), registry.edgeTagOf(RtEdgeA::class.serialName()))
        assertEquals(RtEdgeB::class.typeTag(), registry.edgeTagOf(RtEdgeB::class.serialName()))
    }

    @Test fun `distinct edge types round-trip to distinct tags`() {
        val tagA = registry.edgeTagOf("rt_edge_a")
        val tagB = registry.edgeTagOf("rt_edge_b")
        assertNotEquals(tagA, tagB)
        assertNotEquals(registry.edgeNameOf(tagA), registry.edgeNameOf(tagB))
    }

    @Test fun `edgeTagOf fails loudly for an unregistered type string`() {
        assertFails { registry.edgeTagOf("not_a_real_type") }
    }

    @Test fun `edgeNameOf fails loudly for an unknown tag`() {
        assertFails { registry.edgeNameOf(Short.MAX_VALUE) }
    }
}

@Serializable @SerialName("rt_node") @TypeTag(1)
private data class RtNode(
    override val id: Uuid,
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
) : NodeLike<Uuid>

@Serializable @SerialName("rt_edge_a") @TypeTag(1)
private data class RtEdgeA(
    override val fromId: Uuid,
    override val toId: Uuid,
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
) : EdgeLike<Uuid, Uuid>

@Serializable @SerialName("rt_edge_b") @TypeTag(2)
private data class RtEdgeB(
    override val fromId: Uuid,
    override val toId: Uuid,
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
) : EdgeLike<Uuid, Uuid>
