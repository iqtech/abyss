package pl.iqtech.abyss.dsl

import com.hazelcast.partition.PartitionAware
import java.util.UUID

data class EdgeKey(val fromId: UUID, val toId: UUID, val type: String) : PartitionAware<UUID> {
    override fun getPartitionKey() = fromId
}
