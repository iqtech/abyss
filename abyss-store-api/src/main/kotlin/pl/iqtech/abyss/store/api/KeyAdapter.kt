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

// Serialized as its lowercase-hex string, so cross-schema edges (EdgeLike<NodeId, NodeId>) round-trip
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

// A schema tag: up to 128 bits (SchemaTagWidth.UUID), represented as two longs like
// NodeKeyEncoding.Uuid below. SchemaTag(value) covers the common <=64-bit case (hi=0); SchemaTag.of(uuid)
// covers a Uuid used directly as a tag (e.g. a per-tenant user id in HomogeneousSchemaGraph).
data class SchemaTag(val hi: Long, val lo: Long) {
    constructor(value: Long) : this(0L, value)
    companion object {
        val ZERO = SchemaTag(0L, 0L)
        fun of(uuid: Uuid): SchemaTag = uuid.toJavaUuid().let { SchemaTag(it.mostSignificantBits, it.leastSignificantBits) }
    }
}

sealed interface NodeKeyEncoding {
    data class Int32(val value: Int) : NodeKeyEncoding
    data class Int64(val value: Long) : NodeKeyEncoding
    data class Uuid(val hi: Long, val lo: Long) : NodeKeyEncoding
    data class Str(val value: String) : NodeKeyEncoding
    // Multi-schema: a schema tag plus the inner adapter's native encoding. Written to a fixed,
    // self-describing Compact superset (tag + kind discriminator + hi/lo + nullable str), so one
    // EdgeKey Compact class serves every registered shape while keeping native (non-hex) predicates.
    data class Tagged(val tag: SchemaTag, val inner: NodeKeyEncoding) : NodeKeyEncoding
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
    fun compose(width: SchemaTagWidth, kind: NodeKeyKind, tag: SchemaTag, rawId: ByteArray): NodeId {
        val header = ((width.ordinal shl 4) or kind.id.toInt()).toByte()
        val out = ByteArray(1 + width.bytes + rawId.size)
        out[0] = header
        // Full 16-byte big-endian tag; only the last width.bytes of it are ever stored, so widths
        // <=8 (hi=0) are byte-identical to the old single-Long packing, and width=16 uses all 128 bits.
        val full = ByteBuffer.allocate(16).putLong(tag.hi).putLong(tag.lo).array()
        full.copyInto(out, destinationOffset = 1, startIndex = 16 - width.bytes, endIndex = 16)
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

    fun tag(nodeId: NodeId): SchemaTag {
        val w = width(nodeId).bytes
        require(nodeId.bytes.size >= 1 + w) { "NodeId too short for its header: $nodeId" }
        val full = ByteArray(16)
        nodeId.bytes.copyInto(full, destinationOffset = 16 - w, startIndex = 1, endIndex = 1 + w)
        val buf = ByteBuffer.wrap(full)
        return SchemaTag(buf.long, buf.long)
    }

    fun rawId(nodeId: NodeId): ByteArray =
        nodeId.bytes.copyOfRange(1 + width(nodeId).bytes, nodeId.bytes.size)

    // Headerless tagged layout ([tag:width.bytes][rawId], no header byte) — HomogeneousSchemaGraph
    // (TODO 1.19 follow-up): every schema in that container shares one fixed width/kind, so the
    // header's two pieces of information are already known by the caller and don't need to ride on
    // every key. NOT self-describing standalone: width must come from the container, not the bytes.
    fun composeHeaderlessTag(width: SchemaTagWidth, tag: SchemaTag, rawId: ByteArray): NodeId {
        val out = ByteArray(width.bytes + rawId.size)
        val full = ByteBuffer.allocate(16).putLong(tag.hi).putLong(tag.lo).array()
        full.copyInto(out, destinationOffset = 0, startIndex = 16 - width.bytes, endIndex = 16)
        rawId.copyInto(out, width.bytes)
        return NodeId(out)
    }

    fun tagHeaderless(nodeId: NodeId, width: SchemaTagWidth): SchemaTag {
        require(nodeId.bytes.size >= width.bytes) { "NodeId too short for tag width $width: $nodeId" }
        val full = ByteArray(16)
        nodeId.bytes.copyInto(full, destinationOffset = 16 - width.bytes, startIndex = 0, endIndex = width.bytes)
        val buf = ByteBuffer.wrap(full)
        return SchemaTag(buf.long, buf.long)
    }

    fun rawIdHeaderless(nodeId: NodeId, width: SchemaTagWidth): ByteArray =
        nodeId.bytes.copyOfRange(width.bytes, nodeId.bytes.size)

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

    fun toNodeId(id: ID): NodeId = NodeKey.compose(SchemaTagWidth.NONE, nodeKeyKind, SchemaTag.ZERO, encodeIdBytes(id))
    fun fromNodeId(nodeId: NodeId): ID = decodeIdBytes(NodeKey.rawId(nodeId))

    // Does this schema own nodeId (same tag/width), or is it foreign? Default true: untagged adapters
    // (UuidKeyAdapter, IntKeyAdapter, ..., and HeaderlessKeyAdapter's standalone case) are the only
    // schema in their map by construction — mirrors SingleSchemaResolution.sameSchema.
    fun ownsNodeId(nodeId: NodeId): Boolean = true

    // Native (non-hex) value the partition key uses. Identity for most kinds; Uuid overrides since
    // Hazelcast partitions kotlin.uuid.Uuid and java.util.UUID differently (see UuidKeyAdapter).
    fun nativePartitionValue(id: ID): Any = id as Any

    override fun partitionKey(nodeId: NodeId): Any = nodeId.toString()
    override val keyEncodingShape: KeyEncodingShape get() = nodeKeyKind.toShape()
    override fun encodeKey(nodeId: NodeId): NodeKeyEncoding = NodeKey.encoding(nodeKeyKind, NodeKey.rawId(nodeId))
    override fun decodeKey(encoding: NodeKeyEncoding): NodeId =
        NodeKey.compose(SchemaTagWidth.NONE, nodeKeyKind, SchemaTag.ZERO, NodeKey.rawId(encoding))
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
    override fun nativePartitionValue(id: Uuid): Any = id.toJavaUuid()
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
    val tag: SchemaTag,
    val width: SchemaTagWidth,
    val inner: KeyAdapter<ID>,
) : KeyAdapter<ID> {
    init {
        val fits = when {
            width.bytes >= 16 -> true
            width.bytes == 8  -> tag.hi == 0L
            else              -> tag.hi == 0L && tag.lo in 0 until (1L shl (width.bytes * 8))
        }
        require(fits) { "tag $tag does not fit in $width" }
    }

    override val nodeKeyKind = inner.nodeKeyKind
    override fun encodeIdBytes(id: ID): ByteArray = inner.encodeIdBytes(id)
    override fun decodeIdBytes(bytes: ByteArray): ID = inner.decodeIdBytes(bytes)

    override fun toNodeId(id: ID): NodeId = NodeKey.compose(width, inner.nodeKeyKind, tag, inner.encodeIdBytes(id))
    override fun fromNodeId(nodeId: NodeId): ID = inner.decodeIdBytes(NodeKey.rawId(nodeId))

    override fun ownsNodeId(nodeId: NodeId): Boolean =
        runCatching { NodeKey.width(nodeId) == width && NodeKey.tag(nodeId) == tag }.getOrDefault(false)

    override fun partitionKey(nodeId: NodeId): Any = nodeId.toString()
    override val keyEncodingShape = KeyEncodingShape.TAGGED
    override fun encodeKey(nodeId: NodeId): NodeKeyEncoding =
        NodeKeyEncoding.Tagged(NodeKey.tag(nodeId), NodeKey.encoding(inner.nodeKeyKind, NodeKey.rawId(nodeId)))
    override fun decodeKey(encoding: NodeKeyEncoding): NodeId {
        require(encoding is NodeKeyEncoding.Tagged) { "SchemaKeyAdapter expects Tagged, got $encoding" }
        return NodeKey.compose(width, inner.nodeKeyKind, encoding.tag, NodeKey.rawId(encoding.inner))
    }
}

// Headerless counterpart to SchemaKeyAdapter, for HomogeneousSchemaGraph: every schema in that
// container shares one fixed width/kind, so there's nothing for a header to self-describe — the tag
// rides directly at the front of the key, no header byte. See NodeKey.composeHeaderlessTag.
class HeaderlessSchemaKeyAdapter<ID>(
    val tag: SchemaTag,
    val width: SchemaTagWidth,
    val inner: KeyAdapter<ID>,
) : KeyAdapter<ID> {
    override val nodeKeyKind = inner.nodeKeyKind
    override fun encodeIdBytes(id: ID): ByteArray = inner.encodeIdBytes(id)
    override fun decodeIdBytes(bytes: ByteArray): ID = inner.decodeIdBytes(bytes)

    override fun toNodeId(id: ID): NodeId = NodeKey.composeHeaderlessTag(width, tag, inner.encodeIdBytes(id))
    override fun fromNodeId(nodeId: NodeId): ID = inner.decodeIdBytes(NodeKey.rawIdHeaderless(nodeId, width))

    override fun ownsNodeId(nodeId: NodeId): Boolean =
        runCatching { NodeKey.tagHeaderless(nodeId, width) == tag }.getOrDefault(false)

    override fun partitionKey(nodeId: NodeId): Any = inner.nativePartitionValue(fromNodeId(nodeId))
    override val keyEncodingShape = KeyEncodingShape.TAGGED
    override fun encodeKey(nodeId: NodeId): NodeKeyEncoding =
        NodeKeyEncoding.Tagged(NodeKey.tagHeaderless(nodeId, width), NodeKey.encoding(inner.nodeKeyKind, NodeKey.rawIdHeaderless(nodeId, width)))
    override fun decodeKey(encoding: NodeKeyEncoding): NodeId {
        require(encoding is NodeKeyEncoding.Tagged) { "HeaderlessSchemaKeyAdapter expects Tagged, got $encoding" }
        return NodeKey.composeHeaderlessTag(width, encoding.tag, NodeKey.rawId(encoding.inner))
    }
}

// Zero-header adapter: NodeId.bytes IS encodeIdBytes(id), nothing more — no tag-width/kind byte at
// all. The single-schema tier (TODO 1.19): fastest path, no NodeKey header parsing/composition on
// any key. Wraps a canonical KeyAdapter purely for its encodeIdBytes/decodeIdBytes/nodeKeyKind.
class HeaderlessKeyAdapter<ID>(private val inner: KeyAdapter<ID>) : KeyAdapter<ID> {
    override val nodeKeyKind = inner.nodeKeyKind
    override fun encodeIdBytes(id: ID): ByteArray = inner.encodeIdBytes(id)
    override fun decodeIdBytes(bytes: ByteArray): ID = inner.decodeIdBytes(bytes)

