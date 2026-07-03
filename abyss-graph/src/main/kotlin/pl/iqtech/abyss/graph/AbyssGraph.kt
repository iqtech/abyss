package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.left
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreLike
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.KeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeKey
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.SchemaKeyAdapter
import pl.iqtech.abyss.store.api.SchemaTagWidth
import kotlin.reflect.full.findAnnotation

/**
 * Container hosting multiple [AbyssGraphSchema] views over a shared [HazelcastInstance], and the
 * NodeId-level engine that per-schema traversals run against so a walk can span schemas.
 *
 * Each registered schema wraps its domain [KeyAdapter] in a [SchemaKeyAdapter] that stamps a
 * `tagWidth`-byte schema tag onto every [NodeId]. All schemas share one nodes/edges map — the tag
 * prefix keeps keys globally unique and self-describing, so [resolveSchema] routes any NodeId back to
 * its schema by reading the prefix, and [nodeAt]/[outAt]/[inAt] delegate to it.
 *
 * Cross-schema edges live in the SAME shared edges/reverse maps as intra-schema edges (their tagged
 * endpoints self-describe: `fromTag != toTag`), so `outgoing<E>()`/`incoming<E>()` reach them as
 * ordinary hops. They are gated by [allowCrossSchemaEdges] and are cache-only in this version.
 */
