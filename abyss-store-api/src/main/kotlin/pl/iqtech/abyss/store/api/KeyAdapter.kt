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
    data class Int32(val value: Int) : NodeKeyEncoding
    data class Int64(val value: Long) : NodeKeyEncoding
    data class Uuid(val hi: Long, val lo: Long) : NodeKeyEncoding
    data class Str(val value: String) : NodeKeyEncoding
    // Multi-schema: a schema tag plus the inner adapter's native encoding. Written to a fixed,
    // self-describing Compact superset (tag + kind discriminator + hi/lo + nullable str), so one
    // EdgeKey Compact class serves every registered shape while keeping native (non-hex) predicates.
    data class Tagged(val tag: Long, val inner: NodeKeyEncoding) : NodeKeyEncoding
}

// Compact wire-layout selector. Native members mirror NodeKeyKind 1:1; TAGGED is the multi-schema
// superset (not a domain kind), which is the only reason this stays a separate enum.
enum class KeyEncodingShape { INT32, INT64, UUID, STRING, TAGGED }

// Domain id type persisted in the Tagged Compact record so reads reconstruct the inner type. Each
// kind maps to exactly one canonical KeyAdapter — see [adapter]. STRING is the opaque/Any fallback.
enum class NodeKeyKind(val id: Byte) { INT32(0), INT64(1), UUID(2), STRING(3) }

fun NodeKeyEncoding.kind(): NodeKeyKind = when (this) {
    is NodeKeyEncoding.Int32 -> NodeKeyKind.INT32
    is NodeKeyEncoding.Int64 -> NodeKeyKind.INT64
    is NodeKeyEncoding.Uuid -> NodeKeyKind.UUID
    is NodeKeyEncoding.Str -> NodeKeyKind.STRING
    is NodeKeyEncoding.Tagged -> error("Tagged cannot nest")
}

fun NodeKeyKind.toShape(): KeyEncodingShape = when (this) {
    NodeKeyKind.INT32 -> KeyEncodingShape.INT32
    NodeKeyKind.INT64 -> KeyEncodingShape.INT64
    NodeKeyKind.UUID -> KeyEncodingShape.UUID
    NodeKeyKind.STRING -> KeyEncodingShape.STRING
}

// The 1:1 kind → canonical adapter map: a self-describing NodeId's header nibble alone yields its
// typed adapter, no per-schema registry. STRING is the opaque fallback; custom domain types serialized
// as STRING round-trip only at the byte level and must take their own kind for type-level recovery.
fun NodeKeyKind.adapter(): KeyAdapter<*> = when (this) {
    NodeKeyKind.INT32 -> IntKeyAdapter
    NodeKeyKind.INT64 -> LongKeyAdapter
    NodeKeyKind.UUID -> UuidKeyAdapter
    NodeKeyKind.STRING -> StringKeyAdapter
}

// Composes/parses the self-describing NodeId byte layout: a 1-byte header followed by an optional
// big-endian schema tag and the raw inner id bytes.
//
//   [header:1][tag: width.bytes][rawId: variable]
//     high nibble = SchemaTagWidth.ordinal   (NONE=0, BYTE=1, SHORT=2, INT=4-byte @ ordinal 3, ...)
//     low  nibble = NodeKeyKind.id            (INT32=0, INT64=1, UUID=2, STRING=3)
//
// Every key carries the header, tagged or not (NONE = zero tag bytes), so any NodeId decodes standalone
// — read the header for width + inner shape, skip 1+width.bytes for the tag, decode the tail by kind.
object NodeKey {
    fun compose(width: SchemaTagWidth, kind: NodeKeyKind, tag: Long, rawId: ByteArray): NodeId {
        val header = ((width.ordinal shl 4) or kind.id.toInt()).toByte()
        val out = ByteArray(1 + width.bytes + rawId.size)
        out[0] = header
        for (i in 0 until width.bytes) out[1 + i] = (tag ushr (8 * (width.bytes - 1 - i))).toByte()
        rawId.copyInto(out, 1 + width.bytes)
        return NodeId(out)
    }

    fun width(nodeId: NodeId): SchemaTagWidth {
        require(nodeId.bytes.isNotEmpty()) { "Empty NodeId has no header" }
        val o = (nodeId.bytes[0].toInt() ushr 4) and 0x0F
        require(o < SchemaTagWidth.entries.size) { "Bad tag-width nibble $o in $nodeId" }
        return SchemaTagWidth.entries[o]
    }

    fun kind(nodeId: NodeId): NodeKeyKind {
        require(nodeId.bytes.isNotEmpty()) { "Empty NodeId has no header" }
        val k = (nodeId.bytes[0].toInt() and 0x0F).toByte()
        return NodeKeyKind.entries.firstOrNull { it.id == k } ?: error("Bad kind nibble $k in $nodeId")
    }

