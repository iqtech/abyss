package pl.iqtech.abyss.graph.serialization

import com.hazelcast.nio.serialization.compact.CompactReader
import com.hazelcast.nio.serialization.compact.CompactSerializer
import com.hazelcast.nio.serialization.compact.CompactWriter
import pl.iqtech.abyss.graph.AdjacencyKey
import pl.iqtech.abyss.store.api.NodeId

// No EdgeAdapter dependency (unlike EdgeKeySerializer/ReverseEdgeKeySerializer) — nothing predicate-
// queries this map, so NodeId nests as an opaque Compact field via the already-registered NodeIdSerializer.
class AdjacencyKeySerializer : CompactSerializer<AdjacencyKey> {
    override fun getTypeName() = "AdjacencyKey"
    override fun getCompactClass() = AdjacencyKey::class.java

    override fun write(writer: CompactWriter, obj: AdjacencyKey) {
        writer.writeCompact("nodeId", obj.nodeId)
        writer.writeInt8("shard", obj.shard)
    }

    override fun read(reader: CompactReader) = AdjacencyKey(
        nodeId = reader.readCompact<NodeId>("nodeId")!!,
        shard = reader.readInt8("shard"),
    )
}
