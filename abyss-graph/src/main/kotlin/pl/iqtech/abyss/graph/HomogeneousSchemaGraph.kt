package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import com.hazelcast.core.HazelcastInstance
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreLike
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.KeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeKey
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.HeaderlessSchemaKeyAdapter
import pl.iqtech.abyss.store.api.SchemaTag
import pl.iqtech.abyss.store.api.SchemaTagWidth
import kotlin.reflect.full.findAnnotation
import kotlin.time.Duration

/**
 * Container over one shared [AbyssSchemaWorker] where every schema is the SAME shape — one
 * [keyAdapter] for the whole container — and every registered schema shares one fixed
 * [HomogeneousSchemaResolution] (TODO 1.19: all tags have the same [tagWidth], so the EdgeAdapter is
 * computed once at construction and never re-derived per key — contrast [HeterogeneousSchemaGraph]).
 *
 * A caller-chosen tag carries no domain meaning here — every schema is the same shape, so the tag is
 * purely a coexistence prefix (e.g. a per-tenant user id), not something the container needs to track.
 * There is no registry: [forTag] builds an [AbyssGraphSchema] view on the fly from the shared worker
 * and adapter — nothing is stored, so the tag space can be unbounded (one tag per user, say) without
 * needing a pre-registration step.
 *
 * NodeIds here carry NO header byte at all (unlike [HeterogeneousSchemaGraph]): width and kind are
 * both fixed by this container's construction, so there's nothing left for a header to self-describe
 * — see [HeaderlessSchemaKeyAdapter]/[HeaderlessMultiSchemaAdapter]. This means a
 * `HomogeneousSchemaGraph` needs its OWN dedicated [HazelcastInstance] — its keys aren't
 * self-describing, so they're incompatible with any other container's registered Compact adapter
 * (a [HeterogeneousSchemaGraph], a [SingleSchemaGraph], or a differently-shaped `HomogeneousSchemaGraph`)
 * sharing the same instance.
 *
 * Cross-schema edges live in the SAME shared edges/reverse maps as intra-schema edges (their tagged
 * endpoints self-describe: `fromTag != toTag`), so `outgoing<E>()`/`incoming<E>()` reach them as
 * ordinary hops. Same-tag edges are always allowed (ordinary same-tenant edges); different-tag edges
 * are gated by [allowCrossSchemaEdges]. Cache-only in this version.
 */
class HomogeneousSchemaGraph<ID>(
    hazelcast: HazelcastInstance,
    val tagWidth: SchemaTagWidth,
    private val keyAdapter: KeyAdapter<ID>,
    nodesMapName: String = "abyss-nodes",
    edgesMapName: String = "abyss-edges",
    persistentStore: AbyssStoreLike? = null,
    ephemeralStore: AbyssEphemeralStoreLike? = null,
    private val allowCrossSchemaEdges: Boolean = false,
    private val asyncCachePopulation: Boolean = false,
) : NodeIdEngine {

    init { require(tagWidth != SchemaTagWidth.NONE) { "tagWidth must be tagged; use SingleSchemaGraph for untagged single-schema graphs" } }

    private val worker = AbyssSchemaWorker(
        hazelcast, nodesMapName, edgesMapName, persistentStore, ephemeralStore, asyncCachePopulation,
        HomogeneousSchemaResolution(tagWidth, keyAdapter.nodeKeyKind),
    )

    fun forTag(tag: SchemaTag): AbyssGraphSchema<ID> =
        AbyssGraphSchema(HeaderlessSchemaKeyAdapter(tag, tagWidth, keyAdapter), worker).also { it.traversalEngine = this }

    // --- NodeIdEngine: the shared worker self-resolves each NodeId (cross-schema edges share the maps) -

    override suspend fun nodeAt(nid: NodeId): NodeLike<*>? = worker.nodeAt(nid)
    override suspend fun outAt(nid: NodeId, type: String?, needValue: Boolean): List<Hop> = worker.outAt(nid, type, needValue)
    override suspend fun inAt(nid: NodeId, type: String?, needValue: Boolean): List<Hop> = worker.inAt(nid, type, needValue)
    override suspend fun resolveEdges(hops: List<Hop>): Map<Hop, EdgeLike<*, *>> = worker.resolveEdges(hops)
    override fun allNodeIdsRaw(): Flow<NodeId> = worker.allNodeIdsRaw()

    // --- Cross-schema edges (NodeId-level, in the shared edge/reverse maps, persisted through the
    // same stores as an ordinary edge — see AbyssSchemaWorker.putCrossEdge/putCrossEdgeEphemeral) ----

    suspend fun addCrossEdge(edge: EdgeLike<NodeId, NodeId>, checkIntegrity: Boolean = true): Either<AbyssError, Unit> {
        val type = try { edgeType(edge) } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }
        if (checkIntegrity) integrityError(edge, type)?.let { return it.left() }
        return Either.catch { worker.putCrossEdge(edge, type) }.mapLeft { AbyssError.Unexpected(it) }.flatMap { it }
    }

    // Ephemeral (TTL) cross edge — same integrity gate, routes to the ephemeral store instead.
    suspend fun addCrossEdge(edge: EdgeLike<NodeId, NodeId>, ttl: Duration, checkIntegrity: Boolean = true): Either<AbyssError, Unit> {
        val type = try { edgeType(edge) } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }
        if (checkIntegrity) integrityError(edge, type)?.let { return it.left() }
        return Either.catch { worker.putCrossEdgeEphemeral(edge, type, ttl) }.mapLeft { AbyssError.Unexpected(it) }.flatMap { it }
    }

    suspend fun removeCrossEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Unit> =
        Either.catch { worker.removeCrossEdge(fromId, toId, type) }.mapLeft { AbyssError.Unexpected(it) }.flatMap { it }

    // Same-tag edges (the normal case) always succeed regardless of allowCrossSchemaEdges — they're
    // ordinary same-tenant edges. Different-tag edges only succeed when allowCrossSchemaEdges=true.
    // There is no "was this tag ever used" check (unlike HeterogeneousSchemaGraph's registeredTags
    // membership check) — any tag is implicitly valid, since there's no registry to check it against;
    // only node existence is verified.
    private fun integrityError(edge: EdgeLike<NodeId, NodeId>, type: String): AbyssError? {
        val fromTag = schemaTagOf(edge.fromId)
            ?: return AbyssError.IntegrityError("Cross-edge $type: fromId ${edge.fromId} has no valid schema tag")
        val toTag = schemaTagOf(edge.toId)
            ?: return AbyssError.IntegrityError("Cross-edge $type: toId ${edge.toId} has no valid schema tag")
        if (!allowCrossSchemaEdges && fromTag != toTag) return AbyssError.SchemaError("Cross-edges not allowed")
        // TODO this should be rewritten - get node, check if exists - don't relly on cache, full node reload required when cache missed
        if (!worker.containsNodeInCache(edge.fromId)) return AbyssError.IntegrityError("Cross-edge $type: fromId node ${edge.fromId} not found")
        if (!worker.containsNodeInCache(edge.toId)) return AbyssError.IntegrityError("Cross-edge $type: toId node ${edge.toId} not found")
        return null
    }

    private fun schemaTagOf(nid: NodeId): SchemaTag? =
        if (nid.bytes.size >= tagWidth.bytes) NodeKey.tagHeaderless(nid, tagWidth) else null

    private fun edgeType(edge: EdgeLike<*, *>): String =
        edge::class.findAnnotation<SerialName>()?.value ?: error("${edge::class} missing @SerialName")
}