    fun tag(nodeId: NodeId): Long {
        val w = width(nodeId).bytes
        require(nodeId.bytes.size >= 1 + w) { "NodeId too short for its header: $nodeId" }
        var v = 0L
        for (i in 0 until w) v = (v shl 8) or (nodeId.bytes[1 + i].toLong() and 0xFF)
        return v
    }

    fun rawId(nodeId: NodeId): ByteArray =
        nodeId.bytes.copyOfRange(1 + width(nodeId).bytes, nodeId.bytes.size)

    // Registry-free bridges between raw id bytes and the Compact edge-key encoding: the kind alone
    // fixes the byte layout, so the shared serializer needs no per-schema adapter to decode.
    fun encoding(kind: NodeKeyKind, rawId: ByteArray): NodeKeyEncoding = when (kind) {
        NodeKeyKind.INT32 -> NodeKeyEncoding.Int32(ByteBuffer.wrap(rawId).int)
        NodeKeyKind.INT64 -> NodeKeyEncoding.Int64(ByteBuffer.wrap(rawId).long)
        NodeKeyKind.UUID -> ByteBuffer.wrap(rawId).let { NodeKeyEncoding.Uuid(it.long, it.long) }
        NodeKeyKind.STRING -> NodeKeyEncoding.Str(String(rawId, Charsets.UTF_8))
    }

    fun rawId(enc: NodeKeyEncoding): ByteArray = when (enc) {
        is NodeKeyEncoding.Int32 -> ByteBuffer.allocate(4).putInt(enc.value).array()
        is NodeKeyEncoding.Int64 -> ByteBuffer.allocate(8).putLong(enc.value).array()
        is NodeKeyEncoding.Uuid -> ByteBuffer.allocate(16).putLong(enc.hi).putLong(enc.lo).array()
        is NodeKeyEncoding.Str -> enc.value.toByteArray(Charsets.UTF_8)
        is NodeKeyEncoding.Tagged -> error("Tagged has no raw id")
    }
}

interface EdgeAdapter {
    fun partitionKey(nodeId: NodeId): Any
    val keyEncodingShape: KeyEncodingShape
    fun encodeKey(nodeId: NodeId): NodeKeyEncoding
    fun decodeKey(encoding: NodeKeyEncoding): NodeId
}

// A domain-id adapter. Implementations supply only the inner id shape ([nodeKeyKind]) and its raw byte
// conversion; header composition (self-describing NodeId) and the Compact edge-key bridges are shared
// defaults here — see [NodeKey]. Bare adapters stamp a NONE-width header (no tag); [SchemaKeyAdapter]
// overrides to stamp its schema tag.
interface KeyAdapter<ID> : EdgeAdapter {
    val nodeKeyKind: NodeKeyKind
    fun encodeIdBytes(id: ID): ByteArray
    fun decodeIdBytes(bytes: ByteArray): ID

    fun toNodeId(id: ID): NodeId = NodeKey.compose(SchemaTagWidth.NONE, nodeKeyKind, 0L, encodeIdBytes(id))
    fun fromNodeId(nodeId: NodeId): ID = decodeIdBytes(NodeKey.rawId(nodeId))

    override fun partitionKey(nodeId: NodeId): Any = nodeId.toString()
    override val keyEncodingShape: KeyEncodingShape get() = nodeKeyKind.toShape()
    override fun encodeKey(nodeId: NodeId): NodeKeyEncoding = NodeKey.encoding(nodeKeyKind, NodeKey.rawId(nodeId))
    override fun decodeKey(encoding: NodeKeyEncoding): NodeId =
        NodeKey.compose(SchemaTagWidth.NONE, nodeKeyKind, 0L, NodeKey.rawId(encoding))
}

object UuidKeyAdapter : KeyAdapter<Uuid> {
    override val nodeKeyKind = NodeKeyKind.UUID
    override fun encodeIdBytes(id: Uuid): ByteArray {
        val jid = id.toJavaUuid()
        return ByteBuffer.allocate(16).putLong(jid.mostSignificantBits).putLong(jid.leastSignificantBits).array()
    }
    override fun decodeIdBytes(bytes: ByteArray): Uuid =
        ByteBuffer.wrap(bytes).let { java.util.UUID(it.long, it.long).toKotlinUuid() }
    override fun partitionKey(nodeId: NodeId): Any = fromNodeId(nodeId).toJavaUuid()
}

object IntKeyAdapter : KeyAdapter<Int> {
    override val nodeKeyKind = NodeKeyKind.INT32
    override fun encodeIdBytes(id: Int): ByteArray = ByteBuffer.allocate(4).putInt(id).array()
    override fun decodeIdBytes(bytes: ByteArray): Int = ByteBuffer.wrap(bytes).int
    override fun partitionKey(nodeId: NodeId): Any = fromNodeId(nodeId)
}

