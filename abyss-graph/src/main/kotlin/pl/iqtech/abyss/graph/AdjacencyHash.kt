package pl.iqtech.abyss.graph

import pl.iqtech.abyss.store.api.NodeId

// Vendored rather than reaching into com.hazelcast.internal.util.HashUtil (on the classpath but no
// API stability guarantee). NodeId.hashCode() (bytes.contentHashCode(), a plain polynomial array
// hash) has poor avalanche on the structured [header|tag|rawId] layout — sequential domain ids (e.g.
// LongKeyAdapter) would shard non-uniformly — so shard assignment needs a real mixing hash instead.
private fun murmur3_32(data: ByteArray, seed: Int = 0): Int {
    val c1 = -0x3361d2af // 0xcc9e2d51
    val c2 = 0x1b873593
    var h1 = seed
    val len = data.size
    val nBlocks = len / 4
    for (i in 0 until nBlocks) {
        var k1 = (data[i * 4].toInt() and 0xff) or
            ((data[i * 4 + 1].toInt() and 0xff) shl 8) or
            ((data[i * 4 + 2].toInt() and 0xff) shl 16) or
            ((data[i * 4 + 3].toInt() and 0xff) shl 24)
        k1 *= c1
        k1 = Integer.rotateLeft(k1, 15)
        k1 *= c2
        h1 = h1 xor k1
        h1 = Integer.rotateLeft(h1, 13)
        h1 = h1 * 5 + -0x19ab949c // 0xe6546b64
    }
    var k1 = 0
    val tailStart = nBlocks * 4
    when (len - tailStart) {
        3 -> { k1 = k1 xor ((data[tailStart + 2].toInt() and 0xff) shl 16); k1 = k1 xor ((data[tailStart + 1].toInt() and 0xff) shl 8); k1 = k1 xor (data[tailStart].toInt() and 0xff)
               k1 *= c1; k1 = Integer.rotateLeft(k1, 15); k1 *= c2; h1 = h1 xor k1 }
        2 -> { k1 = k1 xor ((data[tailStart + 1].toInt() and 0xff) shl 8); k1 = k1 xor (data[tailStart].toInt() and 0xff)
               k1 *= c1; k1 = Integer.rotateLeft(k1, 15); k1 *= c2; h1 = h1 xor k1 }
        1 -> { k1 = k1 xor (data[tailStart].toInt() and 0xff)
               k1 *= c1; k1 = Integer.rotateLeft(k1, 15); k1 *= c2; h1 = h1 xor k1 }
    }
    h1 = h1 xor len
    h1 = h1 xor (h1 ushr 16)
    h1 *= -0x7ee3623b // 0x85ebca6b
    h1 = h1 xor (h1 ushr 13)
    h1 *= -0x3d4d51cb // 0xc2b2ae35
    h1 = h1 xor (h1 ushr 16)
    return h1
}

internal fun shardIndexOf(neighborId: NodeId, shardCount: Int): Int =
    (murmur3_32(neighborId.bytes) and 0x7FFFFFFF) % shardCount
