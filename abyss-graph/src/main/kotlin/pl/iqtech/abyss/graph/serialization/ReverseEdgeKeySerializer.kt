package pl.iqtech.abyss.graph.serialization

import com.hazelcast.nio.serialization.compact.CompactReader
import com.hazelcast.nio.serialization.compact.CompactSerializer
import com.hazelcast.nio.serialization.compact.CompactWriter
import pl.iqtech.abyss.graph.ReverseEdgeKey
import pl.iqtech.abyss.store.api.EdgeAdapter

class ReverseEdgeKeySerializer(private val adapter: EdgeAdapter) : CompactSerializer<ReverseEdgeKey> {
    override fun getTypeName() = "ReverseEdgeKey"
    override fun getCompactClass() = ReverseEdgeKey::class.java

    override fun write(writer: CompactWriter, obj: ReverseEdgeKey) {
        writer.writeNodeKey("toId", adapter.encodeKey(obj.toId))
        writer.writeNodeKey("fromId", adapter.encodeKey(obj.fromId))
        writer.writeString("type", obj.type)
    }

    override fun read(reader: CompactReader) = ReverseEdgeKey(
        toId   = adapter.decodeKey(reader.readNodeKey("toId", adapter.keyEncodingShape)),
        fromId = adapter.decodeKey(reader.readNodeKey("fromId", adapter.keyEncodingShape)),
        type   = reader.readString("type")!!
    )
}
