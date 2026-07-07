package pl.iqtech.abyss.graph.serialization

import com.hazelcast.nio.serialization.compact.CompactReader
import com.hazelcast.nio.serialization.compact.CompactSerializer
import com.hazelcast.nio.serialization.compact.CompactWriter
import pl.iqtech.abyss.graph.AdjacencyEntry
import pl.iqtech.abyss.graph.AdjacencyValue

class AdjacencyValueSerializer : CompactSerializer<AdjacencyValue> {
    override fun getTypeName() = "AdjacencyValue"
    override fun getCompactClass() = AdjacencyValue::class.java

    override fun write(writer: CompactWriter, obj: AdjacencyValue) {
        writer.writeArrayOfCompact("entries", obj.entries.toTypedArray())
    }

    override fun read(reader: CompactReader) = AdjacencyValue(
        (reader.readArrayOfCompact("entries", AdjacencyEntry::class.java) ?: emptyArray()).toSet()
    )
}
