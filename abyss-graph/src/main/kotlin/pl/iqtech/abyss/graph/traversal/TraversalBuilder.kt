package pl.iqtech.abyss.graph.traversal

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import pl.iqtech.abyss.dsl.Evaluation
import pl.iqtech.abyss.dsl.HopDirection
import pl.iqtech.abyss.dsl.Path
import pl.iqtech.abyss.dsl.Subgraph
import pl.iqtech.abyss.dsl.EdgeTraversalDirection
import pl.iqtech.abyss.dsl.TraversalBuilderLike
import pl.iqtech.abyss.dsl.TraversalStrategy
import pl.iqtech.abyss.dsl.cachedAnnotation
import pl.iqtech.abyss.graph.Hop
import pl.iqtech.abyss.graph.NodeIdEngine
import pl.iqtech.abyss.store.api.KeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.EdgeLike

// The frontier is NodeId-based so a walk can span schemas; homeAdapter converts the home schema's
// domain ids for the id-valued conveniences (checkReaches, filterFrontierByOutEdgeTo, …).
class TraversalBuilder<ID>(
    private val engine: NodeIdEngine,
    startFrontier: Set<NodeId>,
    private val homeAdapter: KeyAdapter<ID>,
) : TraversalBuilderLike<ID> {

    // Frontier and visited set are backed by NodeId -> @TypeTag maps (tag carried from the producing
    // hop, null when unresolved/origin) so typed filters compare a Short in memory instead of fetching
    // the node. `frontier` stays a Set<NodeId> keys-view — every id-only consumer (paths, cycle,
    // reachability, count, flush) is unchanged; only the tag-producing/consuming sites touch the maps.
    private var frontierTags: Map<NodeId, Short?> = startFrontier.associateWith { null }
    val frontier: Set<NodeId> get() = frontierTags.keys

    private val allVisitedTags: MutableMap<NodeId, Short?> = startFrontier.associateWithTo(mutableMapOf()) { null }
    private val allTraversedHops: MutableList<Hop> = mutableListOf()

    // Record a node as visited, upgrading a previously-unknown (null) tag if this hop resolved it, but
    // never overwriting a known tag with null (a node reached via both a fast-path and an index hop).
    private fun mergeVisited(tags: Map<NodeId, Short?>) {
        for ((k, v) in tags) if (allVisitedTags[k] == null) allVisitedTags[k] = v
    }

    // Narrow the frontier to `keep`, dropping the removed ids from the visited set too (mirrors the old
    // `allVisitedIds -= (frontier - matching)`), preserving each survivor's carried tag.
    private fun retainFrontier(keep: Set<NodeId>) {
        (frontierTags.keys - keep).forEach { allVisitedTags.remove(it) }
        frontierTags = frontierTags.filterKeys { it in keep }
    }
    internal val traversedHops: List<Hop> get() = allTraversedHops

    // Most recent addHop/addNodeHop's own hops (not accumulated like allTraversedHops) — backs
    // flushHopEdges, which exposes just the last hop's edges rather than the whole walk's.
    private var lastHopEdges: List<Hop> = emptyList()
    private var lastHopDirection: HopDirection? = null

    private suspend fun hops(nid: NodeId, direction: HopDirection, type: String?, needValue: Boolean): List<Hop> =
        if (direction == HopDirection.OUTGOING) engine.outAt(nid, type, needValue) else engine.inAt(nid, type, needValue)

    // Fills in edge values for key-only hops (predicate-free hops skip the fetch) in one batched call
    // — the single point where Subgraph.edges is materialized. Returns a Map (not a List) so callers
    // that need to know which hop an edge belongs to (flushHopEdges, pathTo) can look it up directly,
    // instead of relying on positional correspondence that a dropped (unresolvable) hop would break.
    private suspend fun resolveHopEdges(hops: List<Hop>): Map<Hop, EdgeLike<*, *>> {
        val unresolved = hops.filter { it.edge == null }
        val resolved = if (unresolved.isEmpty()) emptyMap() else engine.resolveEdges(unresolved)
        return hops.mapNotNull { hop -> (hop.edge ?: resolved[hop])?.let { hop to it } }.toMap()
    }

    private fun Hop.target(direction: HopDirection): NodeId =
        if (direction == HopDirection.OUTGOING) toId else fromId

    private fun NodeLike<*>.typeName(): String? = this::class.cachedAnnotation<SerialName>()?.value

    override suspend fun addHop(direction: HopDirection, edgeType: String?, edgePredicate: ((EdgeLike<*, *>) -> Boolean)?) {
        val needValue = edgePredicate != null
        val hopEdges = coroutineScope {
            frontier.map { nid ->
                async(engine.hopDispatcher) { hops(nid, direction, edgeType, needValue).filter { edgePredicate == null || edgePredicate(it.edge!!) } }
            }.awaitAll().flatten()
        }
        allTraversedHops += hopEdges
        lastHopEdges = hopEdges; lastHopDirection = direction
        frontierTags = hopEdges.associate { it.target(direction) to it.nodeTypeTag }
        mergeVisited(frontierTags)
    }

    override suspend fun addNodeHop(direction: HopDirection, edgeType: String, nodeType: String, nodeTag: Short?, nodePredicate: ((NodeLike<*>) -> Boolean)?) {
        val hopEdges = coroutineScope {
            frontier.map { nid ->
                async(engine.hopDispatcher) {
                    hops(nid, direction, edgeType, needValue = false).filter { hop ->
                        val tag = hop.nodeTypeTag
                        // Tag known and wanted -> decide by tag; keep fetch-free unless a predicate needs the value.
                        if (tag != null && nodeTag != null) {
                            if (tag != nodeTag) return@filter false
                            if (nodePredicate == null) return@filter true
                        }
                        val node = engine.nodeAt(hop.target(direction)) ?: return@filter false
                        if (node.typeName() != nodeType) return@filter false
                        nodePredicate == null || nodePredicate(node)
                    }
                }
            }.awaitAll().flatten()
        }
        allTraversedHops += hopEdges
        lastHopEdges = hopEdges; lastHopDirection = direction
        frontierTags = hopEdges.associate { it.target(direction) to it.nodeTypeTag }
        mergeVisited(frontierTags)
    }

    override suspend fun filterFrontierByNode(nodeType: String, nodeTag: Short?, predicate: ((NodeLike<*>) -> Boolean)?) {
        val matched = mutableSetOf<NodeId>()
        val toFetch = mutableListOf<NodeId>()
        for ((nid, tag) in frontierTags) {
            if (tag != null && nodeTag != null) {
                if (tag != nodeTag) continue                          // wrong type, no fetch
                if (predicate == null) { matched += nid; continue }   // right type, no predicate -> keep, no fetch
            }
            toFetch += nid                                            // null tag (fallback), or predicate to run
        }
        if (toFetch.isNotEmpty()) {
            matched += coroutineScope {
                toFetch.map { nid ->
                    async(engine.hopDispatcher) {
                        val node = engine.nodeAt(nid) ?: return@async null
                        if (node.typeName() != nodeType) return@async null
                        if (predicate != null && !predicate(node)) return@async null
                        nid
                    }
                }.awaitAll()
            }.filterNotNull()
        }
        retainFrontier(matched)
    }

    private suspend fun filterFrontierByEdge(direction: HopDirection, edgeType: String, endpoint: NodeId) {
        val matching = coroutineScope {
            frontier.map { nid ->
                async(engine.hopDispatcher) { if (hops(nid, direction, edgeType, needValue = false).any { it.target(direction) == endpoint }) nid else null }
            }.awaitAll()
        }.filterNotNull().toSet()
        retainFrontier(matching)
    }

    private suspend fun filterFrontierByEdgeType(direction: HopDirection, edgeType: String, nodeType: String, nodeTag: Short?) {
        val matching = coroutineScope {
            frontier.map { nid ->
                async(engine.hopDispatcher) {
                    val has = hops(nid, direction, edgeType, needValue = false).any { hop ->
                        val tag = hop.nodeTypeTag
                        // Tag known and wanted -> compare in memory; else fall back to a neighbor fetch.
                        if (tag != null && nodeTag != null) tag == nodeTag
                        else engine.nodeAt(hop.target(direction))?.typeName() == nodeType
                    }
                    if (has) nid else null
                }
            }.awaitAll()
        }.filterNotNull().toSet()
        retainFrontier(matching)
    }

    override suspend fun filterFrontierByOutEdgeTo(edgeType: String, toId: ID) =
        filterFrontierByEdge(HopDirection.OUTGOING, edgeType, homeAdapter.toNodeId(toId))

    override suspend fun filterFrontierByOutEdgeToType(edgeType: String, nodeType: String, nodeTag: Short?) =
        filterFrontierByEdgeType(HopDirection.OUTGOING, edgeType, nodeType, nodeTag)

    override suspend fun filterFrontierByInEdgeFrom(edgeType: String, fromId: ID) =
        filterFrontierByEdge(HopDirection.INCOMING, edgeType, homeAdapter.toNodeId(fromId))

    override suspend fun filterFrontierByInEdgeFromType(edgeType: String, nodeType: String, nodeTag: Short?) =
        filterFrontierByEdgeType(HopDirection.INCOMING, edgeType, nodeType, nodeTag)

    override suspend fun filterFrontierByTraversal(block: suspend TraversalBuilderLike<ID>.() -> Unit) {
        val matching = coroutineScope {
            frontier.map { nid ->
                async(engine.hopDispatcher) {
                    val sub = TraversalBuilder(engine, setOf(nid), homeAdapter)
                    sub.block()
                    if (sub.frontier.isNotEmpty()) nid else null
                }
            }.awaitAll()
        }.filterNotNull().toSet()
        retainFrontier(matching)
    }

    override suspend fun flushFrontierNodes(): Flow<NodeLike<*>> = flow {
        for (nid in frontier) engine.nodeAt(nid)?.let { emit(it) }
    }

    override suspend fun flushHopEdges(): Flow<EdgeLike<*, *>> = flow {
        val dir = lastHopDirection ?: return@flow
        val live = lastHopEdges.filter { it.target(dir) in frontier }
        val resolved = resolveHopEdges(live)
        for (hop in live) resolved[hop]?.let { emit(it) }
    }

    override suspend fun count(): Int = frontier.size

    override suspend fun countEdges(direction: HopDirection, edgeType: String): Int = coroutineScope {
        frontier.map { nid -> async(engine.hopDispatcher) { hops(nid, direction, edgeType, needValue = false).size } }.awaitAll()
    }.sum()

    override suspend fun collectSubgraph(nodeType: String?, nodeTag: Short?): Subgraph {
        // With a type filter, skip fetching visited nodes whose known tag rules them out; only null-tag
        // (or all, when unfiltered) nodes are fetched, then filtered by @SerialName as a fallback.
        val candidates = if (nodeType == null || nodeTag == null) allVisitedTags.keys
            else allVisitedTags.filter { (_, tag) -> tag == null || tag == nodeTag }.keys
        val nodes = coroutineScope {
            candidates.map { nid -> async(engine.hopDispatcher) { engine.nodeAt(nid) } }.awaitAll()
        }.filterNotNull().let { all ->
            if (nodeType == null) all else all.filter { it.typeName() == nodeType }
        }
        return Subgraph(nodes, resolveHopEdges(allTraversedHops).values.toList())
    }

    override suspend fun exhaustReachable(block: suspend TraversalBuilderLike<ID>.() -> Unit): Subgraph {
        val visited = mutableSetOf<NodeId>(); visited += frontier
        val allHops = mutableListOf<Hop>()
        var current = frontier.toSet()
        while (current.isNotEmpty()) {
            val sub = TraversalBuilder(engine, current, homeAdapter)
            sub.block()
            allHops += sub.traversedHops
            val next = sub.frontier - visited
            visited += next
            current = next
        }
        val nodes = coroutineScope {
            visited.map { nid -> async(engine.hopDispatcher) { engine.nodeAt(nid) } }.awaitAll()
        }.filterNotNull()
        return Subgraph(nodes, resolveHopEdges(allHops).values.toList())
    }

    override suspend fun detectCycle(block: suspend TraversalBuilderLike<ID>.() -> Unit): Boolean {
        val visited = mutableSetOf<NodeId>()
        for (start in frontier) {
            if (start !in visited && dfsCycle(start, visited, mutableSetOf(), block)) return true
        }
        return false
    }

    private suspend fun dfsCycle(
        nodeId: NodeId,
        visited: MutableSet<NodeId>,
        inStack: MutableSet<NodeId>,
        block: suspend TraversalBuilderLike<ID>.() -> Unit
    ): Boolean {
        visited += nodeId; inStack += nodeId
        val sub = TraversalBuilder(engine, setOf(nodeId), homeAdapter)
        sub.block()
        for (neighbor in sub.frontier) {
            if (neighbor in inStack) return true
            if (neighbor !in visited && dfsCycle(neighbor, visited, inStack, block)) return true
        }
        inStack -= nodeId
        return false
    }

    override fun paths(
        strategy: TraversalStrategy,
        direction: EdgeTraversalDirection,
        maxDepth: Int,
        edgeVisitor: (Path, EdgeLike<*, *>) -> Boolean,
        nodeEvaluator: (Path, NodeLike<*>) -> Evaluation
    ): Flow<Path> = flow {
        val origin = frontier.mapNotNull { nid -> engine.nodeAt(nid)?.let { nid to it } }
        when (strategy) {
            TraversalStrategy.DFS -> for ((nid, node) in origin)
                dfsLoop(Path(listOf(node), emptyList()), nid, nid, setOf(nid), direction, maxDepth, 0, edgeVisitor, nodeEvaluator)
            TraversalStrategy.BFS -> bfsLoop(origin, direction, maxDepth, edgeVisitor, nodeEvaluator)
        }
    }

    private suspend fun edgesFrom(fromNid: NodeId, direction: EdgeTraversalDirection): List<Hop> = when (direction) {
        EdgeTraversalDirection.OUT  -> engine.outAt(fromNid, null)
        EdgeTraversalDirection.IN   -> engine.inAt(fromNid, null)
        EdgeTraversalDirection.BOTH -> engine.outAt(fromNid, null) + engine.inAt(fromNid, null)
    }

    // headNid: NodeId of the last accepted node (currentPath.head); fromNid: physical position (may
    // differ when a node was EXCLUDE_AND_CONTINUE). depth counts hops. seen prevents re-processing a
    // neighbour via different edges within a level. Returns whether this subtree emitted any path —
    // the caller uses it to decide if an INCLUDE_AND_CONTINUE head is itself a natural terminal.
    private suspend fun FlowCollector<Path>.dfsLoop(
        currentPath: Path,
        headNid: NodeId,
        fromNid: NodeId,
        visited: Set<NodeId>,
        direction: EdgeTraversalDirection,
        maxDepth: Int,
        depth: Int,
        edgeVisitor: (Path, EdgeLike<*, *>) -> Boolean,
        nodeEvaluator: (Path, NodeLike<*>) -> Evaluation
    ): Boolean {
        if (depth >= maxDepth) return false
        val edges = edgesFrom(fromNid, direction)
        val seen = visited.toMutableSet()
        var emitted = false
        for (hop in edges) {
            if (!edgeVisitor(currentPath, hop.edge!!)) continue
            val nextNid = if (hop.fromId == fromNid) hop.toId else hop.fromId
            if (nextNid in seen) continue
            seen += nextNid
            val nextNode = engine.nodeAt(nextNid) ?: continue
            val eval = nodeEvaluator(currentPath, nextNode)
            val included = eval == Evaluation.INCLUDE_AND_CONTINUE || eval == Evaluation.INCLUDE_AND_PRUNE
            val extendedPath = if (included) {
                val edgePart = if (fromNid == headNid) listOf(hop.edge) else emptyList()
                Path(currentPath.nodes + nextNode, currentPath.edges + edgePart)
            } else currentPath
            val nextHeadNid = if (included) nextNid else headNid
            val nextDepth = depth + 1
            when {
                eval == Evaluation.INCLUDE_AND_PRUNE -> { emit(extendedPath); emitted = true }
                eval == Evaluation.INCLUDE_AND_CONTINUE && nextDepth >= maxDepth -> { emit(extendedPath); emitted = true }
                eval == Evaluation.INCLUDE_AND_CONTINUE -> {
                    val childEmitted = dfsLoop(extendedPath, nextHeadNid, nextNid, seen.toSet(), direction, maxDepth, nextDepth, edgeVisitor, nodeEvaluator)
                    if (!childEmitted) emit(extendedPath) // natural terminal: included head with no emitting expansion
                    emitted = true
                }
                eval == Evaluation.EXCLUDE_AND_CONTINUE && nextDepth < maxDepth ->
                    if (dfsLoop(extendedPath, nextHeadNid, nextNid, seen.toSet(), direction, maxDepth, nextDepth, edgeVisitor, nodeEvaluator)) emitted = true
                // else: EXCLUDE_AND_PRUNE, or EXCLUDE_AND_CONTINUE at cap → nothing
            }
        }
        return emitted
    }

    private suspend fun FlowCollector<Path>.bfsLoop(
        origin: List<Pair<NodeId, NodeLike<*>>>,
        direction: EdgeTraversalDirection,
        maxDepth: Int,
        edgeVisitor: (Path, EdgeLike<*, *>) -> Boolean,
        nodeEvaluator: (Path, NodeLike<*>) -> Evaluation
    ) {
        data class Entry(val path: Path, val headNid: NodeId, val fromNid: NodeId, val visited: Set<NodeId>, val depth: Int)
        val queue = ArrayDeque<Entry>()
        for ((nid, node) in origin) queue += Entry(Path(listOf(node), emptyList()), nid, nid, setOf(nid), 0)
        while (queue.isNotEmpty()) {
            val (currentPath, headNid, fromNid, visited, depth) = queue.removeFirst()
            if (depth >= maxDepth) continue // safety guard; with the enqueue guard below only fires for maxDepth == 0
            val edges = edgesFrom(fromNid, direction)
            var produced = false // this entry emitted a path or enqueued a continuation
            for (hop in edges) {
                if (!edgeVisitor(currentPath, hop.edge!!)) continue
                val nextNid = if (hop.fromId == fromNid) hop.toId else hop.fromId
                if (nextNid in visited) continue
                val nextNode = engine.nodeAt(nextNid) ?: continue
                val eval = nodeEvaluator(currentPath, nextNode)
                val included = eval == Evaluation.INCLUDE_AND_CONTINUE || eval == Evaluation.INCLUDE_AND_PRUNE
                val extendedPath = if (included) {
                    val edgePart = if (fromNid == headNid) listOf(hop.edge) else emptyList()
                    Path(currentPath.nodes + nextNode, currentPath.edges + edgePart)
                } else currentPath
                val nextHeadNid = if (included) nextNid else headNid
                val nextDepth = depth + 1
                if (eval == Evaluation.INCLUDE_AND_PRUNE ||
                    (eval == Evaluation.INCLUDE_AND_CONTINUE && nextDepth >= maxDepth)) { emit(extendedPath); produced = true }
                if (nextDepth < maxDepth && (eval == Evaluation.INCLUDE_AND_CONTINUE || eval == Evaluation.EXCLUDE_AND_CONTINUE)) {
                    queue += Entry(extendedPath, nextHeadNid, nextNid, visited + nextNid, nextDepth)
                    produced = true
                }
            }
            if (!produced && currentPath.nodes.size > 1) emit(currentPath) // natural terminal (excludes lone origin)
        }
    }

    override suspend fun checkReaches(targetId: ID, block: suspend TraversalBuilderLike<ID>.() -> Unit): Boolean {
        val target = homeAdapter.toNodeId(targetId)
        val visited = mutableSetOf<NodeId>()
        visited += frontier
        var current = frontier.toSet()
        while (current.isNotEmpty()) {
            val sub = TraversalBuilder(engine, current, homeAdapter)
            sub.block()
            val next = sub.frontier - visited
            if (target in next) return true
            visited += next
            current = next
        }
        return false
    }

    // Per-node sub-traversal (unlike checkReaches's single batched sub-traversal over the whole
    // level) so each hop's edges can be attributed back to the specific path that produced them —
    // but every entry's sub-traversal is independent I/O, so they're fanned out concurrently
    // (same coroutineScope+async+awaitAll shape as collectSubgraph/exhaustReachable) rather than
    // awaited one at a time. Fan-out is two-layered: entries in parallel across the level, and each
    // entry's own neighbor nodeAt lookups in parallel too — a single high-degree entry (supernode)
    // would otherwise still pay one round trip per neighbor with no other entries to hide behind.
    // Both layers share hopDispatcher, so its limitedParallelism still bounds total concurrent
    // engine calls regardless of nesting. The dedup/target-match pass runs after the gather,
    // sequentially, in the same entry/hop order the old serial loop used, so a shared neighbor still
    // resolves to whichever entry reached it first — only the round trips are parallel, not the
    // semantics. Trade-off: like checkReaches, the target is only checked once the whole level's
    // fetches are in, not mid-level — a few extra fetches in exchange for the level no longer costing
    // one round trip per node (or per neighbor of one node).
    override suspend fun pathTo(targetId: ID, block: suspend TraversalBuilderLike<ID>.() -> Unit): Path? {
        val target = homeAdapter.toNodeId(targetId)
        data class Entry(val nid: NodeId, val path: Path)
        data class Candidate(val neighborNid: NodeId, val edge: EdgeLike<*, *>, val neighborNode: NodeLike<*>, val fromPath: Path)

        var current = frontier.mapNotNull { nid -> engine.nodeAt(nid)?.let { Entry(nid, Path(listOf(it), emptyList())) } }
        val visited = frontier.toMutableSet()
        while (current.isNotEmpty()) {
            val candidates = coroutineScope {
                current.map { entry ->
                    async(engine.hopDispatcher) {
                        val sub = TraversalBuilder(engine, setOf(entry.nid), homeAdapter)
                        sub.block()
                        val edgesByHop = resolveHopEdges(sub.traversedHops)
                        val resolvedHops = sub.traversedHops.mapNotNull { hop -> edgesByHop[hop]?.let { hop to it } }
                        coroutineScope {
                            resolvedHops.map { (hop, edge) ->
                                async(engine.hopDispatcher) {
                                    val neighborNid = if (hop.fromId == entry.nid) hop.toId else hop.fromId
                                    engine.nodeAt(neighborNid)?.let { Candidate(neighborNid, edge, it, entry.path) }
                                }
                            }.awaitAll()
                        }.filterNotNull()
                    }
                }.awaitAll()
            }.flatten()

            val next = mutableListOf<Entry>()
            for (c in candidates) {
                if (c.neighborNid in visited) continue
                visited += c.neighborNid
                val extended = Path(c.fromPath.nodes + c.neighborNode, c.fromPath.edges + c.edge)
                if (c.neighborNid == target) return extended
                next += Entry(c.neighborNid, extended)
            }
            current = next
        }
        return null
    }
}
