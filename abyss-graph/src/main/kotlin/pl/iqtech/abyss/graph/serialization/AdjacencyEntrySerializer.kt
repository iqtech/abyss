package pl.iqtech.abyss.graph.serialization

import com.hazelcast.nio.serialization.compact.CompactReader
import com.hazelcast.nio.serialization.compact.CompactSerializer
import com.hazelcast.nio.serialization.compact.CompactWriter
import pl.iqtech.abyss.graph.AdjacencyEntry
import pl.iqtech.abyss.store.api.NodeId

class AdjacencyEntrySerializer : CompactSerializer<AdjacencyEntry> {
    override fun getTypeName() = "AdjacencyEntry"
    override fun getCompactClass() = AdjacencyEntry::class.java

    override fun write(writer: CompactWriter, obj: AdjacencyEntry) {
        writer.writeCompact("neighborId", obj.neighborId)
        writer.writeNullableInt16("nodeTypeTag", obj.nodeTypeTag)
        writer.writeInt16("edgeTypeTag", obj.edgeTypeTag)
    }

    override fun read(reader: CompactReader) = AdjacencyEntry(
        neighborId = reader.readCompact<NodeId>("neighborId")!!,
        nodeTypeTag = reader.readNullableInt16("nodeTypeTag"),
        edgeTypeTag = reader.readInt16("edgeTypeTag"),
    )
}
