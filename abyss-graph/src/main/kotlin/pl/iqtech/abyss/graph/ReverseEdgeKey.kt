package pl.iqtech.abyss.graph

import com.hazelcast.partition.PartitionAware
import java.util.UUID

data class ReverseEdgeKey(val toId: UUID, val fromId: UUID, val type: String) : PartitionAware<UUID> {
    override fun getPartitionKey() = toId
}
