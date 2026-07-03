package pl.iqtech.abyss.graph

import kotlin.time.Instant
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.plus
import kotlinx.serialization.modules.subclass
import pl.iqtech.abyss.graph.serialization.UnknownEdge
import pl.iqtech.abyss.graph.serialization.UnknownNode
import pl.iqtech.abyss.graph.serialization.createPolymorphicJsonSerializer
import pl.iqtech.abyss.graph.serialization.customJsonSerializer
import pl.iqtech.abyss.store.api.abyssSerializersModule
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.SchemaEdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

// ── test-local domain types ──────────────────────────────────────────────────

@Serializable @SerialName("long_test_node")
data class LongTestNode(
    override val id: Long,
    val name: String = "",
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
) : NodeLike<Long>

@Serializable @SerialName("long_test_edge")
data class LongTestEdge(
    override val fromId: Long,
    override val toId: Long,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
) : SchemaEdgeLike<Long>

@Serializable @SerialName("str_test_node")
data class StrTestNode(
    override val id: String,
    val name: String = "",
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
) : NodeLike<String>

@Serializable @SerialName("str_test_edge")
data class StrTestEdge(
    override val fromId: String,
    override val toId: String,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
) : SchemaEdgeLike<String>

@Serializable @SerialName("test_node")
data class TestNode(
    override val id: Uuid,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
    val name: String
) : NodeLike<Uuid>

@Serializable @SerialName("test_edge")
data class TestEdge(
    override val fromId: Uuid,
    override val toId: Uuid,
    override val tags: List<String> = emptyList(),
    override val createdAt: Instant = Instant.fromEpochSeconds(0),
    override val updatedAt: Instant = Instant.fromEpochSeconds(0),
    val label: String
) : SchemaEdgeLike<Uuid>

// ── fixtures ─────────────────────────────────────────────────────────────────

private val testModule = SerializersModule {
    polymorphic(NodeLike::class) { subclass(TestNode::class) }
    polymorphic(EdgeLike::class) { subclass(TestEdge::class) }
}

private fun baseJson() = Json(from = customJsonSerializer) {
    serializersModule = abyssSerializersModule + testModule
}

@Suppress("UNCHECKED_CAST")
private val nodeJson = createPolymorphicJsonSerializer<NodeLike<*>>(baseJson()) { UnknownNode(it) }
@Suppress("UNCHECKED_CAST")
private val edgeJson = createPolymorphicJsonSerializer<EdgeLike<*, *>>(baseJson()) { UnknownEdge(it) }
@Suppress("UNCHECKED_CAST")
private val nodeSer  = PolymorphicSerializer(NodeLike::class) as kotlinx.serialization.KSerializer<NodeLike<*>>
@Suppress("UNCHECKED_CAST")
private val edgeSer  = PolymorphicSerializer(EdgeLike::class) as kotlinx.serialization.KSerializer<EdgeLike<*, *>>

// ── tests ────────────────────────────────────────────────────────────────────

class SerializationTest {

    @Test fun `Uuid round-trips through JSON as string`() {
        val id = Uuid.random()
        val encoded = customJsonSerializer.encodeToString(serializer<Uuid>(), id)
        assertEquals(id, customJsonSerializer.decodeFromString(serializer<Uuid>(), encoded))
        assertEquals("\"${id}\"", encoded)
    }

    @Test fun `Instant round-trips through JSON as ISO-8601`() {
        val now = Instant.fromEpochSeconds(1_700_000_000)
        val encoded = customJsonSerializer.encodeToString(serializer<Instant>(), now)
        assertEquals(now, customJsonSerializer.decodeFromString(serializer<Instant>(), encoded))
    }

    @Test fun `TestNode round-trips through polymorphic JSON`() {
        val node = TestNode(id = Uuid.random(), name = "sniper")
        val encoded = nodeJson.encodeToString(nodeSer, node)
        assertEquals(node, nodeJson.decodeFromString(nodeSer, encoded))
    }

    @Test fun `TestEdge round-trips through polymorphic JSON`() {
        val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "has_rifle")
        val encoded = edgeJson.encodeToString(edgeSer, edge)
        assertEquals(edge, edgeJson.decodeFromString(edgeSer, encoded))
    }

    @Test fun `unknown node type falls back to UnknownNode`() {
        val node = TestNode(id = Uuid.random(), name = "sniper")
        val withUnknownType = nodeJson.encodeToString(nodeSer, node).replace("\"test_node\"", "\"future_type\"")
        assertIs<UnknownNode>(nodeJson.decodeFromString(nodeSer, withUnknownType))
    }

    @Test fun `unknown edge type falls back to UnknownEdge`() {
        val edge = TestEdge(fromId = Uuid.random(), toId = Uuid.random(), label = "has_rifle")
        val withUnknownType = edgeJson.encodeToString(edgeSer, edge).replace("\"test_edge\"", "\"future_type\"")
        assertIs<UnknownEdge>(edgeJson.decodeFromString(edgeSer, withUnknownType))
    }

    @Test fun `UnknownNode preserves id from raw JSON`() {
        val id = Uuid.random()
        val node = TestNode(id = id, name = "sniper")
        val withUnknownType = nodeJson.encodeToString(nodeSer, node).replace("\"test_node\"", "\"future_type\"")
        val result = nodeJson.decodeFromString(nodeSer, withUnknownType) as UnknownNode
        assertEquals(id, result.id)
    }
}
