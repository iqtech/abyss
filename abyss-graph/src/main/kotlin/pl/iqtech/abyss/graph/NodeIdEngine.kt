package pl.iqtech.abyss.graph

import kotlinx.coroutines.flow.Flow
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.EdgeLike

// A traversed edge with both endpoints and type resolved from the EdgeKey (uniform across intra- and
// cross-schema edges regardless of the edge value's domain ID type). `edge` is null when the hop was
// fetched key-only (predicate-free hops skip the value fetch); resolveEdges() fills it in on demand.
data class Hop(val fromId: NodeId, val toId: NodeId, val type: String, val edge: EdgeLike<*, *>?)

// NodeId-level view a traversal drives against. Backed either by a single AbyssGraphSchema
// (standalone / single-schema) or by an AbyssGraph container that resolves each NodeId to its schema
// — so one frontier can span schemas and cross-hops are ordinary hops over the shared edge map.
interface NodeIdEngine {
    suspend fun nodeAt(nid: NodeId): NodeLike<*>?
    suspend fun outAt(nid: NodeId, type: String?, needValue: Boolean = true): List<Hop>   // edges where fromId == nid
    suspend fun inAt(nid: NodeId, type: String?, needValue: Boolean = true): List<Hop>    // edges where toId == nid
    suspend fun resolveEdges(hops: List<Hop>): Map<Hop, EdgeLike<*, *>>   // batched value fetch for key-only hops
    fun allNodeIdsRaw(): Flow<NodeId>
}
