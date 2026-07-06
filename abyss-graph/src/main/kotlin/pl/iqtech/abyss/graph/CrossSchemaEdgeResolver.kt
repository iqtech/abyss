package pl.iqtech.abyss.graph

import pl.iqtech.abyss.dsl.cachedAnnotation
import pl.iqtech.abyss.dsl.serialName
import pl.iqtech.abyss.store.api.CrossSchemaEdge
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.HeaderlessSchemaKeyAdapter
import pl.iqtech.abyss.store.api.KeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.SchemaKeyAdapter
import pl.iqtech.abyss.store.api.SchemaTag
import pl.iqtech.abyss.store.api.SchemaTagWidth
import kotlin.reflect.KClass

// Resolves a @CrossSchemaEdge-annotated edge's endpoints to NodeIds, from the annotation plus the
// edge's own fromId/toId and the caller's container-wide (width, headerless) — no per-edge width
// duplication, no tag/adapter registry (SchemaKeyAdapter.toNodeId is pure). Mirrors Op.toNodeOp's
// existing unchecked-cast style (AbyssGraphSchema.kt). `headerless` picks SchemaKeyAdapter
// (HeterogeneousSchemaGraph) vs HeaderlessSchemaKeyAdapter (HomogeneousSchemaGraph).
@Suppress("UNCHECKED_CAST")
internal fun crossSchemaEndpoints(
    edgeClass: KClass<out EdgeLike<*, *>>,
    fromId: Any?,
    toId: Any?,
    width: SchemaTagWidth,
    headerless: Boolean,
): Pair<NodeId, NodeId> {
    val ann = edgeClass.cachedAnnotation<CrossSchemaEdge>()
        ?: error("$edgeClass missing @CrossSchemaEdge — required for annotation-driven addCrossEdge")

    fun endpoint(tag: Long, cls: KClass<out KeyAdapter<*>>, id: Any?): NodeId {
        val inner = cls.objectInstance as? KeyAdapter<Any?>
            ?: error("$cls must be a singleton object (every KeyAdapter implementation is)")
        val schemaTag = SchemaTag(tag)
        return if (headerless) HeaderlessSchemaKeyAdapter(schemaTag, width, inner).toNodeId(id)
        else SchemaKeyAdapter(schemaTag, width, inner).toNodeId(id)
    }

    return endpoint(ann.fromTag, ann.fromAdapter, fromId) to
        endpoint(ann.toTag, ann.toAdapter, toId)
}

internal fun crossSchemaEndpoints(edge: EdgeLike<*, *>, width: SchemaTagWidth, headerless: Boolean): Pair<NodeId, NodeId> =
    crossSchemaEndpoints(edge::class, edge.fromId, edge.toId, width, headerless)

internal fun crossSchemaEdgeType(edgeClass: KClass<out EdgeLike<*, *>>): String = edgeClass.serialName()

// --- Container-level transaction { addCrossEdge(...) } builder, shared by Homogeneous/Heterogeneous ---

interface CrossSchemaTransactionLike {
    fun addCrossEdge(edge: EdgeLike<*, *>)
    fun removeCrossEdge(edgeClass: KClass<out EdgeLike<*, *>>, fromId: Any?, toId: Any?)
}

inline fun <reified E : EdgeLike<*, *>> CrossSchemaTransactionLike.removeCrossEdge(fromId: Any?, toId: Any?) =
    removeCrossEdge(E::class, fromId, toId)

internal sealed interface CrossSchemaOp {
    data class Add(val edge: EdgeLike<*, *>) : CrossSchemaOp
    data class Remove(val edgeClass: KClass<out EdgeLike<*, *>>, val fromId: Any?, val toId: Any?) : CrossSchemaOp
}

internal fun CrossSchemaOp.endpoints(width: SchemaTagWidth, headerless: Boolean): Pair<NodeId, NodeId> = when (this) {
    is CrossSchemaOp.Add -> crossSchemaEndpoints(edge, width, headerless)
    is CrossSchemaOp.Remove -> crossSchemaEndpoints(edgeClass, fromId, toId, width, headerless)
}

internal fun CrossSchemaOp.toNodeOp(width: SchemaTagWidth, headerless: Boolean): NodeOp = when (this) {
    is CrossSchemaOp.Add -> endpoints(width, headerless).let { (f, t) -> NodeOp.AddEdge(f, t, edge, null) }
    is CrossSchemaOp.Remove -> endpoints(width, headerless).let { (f, t) -> NodeOp.RemoveEdge(f, t, crossSchemaEdgeType(edgeClass)) }
}

internal class CrossSchemaTransactionBuffer : CrossSchemaTransactionLike {
    val ops = mutableListOf<CrossSchemaOp>()
    override fun addCrossEdge(edge: EdgeLike<*, *>) { ops += CrossSchemaOp.Add(edge) }
    override fun removeCrossEdge(edgeClass: KClass<out EdgeLike<*, *>>, fromId: Any?, toId: Any?) {
        ops += CrossSchemaOp.Remove(edgeClass, fromId, toId)
    }
}
