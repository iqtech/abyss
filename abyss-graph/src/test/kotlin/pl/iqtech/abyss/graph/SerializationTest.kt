package pl.iqtech.abyss.graph

import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.plus
import kotlinx.serialization.modules.subclass
import pl.iqtech.abyss.graph.serialization.UnknownEdge
import pl.iqtech.abyss.graph.serialization.UnknownNode
import pl.iqtech.abyss.graph.serialization.createPolymorphicJsonSerializer
import pl.iqtech.abyss.graph.serialization.customJsonSerializer
import pl.iqtech.abyss.store.api.InstantSerializer
import pl.iqtech.abyss.store.api.UuidSerializer
import pl.iqtech.abyss.store.api.abyssSerializersModule
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// ── test-local domain types ──────────────────────────────────────────────────

@Serializable @SerialName("test_node")
data class TestNode(
    @Contextual override val id: UUID,
    override val tags: List<String> = emptyList(),
    @Contextual override val createdAt: Instant = Instant.EPOCH,
    @Contextual override val updatedAt: Instant = Instant.EPOCH,
    val name: String
) : NodeLike

@Serializable @SerialName("test_edge")
data class TestEdge(
    @Contextual override val fromId: UUID,
    @Contextual override val toId: UUID,
    override val tags: List<String> = emptyList(),
    @Contextual override val createdAt: Instant = Instant.EPOCH,
    @Contextual override val updatedAt: Instant = Instant.EPOCH,
    val label: String
) : EdgeLike

// ── fixtures ─────────────────────────────────────────────────────────────────

private val testModule = SerializersModule {
    polymorphic(NodeLike::class) { subclass(TestNode::class) }
    polymorphic(EdgeLike::class) { subclass(TestEdge::class) }
}

private fun baseJson() = Json(from = customJsonSerializer) {
    serializersModule = abyssSerializersModule + testModule
}

private val nodeJson = createPolymorphicJsonSerializer<NodeLike>(baseJson()) { UnknownNode(it) }
private val edgeJson = createPolymorphicJsonSerializer<EdgeLike>(baseJson()) { UnknownEdge(it) }
private val nodeSer  = PolymorphicSerializer(NodeLike::class)
private val edgeSer  = PolymorphicSerializer(EdgeLike::class)

// ── tests ────────────────────────────────────────────────────────────────────

class SerializationTest {

    @Test fun `UuidSerializer round-trip`() {
        val id = UUID.randomUUID()
        val json = customJsonSerializer.encodeToString(UuidSerializer, id)
        assertEquals(id, customJsonSerializer.decodeFromString(UuidSerializer, json))
    }

    @Test fun `InstantSerializer round-trip`() {
        val now = Instant.now()
        val json = customJsonSerializer.encodeToString(InstantSerializer, now)
        assertEquals(now, customJsonSerializer.decodeFromString(InstantSerializer, json))
    }

    @Test fun `TestNode round-trips through polymorphic JSON`() {
        val node = TestNode(id = UUID.randomUUID(), name = "sniper")
        val encoded = nodeJson.encodeToString(nodeSer, node)
        assertEquals(node, nodeJson.decodeFromString(nodeSer, encoded))
    }

    @Test fun `TestEdge round-trips through polymorphic JSON`() {
        val edge = TestEdge(fromId = UUID.randomUUID(), toId = UUID.randomUUID(), label = "has_rifle")
        val encoded = edgeJson.encodeToString(edgeSer, edge)
        assertEquals(edge, edgeJson.decodeFromString(edgeSer, encoded))
    }

    @Test fun `unknown node type falls back to UnknownNode`() {
        val node = TestNode(id = UUID.randomUUID(), name = "sniper")
        val withUnknownType = nodeJson.encodeToString(nodeSer, node).replace("\"test_node\"", "\"future_type\"")
        assertIs<UnknownNode>(nodeJson.decodeFromString(nodeSer, withUnknownType))
    }

    @Test fun `unknown edge type falls back to UnknownEdge`() {
        val edge = TestEdge(fromId = UUID.randomUUID(), toId = UUID.randomUUID(), label = "has_rifle")
        val withUnknownType = edgeJson.encodeToString(edgeSer, edge).replace("\"test_edge\"", "\"future_type\"")
        assertIs<UnknownEdge>(edgeJson.decodeFromString(edgeSer, withUnknownType))
    }

    @Test fun `UnknownNode preserves id from raw JSON`() {
        val id = UUID.randomUUID()
        val node = TestNode(id = id, name = "sniper")
        val withUnknownType = nodeJson.encodeToString(nodeSer, node).replace("\"test_node\"", "\"future_type\"")
        val result = nodeJson.decodeFromString(nodeSer, withUnknownType) as UnknownNode
        assertEquals(id, result.id)
    }
}
