package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.hazelcast.query.Predicates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.HopDirection
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreLike
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.KeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.SchemaKeyAdapter
import pl.iqtech.abyss.store.api.SchemaTagWidth
import pl.iqtech.abyss.store.api.UniformHexAdapter
import kotlin.reflect.full.findAnnotation

/**
 * Container hosting multiple [AbyssGraphSchema] views over a shared [HazelcastInstance].
 *
 * Each registered schema wraps its domain [KeyAdapter] in a [SchemaKeyAdapter] that stamps a
 * `tagWidth`-byte schema tag onto every [NodeId]. All schemas share one nodes/edges map — the tag
 * prefix keeps keys globally unique and self-describing, so [resolveSchema] can route any NodeId
 * back to its schema by reading the prefix.
 *
 * Edge keys use the uniform-hex encoding ([UniformHexAdapter]); configure the Hazelcast
 * [com.hazelcast.config.Config] with `registerAbyssSerializers(UniformHexAdapter, module)` where
 * `module` is the union of every schema's node/edge types (plus any cross-schema edge types).
 *
 * Cross-schema edges live in a separate `<edges>-cross` map so intra-schema queries never see them
 * (private-by-default). They are gated by [allowCrossSchemaEdges] and are cache-only in this version.
 */
class AbyssGraph(
    private val hazelcast: HazelcastInstance,
    val tagWidth: SchemaTagWidth = SchemaTagWidth.BYTE,
    private val nodesMapName: String = "abyss-nodes",
    private val edgesMapName: String = "abyss-edges",
    private val allowCrossSchemaEdges: Boolean = false,
) {
    private val schemas = mutableMapOf<Long, AbyssGraphSchema<*>>()

    private val nodesMap: IMap<NodeId, Any> by lazy { hazelcast.getMap(nodesMapName) }
    private val crossEdgesMap: IMap<EdgeKey, EdgeLike<NodeId>> by lazy { hazelcast.getMap("$edgesMapName-cross") }
    private val crossReverseMap: IMap<ReverseEdgeKey, Unit> by lazy { hazelcast.getMap("$edgesMapName-cross-reverse") }

    fun <ID> register(
        tag: Long,
        adapter: KeyAdapter<ID>,
        persistentStore: AbyssStoreLike<ID>? = null,
        ephemeralStore: AbyssEphemeralStoreLike<ID>? = null,
        asyncCachePopulation: Boolean = false,
    ): AbyssGraphSchema<ID> {
        require(tag !in schemas) { "Schema tag $tag already registered" }
        val tagged = SchemaKeyAdapter(tag, tagWidth, adapter)
        return AbyssGraphSchema(tagged, hazelcast, nodesMapName, edgesMapName, persistentStore, ephemeralStore, asyncCachePopulation)
            .also { schemas[tag] = it }
    }

    @Suppress("UNCHECKED_CAST")
    fun <ID> schema(tag: Long): AbyssGraphSchema<ID> =
        (schemas[tag] ?: error("No schema registered for tag $tag")) as AbyssGraphSchema<ID>

    /** Routes a tagged NodeId back to its owning schema by reading the tag prefix. */
    fun resolveSchema(nodeId: NodeId): AbyssGraphSchema<*> {
        val tag = SchemaKeyAdapter.readTag(nodeId, tagWidth)
        return schemas[tag] ?: error("No schema registered for tag $tag (from NodeId $nodeId)")
    }

    // --- Cross-schema edges (NodeId-level, cache-only) ------------------------------------------

    suspend fun addCrossEdge(edge: EdgeLike<NodeId>, checkIntegrity: Boolean = true): Either<AbyssError, Unit> {
        if (!allowCrossSchemaEdges)
            return AbyssError.IntegrityError("Cross-schema edges are disabled (allowCrossSchemaEdges=false)").left()
        val type = try { edgeType(edge) } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }
        if (checkIntegrity) integrityError(edge, type)?.let { return it.left() }
        return Either.catch {
            withContext(Dispatchers.IO) {
                crossEdgesMap.set(EdgeKey(edge.fromId, edge.toId, type, UniformHexAdapter.partitionKey(edge.fromId)), edge)
                crossReverseMap.set(ReverseEdgeKey(edge.toId, edge.fromId, type, UniformHexAdapter.partitionKey(edge.toId)), Unit)
            }
        }.mapLeft { AbyssError.Unexpected(it) }
    }

    suspend fun removeCrossEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Unit> =
        Either.catch {
            withContext(Dispatchers.IO) {
                crossEdgesMap.remove(EdgeKey(fromId, toId, type, UniformHexAdapter.partitionKey(fromId)))
                crossReverseMap.remove(ReverseEdgeKey(toId, fromId, type, UniformHexAdapter.partitionKey(toId)))
            }
            Unit
        }.mapLeft { AbyssError.Unexpected(it) }

    fun crossOutEdges(fromId: NodeId): Flow<EdgeLike<NodeId>> = flow {
        val pred = Predicates.partitionPredicate<EdgeKey, EdgeLike<NodeId>>(
            UniformHexAdapter.partitionKey(fromId),
            Predicates.equal<EdgeKey, EdgeLike<NodeId>>("__key.fromId", fromId.toString())
        )
        withContext(Dispatchers.IO) { crossEdgesMap.values(pred) }.forEach { emit(it) }
    }

    fun crossInEdges(toId: NodeId): Flow<EdgeLike<NodeId>> = flow {
        val revKeys = withContext(Dispatchers.IO) {
            crossReverseMap.keySet(Predicates.partitionPredicate<ReverseEdgeKey, Unit>(
                UniformHexAdapter.partitionKey(toId),
                Predicates.equal<ReverseEdgeKey, Unit>("__key.toId", toId.toString())
            ))
        }
        val edgeKeys = revKeys.map { EdgeKey(it.fromId, it.toId, it.type, UniformHexAdapter.partitionKey(it.fromId)) }.toSet()
        if (edgeKeys.isNotEmpty())
            withContext(Dispatchers.IO) { crossEdgesMap.getAll(edgeKeys) }.values.forEach { emit(it) }
    }

    /**
     * Advances a NodeId frontier across cross-schema edges, returning the reached NodeIds in their
     * target schemas. Compose with per-schema [AbyssGraphSchema] traversals (via [resolveSchema])
     * for cross-schema walks. Returns empty when cross-schema edges are disabled — so an intra-schema
     * traversal that never calls this stays within its own schema (private-by-default).
     */
    suspend fun crossHop(frontier: Set<NodeId>, direction: HopDirection, type: String? = null): Set<NodeId> {
        if (!allowCrossSchemaEdges) return emptySet()
        val result = LinkedHashSet<NodeId>()
        for (nid in frontier) {
            val edges = if (direction == HopDirection.OUTGOING) crossOutEdges(nid) else crossInEdges(nid)
            edges.collect { e ->
                if (type == null || edgeType(e) == type)
                    result += if (direction == HopDirection.OUTGOING) e.toId else e.fromId
            }
        }
        return result
    }

    private fun integrityError(edge: EdgeLike<NodeId>, type: String): AbyssError? {
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
        if (nid.bytes.size >= tagWidth.bytes) SchemaKeyAdapter.readTag(nid, tagWidth) else null

    private fun edgeType(edge: EdgeLike<*>): String =
        edge::class.findAnnotation<SerialName>()?.value ?: error("${edge::class} missing @SerialName")
}
