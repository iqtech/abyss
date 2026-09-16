package pl.iqtech.abyss.graph.traversal

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.serialization.SerialName
import pl.iqtech.abyss.dsl.Evaluation
import pl.iqtech.abyss.dsl.HopDirection
import pl.iqtech.abyss.dsl.Path
import pl.iqtech.abyss.dsl.Subgraph
import pl.iqtech.abyss.dsl.EdgeTraversalDirection
import pl.iqtech.abyss.dsl.TraversalBuilderLike
import pl.iqtech.abyss.dsl.TraversalScope
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

    private companion object {
        // Chunk size for the streaming batched fetch: keeps flushFrontierNodes a real Flow (an early
        // stop pays for consumed chunks, not the whole frontier) instead of materializing every node
        // before the first emission. Matches AbyssSchemaWorker's valueFetchBatch.
        const val FETCH_BATCH = 128
    }

    private fun hops(nid: NodeId, direction: HopDirection, type: String?, needValue: Boolean, includeEphemeral: Boolean = false): Flow<Hop> =
        if (direction == HopDirection.OUTGOING) engine.outAt(nid, type, needValue, includeEphemeral) else engine.inAt(nid, type, needValue)

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

    override suspend fun addHop(direction: HopDirection, edgeType: String?, edgePredicate: ((EdgeLike<*, *>) -> Boolean)?, includeEphemeral: Boolean) {
        val needValue = edgePredicate != null
        // Fold each frontier node's hop stream straight into a shared collector instead of holding every
        // node's full list simultaneously and then flattening — a supernode's hops don't pile up per node.
        val collector = ConcurrentLinkedQueue<Hop>()
        coroutineScope {
            frontier.map { nid ->
                async(engine.hopDispatcher) {
                    hops(nid, direction, edgeType, needValue, includeEphemeral)
                        .filter { edgePredicate == null || edgePredicate(it.edge!!) }
                        .collect { collector += it }
                }
            }.awaitAll()
        }
        val hopEdges = collector.toList()
        allTraversedHops += hopEdges
        lastHopEdges = hopEdges; lastHopDirection = direction
        frontierTags = hopEdges.associate { it.target(direction) to it.nodeTypeTag }
        mergeVisited(frontierTags)
    }

    override suspend fun addNodeHop(direction: HopDirection, edgeType: String, nodeType: String, nodeTag: Short?, nodePredicate: ((NodeLike<*>) -> Boolean)?) {
        val collector = ConcurrentLinkedQueue<Hop>()
        coroutineScope {
            frontier.map { nid ->
                async(engine.hopDispatcher) {
                    // TODO 4.12: neighbours resolve in windows of FETCH_BATCH (one nodesAt each) instead of
                    // one nodeAt per hop — same buffer/flush shape as AbyssSchemaWorker.adjacencyHopFlow.
                    // The window holds every surviving hop in arrival order (order preserved) but only the
                    // tag-undecided ones are fetched (fetch set preserved). Safe to window: this filter was
                    // already exhaustive, nothing short-circuits.
                    val window = ArrayList<Pair<Hop, Boolean>>(FETCH_BATCH)   // hop to needsFetch
                    suspend fun flush() {
                        if (window.isEmpty()) return
                        val toFetch = window.filter { it.second }.map { it.first.target(direction) }
                        val fetched = if (toFetch.isEmpty()) emptyMap() else engine.nodesAt(toFetch)
                        for ((hop, needsFetch) in window) {
                            if (!needsFetch) { collector += hop; continue }
                            val node = fetched[hop.target(direction)] ?: continue
                            if (node.typeName() == nodeType && (nodePredicate == null || nodePredicate(node))) collector += hop
                        }
                        window.clear()
                    }
                    hops(nid, direction, edgeType, needValue = false).collect { hop ->
                        val tag = hop.nodeTypeTag
                        // Tag known and wanted -> decide by tag; keep fetch-free unless a predicate needs the value.
                        val needsFetch = if (tag != null && nodeTag != null) {
                            if (tag != nodeTag) return@collect
                            nodePredicate != null
                        } else true
                        window += hop to needsFetch
                        if (window.size >= FETCH_BATCH) flush()
                    }
                    flush()
                }
            }.awaitAll()
        }
        val hopEdges = collector.toList()
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
            val fetched = engine.nodesAt(toFetch)
            matched += toFetch.filter { nid ->
                val node = fetched[nid] ?: return@filter false
                node.typeName() == nodeType && (predicate == null || predicate(node))
            }
        }
        retainFrontier(matched)
    }

    private suspend fun filterFrontierByEdge(direction: HopDirection, edgeType: String, endpoint: NodeId) {
        val matching = coroutineScope {
            frontier.map { nid ->
                async(engine.hopDispatcher) { if (hops(nid, direction, edgeType, needValue = false).firstOrNull { it.target(direction) == endpoint } != null) nid else null }
            }.awaitAll()
        }.filterNotNull().toSet()
        retainFrontier(matching)
    }

    private suspend fun filterFrontierByEdgeType(direction: HopDirection, edgeType: String, nodeType: String, nodeTag: Short?) {
        val matching = coroutineScope {
            frontier.map { nid ->
                async(engine.hopDispatcher) {
                    val has = hops(nid, direction, edgeType, needValue = false).firstOrNull { hop ->
                        val tag = hop.nodeTypeTag
                        // Tag known and wanted -> compare in memory; else fall back to a neighbor fetch.
                        if (tag != null && nodeTag != null) tag == nodeTag
                        else engine.nodeAt(hop.target(direction))?.typeName() == nodeType
                    } != null
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

    override suspend fun filterFrontierByTraversal(block: suspend TraversalScope<ID>.() -> Unit) {
        val matching = coroutineScope {
            frontier.map { nid ->
                async(engine.hopDispatcher) {
                    val sub = TraversalBuilder(engine, setOf(nid), homeAdapter)
                    TraversalScope(sub).block()
                    if (sub.frontier.isNotEmpty()) nid else null
                }
            }.awaitAll()
        }.filterNotNull().toSet()
        retainFrontier(matching)
    }

    override suspend fun flushFrontierNodes(): Flow<NodeLike<*>> = flow {
        // Batched per chunk, still streamed: a caller that stops early pays for the chunks it
        // consumed, not the whole frontier. Emission order follows the frontier, as before.
        for (chunk in frontier.chunked(FETCH_BATCH)) {
            val fetched = engine.nodesAt(chunk)
            for (nid in chunk) fetched[nid]?.let { emit(it) }
        }
    }

    override suspend fun flushHopEdges(): Flow<EdgeLike<*, *>> = flow {
        val dir = lastHopDirection ?: return@flow
        val live = lastHopEdges.filter { it.target(dir) in frontier }
        val resolved = resolveHopEdges(live)
        for (hop in live) resolved[hop]?.let { emit(it) }
    }

    override suspend fun count(): Int = frontier.size

    override suspend fun countEdges(direction: HopDirection, edgeType: String): Int = coroutineScope {
        frontier.map { nid -> async(engine.hopDispatcher) { hops(nid, direction, edgeType, needValue = false).count() } }.awaitAll()
    }.sum()

    override suspend fun collectSubgraph(nodeType: String?, nodeTag: Short?): Subgraph {
        // With a type filter, skip fetching visited nodes whose known tag rules them out; only null-tag
        // (or all, when unfiltered) nodes are fetched, then filtered by @SerialName as a fallback.
        val candidates = if (nodeType == null || nodeTag == null) allVisitedTags.keys
            else allVisitedTags.filter { (_, tag) -> tag == null || tag == nodeTag }.keys
        val nodes = engine.nodesAt(candidates).let { fetched -> candidates.mapNotNull { fetched[it] } }.let { all ->
            if (nodeType == null) all else all.filter { it.typeName() == nodeType }
        }
        return Subgraph(nodes, resolveHopEdges(allTraversedHops).values.toList())
    }

    override suspend fun exhaustReachable(block: suspend TraversalScope<ID>.() -> Unit): Subgraph {
        val visited = mutableSetOf<NodeId>(); visited += frontier
        val allHops = mutableListOf<Hop>()
        var current = frontier.toSet()
        while (current.isNotEmpty()) {
            val sub = TraversalBuilder(engine, current, homeAdapter)
            TraversalScope(sub).block()
            allHops += sub.traversedHops
            val next = sub.frontier - visited
            visited += next
            current = next
        }
        val nodes = engine.nodesAt(visited).let { fetched -> visited.mapNotNull { fetched[it] } }
        return Subgraph(nodes, resolveHopEdges(allHops).values.toList())
    }

    override suspend fun detectCycle(block: suspend TraversalScope<ID>.() -> Unit): Boolean {
        val visited = mutableSetOf<NodeId>()
        for (start in frontier) {
            if (start !in visited && dfsCycle(start, visited, block)) return true
        }
        return false
    }

    // Explicit-stack iterative DFS (TODO 3.12 / fable.md 2.5): the original recursed one call per
    // node, and every recursive step routed through sub.block()'s async(engine.hopDispatcher)
    // .awaitAll() — a genuine suspension point, so it wasn't a native StackOverflowError but an
    // unbounded chain of heap-allocated Continuations (one per depth), risking OutOfMemoryError on
    // a long chain. Same white/gray/black coloring as before (visited/inStack), same frontier-loop
    // shape exhaustReachable/checkReaches already use — just no recursion. A node's neighbor list
    // still needs one suspend call (sub.block()), computed once at push time and driven by a plain
    // synchronous while loop from then on.
    private suspend fun dfsCycle(
        start: NodeId,
        visited: MutableSet<NodeId>,
        block: suspend TraversalScope<ID>.() -> Unit
    ): Boolean {
        suspend fun neighborsOf(nodeId: NodeId): Iterator<NodeId> {
            val sub = TraversalBuilder(engine, setOf(nodeId), homeAdapter)
            TraversalScope(sub).block()
            return sub.frontier.iterator()
        }

        val inStack = mutableSetOf<NodeId>()
        val stack = ArrayDeque<Pair<NodeId, Iterator<NodeId>>>()
        visited += start; inStack += start
        stack.addLast(start to neighborsOf(start))

        while (stack.isNotEmpty()) {
            val (nodeId, neighbors) = stack.last()
            if (neighbors.hasNext()) {
                val neighbor = neighbors.next()
                if (neighbor in inStack) return true
                if (neighbor !in visited) {
                    visited += neighbor; inStack += neighbor
                    stack.addLast(neighbor to neighborsOf(neighbor))
                }
            } else {
                stack.removeLast()
                inStack -= nodeId
            }
        }
        return false
    }

    override fun paths(
        strategy: TraversalStrategy,
        direction: EdgeTraversalDirection,
        maxDepth: Int,
        edgeVisitor: (Path, EdgeLike<*, *>) -> Boolean,
        nodeEvaluator: (Path, NodeLike<*>) -> Evaluation
    ): Flow<Path> = flow {
        val origin = engine.nodesAt(frontier).let { fetched -> frontier.mapNotNull { nid -> fetched[nid]?.let { nid to it } } }
        when (strategy) {
            TraversalStrategy.DFS -> for ((nid, node) in origin)
                dfsLoop(nid, node, direction, maxDepth, edgeVisitor, nodeEvaluator)
            TraversalStrategy.BFS -> bfsLoop(origin, direction, maxDepth, edgeVisitor, nodeEvaluator)
        }
    }

    private fun edgesFrom(fromNid: NodeId, direction: EdgeTraversalDirection): Flow<Hop> = when (direction) {
        EdgeTraversalDirection.OUT  -> engine.outAt(fromNid, null)
        EdgeTraversalDirection.IN   -> engine.inAt(fromNid, null)
        // Concat (not merge) — out-then-in, preserving the order the List path produced so path
        // enumeration stays identical.
        EdgeTraversalDirection.BOTH -> flow { emitAll(engine.outAt(fromNid, null)); emitAll(engine.inAt(fromNid, null)) }
    }

    // TODO 1.34: iterative DFS — an explicit frame stack driven by one `while` loop, with ONE route set
    // (`onPath`) and ONE path stack (`nodes`/`edges`) pushed on descend and popped when a frame is exhausted.
    // Replaces a recursive dfsLoop that failed two ways at depth:
    //  - per-level copies of visited/seen/Path held on the suspended recursion — O(depth²) heap, OOM ~5k links;
    //  - even without the copies, its unwind recursed on the thread stack: with needValue hops a level descends
    //    from adjacencyHopFlow's flush, after its last read, so every return runs synchronously — a swallowed
    //    StackOverflowError (~3k links) left the walk parked forever.
    // Here every suspend call (hop read, nodeAt, emit) returns into the loop — no nested collect — so stack depth
    // is constant whether or not the engine suspends.
    // Semantics: README path-local uniqueness. `onPath` is the physical route (included and excluded nodes);
    // `expanded` dedups one parent's neighbours only. The old code passed the child `seen` — ancestors PLUS
    // already-expanded siblings — so a sibling blocked a route through it (a→b, a→c, c→b lost a-c-b whenever ab
    // expanded first). Emission order is the recursive order: a natural terminal is emitted when its frame is
    // exhausted, before the parent's next hop. headNid = last included node; fromNid = physical position (differs
    // after EXCLUDE_AND_CONTINUE). Callbacks and emissions get a snapshot Path (callers may keep it).
    // Cost vs recursion: a frame holds its node's full hop list WITH values (read once at push — the worker's
    // resolveEdges doesn't self-heal evicted edges, so key-only hops + a later value fetch would drop them), and a
    // level is read fully before descending. Equivalence to the recursive backtracking oracle and equal map-op
    // counts are gated in IterativeDfsPrototypeTest.
    private suspend fun FlowCollector<Path>.dfsLoop(
        originNid: NodeId,
        originNode: NodeLike<*>,
        direction: EdgeTraversalDirection,
        maxDepth: Int,
        edgeVisitor: (Path, EdgeLike<*, *>) -> Boolean,
        nodeEvaluator: (Path, NodeLike<*>) -> Evaluation
    ) {
        class Frame(
            val fromNid: NodeId, val headNid: NodeId, val depth: Int,
            val hops: Iterator<Hop>,
            val enteredVia: Evaluation?,          // null = origin
            val pushedNode: Boolean, val pushedEdge: Boolean,
        ) {
            val expanded = HashSet<NodeId>()
            var emitted = false                   // this subtree emitted a path (the recursive return value)
        }

        suspend fun hopsOf(nid: NodeId, depth: Int): Iterator<Hop> =
            if (depth >= maxDepth) emptyList<Hop>().iterator() else edgesFrom(nid, direction).toList().iterator()

        val nodes = arrayListOf(originNode)
        val edges = arrayListOf<EdgeLike<*, *>>()
        val onPath = mutableSetOf(originNid)
        fun snapshot() = Path(nodes.toList(), edges.toList())
        fun pop(node: Boolean, edge: Boolean) { if (node) nodes.removeAt(nodes.size - 1); if (edge) edges.removeAt(edges.size - 1) }

        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(originNid, originNid, 0, hopsOf(originNid, 0), null, false, false))
        while (stack.isNotEmpty()) {
            val top = stack.last()
            if (!top.hops.hasNext()) {                                   // subtree done = the recursive "return"
                stack.removeLast()
                onPath -= top.fromNid
                val parent = stack.lastOrNull() ?: continue
                when (top.enteredVia) {
                    Evaluation.INCLUDE_AND_CONTINUE -> { if (!top.emitted) emit(snapshot()); parent.emitted = true } // natural terminal
                    Evaluation.EXCLUDE_AND_CONTINUE -> if (top.emitted) parent.emitted = true
                    else -> {}
                }
                pop(top.pushedNode, top.pushedEdge)
                continue
            }
            val hop = top.hops.next()
            val edge = hop.edge!!
            if (!edgeVisitor(snapshot(), edge)) continue
            val next = if (hop.fromId == top.fromNid) hop.toId else hop.fromId
            if (next in onPath || !top.expanded.add(next)) continue
            val node = engine.nodeAt(next) ?: continue
            val eval = nodeEvaluator(snapshot(), node)
            val included = eval == Evaluation.INCLUDE_AND_CONTINUE || eval == Evaluation.INCLUDE_AND_PRUNE
            val pushedEdge = included && top.fromNid == top.headNid
            val nextDepth = top.depth + 1
            if (included) { nodes += node; if (pushedEdge) edges += edge }
            when {
                eval == Evaluation.INCLUDE_AND_PRUNE || (eval == Evaluation.INCLUDE_AND_CONTINUE && nextDepth >= maxDepth) -> {
                    emit(snapshot()); top.emitted = true; pop(true, pushedEdge)
                }
                (eval == Evaluation.INCLUDE_AND_CONTINUE || eval == Evaluation.EXCLUDE_AND_CONTINUE) && nextDepth < maxDepth -> {
                    onPath += next
                    stack.addLast(Frame(next, if (included) next else top.headNid, nextDepth, hopsOf(next, nextDepth), eval, included, pushedEdge))
                }
                else -> {}                                               // EXCLUDE_AND_PRUNE, or EXCLUDE_AND_CONTINUE at cap
            }
        }
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
            var produced = false // this entry emitted a path or enqueued a continuation
            // Per-expansion dedup, mirroring dfsLoop's `seen`: two edges from this entry to the same
            // neighbour (e.g. a→b and b→a under BOTH) visit it once, per README's path-local uniqueness
            // contract. Only this expansion's gate — children still inherit `visited + nextNid`, not
            // `seen`, so siblings never block each other's descendants (the diamond case).
            val seen = visited.toMutableSet()
            // TODO 4.12: neighbours resolve in windows of FETCH_BATCH, one nodesAt each, instead of one
            // nodeAt per hop. The FIFO above is untouched and the window drains in hop order, so emission
            // order is exactly what it was. edgeVisitor and the seen gate still run per hop, before the
            // fetch, so the fetch set is unchanged too. Cost: a consumer cancelling mid-entry may have
            // paid for up to one window of fetches it never used.
            val window = ArrayList<Hop>(FETCH_BATCH)
            suspend fun flushWindow() {
                if (window.isEmpty()) return
                val nodes = engine.nodesAt(window.map { if (it.fromId == fromNid) it.toId else it.fromId })
                for (hop in window) {
                    val nextNid = if (hop.fromId == fromNid) hop.toId else hop.fromId
                    val nextNode = nodes[nextNid] ?: continue
                    val eval = nodeEvaluator(currentPath, nextNode)
                    val included = eval == Evaluation.INCLUDE_AND_CONTINUE || eval == Evaluation.INCLUDE_AND_PRUNE
                    val extendedPath = if (included) {
                        val edgePart = if (fromNid == headNid) listOf(hop.edge!!) else emptyList()
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
                window.clear()
            }
            edgesFrom(fromNid, direction).collect { hop ->
                if (!edgeVisitor(currentPath, hop.edge!!)) return@collect
                val nextNid = if (hop.fromId == fromNid) hop.toId else hop.fromId
                if (nextNid in seen) return@collect
                seen += nextNid
                window += hop
                if (window.size >= FETCH_BATCH) flushWindow()
            }
            flushWindow()
            if (!produced && currentPath.nodes.size > 1) emit(currentPath) // natural terminal (excludes lone origin)
        }
    }

    override suspend fun checkReaches(targetId: ID, block: suspend TraversalScope<ID>.() -> Unit): Boolean {
        val target = homeAdapter.toNodeId(targetId)
        val visited = mutableSetOf<NodeId>()
        visited += frontier
        var current = frontier.toSet()
        while (current.isNotEmpty()) {
            val sub = TraversalBuilder(engine, current, homeAdapter)
            TraversalScope(sub).block()
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
    // awaited one at a time. Both layers share hopDispatcher, so its limitedParallelism still bounds
    // total concurrent engine calls.
    //
    // Neighbor node resolution is NOT part of that fan-out (TODO 2.30): the per-entry pass produces
    // candidates key-only, then the whole level's neighbors resolve in ONE batched nodesAt. That
    // fixes what fanning out could not — a supernode level cost one round trip per neighbor even
    // when they all completed in parallel — and dedups a neighbor reached by several entries into a
    // single fetch instead of one per reaching entry.
    //
    // The dedup/target-match pass still runs after the gather, sequentially, in the same entry/hop
    // order the original serial loop used, so a shared neighbor still resolves to whichever entry
    // reached it first, and a neighbor whose node is gone is still dropped before it can be marked
    // visited. Trade-off: like checkReaches, the target is only checked once the whole level's
    // fetches are in, not mid-level — a few extra fetches in exchange for the level no longer costing
    // one round trip per node (or per neighbor of one node).
    override suspend fun pathTo(targetId: ID, block: suspend TraversalScope<ID>.() -> Unit): Path? {
        val target = homeAdapter.toNodeId(targetId)
        data class Entry(val nid: NodeId, val path: Path)
        data class Candidate(val neighborNid: NodeId, val edge: EdgeLike<*, *>, val fromPath: Path)

        var current = engine.nodesAt(frontier).let { fetched ->
            frontier.mapNotNull { nid -> fetched[nid]?.let { Entry(nid, Path(listOf(it), emptyList())) } }
        }
        val visited = frontier.toMutableSet()
        while (current.isNotEmpty()) {
            val candidates = coroutineScope {
                current.map { entry ->
                    async(engine.hopDispatcher) {
                        val sub = TraversalBuilder(engine, setOf(entry.nid), homeAdapter)
                        TraversalScope(sub).block()
                        val edgesByHop = resolveHopEdges(sub.traversedHops)
                        sub.traversedHops.mapNotNull { hop ->
                            edgesByHop[hop]?.let { edge ->
                                Candidate(if (hop.fromId == entry.nid) hop.toId else hop.fromId, edge, entry.path)
                            }
                        }
                    }
                }.awaitAll()
            }.flatten()

            val neighborNodes = engine.nodesAt(candidates.mapTo(mutableSetOf()) { it.neighborNid })

            val next = mutableListOf<Entry>()
            for (c in candidates) {
                if (c.neighborNid in visited) continue
                val neighborNode = neighborNodes[c.neighborNid] ?: continue
                visited += c.neighborNid
                val extended = Path(c.fromPath.nodes + neighborNode, c.fromPath.edges + c.edge)
                if (c.neighborNid == target) return extended
                next += Entry(c.neighborNid, extended)
            }
            current = next
        }
        return null
    }
}
