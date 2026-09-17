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
    // A key read back from a map (entrySet/keySet/getAll result) doesn't know the pk it was written with —
    // the serializer doesn't store it, and the writer's EdgeAdapter pk usually differs from the hex default
    // (native Long/UUID/...). Routing with it would silently hit the wrong partition (TODO 4.14), so it fails
    // loudly instead. Verified: Hazelcast never asks a deserialized key for its pk (full suite + 3-member cluster).
    override fun getPartitionKey(): Any =
        if (_pk === READ_BACK) error("$this was read back from a map and has no partition key; rebuild it with the writer's pk before get/put/remove/evict")
        else _pk
    override fun equals(other: Any?) = other is EdgeKey && fromId == other.fromId && toId == other.toId && type == other.type
    override fun hashCode() = Objects.hash(fromId, toId, type)
    override fun toString() = "EdgeKey($fromId, $toId, $type)"

    companion object {
        private val READ_BACK = Any()
        /** For deserializers only: a key whose partition key is unknown (see [getPartitionKey]). */
        @ReadBackKeyApi
        fun readBack(fromId: NodeId, toId: NodeId, type: String) = EdgeKey(fromId, toId, type, READ_BACK)
    }
}

// Opt-in gate for EdgeKey.readBack / AdjacencyKey.readBack: public only because the Compact serializers live in
// abyss-graph. A pk-less key is only meaningful to a deserializer; anywhere else it's a routing crash waiting.
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Builds a key with no partition key — only for map key deserializers. Build routable keys with the writer's pk.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class ReadBackKeyApi
