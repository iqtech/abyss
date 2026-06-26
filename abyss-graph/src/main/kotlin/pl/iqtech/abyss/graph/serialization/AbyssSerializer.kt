package pl.iqtech.abyss.graph.serialization

import com.hazelcast.nio.ObjectDataInput
import com.hazelcast.nio.ObjectDataOutput
import com.hazelcast.nio.serialization.StreamSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import java.time.Instant
import java.util.UUID

// Fallback types for unknown node/edge kinds introduced by newer library versions.
// Properties extract what they can from raw JSON; toString() returns the original JSON
// so PolymorphicFallbackSerializer can re-encode them without data loss.

data class UnknownNode(val raw: JsonElement) : NodeLike {
    override val id: UUID = raw.jsonObject["id"]?.jsonPrimitive?.content?.let(UUID::fromString) ?: UUID(0, 0)
    override val tags: List<String> = emptyList()
    override val createdAt: Instant = Instant.EPOCH
    override val updatedAt: Instant = Instant.EPOCH
    override fun toString() = raw.toString()
}

data class UnknownEdge(val raw: JsonElement) : EdgeLike {
    override val fromId: UUID = raw.jsonObject["fromId"]?.jsonPrimitive?.content?.let(UUID::fromString) ?: UUID(0, 0)
    override val toId: UUID   = raw.jsonObject["toId"]?.jsonPrimitive?.content?.let(UUID::fromString)   ?: UUID(0, 0)
    override val tags: List<String> = emptyList()
    override val createdAt: Instant = Instant.EPOCH
    override val updatedAt: Instant = Instant.EPOCH
    override fun toString() = raw.toString()
}

class NodeLikeHzSerializer(extraModule: SerializersModule = EmptySerializersModule()) : StreamSerializer<NodeLike> {
    private val json = createPolymorphicJsonSerializer<NodeLike>(
        baseJsonSerializer = Json(from = customJsonSerializer) { serializersModule = customJsonSerializer.serializersModule + extraModule }
    ) { UnknownNode(it) }
    private val kSerializer = PolymorphicSerializer(NodeLike::class)

    override fun getTypeId() = TYPE_ID
    override fun destroy() {}
    override fun write(out: ObjectDataOutput, obj: NodeLike) = out.writeString(json.encodeToString(kSerializer, obj))
    override fun read(`in`: ObjectDataInput): NodeLike = json.decodeFromString(kSerializer, `in`.readString()!!)

    companion object { const val TYPE_ID = 2001 }
}

class EdgeLikeHzSerializer(extraModule: SerializersModule = EmptySerializersModule()) : StreamSerializer<EdgeLike> {
    private val json = createPolymorphicJsonSerializer<EdgeLike>(
        baseJsonSerializer = Json(from = customJsonSerializer) { serializersModule = customJsonSerializer.serializersModule + extraModule }
    ) { UnknownEdge(it) }
    private val kSerializer = PolymorphicSerializer(EdgeLike::class)

    override fun getTypeId() = TYPE_ID
    override fun destroy() {}
    override fun write(out: ObjectDataOutput, obj: EdgeLike) = out.writeString(json.encodeToString(kSerializer, obj))
    override fun read(`in`: ObjectDataInput): EdgeLike = json.decodeFromString(kSerializer, `in`.readString()!!)

    companion object { const val TYPE_ID = 2002 }
}
