package pl.iqtech.abyss.graph

import arrow.core.Either
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
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

// Walks every node once (its payload + its own outgoing edges only, so each directed edge is
// emitted exactly once) — same allNodeIds()-then-per-node access pattern as connectedComponents
// (abyss-dsl/Extensions.kt), minus the inEdges half (undirected walk isn't needed for a directed
// edge dump).
suspend fun <ID> AbyssEngineLike<ID>.exportGraphLines(codec: GraphJsonCodec): Flow<String> = flow {
    allNodeIds().collect { id ->
        node(id).getOrNull()?.let { emit(codec.encodeNode(it)) }
        outEdges(id).collect { edge -> emit(codec.encodeEdge(edge)) }
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
