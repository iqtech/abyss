package pl.iqtech.abyss.graph.serialization

import com.hazelcast.nio.serialization.compact.CompactReader
import com.hazelcast.nio.serialization.compact.CompactSerializer
import com.hazelcast.nio.serialization.compact.CompactWriter
import pl.iqtech.abyss.graph.ReverseEdgeKey
import java.util.UUID

class ReverseEdgeKeySerializer : CompactSerializer<ReverseEdgeKey> {
    override fun getTypeName() = "ReverseEdgeKey"
    override fun getCompactClass() = ReverseEdgeKey::class.java

    override fun write(writer: CompactWriter, obj: ReverseEdgeKey) {
        writer.writeString("toId",   obj.toId.toString())
        writer.writeString("fromId", obj.fromId.toString())
        writer.writeString("type",   obj.type)
    }

    override fun read(reader: CompactReader) = ReverseEdgeKey(
        toId   = UUID.fromString(reader.readString("toId")),
        fromId = UUID.fromString(reader.readString("fromId")),
        type   = reader.readString("type")!!
    )
}
