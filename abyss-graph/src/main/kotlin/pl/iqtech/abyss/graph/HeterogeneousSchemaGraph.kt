package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.left
import com.hazelcast.core.HazelcastInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreLike
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.KeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeKey
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.SchemaKeyAdapter
import pl.iqtech.abyss.store.api.SchemaTag
import pl.iqtech.abyss.store.api.SchemaTagWidth
import kotlin.reflect.full.findAnnotation

/**
 * Container hosting multiple typed [AbyssGraphSchema] views over one shared [AbyssSchemaWorker],
 * where every registered schema's EdgeAdapter is re-derived per key from the self-describing NodeId
 * header (TODO 1.19: today's general case — contrast [HomogeneousSchemaGraph], which computes one
 * fixed descriptor once instead).
 *
 * There is no tag→schema registry: every [NodeId] self-describes (tag width + kind + tag), so the
 * worker derives what it needs per key and routing is a pure function of the NodeId. [register] hands
 * back a typed facade the caller holds; the container keeps only the set of registered tags for
 * duplicate-registration and cross-edge integrity guards.
 *
 * Cross-schema edges live in the SAME shared edges/reverse maps as intra-schema edges (their tagged
 * endpoints self-describe: `fromTag != toTag`), so `outgoing<E>()`/`incoming<E>()` reach them as
 * ordinary hops. They are gated by [allowCrossSchemaEdges] and are cache-only in this version.
 */
class HeterogeneousSchemaGraph(
    hazelcast: HazelcastInstance,
    val tagWidth: SchemaTagWidth = SchemaTagWidth.BYTE,
    nodesMapName: String = "abyss-nodes",
    edgesMapName: String = "abyss-edges",
    private val allowCrossSchemaEdges: Boolean = false,
    persistentStore: AbyssStoreLike? = null,
    ephemeralStore: AbyssEphemeralStoreLike? = null,
    asyncCachePopulation: Boolean = false,
) : NodeIdEngine {

    init { require(tagWidth != SchemaTagWidth.NONE) { "tagWidth must be tagged; use SingleSchemaGraph for untagged single-schema graphs" } }

    private val worker = AbyssSchemaWorker(
        hazelcast, nodesMapName, edgesMapName, persistentStore, ephemeralStore, asyncCachePopulation,
        HeterogeneousSchemaResolution,
    )

    private val registeredTags = mutableSetOf<SchemaTag>()

    fun <ID> register(tag: SchemaTag, adapter: KeyAdapter<ID>): AbyssGraphSchema<ID> {
        require(registeredTags.add(tag)) { "Schema tag $tag already registered" }
        val tagged = SchemaKeyAdapter(tag, tagWidth, adapter)
        return AbyssGraphSchema(tagged, worker).also { it.traversalEngine = this }
    }

    // --- NodeIdEngine: the shared worker self-resolves each NodeId (cross-schema edges share the maps) -

    override suspend fun nodeAt(nid: NodeId): NodeLike<*>? = worker.nodeAt(nid)
    override suspend fun outAt(nid: NodeId, type: String?, needValue: Boolean): List<Hop> = worker.outAt(nid, type, needValue)
    override suspend fun inAt(nid: NodeId, type: String?, needValue: Boolean): List<Hop> = worker.inAt(nid, type, needValue)
    override suspend fun resolveEdges(hops: List<Hop>): Map<Hop, EdgeLike<*, *>> = worker.resolveEdges(hops)
    override fun allNodeIdsRaw(): Flow<NodeId> = worker.allNodeIdsRaw()

    // --- Cross-schema edges (NodeId-level, cache-only, in the shared edge/reverse maps) -------------

    suspend fun addCrossEdge(edge: EdgeLike<NodeId, NodeId>, checkIntegrity: Boolean = true): Either<AbyssError, Unit> {
        if (!allowCrossSchemaEdges)
            return AbyssError.IntegrityError("Cross-schema edges are disabled (allowCrossSchemaEdges=false)").left()
        val type = try { edgeType(edge) } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }
        if (checkIntegrity) integrityError(edge, type)?.let { return it.left() }
        return Either.catch {
            withContext(Dispatchers.IO) { worker.putCrossEdge(edge, type) }
        }.mapLeft { AbyssError.Unexpected(it) }
    }

    suspend fun removeCrossEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Unit> =
        Either.catch {
            withContext(Dispatchers.IO) { worker.removeCrossEdge(fromId, toId, type) }
            Unit
        }.mapLeft { AbyssError.Unexpected(it) }

    private fun integrityError(edge: EdgeLike<NodeId, NodeId>, type: String): AbyssError? {
        val fromTag = schemaTagOf(edge.fromId)
            ?: return AbyssError.IntegrityError("Cross-edge $type: fromId ${edge.fromId} has no valid schema tag")
        if (fromTag !in registeredTags) return AbyssError.IntegrityError("Cross-edge $type: fromId schema tag $fromTag not registered")
        val toTag = schemaTagOf(edge.toId)
            ?: return AbyssError.IntegrityError("Cross-edge $type: toId ${edge.toId} has no valid schema tag")
        if (toTag !in registeredTags) return AbyssError.IntegrityError("Cross-edge $type: toId schema tag $toTag not registered")
        if (!worker.containsNodeInCache(edge.fromId)) return AbyssError.IntegrityError("Cross-edge $type: fromId node ${edge.fromId} not found")
        if (!worker.containsNodeInCache(edge.toId)) return AbyssError.IntegrityError("Cross-edge $type: toId node ${edge.toId} not found")
        return null
    }

    private fun schemaTagOf(nid: NodeId): SchemaTag? =
        if (nid.bytes.isNotEmpty() && NodeKey.width(nid) != SchemaTagWidth.NONE) NodeKey.tag(nid) else null

    private fun edgeType(edge: EdgeLike<*, *>): String =
        edge::class.findAnnotation<SerialName>()?.value ?: error("${edge::class} missing @SerialName")
}