object LongKeyAdapter : KeyAdapter<Long> {
    override val nodeKeyKind = NodeKeyKind.INT64
    override fun encodeIdBytes(id: Long): ByteArray = ByteBuffer.allocate(8).putLong(id).array()
    override fun decodeIdBytes(bytes: ByteArray): Long = ByteBuffer.wrap(bytes).long
    override fun partitionKey(nodeId: NodeId): Any = fromNodeId(nodeId)
}

object StringKeyAdapter : KeyAdapter<String> {
    override val nodeKeyKind = NodeKeyKind.STRING
    override fun encodeIdBytes(id: String): ByteArray = id.toByteArray(Charsets.UTF_8)
    override fun decodeIdBytes(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)
    override fun partitionKey(nodeId: NodeId): Any = fromNodeId(nodeId)
}

// Width of the schema tag a multi-schema graph stamps into each NodeId (after the 1-byte header).
// BYTE = 256 schemas. NONE = single/standalone schema: no tag bytes (the header still rides on every
// key, so a NONE key is [header][rawId] and still decodes standalone). Encoded in the header as an
// ordinal, so 16-byte UUID tags fit the 4-bit nibble.
enum class SchemaTagWidth(val bytes: Int) { NONE(0), BYTE(1), SHORT(2), INT(4), LONG(8), UUID(16) }

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

// Wraps an inner KeyAdapter, stamping a big-endian schema tag (`width` bytes) into each NodeId's
// header so schemas coexist in shared maps with globally-unique, self-describing keys. Edge-key
// encoding is tag + the inner adapter's native shape (see NodeKeyEncoding.Tagged) — native
// predicates, no hex.
class SchemaKeyAdapter<ID>(
    val tag: Long,
    val width: SchemaTagWidth,
    val inner: KeyAdapter<ID>,
) : KeyAdapter<ID> {
    init { require(tag >= 0 && (width.bytes >= 8 || tag < (1L shl (width.bytes * 8)))) { "tag $tag does not fit in $width" } }

    override val nodeKeyKind = inner.nodeKeyKind
    override fun encodeIdBytes(id: ID): ByteArray = inner.encodeIdBytes(id)
    override fun decodeIdBytes(bytes: ByteArray): ID = inner.decodeIdBytes(bytes)

    override fun toNodeId(id: ID): NodeId = NodeKey.compose(width, inner.nodeKeyKind, tag, inner.encodeIdBytes(id))
    override fun fromNodeId(nodeId: NodeId): ID = inner.decodeIdBytes(NodeKey.rawId(nodeId))

    override fun partitionKey(nodeId: NodeId): Any = nodeId.toString()
    override val keyEncodingShape = KeyEncodingShape.TAGGED
    override fun encodeKey(nodeId: NodeId): NodeKeyEncoding =
        NodeKeyEncoding.Tagged(NodeKey.tag(nodeId), NodeKey.encoding(inner.nodeKeyKind, NodeKey.rawId(nodeId)))
    override fun decodeKey(encoding: NodeKeyEncoding): NodeId {
        require(encoding is NodeKeyEncoding.Tagged) { "SchemaKeyAdapter expects Tagged, got $encoding" }
        return NodeKey.compose(width, inner.nodeKeyKind, encoding.tag, NodeKey.rawId(encoding.inner))
    }
}

// Edge-key adapter for a multi-schema container's SHARED serializer: it decodes edges from every
// registered schema. Self-describing keys make it stateless bar the tag `width` (which the Compact
// Tagged form doesn't carry, so it's needed to rebuild the NodeId prefix) — the inner shape comes
// straight off the key/encoding kind, no per-schema adapter registry. Per-schema predicates still use
// each schema's own SchemaKeyAdapter.
class MultiSchemaAdapter(val width: SchemaTagWidth) : EdgeAdapter {
    init { require(width != SchemaTagWidth.NONE) { "MultiSchemaAdapter needs a tagged width" } }

    override fun partitionKey(nodeId: NodeId): Any = nodeId.toString()
    override val keyEncodingShape = KeyEncodingShape.TAGGED
    override fun encodeKey(nodeId: NodeId): NodeKeyEncoding =
        NodeKeyEncoding.Tagged(NodeKey.tag(nodeId), NodeKey.encoding(NodeKey.kind(nodeId), NodeKey.rawId(nodeId)))
    override fun decodeKey(encoding: NodeKeyEncoding): NodeId {
        require(encoding is NodeKeyEncoding.Tagged) { "MultiSchemaAdapter expects Tagged, got $encoding" }
        return NodeKey.compose(width, encoding.inner.kind(), encoding.tag, NodeKey.rawId(encoding.inner))
    }
}
