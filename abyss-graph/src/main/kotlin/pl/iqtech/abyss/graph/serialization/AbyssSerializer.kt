package pl.iqtech.abyss.graph.serialization

import com.hazelcast.nio.ObjectDataInput
import com.hazelcast.nio.ObjectDataOutput
import com.hazelcast.nio.serialization.StreamSerializer
import kotlin.time.Instant
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import kotlin.uuid.Uuid

// Fallback types for unknown node/edge kinds introduced by newer library versions.
// Properties extract what they can from raw JSON; toString() returns the original JSON
// so PolymorphicFallbackSerializer can re-encode them without data loss.
//
// UnknownNode/UnknownEdge use Uuid as the ID type; for non-UUID graphs the id field will be a
// zero-UUID fallback. These types are only reached when the @SerialName is truly unrecognised,
// and callers pattern-match on concrete types rather than Unknown* — so the mismatch is harmless.
data class UnknownNode(val raw: JsonElement) : NodeLike<Uuid> {
    override val id: Uuid = raw.jsonObject["id"]?.jsonPrimitive?.content?.let(Uuid::parse) ?: Uuid.parse("00000000-0000-0000-0000-000000000000")
    override val tags: List<String> = emptyList()
    override val createdAt: Instant = Instant.fromEpochSeconds(0)
    override val updatedAt: Instant = Instant.fromEpochSeconds(0)
    override fun toString() = raw.toString()
}

// Endpoints are NodeId (parsed from the raw hex when present) rather than a hardcoded ID type — an
// unknown edge may be same- or cross-schema. Endpoints are best-effort; traversal keys on the EdgeKey.
data class UnknownEdge(val raw: JsonElement) : EdgeLike<NodeId, NodeId> {
    override val fromId: NodeId = raw.jsonObject["fromId"]?.jsonPrimitive?.content?.let(::nodeIdOrEmpty) ?: NodeId(ByteArray(0))
    override val toId: NodeId   = raw.jsonObject["toId"]?.jsonPrimitive?.content?.let(::nodeIdOrEmpty) ?: NodeId(ByteArray(0))
    override val tags: List<String> = emptyList()
    override val createdAt: Instant = Instant.fromEpochSeconds(0)
    override val updatedAt: Instant = Instant.fromEpochSeconds(0)
    override fun toString() = raw.toString()
}

private fun nodeIdOrEmpty(s: String): NodeId = runCatching { NodeId.fromHex(s) }.getOrElse { NodeId(ByteArray(0)) }

@Suppress("UNCHECKED_CAST")
class NodeLikeHzSerializer(extraModule: SerializersModule = EmptySerializersModule()) : StreamSerializer<NodeLike<*>> {
    private val json = createPolymorphicJsonSerializer<NodeLike<*>>(
        baseJsonSerializer = Json(from = customJsonSerializer) { serializersModule = customJsonSerializer.serializersModule + extraModule }
    ) { UnknownNode(it) }
    private val kSerializer = PolymorphicSerializer(NodeLike::class) as kotlinx.serialization.KSerializer<NodeLike<*>>

    override fun getTypeId() = TYPE_ID
    override fun destroy() {}
    override fun write(out: ObjectDataOutput, obj: NodeLike<*>) = out.writeString(json.encodeToString(kSerializer, obj))
    override fun read(`in`: ObjectDataInput): NodeLike<*> = json.decodeFromString(kSerializer, `in`.readString()!!)

    companion object { const val TYPE_ID = 2001 }
}

@Suppress("UNCHECKED_CAST")
class EdgeLikeHzSerializer(extraModule: SerializersModule = EmptySerializersModule()) : StreamSerializer<EdgeLike<*, *>> {
    private val json = createPolymorphicJsonSerializer<EdgeLike<*, *>>(
        baseJsonSerializer = Json(from = customJsonSerializer) { serializersModule = customJsonSerializer.serializersModule + extraModule }
    ) { UnknownEdge(it) }
    private val kSerializer = PolymorphicSerializer(EdgeLike::class) as kotlinx.serialization.KSerializer<EdgeLike<*, *>>

    override fun getTypeId() = TYPE_ID
    override fun destroy() {}
    override fun write(out: ObjectDataOutput, obj: EdgeLike<*, *>) = out.writeString(json.encodeToString(kSerializer, obj))
    override fun read(`in`: ObjectDataInput): EdgeLike<*, *> = json.decodeFromString(kSerializer, `in`.readString()!!)

    companion object { const val TYPE_ID = 2002 }
}
