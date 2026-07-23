package pl.iqtech.abyss.graph

import arrow.core.Either
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import pl.iqtech.abyss.dsl.AbyssEngineLike
import pl.iqtech.abyss.dsl.Subgraph
import pl.iqtech.abyss.graph.serialization.GraphJsonCodec
import pl.iqtech.abyss.graph.serialization.GraphLine
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike

// TODO 2.3: graph export / import (property graph JSON). Lives in abyss-graph, not alongside
// connectedComponents/ensureSubgraph in abyss-dsl/Extensions.kt, because the JSON codec machinery
// (GraphJsonCodec, customJsonSerializer) lives in abyss-graph — abyss-dsl can't depend on it
// (abyss-graph depends on abyss-dsl, not the reverse).
//
// Cross-schema edges (added via a container's addCrossEdge, not through a schema facade's own
// transaction { }) are out of scope here: export walks one schema's own outEdges only, and
// importGraphLines only calls addEdge, never addCrossEdge. Round-trip a container's cross-edge
// data via its own addCrossEdge API if needed.

// Walks every node once (its payload + its own outgoing edges only, so each directed edge is
// emitted exactly once). Id source is merge(allNodeIds(), scanNodeIds()) (TODO 1.23), not either
// alone: scanNodeIds() is DB-only, so a graph with no persistentStore configured (common in
// tests/pure-cache setups) would export nothing from it; allNodeIds() alone is Hazelcast-cache-only
// and silently misses cold/evicted nodes on a real persisted graph. The union covers both — a `seen`
// set dedupes ids the two sources agree on (the common case for a warm, persisted graph) without
// double-emitting. Receiver is the concrete AbyssGraphSchema<ID>, not AbyssEngineLike<ID>, since
// scanNodeIds() lives there, not on the general interface. outEdges(id) per node was already
// store-backed/self-healing before this change (TODO 1.20/1.22/1.26), so reliability only needed
// fixing at the node-enumeration step.
suspend fun <ID> AbyssGraphSchema<ID>.exportGraphLines(codec: GraphJsonCodec): Flow<String> = flow {
    val seen = mutableSetOf<ID>()
    merge(allNodeIds(), scanNodeIds()).collect { id ->
        if (seen.add(id)) {
            node(id).getOrNull()?.let { emit(codec.encodeNode(it)) }
            outEdges(id).collect { edge -> emit(codec.encodeEdge(edge)) }
        }
    }
}

// Export an already-computed Subgraph (e.g. from allReachable { }) instead of the whole graph.
fun Subgraph.exportLines(codec: GraphJsonCodec): Sequence<String> =
    nodes.asSequence().map { codec.encodeNode(it) } + edges.asSequence().map { codec.encodeEdge(it) }

// Import in the same format via transaction { } — checkIntegrity defaults to false, matching the
// bulk-import convention already documented in README.md's "Referential integrity" section (node
// existence is guaranteed by the caller for a fresh import).
@Suppress("UNCHECKED_CAST")
suspend fun <ID> AbyssEngineLike<ID>.importGraphLines(
    lines: Flow<String>,
    codec: GraphJsonCodec,
    checkIntegrity: Boolean = false,
): Either<AbyssError, Unit> {
    val nodes = mutableListOf<NodeLike<ID>>()
    val edges = mutableListOf<EdgeLike<ID, ID>>()
    lines.collect { line ->
        when (val parsed = codec.decodeLine(line)) {
            is GraphLine.Node -> nodes += parsed.node as NodeLike<ID>
            is GraphLine.Relationship -> edges += parsed.edge as EdgeLike<ID, ID>
        }
    }
    return transaction(checkIntegrity) {
        nodes.forEach { addNode(it) }
        edges.forEach { addEdge(it) }
    }
}
