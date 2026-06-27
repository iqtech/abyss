package pl.iqtech.abyss.graph

import com.hazelcast.partition.PartitionAware
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

data class ReverseEdgeKey(val toId: Uuid, val fromId: Uuid, val type: String) : PartitionAware<java.util.UUID> {
    override fun getPartitionKey() = toId.toJavaUuid()
}
