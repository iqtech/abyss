package pl.iqtech.abyss.graph

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.EdgeLike

// A traversed edge with both endpoints and type resolved from the EdgeKey (uniform across intra- and
// cross-schema edges regardless of the edge value's domain ID type). `edge` is null when the hop was
// fetched key-only (predicate-free hops skip the value fetch); resolveEdges() fills it in on demand.
// `nodeTypeTag` is the @TypeTag of the node at target(direction) (== neighborId), carried straight
// from the adjacency index so typed traversal can filter by node type without a node fetch. Null when
// unresolved (dangling edge, cold-preloaded entry) or when the hop came from the edgesMap fast path
// (no adjacency entry) — every consumer degrades to a nodeAt fetch on null.
data class Hop(val fromId: NodeId, val toId: NodeId, val type: String, val edge: EdgeLike<*, *>?, val nodeTypeTag: Short? = null)

// NodeId-level view a traversal drives against. Backed either by a single AbyssGraphSchema
// (standalone / single-schema) or by a HomogeneousSchemaGraph/HeterogeneousSchemaGraph container
// that resolves each NodeId to its schema — so one frontier can span schemas and cross-hops are
// ordinary hops over the shared edge map.
interface NodeIdEngine {
    suspend fun nodeAt(nid: NodeId): NodeLike<*>?

    // Batched counterpart of nodeAt, for the set-shaped call sites (a frontier, a visited set, one
    // BFS level's neighbours) — the node-side twin of resolveEdges. Absent ids are absent from the
    // result, exactly like resolveEdges drops unresolvable hops.
    //
    // The default fans out over nodeAt rather than being abstract, for two reasons: it keeps the
    // test fakes implementing this interface compiling untouched, and it keeps *which* nodes get
    // fetched identical to the per-id path — batching is a round-trip optimization, never a change
    // to the tag-based fetch-avoidance semantics the typed filters rely on.
    suspend fun nodesAt(ids: Collection<NodeId>): Map<NodeId, NodeLike<*>> = coroutineScope {
        ids.map { nid -> async(hopDispatcher) { nodeAt(nid)?.let { nid to it } } }.awaitAll()
    }.filterNotNull().toMap()
    // Cold, lazy hop streams — a supernode's neighbors arrive in bounded batches, so short-circuiting
    // consumers (reachability, hasOutgoing) stop early instead of materializing the whole set.
    // includeEphemeral (OUT-only): also stream ephemeral (TTL) out-edges, read from the ephemeral store
    // (reliable across cache eviction — TODO 1.27). Default false keeps the fast persistent-only path.
    fun outAt(nid: NodeId, type: String?, needValue: Boolean = true, includeEphemeral: Boolean = false): Flow<Hop>   // edges where fromId == nid
    fun inAt(nid: NodeId, type: String?, needValue: Boolean = true): Flow<Hop>    // edges where toId == nid
    suspend fun resolveEdges(hops: List<Hop>): Map<Hop, EdgeLike<*, *>>   // batched value fetch for key-only hops
    fun allNodeIdsRaw(): Flow<NodeId>

    // Shared across every TraversalBuilder fan-out for this engine (one dispatcher instance, not
    // per-call) so a single supernode-heavy hop can occupy at most hopFanoutParallelism of
    // Dispatchers.IO's execution slots, leaving room for concurrently-running traversals against the
    // same engine to interleave instead of queuing behind one hop's unbounded burst. Each engine
    // (AbyssSchemaWorker, or a Homogeneous/HeterogeneousSchemaGraph container delegating to its own
    // worker) owns its own dispatcher, sized by its own hopFanoutParallelism constructor param.
    val hopDispatcher: CoroutineDispatcher
}
