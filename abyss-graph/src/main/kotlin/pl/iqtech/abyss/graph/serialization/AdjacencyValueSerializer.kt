package pl.iqtech.abyss.graph.serialization

import com.hazelcast.nio.serialization.compact.CompactReader
import com.hazelcast.nio.serialization.compact.CompactSerializer
import com.hazelcast.nio.serialization.compact.CompactWriter
import com.hazelcast.nio.serialization.FieldKind
import pl.iqtech.abyss.graph.AdjacencyEntry
import pl.iqtech.abyss.graph.AdjacencyValue

class AdjacencyValueSerializer : CompactSerializer<AdjacencyValue> {
    override fun getTypeName() = "AdjacencyValue"
    override fun getCompactClass() = AdjacencyValue::class.java

    override fun write(writer: CompactWriter, obj: AdjacencyValue) {
        writer.writeArrayOfCompact("entries", obj.entries.toTypedArray())
        writer.writeBoolean("loaded", obj.loaded)
    }

    // `loaded` is absent from values written by a pre-flag member: default false (not loaded → preload again).
    override fun read(reader: CompactReader) = AdjacencyValue(
        (reader.readArrayOfCompact("entries", AdjacencyEntry::class.java) ?: emptyArray()).toSet(),
        reader.getFieldKind("loaded") != FieldKind.NOT_AVAILABLE && reader.readBoolean("loaded"),
    )
}
