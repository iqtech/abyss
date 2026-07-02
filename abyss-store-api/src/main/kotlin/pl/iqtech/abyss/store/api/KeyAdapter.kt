package pl.iqtech.abyss.store.api

import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import java.nio.ByteBuffer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// Serialized as its lowercase-hex string, so cross-schema edges (SchemaEdgeLike<NodeId>) round-trip
// through the JSON edge serializer.
@Serializable(with = NodeIdHexSerializer::class)
class NodeId(val bytes: ByteArray) : Comparable<NodeId> {
    override fun equals(other: Any?) = other is NodeId && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = bytes.joinToString("") { "%02x".format(it) }
    override fun compareTo(other: NodeId): Int {
        val len = minOf(bytes.size, other.bytes.size)
        for (i in 0 until len) {
            val c = (bytes[i].toInt() and 0xFF).compareTo(other.bytes[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return bytes.size.compareTo(other.bytes.size)
    }
    companion object {
        fun fromHex(hex: String) = NodeId(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
    }
}

object NodeIdHexSerializer : KSerializer<NodeId> {
    override val descriptor = PrimitiveSerialDescriptor("NodeId", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: NodeId) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): NodeId = NodeId.fromHex(decoder.decodeString())
}

sealed interface NodeKeyEncoding {
    data class Int64(val value: Long) : NodeKeyEncoding
    data class Str(val value: String) : NodeKeyEncoding
    data class Int64Pair(val hi: Long, val lo: Long) : NodeKeyEncoding
    // Multi-schema: a schema tag plus the inner adapter's native encoding. Written to a fixed,
    // self-describing Compact superset (tag + kind discriminator + hi/lo + nullable str), so one
    // EdgeKey Compact class serves every registered shape while keeping native (non-hex) predicates.
    data class Tagged(val tag: Long, val inner: NodeKeyEncoding) : NodeKeyEncoding
}

enum class KeyEncodingShape { INT64, STRING, INT64_PAIR, TAGGED }

// Discriminator persisted in the Tagged Compact record so reads reconstruct the inner shape.
enum class NodeKeyKind(val id: Byte) { INT64(0), STRING(1), INT64_PAIR(2) }

fun NodeKeyEncoding.kind(): NodeKeyKind = when (this) {
    is NodeKeyEncoding.Int64 -> NodeKeyKind.INT64
    is NodeKeyEncoding.Str -> NodeKeyKind.STRING
    is NodeKeyEncoding.Int64Pair -> NodeKeyKind.INT64_PAIR
    is NodeKeyEncoding.Tagged -> error("Tagged cannot nest")
}

interface EdgeAdapter {
    fun partitionKey(nodeId: NodeId): Any
    val keyEncodingShape: KeyEncodingShape
    fun encodeKey(nodeId: NodeId): NodeKeyEncoding
    fun decodeKey(encoding: NodeKeyEncoding): NodeId
}

interface KeyAdapter<ID> : EdgeAdapter {
    fun toNodeId(id: ID): NodeId
    fun fromNodeId(nodeId: NodeId): ID
    override fun partitionKey(nodeId: NodeId): Any = nodeId.toString()
}

object UuidKeyAdapter : KeyAdapter<Uuid> {
    override fun toNodeId(id: Uuid): NodeId {
        val jid = id.toJavaUuid()
        val bb = ByteBuffer.allocate(16)
        bb.putLong(jid.mostSignificantBits)
        bb.putLong(jid.leastSignificantBits)
        return NodeId(bb.array())
    }
    override fun fromNodeId(nodeId: NodeId): Uuid {
        val bb = ByteBuffer.wrap(nodeId.bytes)
        return java.util.UUID(bb.getLong(), bb.getLong()).toKotlinUuid()
    }
    override fun partitionKey(nodeId: NodeId): Any = fromNodeId(nodeId).toJavaUuid()

    override val keyEncodingShape = KeyEncodingShape.INT64_PAIR
    override fun encodeKey(nodeId: NodeId): NodeKeyEncoding {
        val bb = ByteBuffer.wrap(nodeId.bytes)
        return NodeKeyEncoding.Int64Pair(bb.getLong(), bb.getLong())
    }
    override fun decodeKey(encoding: NodeKeyEncoding): NodeId {
        require(encoding is NodeKeyEncoding.Int64Pair) { "UuidKeyAdapter expects Int64Pair, got $encoding" }
        val bb = ByteBuffer.allocate(16)
        bb.putLong(encoding.hi)
        bb.putLong(encoding.lo)
        return NodeId(bb.array())
    }
}

object LongKeyAdapter : KeyAdapter<Long> {
    override fun toNodeId(id: Long): NodeId = NodeId(ByteBuffer.allocate(8).also { it.putLong(id) }.array())
    override fun fromNodeId(nodeId: NodeId): Long = ByteBuffer.wrap(nodeId.bytes).getLong()
    override fun partitionKey(nodeId: NodeId): Any = fromNodeId(nodeId)

    override val keyEncodingShape = KeyEncodingShape.INT64
    override fun encodeKey(nodeId: NodeId): NodeKeyEncoding = NodeKeyEncoding.Int64(fromNodeId(nodeId))
    override fun decodeKey(encoding: NodeKeyEncoding): NodeId {
        require(encoding is NodeKeyEncoding.Int64) { "LongKeyAdapter expects Int64, got $encoding" }
        return toNodeId(encoding.value)
    }
}

object StringKeyAdapter : KeyAdapter<String> {
    override fun toNodeId(id: String): NodeId = NodeId(id.toByteArray(Charsets.UTF_8))
    override fun fromNodeId(nodeId: NodeId): String = String(nodeId.bytes, Charsets.UTF_8)
    override fun partitionKey(nodeId: NodeId): Any = fromNodeId(nodeId)

    override val keyEncodingShape = KeyEncodingShape.STRING
    override fun encodeKey(nodeId: NodeId): NodeKeyEncoding = NodeKeyEncoding.Str(fromNodeId(nodeId))
    override fun decodeKey(encoding: NodeKeyEncoding): NodeId {
        require(encoding is NodeKeyEncoding.Str) { "StringKeyAdapter expects Str, got $encoding" }
        return toNodeId(encoding.value)
    }
}

// Width of the schema-tag prefix a multi-schema graph stamps onto every NodeId. BYTE = 256 schemas.
// NONE = single-schema degenerate case: zero-length prefix, NodeIds are untagged and byte-identical
// to the inner adapter's, so a one-schema container costs nothing over a standalone AbyssGraphSchema.
enum class SchemaTagWidth(val bytes: Int) { NONE(0), BYTE(1), SHORT(2), INT(4), LONG(8) }

// Adapter-independent edge-key encoding for multi-schema graphs. A single Hazelcast Compact
// serializer per class cannot express multiple native shapes, so the container encodes every
// (self-describing) tagged NodeId as its hex string, riding the existing STRING/Str shape.
object UniformHexAdapter : EdgeAdapter {
    override fun partitionKey(nodeId: NodeId): Any = nodeId.toString()
    override val keyEncodingShape = KeyEncodingShape.STRING
    override fun encodeKey(nodeId: NodeId): NodeKeyEncoding = NodeKeyEncoding.Str(nodeId.toString())
    override fun decodeKey(encoding: NodeKeyEncoding): NodeId {
        require(encoding is NodeKeyEncoding.Str) { "UniformHexAdapter expects Str, got $encoding" }
        return NodeId.fromHex(encoding.value)
    }
}

// Wraps an inner KeyAdapter, prefixing every NodeId with a big-endian schema tag (`width` bytes)
// so schemas coexist in shared maps with globally-unique, self-describing keys. Edge-key encoding is
// tag + the inner adapter's native shape (see NodeKeyEncoding.Tagged) — native predicates, no hex.
class SchemaKeyAdapter<ID>(
    val tag: Long,
    val width: SchemaTagWidth,
    val inner: KeyAdapter<ID>,
) : KeyAdapter<ID> {
    private val prefix: ByteArray = tagPrefix(tag, width)

    init { require(tag >= 0 && (width.bytes == 8 || tag < (1L shl (width.bytes * 8)))) { "tag $tag does not fit in $width" } }

    override fun toNodeId(id: ID): NodeId = NodeId(prefix + inner.toNodeId(id).bytes)
    override fun fromNodeId(nodeId: NodeId): ID = inner.fromNodeId(stripTag(nodeId, width))

    override fun partitionKey(nodeId: NodeId): Any = nodeId.toString()
    override val keyEncodingShape = KeyEncodingShape.TAGGED
    override fun encodeKey(nodeId: NodeId): NodeKeyEncoding =
        NodeKeyEncoding.Tagged(tag, inner.encodeKey(stripTag(nodeId, width)))
    override fun decodeKey(encoding: NodeKeyEncoding): NodeId {
        require(encoding is NodeKeyEncoding.Tagged) { "SchemaKeyAdapter expects Tagged, got $encoding" }
        return NodeId(prefix + inner.decodeKey(encoding.inner).bytes)
    }

    companion object {
        // Reads the schema-tag prefix from a tagged NodeId without needing the inner adapter.
        fun readTag(nodeId: NodeId, width: SchemaTagWidth): Long {
            require(nodeId.bytes.size >= width.bytes) { "NodeId too short for $width tag" }
            var v = 0L
            for (i in 0 until width.bytes) v = (v shl 8) or (nodeId.bytes[i].toLong() and 0xFF)
            return v
        }
    }
}

private fun tagPrefix(tag: Long, width: SchemaTagWidth): ByteArray =
    ByteBuffer.allocate(8).putLong(tag).array().copyOfRange(8 - width.bytes, 8)

private fun stripTag(nodeId: NodeId, width: SchemaTagWidth): NodeId =
    NodeId(nodeId.bytes.copyOfRange(width.bytes, nodeId.bytes.size))

// Edge-key adapter for a multi-schema container's SHARED serializer: it must decode edges from every
// registered schema, so it holds the full tag -> inner-adapter registry. Built once by the caller
// (before the HazelcastInstance starts) and passed to registerAbyssSerializers; the same tags must be
// register()ed on the AbyssGraph. Per-schema predicates use each schema's SchemaKeyAdapter instead.
class MultiSchemaAdapter(
    val width: SchemaTagWidth,
    private val registry: Map<Long, KeyAdapter<*>>,
) : EdgeAdapter {
    init { require(width != SchemaTagWidth.NONE) { "MultiSchemaAdapter needs a tagged width" } }
    private fun inner(tag: Long) = registry[tag] ?: error("No adapter registered for schema tag $tag")

    override fun partitionKey(nodeId: NodeId): Any = nodeId.toString()
    override val keyEncodingShape = KeyEncodingShape.TAGGED
    override fun encodeKey(nodeId: NodeId): NodeKeyEncoding {
        val tag = SchemaKeyAdapter.readTag(nodeId, width)
        return NodeKeyEncoding.Tagged(tag, inner(tag).encodeKey(stripTag(nodeId, width)))
    }
    override fun decodeKey(encoding: NodeKeyEncoding): NodeId {
        require(encoding is NodeKeyEncoding.Tagged) { "MultiSchemaAdapter expects Tagged, got $encoding" }
        return NodeId(tagPrefix(encoding.tag, width) + inner(encoding.tag).decodeKey(encoding.inner).bytes)
    }
}
