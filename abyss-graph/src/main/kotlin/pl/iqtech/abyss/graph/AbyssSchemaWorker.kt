package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.hazelcast.config.EvictionPolicy
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.hazelcast.query.Predicate
import com.hazelcast.query.Predicates
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import org.slf4j.LoggerFactory
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.cachedAnnotation
import pl.iqtech.abyss.dsl.serialName
import pl.iqtech.abyss.dsl.typeTag
import pl.iqtech.abyss.graph.serialization.UnknownNode
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreLike
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreTransactionLike
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeConstraint
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

// A NodeId-level unit of work. The typed facade converts its domain ops into these (ids -> NodeId via
// its adapter) before handing them to the worker.
internal sealed interface NodeOp {
    val ttl: Duration?
    data class AddNode(val id: NodeId, val node: NodeLike<*>, override val ttl: Duration?, val tags: Set<String>) : NodeOp
    data class RemoveNode(val id: NodeId) : NodeOp { override val ttl: Duration? get() = null }
    data class AddEdge(val fromId: NodeId, val toId: NodeId, val edge: EdgeLike<*, *>, override val ttl: Duration?, val tags: Set<String>) : NodeOp
    data class RemoveEdge(val fromId: NodeId, val toId: NodeId, val type: String) : NodeOp { override val ttl: Duration? get() = null }
}

/**
 * The untyped graph engine. Owns the shared Hazelcast maps and the (optional) single shared store, and
 * performs every schema operation purely on [NodeId] / [NodeLike]/[EdgeLike]. It never holds a
 * per-schema `<ID>` type or a tag→schema registry: each operation resolves the EdgeAdapter for a key,
 * and whether two keys share a schema, via the injected [resolution] (TODO 1.19: this is the one thing
 * that differs across the single/homogeneous/heterogeneous container tiers).
 *
 * A typed [AbyssGraphSchema] facade converts domain ids to NodeIds at the boundary and delegates here;
 * a multi-schema container owns one worker and routes its [NodeIdEngine] surface to it.
 */
