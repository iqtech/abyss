package pl.iqtech.abyss.graph

import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike

// Shared fixture for the ring-wrap perf suites (Long/Uuid/String standalone, MultiSchema, LongSchemaConcurrency):
// RING_NODES nodes, each with RING_EDGES_PER_NODE out-edges to the next nodes around the ring.
//
// TODO 4.14: these suites used to seed the Hazelcast maps directly, re-implementing the internal layout by hand
// (EdgeKey type strings, adjacency direction and partition keys). Every layout change silently broke them and
// none of them checked results: 3-hop walks were empty on every commit since df76b70, outEdges since the paged
// read, MultiSchema inEdges always (wrong edge type string). So: seed through the real write path, and assert
// what every timed call returns — a benchmark that times the wrong work must fail, not print a number.
internal const val RING_NODES = 10_000
internal const val RING_EDGES_PER_NODE = 5
internal const val RING_OUT = 5
internal const val RING_IN = 5
// 3 outgoing hops from node i reach exactly i+3 .. i+15 (every sum of three steps in 1..5): 13 distinct nodes.
internal const val RING_3HOP = 13

// Chunked transaction(), not batchTransaction: these files are dropped unchanged into df76b70 exports for A/B
// runs, and df76b70 has no batchTransaction.
internal suspend fun <ID> AbyssGraphSchema<ID>.seedRing(ids: List<ID>, node: (ID) -> NodeLike<ID>, edge: (ID, ID) -> EdgeLike<ID, ID>) {
    ids.indices.chunked(1_000).forEach { chunk ->
        val result = transaction(checkIntegrity = false) {
            chunk.forEach { i ->
                addNode(node(ids[i]))
                repeat(RING_EDGES_PER_NODE) { j -> addEdge(edge(ids[i], ids[(i + j + 1) % ids.size])) }
            }
        }
        check(result.isRight()) { "ring seeding failed: $result" }
    }
}

internal fun expectSize(what: String, actual: Int, expected: Int) =
    check(actual == expected) { "$what returned $actual results, expected $expected — the benchmark would time the wrong work" }
