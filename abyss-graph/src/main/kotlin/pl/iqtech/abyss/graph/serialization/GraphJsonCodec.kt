package pl.iqtech.abyss.graph.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike

// TODO 2.3: a node or a relationship decoded from one export line.
sealed interface GraphLine {
    data class Node(val node: NodeLike<*>) : GraphLine
    data class Relationship(val edge: EdgeLike<*, *>) : GraphLine
}

// The seam for other export formats later (e.g. a literal external-tool schema) — one interface,
// AbyssJsonLinesCodec is the only implementation for now.
interface GraphJsonCodec {
    fun encodeNode(node: NodeLike<*>): String
    fun encodeEdge(edge: EdgeLike<*, *>): String
    fun decodeLine(line: String): GraphLine
}

// Default codec: the existing flat NodeLike/EdgeLike JSON shape (same machinery as
// NodeLikeHzSerializer/EdgeLikeHzSerializer — a "type" field equal to the class's @SerialName plus
// every property inline), wrapped one level with a "kind" discriminator so a node-line and a
// relationship-line are distinguishable without colliding with that inner "type" field.
class AbyssJsonLinesCodec(module: SerializersModule = EmptySerializersModule()) : GraphJsonCodec {
    private val json = createPolymorphicJsonSerializer<EdgeLike<*, *>>(
        baseJsonSerializer = createPolymorphicJsonSerializer<NodeLike<*>>(
            baseJsonSerializer = Json(from = customJsonSerializer) { serializersModule = customJsonSerializer.serializersModule + module }
        ) { UnknownNode(it) }
    ) { UnknownEdge(it) }

    @Suppress("UNCHECKED_CAST")
    private val nodeSer = PolymorphicSerializer(NodeLike::class) as KSerializer<NodeLike<*>>

    @Suppress("UNCHECKED_CAST")
    private val edgeSer = PolymorphicSerializer(EdgeLike::class) as KSerializer<EdgeLike<*, *>>

    override fun encodeNode(node: NodeLike<*>) =
        json.encodeToString(JsonObject.serializer(), buildJsonObject { put("kind", "node"); put("node", json.encodeToJsonElement(nodeSer, node)) })

    override fun encodeEdge(edge: EdgeLike<*, *>) =
        json.encodeToString(JsonObject.serializer(), buildJsonObject { put("kind", "relationship"); put("edge", json.encodeToJsonElement(edgeSer, edge)) })

    override fun decodeLine(line: String): GraphLine {
        val obj = json.parseToJsonElement(line).jsonObject
        return when (obj.getValue("kind").jsonPrimitive.content) {
            "node" -> GraphLine.Node(json.decodeFromJsonElement(nodeSer, obj.getValue("node")))
            "relationship" -> GraphLine.Relationship(json.decodeFromJsonElement(edgeSer, obj.getValue("edge")))
            else -> error("Unknown line kind in: $line")
        }
    }
}
