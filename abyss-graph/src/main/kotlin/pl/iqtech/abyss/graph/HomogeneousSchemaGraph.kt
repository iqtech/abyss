package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.left
import com.hazelcast.core.HazelcastInstance
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
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
 * Cross-schema edges live in the SAME shared edges/adjacency maps as intra-schema edges (their tagged
 * endpoints self-describe: `fromTag != toTag`), so `outgoing<E>()`/`incoming<E>()` reach them as
 * ordinary hops. Same-tag edges are always allowed (ordinary same-tenant edges); different-tag edges
 * are gated by [allowCrossSchemaEdges]. Persist through the same stores as an ordinary edge
 * (`worker.transaction`/`worker.ephemeral`) — see [transaction] and [addCrossEdge].
 */
class HomogeneousSchemaGraph<ID>(
    hazelcast: HazelcastInstance,
    val tagWidth: SchemaTagWidth,
    private val keyAdapter: KeyAdapter<ID>,
    nodesMapName: String = "abyss-nodes",
    edgesMapName: String = "abyss-edges",
    edgesAdjacencyMapName: String = "$edgesMapName-adjacency",
    persistentStore: AbyssStoreLike? = null,
    ephemeralStore: AbyssEphemeralStoreLike? = null,
    private val allowCrossSchemaEdges: Boolean = false,
    private val asyncCachePopulation: Boolean = false,
    module: SerializersModule = EmptySerializersModule(),
    adjacencyShardCount: Int = 16,
    hopFanoutParallelism: Int = 256,
) : NodeIdEngine {

    init { require(tagWidth != SchemaTagWidth.NONE) { "tagWidth must be tagged; use SingleSchemaGraph for untagged single-schema graphs" } }

    private val worker = AbyssSchemaWorker(
        hazelcast, nodesMapName, edgesMapName, edgesAdjacencyMapName, persistentStore, ephemeralStore, asyncCachePopulation,
        HomogeneousSchemaResolution(tagWidth, keyAdapter.nodeKeyKind), module, adjacencyShardCount, hopFanoutParallelism,
    )

    fun forTag(tag: SchemaTag): AbyssGraphSchema<ID> =
        AbyssGraphSchema(HeaderlessSchemaKeyAdapter(tag, tagWidth, keyAdapter), worker).also {
            it.traversalEngine = this
            it.crossEdgeGate = { null } // no unconditional gate here — see tagCheckFailure
            it.crossEdgeTagCheck = ::tagCheckFailure
        }

    // --- NodeIdEngine: the shared worker self-resolves each NodeId (cross-schema edges share the maps) -

    override suspend fun nodeAt(nid: NodeId): NodeLike<*>? = worker.nodeAt(nid)
    override fun outAt(nid: NodeId, type: String?, needValue: Boolean, includeEphemeral: Boolean): Flow<Hop> = worker.outAt(nid, type, needValue, includeEphemeral)
    override fun inAt(nid: NodeId, type: String?, needValue: Boolean): Flow<Hop> = worker.inAt(nid, type, needValue)
    override suspend fun resolveEdges(hops: List<Hop>): Map<Hop, EdgeLike<*, *>> = worker.resolveEdges(hops)
    override fun allNodeIdsRaw(): Flow<NodeId> = worker.allNodeIdsRaw()
    override val hopDispatcher: CoroutineDispatcher get() = worker.hopDispatcher

    // --- Cross-schema edges. NodeId-level, in the shared edge/adjacency maps, routed through the SAME
    // worker.transaction/worker.ephemeral pipeline as ordinary node/edge ops — atomic, persisted
    // through the same stores, endpoint-existence + @EdgeConstraint checked for free. -------------

    // Batch of on(schema).addNode/addEdge/etc plus addCrossEdge/removeCrossEdge calls, committed as
    // one atomic worker.transaction — able to span every tag registered on this container in one commit.
    suspend fun transaction(checkIntegrity: Boolean = true, block: suspend MultiSchemaTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        val buffer = MultiSchemaTransactionBuffer(this)
        try { buffer.block() } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }

        if (checkIntegrity) {
            for (op in buffer.crossOps) {
                val (fromNid, toNid) = op.endpoints(tagWidth, headerless = true)
                tagCheckFailure(fromNid, toNid)?.let { return it.left() }
            }
        }

        val ops = buffer.schemaBuffers.entries.flatMap { (schema, buf) -> schema.toNodeOps(buf.ops) } +
            buffer.crossOps.map { it.toNodeOp(tagWidth, headerless = true) }
        return worker.transaction(ops, checkIntegrity)
    }

    // Raw NodeId escape hatch (no @CrossSchemaEdge required) — kept for schemas without a static tag.
    suspend fun addCrossEdge(edge: EdgeLike<NodeId, NodeId>, tags: Set<String> = emptySet(), checkIntegrity: Boolean = true): Either<AbyssError, Unit> {
        if (checkIntegrity) tagCheckFailure(edge.fromId, edge.toId)?.let { return it.left() }
        return worker.transaction(listOf(NodeOp.AddEdge(edge.fromId, edge.toId, edge, null, tags)), checkIntegrity)
    }

    // Ephemeral (TTL) cross edge — same integrity gate, routes to the ephemeral store instead.
    suspend fun addCrossEdge(edge: EdgeLike<NodeId, NodeId>, ttl: Duration, tags: Set<String> = emptySet(), checkIntegrity: Boolean = true): Either<AbyssError, Unit> {
        if (checkIntegrity) tagCheckFailure(edge.fromId, edge.toId)?.let { return it.left() }
        return worker.ephemeral(listOf(NodeOp.AddEdge(edge.fromId, edge.toId, edge, ttl, tags)), checkIntegrity)
    }

    suspend fun removeCrossEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Unit> =
        worker.transaction(listOf(NodeOp.RemoveEdge(fromId, toId, type)), checkIntegrity = false)

    // Same-tag edges (the normal case) always succeed regardless of allowCrossSchemaEdges — they're
    // ordinary same-tenant edges. Different-tag edges only succeed when allowCrossSchemaEdges=true.
    // There is no "was this tag ever used" check (unlike HeterogeneousSchemaGraph's registeredTags
    // membership check) — any tag is implicitly valid, since there's no registry to check it against.
    // Only checked when checkIntegrity=true (no gate at all otherwise) — matches the pre-existing
    // addCrossEdge asymmetry with HeterogeneousSchemaGraph's unconditional allowCrossSchemaEdges gate.
    private fun tagCheckFailure(fromNid: NodeId, toNid: NodeId): AbyssError? {
        val fromTag = schemaTagOf(fromNid) ?: return AbyssError.IntegrityError("Cross-edge: fromId $fromNid has no valid schema tag")
        val toTag = schemaTagOf(toNid) ?: return AbyssError.IntegrityError("Cross-edge: toId $toNid has no valid schema tag")
        if (!allowCrossSchemaEdges && fromTag != toTag) return AbyssError.SchemaError("Cross-edges not allowed")
        return null
    }

    private fun schemaTagOf(nid: NodeId): SchemaTag? =
        if (nid.bytes.size >= tagWidth.bytes) NodeKey.tagHeaderless(nid, tagWidth) else null
}
