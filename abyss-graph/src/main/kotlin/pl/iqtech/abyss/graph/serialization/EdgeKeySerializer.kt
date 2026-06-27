package pl.iqtech.abyss.graph.serialization

import com.hazelcast.nio.serialization.compact.CompactReader
import com.hazelcast.nio.serialization.compact.CompactSerializer
import com.hazelcast.nio.serialization.compact.CompactWriter
import pl.iqtech.abyss.dsl.EdgeKey
import kotlin.uuid.Uuid

class EdgeKeySerializer : CompactSerializer<EdgeKey> {
    override fun getTypeName() = "EdgeKey"
    override fun getCompactClass() = EdgeKey::class.java

    override fun write(writer: CompactWriter, obj: EdgeKey) {
        writer.writeString("fromId", obj.fromId.toString())
        writer.writeString("toId",   obj.toId.toString())
        writer.writeString("type",   obj.type)
    }

    override fun read(reader: CompactReader) = EdgeKey(
        fromId = Uuid.parse(reader.readString("fromId")!!),
        toId   = Uuid.parse(reader.readString("toId")!!),
        type   = reader.readString("type")!!
    )
}