    override fun toNodeId(id: ID): NodeId = NodeId(encodeIdBytes(id))
    override fun fromNodeId(nodeId: NodeId): ID = decodeIdBytes(nodeId.bytes)

    override fun partitionKey(nodeId: NodeId): Any = inner.nativePartitionValue(fromNodeId(nodeId))
    override val keyEncodingShape = nodeKeyKind.toShape()
    override fun encodeKey(nodeId: NodeId): NodeKeyEncoding = NodeKey.encoding(nodeKeyKind, nodeId.bytes)
    override fun decodeKey(encoding: NodeKeyEncoding): NodeId = NodeId(NodeKey.rawId(encoding))
}

// Everything an untyped schema operation needs to touch the shared maps for a given NodeId —
// derived straight from the self-describing key, never looked up in a registry. `edgeAdapter` is the
// native canonical adapter for NONE-width keys and the stateless [MultiSchemaAdapter] for tagged keys;
// both agree with the per-schema [SchemaKeyAdapter] on `partitionKey`/`encodeKey`, so keys built from a
// derived descriptor match keys built by a typed schema.
class SchemaDescriptor(
    val edgeAdapter: EdgeAdapter,
    val tagWidth: SchemaTagWidth,
    val tag: SchemaTag,
) {
    companion object {
        fun of(nid: NodeId): SchemaDescriptor {
            val width = NodeKey.width(nid)
            val edgeAdapter: EdgeAdapter =
                if (width == SchemaTagWidth.NONE) NodeKey.kind(nid).adapter() else MultiSchemaAdapter(width)
            return SchemaDescriptor(edgeAdapter, width, NodeKey.tag(nid))
        }
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

// Headerless counterpart to MultiSchemaAdapter, for HomogeneousSchemaGraph. Unlike MultiSchemaAdapter
// (derives `kind` from the header per key, since Heterogeneous keys can differ in shape), `kind` is
// fixed here too — every key in a Homogeneous container is the same shape, so nothing is ever read
// from a header; the tag/rawId split comes from the fixed `width` alone.
class HeaderlessMultiSchemaAdapter(val width: SchemaTagWidth, val kind: NodeKeyKind) : EdgeAdapter {
    init { require(width != SchemaTagWidth.NONE) { "HeaderlessMultiSchemaAdapter needs a tagged width" } }

    override fun partitionKey(nodeId: NodeId): Any = nodeId.toString()
    override val keyEncodingShape = KeyEncodingShape.TAGGED
    override fun encodeKey(nodeId: NodeId): NodeKeyEncoding =
        NodeKeyEncoding.Tagged(NodeKey.tagHeaderless(nodeId, width), NodeKey.encoding(kind, NodeKey.rawIdHeaderless(nodeId, width)))
    override fun decodeKey(encoding: NodeKeyEncoding): NodeId {
        require(encoding is NodeKeyEncoding.Tagged) { "HeaderlessMultiSchemaAdapter expects Tagged, got $encoding" }
        return NodeKey.composeHeaderlessTag(width, encoding.tag, NodeKey.rawId(encoding.inner))
    }
}