class AbyssGraph(
    private val hazelcast: HazelcastInstance,
    val tagWidth: SchemaTagWidth = SchemaTagWidth.BYTE,
    private val nodesMapName: String = "abyss-nodes",
    private val edgesMapName: String = "abyss-edges",
    private val allowCrossSchemaEdges: Boolean = false,
) : NodeIdEngine {
    private val schemas = mutableMapOf<Long, AbyssGraphSchema<*>>()

    // Populated only when tagWidth == NONE: the single, untagged schema this container fronts.
    private var fallback: AbyssGraphSchema<*>? = null

    private val nodesMap: IMap<NodeId, Any> by lazy { hazelcast.getMap(nodesMapName) }
    private val edgesMap: IMap<EdgeKey, Any> by lazy { hazelcast.getMap(edgesMapName) }
    private val reverseMap: IMap<ReverseEdgeKey, Unit> by lazy { hazelcast.getMap("$edgesMapName-reverse") }

    fun <ID> register(
        tag: Long,
        adapter: KeyAdapter<ID>,
        persistentStore: AbyssStoreLike<ID>? = null,
        ephemeralStore: AbyssEphemeralStoreLike<ID>? = null,
        asyncCachePopulation: Boolean = false,
    ): AbyssGraphSchema<ID> {
        require(tagWidth != SchemaTagWidth.NONE) { "register requires a tagged width; use singleSchema() for NONE" }
        require(tag !in schemas) { "Schema tag $tag already registered" }
        val tagged = SchemaKeyAdapter(tag, tagWidth, adapter)
        return AbyssGraphSchema(tagged, hazelcast, nodesMapName, edgesMapName, persistentStore, ephemeralStore, asyncCachePopulation)
            .also { it.traversalEngine = this; schemas[tag] = it }
    }

    /**
     * Single-schema entry point ([tagWidth] must be [SchemaTagWidth.NONE]). Holds the caller's raw
     * [adapter] directly — no [SchemaKeyAdapter] wrapping — so edge-key Compact serialization uses
     * the adapter's native shape (Int64/Int64Pair/Str), never the hex fallback. NodeIds are untagged
     * and byte-identical to a standalone `AbyssGraphSchema(adapter, …)`.
     */
    fun <ID> singleSchema(
        adapter: KeyAdapter<ID>,
        persistentStore: AbyssStoreLike<ID>? = null,
        ephemeralStore: AbyssEphemeralStoreLike<ID>? = null,
        asyncCachePopulation: Boolean = false,
    ): AbyssGraphSchema<ID> {
        require(tagWidth == SchemaTagWidth.NONE) { "singleSchema requires SchemaTagWidth.NONE, got $tagWidth" }
        require(fallback == null) { "singleSchema already set" }
        return AbyssGraphSchema(adapter, hazelcast, nodesMapName, edgesMapName, persistentStore, ephemeralStore, asyncCachePopulation)
            .also { it.traversalEngine = this; fallback = it }
    }

    @Suppress("UNCHECKED_CAST")
    fun <ID> schema(tag: Long): AbyssGraphSchema<ID> =
        (schemas[tag] ?: error("No schema registered for tag $tag")) as AbyssGraphSchema<ID>

    /** Routes a tagged NodeId back to its owning schema by reading the tag prefix. */
    fun resolveSchema(nodeId: NodeId): AbyssGraphSchema<*> =
        if (tagWidth == SchemaTagWidth.NONE) fallback ?: error("No single schema registered")
        else {
            val tag = NodeKey.tag(nodeId)
            schemas[tag] ?: error("No schema registered for tag $tag (from NodeId $nodeId)")
        }

    // --- NodeIdEngine: route each NodeId to its schema (cross-schema edges share the same maps) -----

    override suspend fun nodeAt(nid: NodeId): NodeLike<*>? = resolveSchema(nid).nodeAt(nid)
    override suspend fun outAt(nid: NodeId, type: String?, needValue: Boolean): List<Hop> = resolveSchema(nid).outAt(nid, type, needValue)
    override suspend fun inAt(nid: NodeId, type: String?, needValue: Boolean): List<Hop> = resolveSchema(nid).inAt(nid, type, needValue)
    // Any schema owning one of the hops' endpoints resolves the whole batch: the edges map is one
    // shared instance and edgeKey's partition key is schema-agnostic (SchemaKeyAdapter.partitionKey).
    override suspend fun resolveEdges(hops: List<Hop>): Map<Hop, EdgeLike<*, *>> =
        if (hops.isEmpty()) emptyMap() else resolveSchema(hops.first().fromId).resolveEdges(hops)
    override fun allNodeIdsRaw(): Flow<NodeId> = flow { nodesMap.keys.forEach { emit(it) } }

    // --- Cross-schema edges (NodeId-level, cache-only, in the shared edge/reverse maps) -------------

    suspend fun addCrossEdge(edge: EdgeLike<NodeId, NodeId>, checkIntegrity: Boolean = true): Either<AbyssError, Unit> {
        if (!allowCrossSchemaEdges)
            return AbyssError.IntegrityError("Cross-schema edges are disabled (allowCrossSchemaEdges=false)").left()
        val type = try { edgeType(edge) } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }
        if (checkIntegrity) integrityError(edge, type)?.let { return it.left() }
        return Either.catch {
            withContext(Dispatchers.IO) {
                edgesMap.set(EdgeKey(edge.fromId, edge.toId, type, edge.fromId.toString()), edge)
                reverseMap.set(ReverseEdgeKey(edge.toId, edge.fromId, type, edge.toId.toString()), Unit)
            }
        }.mapLeft { AbyssError.Unexpected(it) }
    }

    suspend fun removeCrossEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Unit> =
        Either.catch {
            withContext(Dispatchers.IO) {
                edgesMap.remove(EdgeKey(fromId, toId, type, fromId.toString()))
                reverseMap.remove(ReverseEdgeKey(toId, fromId, type, toId.toString()))
            }
            Unit
        }.mapLeft { AbyssError.Unexpected(it) }

    private fun integrityError(edge: EdgeLike<NodeId, NodeId>, type: String): AbyssError? {
        val fromTag = schemaTagOf(edge.fromId)
            ?: return AbyssError.IntegrityError("Cross-edge $type: fromId ${edge.fromId} has no valid schema tag")
        if (fromTag !in schemas) return AbyssError.IntegrityError("Cross-edge $type: fromId schema tag $fromTag not registered")
        val toTag = schemaTagOf(edge.toId)
            ?: return AbyssError.IntegrityError("Cross-edge $type: toId ${edge.toId} has no valid schema tag")
        if (toTag !in schemas) return AbyssError.IntegrityError("Cross-edge $type: toId schema tag $toTag not registered")
        if (!nodesMap.containsKey(edge.fromId)) return AbyssError.IntegrityError("Cross-edge $type: fromId node ${edge.fromId} not found")
        if (!nodesMap.containsKey(edge.toId)) return AbyssError.IntegrityError("Cross-edge $type: toId node ${edge.toId} not found")
        return null
    }

    private fun schemaTagOf(nid: NodeId): Long? =
        if (nid.bytes.isNotEmpty() && NodeKey.width(nid) != SchemaTagWidth.NONE) NodeKey.tag(nid) else null

    private fun edgeType(edge: EdgeLike<*, *>): String =
        edge::class.findAnnotation<SerialName>()?.value ?: error("${edge::class} missing @SerialName")
}
