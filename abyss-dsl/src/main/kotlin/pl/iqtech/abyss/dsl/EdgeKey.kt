package pl.iqtech.abyss.dsl

import com.hazelcast.partition.PartitionAware
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

data class EdgeKey(val fromId: Uuid, val toId: Uuid, val type: String) : PartitionAware<java.util.UUID> {
    override fun getPartitionKey() = fromId.toJavaUuid()
}
