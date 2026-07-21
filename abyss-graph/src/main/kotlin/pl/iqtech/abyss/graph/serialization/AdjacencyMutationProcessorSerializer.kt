package pl.iqtech.abyss.graph.serialization

import com.hazelcast.nio.serialization.compact.CompactReader
import com.hazelcast.nio.serialization.compact.CompactSerializer
import com.hazelcast.nio.serialization.compact.CompactWriter
import pl.iqtech.abyss.graph.AdjacencyEntry
import pl.iqtech.abyss.graph.AdjacencyMutation
import pl.iqtech.abyss.graph.AdjacencyMutationProcessor
import pl.iqtech.abyss.store.api.NodeId

// EntryProcessor already implements java.io.Serializable by interface contract, but every other wire
// type in this codebase uses Compact — register one here too rather than accepting Serializable as
// the one exception. Fields are the same shape for both mutation kinds (neighborId + edgeTypeTag
// always present; nodeTypeTag only for Add) so the Compact schema stays consistent across writes.
class AdjacencyMutationProcessorSerializer : CompactSerializer<AdjacencyMutationProcessor> {
    override fun getTypeName() = "AdjacencyMutationProcessor"
    override fun getCompactClass() = AdjacencyMutationProcessor::class.java

    override fun write(writer: CompactWriter, obj: AdjacencyMutationProcessor) {
        when (val m = obj.mutation) {
            is AdjacencyMutation.Add -> {
                writer.writeInt8("kind", KIND_ADD)
                writer.writeCompact("neighborId", m.entry.neighborId)
                writer.writeNullableInt16("nodeTypeTag", m.entry.nodeTypeTag)
                writer.writeInt16("edgeTypeTag", m.entry.edgeTypeTag)
            }
            is AdjacencyMutation.Remove -> {
                writer.writeInt8("kind", KIND_REMOVE)
                writer.writeCompact("neighborId", m.neighborId)
                writer.writeNullableInt16("nodeTypeTag", null)
                writer.writeInt16("edgeTypeTag", m.edgeTypeTag)
            }
        }
    }

    override fun read(reader: CompactReader): AdjacencyMutationProcessor {
        val neighborId = reader.readCompact<NodeId>("neighborId")!!
        val edgeTypeTag = reader.readInt16("edgeTypeTag")
        val mutation = when (reader.readInt8("kind")) {
            KIND_ADD -> AdjacencyMutation.Add(AdjacencyEntry(neighborId, reader.readNullableInt16("nodeTypeTag"), edgeTypeTag))
            else -> AdjacencyMutation.Remove(neighborId, edgeTypeTag)
        }
        return AdjacencyMutationProcessor(mutation)
    }

    private companion object {
        const val KIND_ADD: Byte = 0
        const val KIND_REMOVE: Byte = 1
    }
}
