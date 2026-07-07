package pl.iqtech.abyss.graph

import com.hazelcast.partition.PartitionAware
import pl.iqtech.abyss.store.api.NodeId
import java.util.Objects

enum class AdjacencyDirection { OUT, IN }

// One byte packs direction (bit 7) + shard index (bits 0-6, 0..127) — mirrors 1.15's header-byte idiom.
fun packShard(direction: AdjacencyDirection, index: Int): Byte {
    require(index in 0..127) { "shard index $index out of range 0..127" }
    val bit = if (direction == AdjacencyDirection.IN) 0x80 else 0x00
    return (bit or index).toByte()
}
fun Byte.direction(): AdjacencyDirection = if ((toInt() and 0x80) != 0) AdjacencyDirection.IN else AdjacencyDirection.OUT
fun Byte.shardIndex(): Int = toInt() and 0x7F

// Owner is the "from" node for an OUT entry, the "to" node for an IN entry. PartitionAware pins it to
// the owner's partition, same trick EdgeKey/ReverseEdgeKey use, so all of a node's shards co-locate.
class AdjacencyKey(
    val nodeId: NodeId,
    val shard: Byte,
    pk: Any = nodeId.toString(),
) : PartitionAware<Any> {
    private val _pk = pk
    override fun getPartitionKey(): Any = _pk
    override fun equals(other: Any?) = other is AdjacencyKey && nodeId == other.nodeId && shard == other.shard
    override fun hashCode() = Objects.hash(nodeId, shard)
    override fun toString() = "AdjacencyKey($nodeId, $shard)"
}

// Set uniqueness is effectively (neighborId, edgeTypeTag) — nodeTypeTag is a pure function of
// neighborId, carried along purely to save a fetch on type-filtered reads, not for disambiguation.
// Nullable: the neighbor node may not be resolvable at write time (checkIntegrity=false dangling
// edges, or a preload racing ahead of the node's own store row) — the edge write must still succeed
// and stay reachable through the index, so a missing tag degrades to "unknown" rather than failing.
data class AdjacencyEntry(val neighborId: NodeId, val nodeTypeTag: Short?, val edgeTypeTag: Short)
data class AdjacencyValue(val entries: Set<AdjacencyEntry>)