internal class AbyssSchemaWorker(
    hazelcast: HazelcastInstance,
    val nodesMapName: String,
    val edgesMapName: String,
    edgesAdjacencyMapName: String = "$edgesMapName-adjacency",
    private val persistentStore: AbyssStoreLike? = null,
    private val ephemeralStore: AbyssEphemeralStoreLike? = null,
    private val asyncCachePopulation: Boolean = false,
    private val resolution: SchemaResolution,
    module: SerializersModule = EmptySerializersModule(),
    private val adjacencyShardCount: Int = 16,
    hopFanoutParallelism: Int = 256,
) : NodeIdEngine {

    private val log = LoggerFactory.getLogger(AbyssSchemaWorker::class.java)

    private val nodesMap: IMap<NodeId, NodeLike<*>> = hazelcast.getMap(nodesMapName)
    private val edgesMap: IMap<EdgeKey, EdgeLike<*, *>> = hazelcast.getMap(edgesMapName)
    private val adjacency: AdjacencyIndex =
        ShardedAdjacencyIndex(hazelcast.getMap(edgesAdjacencyMapName), adjacencyShardCount, partitionKeyOf = { partitionKey(it) })
    private val tagRegistry = TypeTagRegistry.of(module)
    override val hopDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(hopFanoutParallelism)

    // Value-carrying hop/edge flows fetch edge values from edgesMap in batches of this many neighbors,
    // so peak in-flight stays bounded regardless of degree.
    private val valueFetchBatch = 128

    init {
        // Index-always-alive (TODO 1.27): the adjacency index is the authoritative in-memory topology and
        // MUST NOT be evicted — never-evict is the default. Eviction/TTL on it silently corrupts traversal:
        // Hazelcast evicts individual shard entries, and the per-node warm-check only inspects the first
        // window, so partial eviction reads as "warm" and returns an incomplete neighbor set with no error.
        // Value maps (nodes/edges) may evict — they self-heal from the store. Fail fast rather than lie.
        // (findMapConfig resolves wildcard/default configs; skipped on a client instance with no local config.)
        runCatching { hazelcast.config.findMapConfig(edgesAdjacencyMapName) }.getOrNull()?.let { cfg ->
            require(cfg.evictionConfig.evictionPolicy == EvictionPolicy.NONE && cfg.timeToLiveSeconds == 0 && cfg.maxIdleSeconds == 0) {
                "Eviction/TTL is configured on the adjacency map '$edgesAdjacencyMapName' " +
                "(evictionPolicy=${cfg.evictionConfig.evictionPolicy}, ttl=${cfg.timeToLiveSeconds}s, maxIdle=${cfg.maxIdleSeconds}s). " +
                "This silently corrupts traversal — partial eviction is invisible to the per-node warm-check, so reads " +
                "return an incomplete neighbor set with no error. Remove all eviction from this map; evict " +
                "'$edgesMapName'/'$nodesMapName' instead (those self-heal from the store)."
            }
        }
    }

    private fun edgeAdapterOf(nid: NodeId) = resolution.edgeAdapterOf(nid)

    private fun edgeKey(fromNid: NodeId, toNid: NodeId, type: String) =
        EdgeKey(fromNid, toNid, type, edgeAdapterOf(fromNid).partitionKey(fromNid))

    private fun partitionKey(nid: NodeId): Any = edgeAdapterOf(nid).partitionKey(nid)

    private fun <K, V> keyEq(field: String, nid: NodeId): Predicate<K, V> =
        nativeKeyEq(field, edgeAdapterOf(nid).encodeKey(nid))

    // --- Reads (cache read-through, self-healing from the store on a miss) --------------------------

    suspend fun readNode(nid: NodeId): NodeLike<*>? =
        nodesMap.getAsync(nid).asDeferred().await() ?: loadAndCacheNode(nid)

    suspend fun readEdge(fromNid: NodeId, toNid: NodeId, type: String): EdgeLike<*, *>? =
        edgesMap.getAsync(edgeKey(fromNid, toNid, type)).asDeferred().await() ?: loadAndCacheEdge(fromNid, toNid, type)

    suspend fun nodeExists(nid: NodeId): Boolean =
        nodesMap.getAsync(nid).asDeferred().await() != null || loadAndCacheNode(nid) != null

    suspend fun edgeExists(fromNid: NodeId, toNid: NodeId, type: String): Boolean =
        edgesMap.getAsync(edgeKey(fromNid, toNid, type)).asDeferred().await() != null || loadAndCacheEdge(fromNid, toNid, type) != null

    fun allNodeIds(): Flow<NodeId> = flow { nodesMap.keys.forEach { emit(it) } }

    // Bounded/streamed outgoing edges. Persistent edges ride the OUT adjacency index (shard-window
    // paged, honors pageSize). Ephemeral (TTL) edges are store-only (TODO 1.27) — included only when
    // includeEphemeral is set, read from ephemeralStore (reliable across cache eviction), concatenated
    // after the persistent page. Default false keeps the fast persistent-only path.
    fun outEdges(nid: NodeId, type: String? = null, pageSize: Int = 100, includeEphemeral: Boolean = false): Flow<EdgeLike<*, *>> = flow {
        emitAll(adjacencyEdgeFlow(nid, AdjacencyDirection.OUT, type, batch = pageSize))
        if (includeEphemeral) emitAll(ephemeralStoreHops(nid, type).mapNotNull { it.edge })
    }

    // Ephemeral (TTL) out-edges are store-only (TODO 1.27): reliable only from ephemeralStore, since the
    // cache holds none. Empty when no ephemeral store is configured. nodeTypeTag is null (YCQL loadEdges
    // carries no neighbor-type JOIN) so typed filters fetch-fall-back. Expired entries are skipped.
    private fun ephemeralStoreHops(nid: NodeId, type: String?): Flow<Hop> = flow {
        val loaded = withContext(Dispatchers.IO) { ephemeralStore?.loadEdges(nid)?.getOrNull() } ?: return@flow
        for (e in loaded) {
            if (type != null && edgeType(e.edge) != type) continue
            val remaining = e.remaining
            if (remaining != null && remaining.inWholeSeconds <= 0) continue
            emit(Hop(e.fromId, e.toId, edgeType(e.edge), e.edge, null))
        }
    }

    fun inEdges(nid: NodeId, type: String? = null, pageSize: Int = 100): Flow<EdgeLike<*, *>> =
        adjacencyEdgeFlow(nid, AdjacencyDirection.IN, type, batch = pageSize)

    private fun edgeType(edge: EdgeLike<*, *>): String =
        edge::class.serialName()

    // --- NodeIdEngine (endpoints as NodeId, so a walk can span schemas over the shared edge map) ----

    override suspend fun nodeAt(nid: NodeId): NodeLike<*>? = readNode(nid)

    override fun outAt(nid: NodeId, type: String?, needValue: Boolean, includeEphemeral: Boolean): Flow<Hop> {
        val persistent = outAtPersistent(nid, type, needValue)
        // Ephemeral edges are store-only (TODO 1.27): included only on explicit opt-in, from the store.
        return if (includeEphemeral) flow { emitAll(persistent); emitAll(ephemeralStoreHops(nid, type)) } else persistent
    }

    @Suppress("UNCHECKED_CAST")
    private fun outAtPersistent(nid: NodeId, type: String?, needValue: Boolean): Flow<Hop> =
        // Already-optimal hottest path (edgesMap is partitioned by fromId, and now holds persistent edges
        // only) — don't route it through the adjacency index, which would cost 2 round trips for no gain.
        // Materialized-then-emitted: a partition entrySet can't be paged (PagingPredicate doesn't compose
        // with PartitionPredicate), and this path's consumers collect-all anyway.
        if (type != null && needValue) flow {
            ensureOutWarm(nid)
            val pred = Predicates.and<EdgeKey, Any>(keyEq<EdgeKey, Any>("fromId", nid), Predicates.equal<EdgeKey, Any>("__key.type", type))
            val part = Predicates.partitionPredicate<EdgeKey, Any>(partitionKey(nid), pred)
            val map = edgesMap as IMap<EdgeKey, Any>
            withContext(Dispatchers.IO) { map.entrySet(part) }.forEach { emit(Hop(it.key.fromId, it.key.toId, it.key.type, it.value as EdgeLike<*, *>)) }
        }
        else adjacencyHopFlow(nid, AdjacencyDirection.OUT, type, needValue, valueFetchBatch)

    override fun inAt(nid: NodeId, type: String?, needValue: Boolean): Flow<Hop> =
        adjacencyHopFlow(nid, AdjacencyDirection.IN, type, needValue, valueFetchBatch)

    // preloadOut warms both edgesMap and the adjacency index together, so an empty adjacency OUT side is
    // also the signal that edgesMap's fast-path scan needs warming — used by callers that bypass
    // adjacencyRead (outAt's fast path, outEdgeFlow, cascadeEdgeRemovals).
    private suspend fun ensureOutWarm(nid: NodeId) {
        if (adjacency.isEmpty(nid, AdjacencyDirection.OUT)) preloadOut(nid)
    }

    // Bounded, streamed hop flow off the adjacency index (self-healing from the store on a cold miss).
    // The index emits in bounded windows (see ShardedAdjacencyIndex); needValue batches the edge-value
    // getAll every [batch] hops (peak heap ~[batch] edges regardless of degree). needValue=false emits
    // key-only hops straight through, so short-circuiting consumers (.any/.firstOrNull) stop early.
    @Suppress("UNCHECKED_CAST")
    private fun adjacencyHopFlow(nid: NodeId, direction: AdjacencyDirection, type: String?, needValue: Boolean, batch: Int): Flow<Hop> = flow {
        if (adjacency.isEmpty(nid, direction)) {
            // ponytail: a node with genuinely zero edges in this direction is indistinguishable from
            // "never preloaded" (no shard entry to tell them apart) — it retries the store on every
            // call instead of caching "confirmed empty". Upgrade to a dedicated warm-marker key if a
            // hot zero-degree node's repeated store hits ever show up in profiling.
            if (direction == AdjacencyDirection.OUT) preloadOut(nid) else preloadIn(nid)
        }
        val edgeTag = type?.let { tagRegistry.edgeTagOf(it) }
        // nodeTypeTag is the neighbor's (== target's) type — carried so typed filters skip a fetch.
        fun hopOf(entry: AdjacencyEntry): Hop {
            val (fromId, toId) = if (direction == AdjacencyDirection.OUT) nid to entry.neighborId else entry.neighborId to nid
            return Hop(fromId, toId, tagRegistry.edgeNameOf(entry.edgeTypeTag), null, entry.nodeTypeTag)
        }
        if (!needValue) {
            adjacency.read(nid, direction, edgeTag).collect { emit(hopOf(it)) }
            return@flow
        }
        val map = edgesMap as IMap<EdgeKey, Any>
        val buffer = ArrayList<Hop>(batch)
        suspend fun FlowCollector<Hop>.flush() {
            if (buffer.isEmpty()) return
            val keys = buffer.associateWith { edgeKey(it.fromId, it.toId, it.type) }
            val values = withContext(Dispatchers.IO) { map.getAll(keys.values.toSet()) }
            // Index-always-alive (TODO 1.27): the adjacency index is authoritative, so a null value is an
            // EVICTED persistent edge, not a removed one — self-heal it from the persistent store (per
            // missing edge, in parallel; loadAndCacheEdge re-warms edgesMap). A store-null means the edge
            // was genuinely removed → skip. In steady state (nothing evicted) `missing` is empty, no store hit.
            val missing = buffer.filter { values[keys.getValue(it)] == null }
            val healed: Map<Hop, EdgeLike<*, *>> = if (missing.isEmpty()) emptyMap() else coroutineScope {
                missing.map { hop -> async(Dispatchers.IO) { loadAndCacheEdge(hop.fromId, hop.toId, hop.type)?.let { hop to it } } }.awaitAll()
            }.filterNotNull().toMap()
            for (hop in buffer) {
                val edge = (values[keys.getValue(hop)] as EdgeLike<*, *>?) ?: healed[hop]
                if (edge != null) emit(Hop(hop.fromId, hop.toId, hop.type, edge, hop.nodeTypeTag))
            }
            buffer.clear()
        }
        adjacency.read(nid, direction, edgeTag).collect { entry ->
            buffer += hopOf(entry)
            if (buffer.size >= batch) flush()
        }
        flush()
    }

    // Public edge flows are the value-carrying hop flow with the key stripped back to the edge.
    private fun adjacencyEdgeFlow(nid: NodeId, direction: AdjacencyDirection, type: String?, batch: Int): Flow<EdgeLike<*, *>> =
        adjacencyHopFlow(nid, direction, type, needValue = true, batch = batch).mapNotNull { it.edge }

    @Suppress("UNCHECKED_CAST")
    override suspend fun resolveEdges(hops: List<Hop>): Map<Hop, EdgeLike<*, *>> {
        if (hops.isEmpty()) return emptyMap()
        val keyByHop = hops.associateWith { edgeKey(it.fromId, it.toId, it.type) }
        val map = edgesMap as IMap<EdgeKey, Any>
        val values = withContext(Dispatchers.IO) { map.getAll(keyByHop.values.toSet()) }
        return keyByHop.mapNotNull { (hop, key) -> (values[key] as EdgeLike<*, *>?)?.let { hop to it } }.toMap()
    }

    override fun allNodeIdsRaw(): Flow<NodeId> = flow { nodesMap.keys.forEach { emit(it) } }

    // Cross-schema edges (NodeId-valued EdgeLike, in the shared maps) no longer have dedicated
    // put/remove methods: HomogeneousSchemaGraph/HeterogeneousSchemaGraph route them through the same
    // transaction()/ephemeral() below as an ordinary NodeOp.AddEdge/RemoveEdge — atomic, and
    // endpoint-existence/@EdgeConstraint-checked for free, instead of an independent store commit.

    // Store -> cache preload for a node's outgoing edges (self-healing on cache miss). The store
    // returns both endpoint NodeIds (from its PK columns), so the cache key rebuilds untyped. Also
    // warms the OUT-direction adjacency entry — an adjacency self-heal preloadOut never needed before
    // there was an outgoing index at all.
    private suspend fun preloadOut(nid: NodeId) = withContext(Dispatchers.IO) {
        persistentStore?.loadEdges(nid)?.getOrNull()?.forEach { e ->
            val type = edgeType(e.edge)
            edgesMap.putIfAbsent(edgeKey(e.fromId, e.toId, type), e.edge)
            // Neighbor tag rides the edge scan (StoredEdge.neighborType) — no per-neighbor node read.
            // Null (dangling / store can't resolve) leaves the tag null; typed traversal fetch-falls-back.
            val toTag = e.neighborType?.let { tagRegistry.nodeTagOf(it) }
            adjacency.addAsync(e.fromId, AdjacencyDirection.OUT, AdjacencyEntry(e.toId, toTag, e.edge::class.typeTag())).asDeferred().await()
        }
        // Ephemeral edges are store-only (TODO 1.27): not cached, not indexed — traversal reaches them
        // via includeEphemeral, which reads ephemeralStore directly. Nothing to warm here.
    }

    // Only persistent edges have an adjacency index to warm (ephemeral edges are outgoing-only, TODO 1.13).
    private suspend fun preloadIn(nid: NodeId) = withContext(Dispatchers.IO) {
        persistentStore?.loadInEdges(nid)?.getOrNull()?.forEach { e ->
            val type = edgeType(e.edge)
            edgesMap.putIfAbsent(edgeKey(e.fromId, e.toId, type), e.edge)
            // Neighbor (the from-node) tag rides the loadInEdges scan; see preloadOut.
            val fromTag = e.neighborType?.let { tagRegistry.nodeTagOf(it) }
            adjacency.addAsync(e.toId, AdjacencyDirection.IN, AdjacencyEntry(e.fromId, fromTag, e.edge::class.typeTag())).asDeferred().await()
        }
    }

    // --- Commit pipelines ---------------------------------------------------------------------------

    suspend fun transaction(baseOps: List<NodeOp>, checkIntegrity: Boolean): Either<AbyssError, Unit> {
        val ops = expandCascades(baseOps)
        integrityError(ops, checkIntegrity)?.let { return it.left() }

        if (persistentStore != null) {
            val storeResult = persistentStore.transaction { ops.forEach { applyPersistentOp(it) } }
            if (storeResult.isLeft()) {
                log.error("Store transaction failed; cache unchanged [nodes={}, edges={}]", nodesMapName, edgesMapName)
                return storeResult
            }
        }
        val deletes = ops.filter { it is NodeOp.RemoveNode || it is NodeOp.RemoveEdge }
        if (ephemeralStore != null && deletes.isNotEmpty()) {
            ephemeralStore.transaction { deletes.forEach { applyEphemeralOp(it) } }
                .onLeft { log.warn("Ephemeral delete fanout failed during transaction; stale ephemeral data possible [nodes={}, edges={}]", nodesMapName, edgesMapName) }
        }

        populateCache(ops, "Cache update failed after store commit; cache may be stale")
        log.debug("Transaction committed [{} op(s), nodes={}, edges={}]", ops.size, nodesMapName, edgesMapName)
        return Unit.right()
    }

    // Bulk-load path, independent of transaction(): commits the persistent side in `batchSize`-sized
    // chunks (each its own DB transaction, see YugabytePersistentStore.batchTransaction) instead of
    // one atomic transaction for the whole op list. Cascade expansion and the integrity check still
    // run ONCE over the full expanded op list before any chunking happens — a RemoveNode and its
    // cascade-deleted edges must never be split across chunks, and integrity's addedInTx map needs
    // every add in the batch visible regardless of which chunk it lands in.
    suspend fun batchTransaction(baseOps: List<NodeOp>, batchSize: Int, checkIntegrity: Boolean): Either<AbyssError, Unit> {
        val ops = expandCascades(baseOps)
        integrityError(ops, checkIntegrity)?.let { return it.left() }

        if (persistentStore != null) {
            val storeResult = persistentStore.batchTransaction(batchSize) { ops.forEach { applyPersistentOp(it) } }
            if (storeResult.isLeft()) {
                log.error("Batch transaction failed partway; chunks committed before the failure remain persisted [nodes={}, edges={}]", nodesMapName, edgesMapName)
                return storeResult
            }
        }
        val deletes = ops.filter { it is NodeOp.RemoveNode || it is NodeOp.RemoveEdge }
        if (ephemeralStore != null && deletes.isNotEmpty()) {
            ephemeralStore.transaction { deletes.forEach { applyEphemeralOp(it) } }
                .onLeft { log.warn("Ephemeral delete fanout failed during batch transaction; stale ephemeral data possible [nodes={}, edges={}]", nodesMapName, edgesMapName) }
        }

        populateCache(ops, "Cache update failed after batch store commit; cache may be stale")
        log.debug("Batch transaction committed [{} op(s), batchSize={}, nodes={}, edges={}]", ops.size, batchSize, nodesMapName, edgesMapName)
        return Unit.right()
    }

    suspend fun ephemeral(baseOps: List<NodeOp>, checkIntegrity: Boolean): Either<AbyssError, Unit> {
        val ops = expandCascades(baseOps)
        integrityError(ops, checkIntegrity)?.let { return it.left() }

        if (ephemeralStore != null) {
            val storeResult = ephemeralStore.transaction { ops.forEach { applyEphemeralOp(it) } }
            if (storeResult.isLeft()) {
                log.error("Ephemeral store commit failed; cache unchanged [nodes={}, edges={}]", nodesMapName, edgesMapName)
                return storeResult
            }
        }
        val deletes = ops.filter { it is NodeOp.RemoveNode || it is NodeOp.RemoveEdge }
        if (persistentStore != null && deletes.isNotEmpty()) {
            persistentStore.transaction { deletes.forEach { applyPersistentOp(it) } }
                .onLeft { log.warn("Persistent delete fanout failed during ephemeral commit; stale persistent data possible [nodes={}, edges={}]", nodesMapName, edgesMapName) }
        }

        populateCache(ops, "Cache update failed after ephemeral store commit; cache may be stale")
        log.debug("Ephemeral committed [{} op(s), nodes={}, edges={}]", ops.size, nodesMapName, edgesMapName)
        return Unit.right()
    }

    private suspend fun expandCascades(baseOps: List<NodeOp>): List<NodeOp> {
        val result = mutableListOf<NodeOp>()
        for (op in baseOps) {
            result += op
            if (op is NodeOp.RemoveNode) result += cascadeEdgeRemovals(op.id)
        }
        return result
    }

    // TODO 1.20 fix: self-heals via readNode (cache-miss falls back to the store) instead of a raw
    // nodesMap read, so a genuinely-existing but not-yet-warmed node doesn't spuriously fail addEdge.
    private suspend fun integrityError(ops: List<NodeOp>, checkIntegrity: Boolean): AbyssError? {
        if (!checkIntegrity) return null
        val addedInTx = ops.filterIsInstance<NodeOp.AddNode>().associate { it.id to it.node }
        for (addOp in ops.filterIsInstance<NodeOp.AddEdge>()) {
            val fromNode = addedInTx[addOp.fromId] ?: readNode(addOp.fromId)
            val toNode   = addedInTx[addOp.toId]   ?: readNode(addOp.toId)
            val err = when {
                fromNode == null -> AbyssError.IntegrityError("Node ${addOp.edge.fromId} (fromId) not found")
                toNode   == null -> AbyssError.IntegrityError("Node ${addOp.edge.toId} (toId) not found")
                else             -> schemaCheck(addOp.edge, fromNode, toNode)
            }
            if (err != null) return err
        }
        return null
    }

    // TODO 1.20 fix: preloadOut/preloadIn warm the cache from the store first (same self-heal
    // preloadOut/preloadIn already give outAt/inAt), so a cold cache after a restart or partition
    // eviction can't make this scan silently miss a node's durable edges and leave them dangling.
    // Schema-agnostic by design: an edge lives in these same shared maps and this same store
    // regardless of whether its other endpoint shares nid's schema tag, so cascade removes it either
    // way — a cross-schema edge left dangling after its endpoint is deleted is exactly the bug this
    // used to have (a since-removed `sameSchema(...)` filter excluded cross-schema edges here).
    private suspend fun cascadeEdgeRemovals(nid: NodeId): List<NodeOp.RemoveEdge> {
        ensureOutWarm(nid)
        val pk = partitionKey(nid)
        val out = withContext(Dispatchers.IO) {
            edgesMap.entrySet(Predicates.partitionPredicate(pk, keyEq<EdgeKey, EdgeLike<*, *>>("fromId", nid)))
        }.map { NodeOp.RemoveEdge(it.key.fromId, it.key.toId, it.key.type) }
        // ponytail: ephemeral edges are outgoing-only (TODO 1.13) — no adjacency IN entry, so deleting
        // the TO-node can't cascade them; they expire via TTL. Deleting the FROM-node still cascades (out).
        val inc = inAt(nid, type = null, needValue = false).toList()
            .map { NodeOp.RemoveEdge(it.fromId, it.toId, it.type) }
        return (out + inc).distinctBy { Triple(it.fromId, it.toId, it.type) }
    }

    private fun AbyssStoreTransactionLike.applyPersistentOp(op: NodeOp) = when (op) {
        is NodeOp.AddNode    -> saveNode(op.id, op.node, op.tags)
        is NodeOp.RemoveNode -> deleteNode(op.id)
        is NodeOp.AddEdge    -> saveEdge(op.fromId, op.toId, op.edge, op.tags)
        is NodeOp.RemoveEdge -> deleteEdge(op.fromId, op.toId, op.type)
    }

    private fun AbyssEphemeralStoreTransactionLike.applyEphemeralOp(op: NodeOp) = when (op) {
        is NodeOp.AddNode    -> saveNode(op.id, op.node, op.ttl!!, op.tags)
        is NodeOp.RemoveNode -> deleteNode(op.id)
        is NodeOp.AddEdge    -> saveEdge(op.fromId, op.toId, op.edge, op.ttl!!, op.tags)
        is NodeOp.RemoveEdge -> deleteEdge(op.fromId, op.toId, op.type)
    }

    private suspend fun populateCache(ops: List<NodeOp>, warnMsg: String) {
        // A node added in the same transaction as its edge resolves its type tag from here rather
        // than a (not-yet-committed-to-cache) readNode lookup.
        val addedInTx = ops.filterIsInstance<NodeOp.AddNode>().associate { it.id to it.node }
        val stages = ops.flatMap { applyToCacheAsync(it, addedInTx) }
        if (asyncCachePopulation) {
            // ponytail: fire-and-forget — no thread pinned during network wait
            stages.forEach { it.exceptionally { ex -> log.warn(warnMsg, ex); null } }
        } else {
            Either.catch { stages.map { it.asDeferred() }.awaitAll() }
                .fold(ifLeft = { log.warn(warnMsg, it) }, ifRight = {})
        }
    }

    // Nullable, not error(): checkIntegrity=false explicitly allows edges to a node that doesn't exist
    // (a supported, tested scenario) — the edge write must still succeed, just without a resolvable
    // nodeTypeTag hint (AdjacencyEntry.nodeTypeTag degrades to "unknown" rather than failing the write).
    private suspend fun resolveNodeTag(nid: NodeId, addedInTx: Map<NodeId, NodeLike<*>>): Short? =
        (addedInTx[nid] ?: readNode(nid))?.let { it::class.typeTag() }

    private suspend fun applyToCacheAsync(op: NodeOp, addedInTx: Map<NodeId, NodeLike<*>>): List<CompletionStage<*>> = when (op) {
        is NodeOp.AddNode ->
            if (op.ttl != null) listOf(nodesMap.setAsync(op.id, op.node, op.ttl.inWholeSeconds, TimeUnit.SECONDS))
            else listOf(nodesMap.setAsync(op.id, op.node))
        is NodeOp.RemoveNode -> listOf(nodesMap.removeAsync(op.id))
        is NodeOp.AddEdge -> {
            // Ephemeral (TTL) edges are store-only (TODO 1.27): the ephemeral() commit persists them to
            // ephemeralStore; they are NOT cached in edgesMap nor indexed. This keeps the persistent
            // read path (adjacency index + edgesMap) clean and fast; traversal reaches ephemeral edges
            // only via includeEphemeral, which reads the store (reliable across cache eviction).
            if (op.ttl != null) emptyList()
            else {
                val type = edgeType(op.edge)
                val key  = edgeKey(op.fromId, op.toId, type)
                val edgeTag = op.edge::class.typeTag()
                val toTag = resolveNodeTag(op.toId, addedInTx)
                val fromTag = resolveNodeTag(op.fromId, addedInTx)
                listOf(
                    edgesMap.setAsync(key, op.edge),
                    adjacency.addAsync(op.fromId, AdjacencyDirection.OUT, AdjacencyEntry(op.toId, toTag, edgeTag)),
                    adjacency.addAsync(op.toId, AdjacencyDirection.IN, AdjacencyEntry(op.fromId, fromTag, edgeTag)),
                )
            }
        }
        is NodeOp.RemoveEdge -> {
            // RemoveEdge only carries a type string, no live EdgeLike — this is the String -> Short
            // registry lookup direction.
            val edgeTag = tagRegistry.edgeTagOf(op.type)
            listOf(
                edgesMap.removeAsync(edgeKey(op.fromId, op.toId, op.type)),
                adjacency.removeAsync(op.fromId, AdjacencyDirection.OUT, op.toId, edgeTag),
                adjacency.removeAsync(op.toId, AdjacencyDirection.IN, op.fromId, edgeTag),
            )
        }
    }

    private suspend fun loadAndCacheNode(nid: NodeId): NodeLike<*>? {
        val (node, remaining) = coroutineScope {
            val fromPersistent = async(Dispatchers.IO) { persistentStore?.loadNode(nid)?.getOrNull() }
            val fromEphemeral  = async(Dispatchers.IO) { ephemeralStore?.loadNode(nid)?.getOrNull() }
            fromPersistent.await() ?: fromEphemeral.await()
        } ?: return null
        node ?: return null
        if (remaining != null && remaining.inWholeSeconds <= 0) return null
        withContext(Dispatchers.IO) {
            if (remaining == null) nodesMap.set(nid, node)
            else nodesMap.set(nid, node, remaining.inWholeSeconds, TimeUnit.SECONDS)
        }
        return node
    }

    private suspend fun loadAndCacheEdge(fromNid: NodeId, toNid: NodeId, type: String): EdgeLike<*, *>? {
        val (edge, remaining) = coroutineScope {
            val fromPersistent = async(Dispatchers.IO) { persistentStore?.loadEdge(fromNid, toNid, type)?.getOrNull() }
            val fromEphemeral  = async(Dispatchers.IO) { ephemeralStore?.loadEdge(fromNid, toNid, type)?.getOrNull() }
            fromPersistent.await() ?: fromEphemeral.await()
        } ?: return null
        edge ?: return null
        if (remaining != null && remaining.inWholeSeconds <= 0) return null
        // Ephemeral edges (remaining != null) are store-only (TODO 1.27): return without caching, so a
        // point-read never re-populates edgesMap with an ephemeral edge and re-pollutes the persistent
        // fast path. Only persistent edges (remaining == null) are cached.
        if (remaining != null) return edge
        withContext(Dispatchers.IO) { edgesMap.set(edgeKey(fromNid, toNid, type), edge) }
        return edge
    }

    private fun schemaCheck(edge: EdgeLike<*, *>, from: NodeLike<*>, to: NodeLike<*>): AbyssError? {
        val c = edge::class.cachedAnnotation<EdgeConstraint>() ?: return null
        if (c.fromTypes.isNotEmpty() && from !is UnknownNode && from::class !in c.fromTypes)
            return AbyssError.SchemaError("Edge ${edgeType(edge)}: fromId is ${from::class.simpleName}, expected ${c.fromTypes.map { it.simpleName }}")
        if (c.toTypes.isNotEmpty() && to !is UnknownNode && to::class !in c.toTypes)
            return AbyssError.SchemaError("Edge ${edgeType(edge)}: toId is ${to::class.simpleName}, expected ${c.toTypes.map { it.simpleName }}")
        return null
    }

    // ponytail: bridges CompletionStage → Deferred; avoids kotlinx-coroutines-jdk8 dependency
    private fun <T> CompletionStage<T>.asDeferred(): Deferred<T> = CompletableDeferred<T>().also { d ->
        whenComplete { v, ex -> if (ex != null) d.completeExceptionally(ex) else d.complete(v) }
    }
}
