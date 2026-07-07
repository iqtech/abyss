package pl.iqtech.abyss.dsl

import com.hazelcast.partition.PartitionAware
import pl.iqtech.abyss.store.api.NodeId
import java.util.Objects

class EdgeKey(
    val fromId: NodeId,
    val toId: NodeId,
    val type: String,
    pk: Any = fromId.toString()
) : PartitionAware<Any> {
    private val _pk = pk
    override fun getPartitionKey(): Any = _pk
    override fun equals(other: Any?) = other is EdgeKey && fromId == other.fromId && toId == other.toId && type == other.type
    override fun hashCode() = Objects.hash(fromId, toId, type)
    override fun toString() = "EdgeKey($fromId, $toId, $type)"
}
