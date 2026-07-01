package pl.iqtech.abyss.graph

import com.hazelcast.partition.PartitionAware
import pl.iqtech.abyss.store.api.NodeId
import java.util.Objects

class ReverseEdgeKey(
    val toId: NodeId,
    val fromId: NodeId,
    val type: String,
    pk: Any = toId.toString()
) : PartitionAware<Any> {
    private val _pk = pk
    override fun getPartitionKey(): Any = _pk
    override fun equals(other: Any?) = other is ReverseEdgeKey && toId == other.toId && fromId == other.fromId && type == other.type
    override fun hashCode() = Objects.hash(toId, fromId, type)
    override fun toString() = "ReverseEdgeKey($toId, $fromId, $type)"
}
