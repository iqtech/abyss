package pl.iqtech.abyss.graph.serialization

import com.hazelcast.nio.serialization.compact.CompactReader
import com.hazelcast.nio.serialization.compact.CompactSerializer
import com.hazelcast.nio.serialization.compact.CompactWriter
import pl.iqtech.abyss.store.api.NodeId

class NodeIdSerializer : CompactSerializer<NodeId> {
    override fun getTypeName() = "NodeId"
    override fun getCompactClass() = NodeId::class.java

    override fun write(writer: CompactWriter, obj: NodeId) = writer.writeArrayOfInt8("bytes", obj.bytes)
    override fun read(reader: CompactReader) = NodeId(reader.readArrayOfInt8("bytes")!!)
}
