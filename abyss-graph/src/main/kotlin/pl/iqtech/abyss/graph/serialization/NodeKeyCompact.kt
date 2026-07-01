package pl.iqtech.abyss.graph.serialization

import com.hazelcast.nio.serialization.compact.CompactReader
import com.hazelcast.nio.serialization.compact.CompactWriter
import pl.iqtech.abyss.store.api.KeyEncodingShape
import pl.iqtech.abyss.store.api.NodeKeyEncoding

internal fun CompactWriter.writeNodeKey(field: String, encoding: NodeKeyEncoding) {
    when (encoding) {
        is NodeKeyEncoding.Int64 -> writeInt64(field, encoding.value)
        is NodeKeyEncoding.Str -> writeString(field, encoding.value)
        is NodeKeyEncoding.Int64Pair -> {
            writeInt64("${field}Hi", encoding.hi)
            writeInt64("${field}Lo", encoding.lo)
        }
    }
}

internal fun CompactReader.readNodeKey(field: String, shape: KeyEncodingShape): NodeKeyEncoding = when (shape) {
    KeyEncodingShape.INT64 -> NodeKeyEncoding.Int64(readInt64(field))
    KeyEncodingShape.STRING -> NodeKeyEncoding.Str(readString(field)!!)
    KeyEncodingShape.INT64_PAIR -> NodeKeyEncoding.Int64Pair(readInt64("${field}Hi"), readInt64("${field}Lo"))
}
