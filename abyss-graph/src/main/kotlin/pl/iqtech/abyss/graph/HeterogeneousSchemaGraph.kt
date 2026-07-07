package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.left
import com.hazelcast.core.HazelcastInstance
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
import pl.iqtech.abyss.store.api.SchemaKeyAdapter
import pl.iqtech.abyss.store.api.SchemaTag
import pl.iqtech.abyss.store.api.SchemaTagWidth
import kotlin.time.Duration

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
 * Cross-schema edges live in the SAME shared edges/adjacency maps as intra-schema edges (their tagged
 * endpoints self-describe: `fromTag != toTag`), so `outgoing<E>()`/`incoming<E>()` reach them as
 * ordinary hops. They are gated by [allowCrossSchemaEdges] and persist through the same stores as an
 * ordinary edge (`worker.transaction`/`worker.ephemeral`) — see [transaction] and [addCrossEdge].
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
    module: SerializersModule = EmptySerializersModule(),
    adjacencyShardCount: Int = 16,
) : NodeIdEngine {

    init { require(tagWidth != SchemaTagWidth.NONE) { "tagWidth must be tagged; use SingleSchemaGraph for untagged single-schema graphs" } }

    private val worker = AbyssSchemaWorker(
        hazelcast, nodesMapName, edgesMapName, persistentStore, ephemeralStore, asyncCachePopulation,
        HeterogeneousSchemaResolution, module, adjacencyShardCount,
    )

    private val registeredTags = mutableSetOf<SchemaTag>()

    fun <ID> register(tag: SchemaTag, adapter: KeyAdapter<ID>): AbyssGraphSchema<ID> {
        require(registeredTags.add(tag)) { "Schema tag $tag already registered" }
        val tagged = SchemaKeyAdapter(tag, tagWidth, adapter)
        return AbyssGraphSchema(tagged, worker).also {
            it.traversalEngine = this
            it.crossEdgeGate = ::crossEdgeGateFailure
            it.crossEdgeTagCheck = ::tagCheckFailure
        }
    }

    // --- NodeIdEngine: the shared worker self-resolves each NodeId (cross-schema edges share the maps) -

    override suspend fun nodeAt(nid: NodeId): NodeLike<*>? = worker.nodeAt(nid)
    override suspend fun outAt(nid: NodeId, type: String?, needValue: Boolean): List<Hop> = worker.outAt(nid, type, needValue)
    override suspend fun inAt(nid: NodeId, type: String?, needValue: Boolean): List<Hop> = worker.inAt(nid, type, needValue)
    override suspend fun resolveEdges(hops: List<Hop>): Map<Hop, EdgeLike<*, *>> = worker.resolveEdges(hops)
    override fun allNodeIdsRaw(): Flow<NodeId> = worker.allNodeIdsRaw()

    // --- Cross-schema edges. NodeId-level, in the shared edge/adjacency maps, routed through the SAME
    // worker.transaction/worker.ephemeral pipeline as ordinary node/edge ops — atomic, persisted
    // through the same stores, endpoint-existence + @EdgeConstraint checked for free. -------------

    // Batch of addCrossEdge/removeCrossEdge calls, committed as one atomic worker.transaction — the
    // annotation-driven analogue of a per-schema transaction{}, for container-level (cross-only) ops.
    suspend fun transaction(checkIntegrity: Boolean = true, block: suspend CrossSchemaTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        val buffer = CrossSchemaTransactionBuffer()
        try { buffer.block() } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }
        crossEdgeGateFailure()?.let { return it.left() }
        if (checkIntegrity) {
            for (op in buffer.ops) {
                val (fromNid, toNid) = op.endpoints(tagWidth, headerless = false)
                tagCheckFailure(fromNid, toNid)?.let { return it.left() }
            }
        }
        return worker.transaction(buffer.ops.map { it.toNodeOp(tagWidth, headerless = false) }, checkIntegrity)
    }

    // Raw NodeId escape hatch (no @CrossSchemaEdge required) — kept for schemas without a static tag.
    suspend fun addCrossEdge(edge: EdgeLike<NodeId, NodeId>, checkIntegrity: Boolean = true): Either<AbyssError, Unit> {
        crossEdgeGateFailure()?.let { return it.left() }
        if (checkIntegrity) tagCheckFailure(edge.fromId, edge.toId)?.let { return it.left() }
        return worker.transaction(listOf(NodeOp.AddEdge(edge.fromId, edge.toId, edge, null)), checkIntegrity)
    }

    // Ephemeral (TTL) cross edge — same gate and tag check, routes to the ephemeral store instead.
    suspend fun addCrossEdge(edge: EdgeLike<NodeId, NodeId>, ttl: Duration, checkIntegrity: Boolean = true): Either<AbyssError, Unit> {
        crossEdgeGateFailure()?.let { return it.left() }
        if (checkIntegrity) tagCheckFailure(edge.fromId, edge.toId)?.let { return it.left() }
        return worker.ephemeral(listOf(NodeOp.AddEdge(edge.fromId, edge.toId, edge, ttl)), checkIntegrity)
    }

    suspend fun removeCrossEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Unit> =
        worker.transaction(listOf(NodeOp.RemoveEdge(fromId, toId, type)), checkIntegrity = false)

    // Unconditional (not gated by checkIntegrity) — matches the pre-existing addCrossEdge asymmetry.
    private fun crossEdgeGateFailure(): AbyssError? =
        if (!allowCrossSchemaEdges) AbyssError.IntegrityError("Cross-schema edges are disabled (allowCrossSchemaEdges=false)") else null

    private fun tagCheckFailure(fromNid: NodeId, toNid: NodeId): AbyssError? {
        val fromTag = schemaTagOf(fromNid) ?: return AbyssError.IntegrityError("Cross-edge: fromId $fromNid has no valid schema tag")
        if (fromTag !in registeredTags) return AbyssError.IntegrityError("Cross-edge: fromId schema tag $fromTag not registered")
        val toTag = schemaTagOf(toNid) ?: return AbyssError.IntegrityError("Cross-edge: toId $toNid has no valid schema tag")
        if (toTag !in registeredTags) return AbyssError.IntegrityError("Cross-edge: toId schema tag $toTag not registered")
        return null
    }

    private fun schemaTagOf(nid: NodeId): SchemaTag? =
        if (nid.bytes.isNotEmpty() && NodeKey.width(nid) != SchemaTagWidth.NONE) NodeKey.tag(nid) else null
}
