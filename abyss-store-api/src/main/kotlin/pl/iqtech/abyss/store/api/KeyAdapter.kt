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

interface EdgeAdapter {
    fun partitionKey(nodeId: NodeId): Any
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
}

object LongKeyAdapter : KeyAdapter<Long> {
    override fun toNodeId(id: Long): NodeId = NodeId(ByteBuffer.allocate(8).also { it.putLong(id) }.array())
    override fun fromNodeId(nodeId: NodeId): Long = ByteBuffer.wrap(nodeId.bytes).getLong()
    override fun partitionKey(nodeId: NodeId): Any = fromNodeId(nodeId)
}

object StringKeyAdapter : KeyAdapter<String> {
    override fun toNodeId(id: String): NodeId = NodeId(id.toByteArray(Charsets.UTF_8))
    override fun fromNodeId(nodeId: NodeId): String = String(nodeId.bytes, Charsets.UTF_8)
}
