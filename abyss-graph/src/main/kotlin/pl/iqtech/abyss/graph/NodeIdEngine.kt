package pl.iqtech.abyss.graph

import kotlinx.coroutines.flow.Flow
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.RawEdgeLike

// A traversed edge with both endpoints resolved to NodeId (read from the EdgeKey, so uniform across
// intra- and cross-schema edges regardless of the edge value's domain ID type).
data class Hop(val fromId: NodeId, val toId: NodeId, val edge: RawEdgeLike<*, *>)

// NodeId-level view a traversal drives against. Backed either by a single AbyssGraphSchema
// (standalone / single-schema) or by an AbyssGraph container that resolves each NodeId to its schema
// — so one frontier can span schemas and cross-hops are ordinary hops over the shared edge map.
interface NodeIdEngine {
    suspend fun nodeAt(nid: NodeId): NodeLike<*>?
    suspend fun outAt(nid: NodeId, type: String?): List<Hop>   // edges where fromId == nid
    suspend fun inAt(nid: NodeId, type: String?): List<Hop>    // edges where toId == nid
    fun allNodeIdsRaw(): Flow<NodeId>
}
