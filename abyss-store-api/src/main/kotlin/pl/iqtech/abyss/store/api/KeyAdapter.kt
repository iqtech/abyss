package pl.iqtech.abyss.store.api

import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import java.nio.ByteBuffer

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

sealed interface NodeKeyEncoding {
    data class Int64(val value: Long) : NodeKeyEncoding
    data class Str(val value: String) : NodeKeyEncoding
    data class Int64Pair(val hi: Long, val lo: Long) : NodeKeyEncoding
}

enum class KeyEncodingShape { INT64, STRING, INT64_PAIR }

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
