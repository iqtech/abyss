# TODO 1.15 — Self-describing NodeId keys (1-byte header: tag-width + id-shape)

## Context

Today a `NodeId`'s bytes are `tagPrefix(width) ++ innerIdBytes`. Neither the **schema-tag width**
(`SchemaTagWidth`, `KeyAdapter.kt:132`) nor the **inner id shape** (`NodeKeyKind`, `KeyAdapter.kt:53`)
lives in the key — width is a graph-global constant (`AbyssGraph.tagWidth`) and shape is recovered
indirectly by reading the tag, looking up the schema, then asking the schema's adapter. A key can't
be decoded without that external context. `readTag(nodeId, width)` has to be *told* the width; a bare
`NodeId` off the wire is opaque.

Fix: prepend **one header byte** to every key, so every key self-describes.

```
[header:1][tag: width.bytes][rawId: variable]
   │
   ├─ high nibble = SchemaTagWidth.ordinal   (NONE=0, BYTE=1, SHORT=2, INT=3, LONG=4, UUID=5)
   └─ low  nibble = NodeKeyKind.id           (INT64=0, STRING=1, INT64_PAIR=2)
```

Header is on **all** keys, tagged or not — a standalone / single-schema (`NONE`) key is
`[header(NONE,kind)][rawId]`, no tag bytes. So `NodeId.fromHex(...)` off the wire decodes standalone:
read byte 0 → width + kind, skip `1 + width.bytes` for the tag, decode the tail by kind's canonical
layout (INT64→8 BE bytes, INT64_PAIR→16, STRING→UTF-8 tail). No graph, no registry.

### Two encoding decisions that are load-bearing

1. **Width nibble = `SchemaTagWidth.ordinal`, NOT the byte count.** `UUID(16)` doesn't fit in 4 bits;
   its ordinal (5) does. Decode via `SchemaTagWidth.entries[nibble]`, guarded against out-of-range.
2. **Low nibble = `NodeKeyKind`, NOT `KeyEncodingShape`.** `KeyEncodingShape.TAGGED` is a Compact-layer
   wrapper marker, not a leaf id shape. `NodeKeyKind` is the concrete inner shape and already carries
   stable byte ids (0/1/2). Reuse it.

### Deliberate regression

The "single-schema container costs nothing over a standalone schema" property (`AbyssGraph.kt:71`,
byte-identical untagged keys) is **gone**: every key gains 1 byte and is no longer byte-identical to
the raw domain id. Accepted — universal self-decoding is the goal, and half-self-decoding isn't
self-decoding.

### Scope boundary

This changes only the `NodeId.bytes` **identity form**. The Compact **edge-key** form
(`NodeKeyCompact.kt`: `tag + kind + hi/lo/str`) is a separate representation for native predicates and
already self-describes shape via `kind`; it does **not** grow a header and does **not** change. Neither
does `nativeKeyEq` (`AbyssGraphSchema.kt:464`) — it operates on `NodeKeyEncoding`, never raw bytes.
Note the asymmetry: reconstructing a `NodeId` from a Compact `Tagged` encoding still needs the
external width (the Compact form carries the tag *value* but not the width), so `MultiSchemaAdapter`
keeps `width`. That's inherent to the two-representation split.

## Changes

### `abyss-store-api/.../KeyAdapter.kt`

**New `NodeKey` helper** (single home for header compose/parse — replaces `tagPrefix`/`stripTag` free
funcs and the `readTag(_, width)` companion):

```kotlin
object NodeKey {
    fun compose(width: SchemaTagWidth, kind: NodeKeyKind, tag: Long, rawId: ByteArray): NodeId {
        val header = ((width.ordinal shl 4) or kind.id.toInt()).toByte()
        val out = ByteArray(1 + width.bytes + rawId.size)
        out[0] = header
        for (i in 0 until width.bytes) out[1 + i] = (tag ushr (8 * (width.bytes - 1 - i))).toByte()
        rawId.copyInto(out, 1 + width.bytes)
        return NodeId(out)
    }
    fun width(nodeId: NodeId): SchemaTagWidth {
        val o = (nodeId.bytes[0].toInt() ushr 4) and 0x0F
        require(o < SchemaTagWidth.entries.size) { "Bad tag-width nibble $o in $nodeId" }
        return SchemaTagWidth.entries[o]
    }
    fun kind(nodeId: NodeId): NodeKeyKind {
        val k = (nodeId.bytes[0].toInt() and 0x0F).toByte()
        return NodeKeyKind.entries.firstOrNull { it.id == k } ?: error("Bad kind nibble $k in $nodeId")
    }
    fun tag(nodeId: NodeId): Long {
        val w = width(nodeId).bytes
        require(nodeId.bytes.size >= 1 + w) { "NodeId too short for its header: $nodeId" }
        var v = 0L; for (i in 0 until w) v = (v shl 8) or (nodeId.bytes[1 + i].toLong() and 0xFF)
        return v
    }
    fun rawId(nodeId: NodeId): ByteArray =
        nodeId.bytes.copyOfRange(1 + width(nodeId).bytes, nodeId.bytes.size)

    // Registry-free: raw bytes + kind -> Compact encoding (and back), used by the shared serializer.
    fun encoding(kind: NodeKeyKind, rawId: ByteArray): NodeKeyEncoding = when (kind) {
        NodeKeyKind.INT64 -> NodeKeyEncoding.Int64(ByteBuffer.wrap(rawId).long)
        NodeKeyKind.STRING -> NodeKeyEncoding.Str(String(rawId, Charsets.UTF_8))
        NodeKeyKind.INT64_PAIR -> ByteBuffer.wrap(rawId).let { NodeKeyEncoding.Int64Pair(it.long, it.long) }
    }
    fun rawId(enc: NodeKeyEncoding): ByteArray = when (enc) {
        is NodeKeyEncoding.Int64 -> ByteBuffer.allocate(8).putLong(enc.value).array()
        is NodeKeyEncoding.Str -> enc.value.toByteArray(Charsets.UTF_8)
        is NodeKeyEncoding.Int64Pair -> ByteBuffer.allocate(16).putLong(enc.hi).putLong(enc.lo).array()
        is NodeKeyEncoding.Tagged -> error("Tagged has no raw id")
    }
}
```

