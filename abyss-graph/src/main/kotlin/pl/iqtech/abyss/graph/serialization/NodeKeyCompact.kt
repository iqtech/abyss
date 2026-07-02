package pl.iqtech.abyss.graph.serialization

import com.hazelcast.nio.serialization.compact.CompactReader
import com.hazelcast.nio.serialization.compact.CompactWriter
import pl.iqtech.abyss.store.api.KeyEncodingShape
import pl.iqtech.abyss.store.api.NodeKeyEncoding
import pl.iqtech.abyss.store.api.NodeKeyKind
import pl.iqtech.abyss.store.api.kind

internal fun CompactWriter.writeNodeKey(field: String, encoding: NodeKeyEncoding) {
    when (encoding) {
        is NodeKeyEncoding.Int64 -> writeInt64(field, encoding.value)
        is NodeKeyEncoding.Str -> writeString(field, encoding.value)
        is NodeKeyEncoding.Int64Pair -> {
            writeInt64("${field}Hi", encoding.hi)
            writeInt64("${field}Lo", encoding.lo)
        }
        // Fixed self-describing superset so one EdgeKey Compact class serves every registered shape.
        is NodeKeyEncoding.Tagged -> {
            writeInt64("${field}Tag", encoding.tag)
            writeInt8("${field}Kind", encoding.inner.kind().id)
            val inner = encoding.inner
            writeInt64("${field}Hi", if (inner is NodeKeyEncoding.Int64Pair) inner.hi else 0L)
            writeInt64("${field}Lo", when (inner) {
                is NodeKeyEncoding.Int64 -> inner.value
                is NodeKeyEncoding.Int64Pair -> inner.lo
                else -> 0L
            })
            writeString("${field}Str", (inner as? NodeKeyEncoding.Str)?.value)
        }
    }
}

internal fun CompactReader.readNodeKey(field: String, shape: KeyEncodingShape): NodeKeyEncoding = when (shape) {
    KeyEncodingShape.INT64 -> NodeKeyEncoding.Int64(readInt64(field))
    KeyEncodingShape.STRING -> NodeKeyEncoding.Str(readString(field)!!)
    KeyEncodingShape.INT64_PAIR -> NodeKeyEncoding.Int64Pair(readInt64("${field}Hi"), readInt64("${field}Lo"))
    KeyEncodingShape.TAGGED -> {
        val tag = readInt64("${field}Tag")
        val hi = readInt64("${field}Hi"); val lo = readInt64("${field}Lo"); val str = readString("${field}Str")
        val inner = when (readInt8("${field}Kind")) {
            NodeKeyKind.INT64.id -> NodeKeyEncoding.Int64(lo)
            NodeKeyKind.STRING.id -> NodeKeyEncoding.Str(str!!)
            NodeKeyKind.INT64_PAIR.id -> NodeKeyEncoding.Int64Pair(hi, lo)
            else -> error("Unknown NodeKeyKind")
        }
        NodeKeyEncoding.Tagged(tag, inner)
    }
}
