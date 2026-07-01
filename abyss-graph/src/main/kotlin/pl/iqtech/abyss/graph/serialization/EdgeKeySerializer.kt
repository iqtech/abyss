package pl.iqtech.abyss.graph.serialization

import com.hazelcast.nio.serialization.compact.CompactReader
import com.hazelcast.nio.serialization.compact.CompactSerializer
import com.hazelcast.nio.serialization.compact.CompactWriter
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.store.api.EdgeAdapter

class EdgeKeySerializer(private val adapter: EdgeAdapter) : CompactSerializer<EdgeKey> {
    override fun getTypeName() = "EdgeKey"
    override fun getCompactClass() = EdgeKey::class.java

    override fun write(writer: CompactWriter, obj: EdgeKey) {
        writer.writeNodeKey("fromId", adapter.encodeKey(obj.fromId))
        writer.writeNodeKey("toId", adapter.encodeKey(obj.toId))
        writer.writeString("type", obj.type)
    }

    override fun read(reader: CompactReader) = EdgeKey(
        fromId = adapter.decodeKey(reader.readNodeKey("fromId", adapter.keyEncodingShape)),
        toId   = adapter.decodeKey(reader.readNodeKey("toId", adapter.keyEncodingShape)),
        type   = reader.readString("type")!!
    )
}