**`KeyAdapter<ID>` interface** — split raw-id conversion from header composition. `toNodeId`/`fromNodeId`
become header-composing defaults; base adapters implement `nodeKeyKind` + `encodeIdBytes`/`decodeIdBytes`:

```kotlin
interface KeyAdapter<ID> : EdgeAdapter {
    val nodeKeyKind: NodeKeyKind
    fun encodeIdBytes(id: ID): ByteArray
    fun decodeIdBytes(bytes: ByteArray): ID
    fun toNodeId(id: ID): NodeId = NodeKey.compose(SchemaTagWidth.NONE, nodeKeyKind, 0L, encodeIdBytes(id))
    fun fromNodeId(nodeId: NodeId): ID = decodeIdBytes(NodeKey.rawId(nodeId))
    override fun partitionKey(nodeId: NodeId): Any = nodeId.toString()
}
```

**Base adapters** (`Long`/`String`/`Uuid`) — drop the direct `toNodeId`/`fromNodeId` overrides; move
their raw conversions into `encodeIdBytes`/`decodeIdBytes`, add `nodeKeyKind`. Their `encodeKey`/
`decodeKey` route through the (now header-aware) `fromNodeId`/`toNodeId` and stay as-is in spirit —
e.g. `LongKeyAdapter.decodeKey` = `toNodeId(encoding.value)` produces a header'd key automatically.
`partitionKey` for `Long`/`String` must switch to `nodeId.toString()` (the raw-typed partition key no
longer round-trips now that bytes carry a header — or keep typed by decoding via `fromNodeId`, pick one
and keep the standalone `AbyssGraphSchema` tests green).

**`SchemaKeyAdapter`** — no precomputed `prefix`; compose with its `width`/`tag` and the inner's raw
bytes:

```kotlin
override val nodeKeyKind = inner.nodeKeyKind
override fun toNodeId(id: ID): NodeId = NodeKey.compose(width, inner.nodeKeyKind, tag, inner.encodeIdBytes(id))
override fun fromNodeId(nodeId: NodeId): ID = inner.decodeIdBytes(NodeKey.rawId(nodeId))
override fun encodeKey(nodeId: NodeId) = NodeKeyEncoding.Tagged(NodeKey.tag(nodeId), NodeKey.encoding(inner.nodeKeyKind, NodeKey.rawId(nodeId)))
override fun decodeKey(encoding) = NodeKey.compose(width, inner.nodeKeyKind, (encoding as Tagged).tag, NodeKey.rawId(encoding.inner))
```
Drop the `readTag(nodeId, width)` companion; callers use `NodeKey.tag(nodeId)`.

**`MultiSchemaAdapter`** — **registry drops** (decode is registry-free via header kind); keep `width`
for reconstructing the prefix from a Compact `Tagged`:

```kotlin
class MultiSchemaAdapter(val width: SchemaTagWidth) : EdgeAdapter {
    override val keyEncodingShape = KeyEncodingShape.TAGGED
    override fun partitionKey(nodeId: NodeId) = nodeId.toString()
    override fun encodeKey(nodeId: NodeId) = NodeKeyEncoding.Tagged(NodeKey.tag(nodeId), NodeKey.encoding(NodeKey.kind(nodeId), NodeKey.rawId(nodeId)))
    override fun decodeKey(encoding: NodeKeyEncoding) =
        NodeKey.compose(width, (encoding as NodeKeyEncoding.Tagged).inner.kind(), encoding.tag, NodeKey.rawId(encoding.inner))
}
```

**`UniformHexAdapter`** — unchanged (hex of full bytes, header rides along).

### `abyss-graph/.../AbyssGraph.kt`
- `resolveSchema`: `NodeKey.tag(nodeId)` (drop the `tagWidth` arg); keep the `NONE` → `fallback` branch.
- `schemaTagOf`: `if (nid.bytes.isNotEmpty() && NodeKey.width(nid) != NONE) NodeKey.tag(nid) else null`.
- `SchemaKeyAdapter(tag, tagWidth, adapter)` at register stays (width still configured per graph).

### Serde — no change
`NodeKeyCompact.kt`, `EdgeKeySerializer`, `ReverseEdgeKeySerializer`, `nativeKeyEq` untouched.

### Tests to update
- `SerializationTest`, `MultiSchemaTest`, `SingleSchemaTest`, `GraphTest`: any hard-coded key-byte /
  hex assertions shift by the leading header byte; `MultiSchemaAdapter(width, registry)` call sites
  drop the registry (`MultiSchemaTest.kt:48`).
- Add one self-decode assertion: build a `NodeId` via each base + `SchemaKeyAdapter`, then recover
  `(width, kind, tag, rawId)` from bytes alone with `NodeKey.*` and assert round-trip — the check that
  fails if the header math breaks.

## Format break
Every `NodeId`'s bytes change (hex strings, partition keys, cross-edge `EdgeKey`s). Pre-1.0 (0.21.0),
no migration owed — lands as one commit.