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
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
    private val hazelcast: HazelcastInstance,
    val nodesMapName: String,
    val edgesMapName: String,
    edgesAdjacencyMapName: String = "$edgesMapName-adjacency",
    private val persistentStore: AbyssStoreLike? = null,
    ephemeralStore: AbyssEphemeralStoreLike? = null,
    private val asyncCachePopulation: Boolean = false,
    private val resolution: SchemaResolution,
    module: SerializersModule = EmptySerializersModule(),
    private val adjacencyShardCount: Int = 16,
    hopFanoutParallelism: Int = 256,
    private val evictionVerifiedExternally: Boolean = false,
) : NodeIdEngine {

    private val log = LoggerFactory.getLogger(AbyssSchemaWorker::class.java)

    private val nodesMap: IMap<NodeId, NodeLike<*>> = hazelcast.getMap(nodesMapName)
    private val edgesMap: IMap<EdgeKey, EdgeLike<*, *>> = hazelcast.getMap(edgesMapName)
    private val adjacency: AdjacencyIndex =
        ShardedAdjacencyIndex(hazelcast.getMap(edgesAdjacencyMapName), adjacencyShardCount, partitionKeyOf = { partitionKey(it) })
    // No explicit store means no ephemeral support at all (TODO 1.27: ephemeral edges are store-only,
    // never cached, so a null store makes ephemeral() a silent no-op) — default to a real memory-only
    // Hazelcast-backed store instead, named the same way as the adjacency map.
    private val ephemeralStore: AbyssEphemeralStoreLike =
        ephemeralStore ?: HazelcastEphemeralStore(hazelcast, "$edgesMapName-ephemeral", "$nodesMapName-ephemeral")
    private val tagRegistry = TypeTagRegistry.of(module)
    override val hopDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(hopFanoutParallelism)

    // Value-carrying hop/edge flows fetch edge values from edgesMap in batches of this many neighbors,
    // so peak in-flight stays bounded regardless of degree.
    private val valueFetchBatch = 128

    // Index-always-alive (TODO 1.27) fail-fast guard, shared by the adjacency map (always) and the
    // edges map (only in cache-only mode, TODO 1.29 item 3 below). findMapConfig resolves
    // wildcard/default configs on an embedded member; on a Hazelcast CLIENT instance it always throws
    // UnsupportedOperationException instead (client Config is add-only dynamic config — it cannot read
    // back the cluster's real static map config), so that specific exception gets its own loud failure
    // rather than being silently swallowed like any other unexpected error here.
    // Index-always-alive (TODO 1.27) fail-fast guard, shared by the adjacency map (always) and the
    // edges map (only in cache-only mode, TODO 1.29 item 3 below). findMapConfig resolves
    // wildcard/default configs on an embedded member; on a Hazelcast CLIENT instance it always throws
    // UnsupportedOperationException instead (client Config is add-only dynamic config — it cannot read
    // back the cluster's real static map config), so that specific exception gets its own loud failure
    // rather than being silently swallowed like any other unexpected error here.
    private fun requireNoEviction(mapName: String, why: String) {
        val cfgResult = runCatching { hazelcast.config.findMapConfig(mapName) }
        val cfg = cfgResult.getOrNull()
        if (cfg != null) {
            require(cfg.evictionConfig.evictionPolicy == EvictionPolicy.NONE && cfg.timeToLiveSeconds == 0 && cfg.maxIdleSeconds == 0) {
                "Eviction/TTL is configured on map '$mapName' (evictionPolicy=${cfg.evictionConfig.evictionPolicy}, " +
                "ttl=${cfg.timeToLiveSeconds}s, maxIdle=${cfg.maxIdleSeconds}s). $why"
            }
        } else if (cfgResult.exceptionOrNull() is UnsupportedOperationException) {
            require(evictionVerifiedExternally) {
                "Cannot verify eviction/TTL is disabled on map '$mapName': this HazelcastInstance is a client " +
                "connection, and Hazelcast's client Config API can't read the cluster's real map config " +
                "(findMapConfig always throws UnsupportedOperationException on a client). $why Either connect " +
                "via an embedded member instance, or confirm server-side and pass evictionVerifiedExternally = true."
            }
        }
    }

    init {
        // The adjacency index is the authoritative in-memory topology and MUST NOT be evicted —
        // never-evict is the default. Eviction/TTL on it silently corrupts traversal: Hazelcast evicts
        // individual shard entries, and the per-node warm-check only inspects the first window, so
        // partial eviction reads as "warm" and returns an incomplete neighbor set with no error.
        requireNoEviction(edgesAdjacencyMapName,
            "This silently corrupts traversal — partial eviction is invisible to the per-node warm-check, so reads " +
            "return an incomplete neighbor set with no error. Remove all eviction from this map; evict " +
            "'$edgesMapName'/'$nodesMapName' instead (those self-heal from the store)."
        )
        // Value maps (nodes/edges) may evict when a persistentStore self-heals them — but in cache-only
        // mode (no persistentStore, README-documented as supported) an evicted edge has nothing to
        // reload from: it's silently dropped out of traversal results with no error (adjacency still
        // lists it, the value read comes back null). Same fail-fast treatment as the adjacency map.
        if (persistentStore == null) {
            requireNoEviction(edgesMapName,
                "No persistentStore is configured (pure in-memory / cache-only mode) — without a store to self-heal " +
                "from, an evicted edge is unrecoverable and is silently dropped from traversal results with no error " +
                "(the adjacency index still lists it, the value read comes back null). Remove eviction/TTL from " +
                "'$edgesMapName', or configure a persistentStore."
            )
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

    // fable.md 3.2 / TODO 3.13: nodesMap.keys eagerly materializes the *entire* key set into memory
    // before this flow starts emitting — not a streaming/paginated scan. At README-cited scale
    // (36k users × 500 nodes = 18M keys) that's a multi-GB in-memory Set; avoiding that
    // materialization needs a real store-scan capability (TODO 1.23), out of scope here. The
    // withContext below only keeps the blocking Hazelcast fetch off the caller's dispatcher — same
    // pattern as outAtPersistent/resolveEdges — it does not reduce the memory footprint.
    fun allNodeIds(): Flow<NodeId> = flow { withContext(Dispatchers.IO) { nodesMap.keys }.forEach { emit(it) } }

    // Admin/orphan-sweep scan (TODO 1.23) — deliberately NOT part of NodeIdEngine (that seam is
    // traversal plumbing); this is a distinct, admin-facing, unscoped-by-schema capability. Spans
    // both stores, merged into one Flow so callers don't combine two flows themselves. A tag filter
    // with no persistentStore configured fails loudly (tags aren't cached anywhere to answer from).
    fun scanNodeIds(tag: String? = null, parallelism: Int = 4): Flow<NodeId> = channelFlow {
        if (persistentStore != null) {
            launch { persistentStore.scanNodeIds(tag, parallelism).collect { send(it) } }
        } else if (tag != null) {
            error("scanNodeIds(tag = ...) requires a configured persistentStore")
        }
        launch { ephemeralStore.scanNodeIds(tag, parallelism).collect { send(it) } }
    }

    fun scanEdgeIds(parallelism: Int = 4): Flow<Pair<NodeId, NodeId>> =
        persistentStore?.scanEdgeIds(parallelism) ?: emptyFlow()

    // Outgoing edges. Persistent edges come from cachedOutScan (one partition scan, checked against the
    // index count); pageSize bounds only its heal path, the scan materializes the node's cached values.
    // Ephemeral (TTL) edges are store-only (TODO 1.27) — included only when includeEphemeral is set, read
    // from ephemeralStore (reliable across cache eviction), concatenated after the persistent edges.
    @Suppress("UNCHECKED_CAST")
    fun outEdges(nid: NodeId, type: String? = null, pageSize: Int = 100, includeEphemeral: Boolean = false): Flow<EdgeLike<*, *>> = flow {
        val map = edgesMap as IMap<EdgeKey, Any>
        emitAll(cachedOutScan(nid, type,
            scan = { pred -> map.values(pred) as Collection<EdgeLike<*, *>> },
            heal = { adjacencyEdgeFlow(nid, AdjacencyDirection.OUT, type, batch = pageSize) }))
        if (includeEphemeral) emitAll(ephemeralStoreHops(nid, type).mapNotNull { it.edge })
    }

    // TODO 4.14: edgesMap is partitioned by fromId, so a node's cached out-edges are ONE partition scan — but
    // the cache may evict (TODO 1.27), and a scan only sees what's cached. The adjacency index is never
    // evicted and authoritative, so its count (fetched in parallel) proves the scan complete. Equal and
    // non-zero → emit the scan. Otherwise (evicted values, or a cold node) → heal: the index-driven paged
    // path, which reloads missing values from the store and warms a cold node. No store and an empty index
    // → nothing. Measured (TODO 4.14 sweep): 2 map ops, 1.07-1.51x the old unchecked scan, 2-3.5x faster
    // than always paging.
    // ponytail: count and scan are two reads, not a snapshot — a concurrent add/remove between them shows as a
    // mismatch and takes the heal path (correct, just slower); a simultaneous add+remove could balance the
    // count, same class of read skew any non-transactional read here already has.
    private fun <T> cachedOutScan(
        nid: NodeId, type: String?,
        scan: (Predicate<EdgeKey, Any>) -> Collection<T>,
        heal: () -> Flow<T>,
    ): Flow<T> = flow {
        val edgeTag = type?.let { tagRegistry.edgeTagOf(it) }
        val fromPred = keyEq<EdgeKey, Any>("fromId", nid)
        val pred = if (type == null) fromPred else Predicates.and(fromPred, Predicates.equal<EdgeKey, Any>("__key.type", type))
        val part = Predicates.partitionPredicate<EdgeKey, Any>(partitionKey(nid), pred)
        val (scanned, indexed) = coroutineScope {
            val values = async(Dispatchers.IO) { scan(part) }
            val count = async { adjacency.count(nid, AdjacencyDirection.OUT, edgeTag) }
            values.await() to count.await()
        }
        when {
            indexed > 0 && scanned.size == indexed -> scanned.forEach { emit(it) }
            indexed > 0 || persistentStore != null -> emitAll(heal())
        }
    }

    // Ephemeral (TTL) out-edges are store-only (TODO 1.27): reliable only from ephemeralStore, since the
    // cache holds none. Empty when no ephemeral store is configured. nodeTypeTag is null (YCQL loadEdges
    // carries no neighbor-type JOIN) so typed filters fetch-fall-back. Expired entries are skipped.
    private fun ephemeralStoreHops(nid: NodeId, type: String?): Flow<Hop> = flow {
        val loaded = withContext(Dispatchers.IO) { ephemeralStore.loadEdges(nid).getOrNull() } ?: return@flow
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

    // Batched read-through (TODO 2.30), node-side twin of resolveEdges: one getAll for the whole set
    // instead of one getAsync per id, then ONE batched store read to heal whatever the cache missed
    // instead of a point-read per miss. Chunked at valueFetchBatch so peak heap and the store's bound
    // id-array stay bounded the same way the edge-value fetch already is.
    override suspend fun nodesAt(ids: Collection<NodeId>): Map<NodeId, NodeLike<*>> {
        if (ids.isEmpty()) return emptyMap()
        return buildMap {
            for (chunk in ids.toSet().chunked(valueFetchBatch)) {
                val cached = withContext(Dispatchers.IO) { nodesMap.getAll(chunk.toSet()) }
                putAll(cached)
                val missing = chunk.filterNot { it in cached }
                if (missing.isNotEmpty()) putAll(loadAndCacheNodes(missing))
            }
        }
    }

    override fun outAt(nid: NodeId, type: String?, needValue: Boolean, includeEphemeral: Boolean): Flow<Hop> {
        val persistent = outAtPersistent(nid, type, needValue)
        // Ephemeral edges are store-only (TODO 1.27): included only on explicit opt-in, from the store.
        return if (includeEphemeral) flow { emitAll(persistent); emitAll(ephemeralStoreHops(nid, type)) } else persistent
    }

    @Suppress("UNCHECKED_CAST")
    private fun outAtPersistent(nid: NodeId, type: String?, needValue: Boolean): Flow<Hop> =
        // Typed value hop: the checked partition scan (cachedOutScan). Before TODO 4.14 this was an unchecked
        // scan behind ensureOutWarm, which silently dropped evicted edges from the traversal. entrySet, not
        // values: the hop needs the key. Materialized-then-emitted: a partition entrySet can't be paged
        // (PagingPredicate doesn't compose with PartitionPredicate), and this path's consumers collect-all anyway.
        if (type != null && needValue) {
            val map = edgesMap as IMap<EdgeKey, Any>
            cachedOutScan(nid, type,
                scan = { pred -> map.entrySet(pred).map { Hop(it.key.fromId, it.key.toId, it.key.type, it.value as EdgeLike<*, *>) } },
                heal = { adjacencyHopFlow(nid, AdjacencyDirection.OUT, type, needValue = true, batch = valueFetchBatch) })
        }
        else adjacencyHopFlow(nid, AdjacencyDirection.OUT, type, needValue, valueFetchBatch)

    override fun inAt(nid: NodeId, type: String?, needValue: Boolean): Flow<Hop> =
        adjacencyHopFlow(nid, AdjacencyDirection.IN, type, needValue, valueFetchBatch)

    // Bounded, streamed hop flow off the adjacency index (self-healing from the store on a cold miss).
    // The index emits in bounded windows (see ShardedAdjacencyIndex); needValue batches the edge-value
    // getAll every [batch] hops (peak heap ~[batch] edges regardless of degree). needValue=false emits
    // key-only hops straight through, so short-circuiting consumers (.any/.firstOrNull) stop early.
    @Suppress("UNCHECKED_CAST")
    private fun adjacencyHopFlow(nid: NodeId, direction: AdjacencyDirection, type: String?, needValue: Boolean, batch: Int): Flow<Hop> = flow {
        val edgeTag = type?.let { tagRegistry.edgeTagOf(it) }
        // Read first, warm only when cold (TODO 4.14): no separate empty-probe round trip, so a warm hop is ONE
        // index read (was probe 1-2 + read windows 2). The direction is read unfiltered and filtered here, so a
        // warm node with no entries of THIS edge type isn't mistaken for cold and sent to the store every call.
        // Entries are consumed as they arrive; only a direction with no entries at all preloads and reads again.
        // ponytail: a node with genuinely zero edges in this direction is indistinguishable from
        // "never preloaded" (no shard entry to tell them apart) — it retries the store on every
        // call instead of caching "confirmed empty". Upgrade to a dedicated warm-marker key if a
        // hot zero-degree node's repeated store hits ever show up in profiling.
        suspend fun readEntries(consume: suspend (AdjacencyEntry) -> Unit) {
            var sawAny = false
            adjacency.read(nid, direction).collect { e -> sawAny = true; if (edgeTag == null || e.edgeTypeTag == edgeTag) consume(e) }
            if (sawAny) return
            if (direction == AdjacencyDirection.OUT) preloadOut(nid) else preloadIn(nid)
            adjacency.read(nid, direction, edgeTag).collect { consume(it) }
        }
        // nodeTypeTag is the neighbor's (== target's) type — carried so typed filters skip a fetch.
        fun hopOf(entry: AdjacencyEntry): Hop {
            val (fromId, toId) = if (direction == AdjacencyDirection.OUT) nid to entry.neighborId else entry.neighborId to nid
            return Hop(fromId, toId, tagRegistry.edgeNameOf(entry.edgeTypeTag), null, entry.nodeTypeTag)
        }
        if (!needValue) {
            readEntries { emit(hopOf(it)) }
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
        readEntries { entry ->
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

    // See allNodeIds() above — same full-materialization caveat and dispatcher fix apply here.
    override fun allNodeIdsRaw(): Flow<NodeId> = flow { withContext(Dispatchers.IO) { nodesMap.keys }.forEach { emit(it) } }

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

    // AbyssError variants that wrap a Throwable (store-layer failures); the rest are validation
    // errors with no underlying exception. Passed as a trailing, placeholder-unmatched arg to
    // log.error/log.warn, SLF4J attaches the full stack trace (incl. nested causes).
    private fun AbyssError.cause(): Throwable? = when (this) {
        is AbyssError.Unexpected -> cause
        is AbyssError.BatchPartiallyCommitted -> cause
        else -> null
    }

    suspend fun transaction(baseOps: List<NodeOp>, checkIntegrity: Boolean): Either<AbyssError, Unit> {
        val ops = expandCascades(baseOps)
        integrityError(ops, checkIntegrity)?.let { return it.left() }

        if (persistentStore != null) {
            val storeResult = persistentStore.transaction { ops.forEach { applyPersistentOp(it) } }
            if (storeResult.isLeft()) {
                val err = storeResult.leftOrNull()
                log.error("Store transaction failed; cache unchanged [nodes={}, edges={}, error={}]", nodesMapName, edgesMapName, err, err?.cause())
                return storeResult
            }
        }
        val deletes = ops.filter { it is NodeOp.RemoveNode || it is NodeOp.RemoveEdge }
        if (deletes.isNotEmpty()) {
            ephemeralStore.transaction { deletes.forEach { applyEphemeralOp(it) } }
                .onLeft { log.warn("Ephemeral delete fanout failed during transaction; stale ephemeral data possible [nodes={}, edges={}, error={}]", nodesMapName, edgesMapName, it, it.cause()) }
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
                val err = storeResult.leftOrNull()
                val committed = (err as? AbyssError.BatchPartiallyCommitted)?.committedOps ?: 0
                if (committed > 0) populateCache(ops.take(committed), "Cache update failed after partial batch store commit; cache may be stale")
                log.error("Batch transaction failed partway; {} of {} op(s) committed and cache-synced before the failure [nodes={}, edges={}, error={}]", committed, ops.size, nodesMapName, edgesMapName, err, err?.cause())
                return storeResult
            }
        }
        val deletes = ops.filter { it is NodeOp.RemoveNode || it is NodeOp.RemoveEdge }
        if (deletes.isNotEmpty()) {
            ephemeralStore.transaction { deletes.forEach { applyEphemeralOp(it) } }
                .onLeft { log.warn("Ephemeral delete fanout failed during batch transaction; stale ephemeral data possible [nodes={}, edges={}, error={}]", nodesMapName, edgesMapName, it, it.cause()) }
        }

        populateCache(ops, "Cache update failed after batch store commit; cache may be stale")
        log.debug("Batch transaction committed [{} op(s), batchSize={}, nodes={}, edges={}]", ops.size, batchSize, nodesMapName, edgesMapName)
        return Unit.right()
    }

    suspend fun ephemeral(baseOps: List<NodeOp>, checkIntegrity: Boolean): Either<AbyssError, Unit> {
        val ops = expandCascades(baseOps)
        integrityError(ops, checkIntegrity)?.let { return it.left() }

        val storeResult = ephemeralStore.transaction { ops.forEach { applyEphemeralOp(it) } }
        if (storeResult.isLeft()) {
            val err = storeResult.leftOrNull()
            log.error("Ephemeral store commit failed; cache unchanged [nodes={}, edges={}, error={}]", nodesMapName, edgesMapName, err, err?.cause())
            return storeResult
        }
        val deletes = ops.filter { it is NodeOp.RemoveNode || it is NodeOp.RemoveEdge }
        if (persistentStore != null && deletes.isNotEmpty()) {
            persistentStore.transaction { deletes.forEach { applyPersistentOp(it) } }
                .onLeft { log.warn("Persistent delete fanout failed during ephemeral commit; stale persistent data possible [nodes={}, edges={}, error={}]", nodesMapName, edgesMapName, it, it.cause()) }
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

    // Both directions come from the adjacency index, key-only: a cascade needs (from, to, type), never the
    // values. The index is authoritative and never evicted (TODO 1.27), and a cold node self-heals (an empty
    // index side preloads from the store). TODO 4.14: the OUT side used to scan edgesMap behind a warm probe —
    // under partial value eviction the probe saw a non-empty index, the scan missed the evicted edges, and
    // with no DeleteEdge for them (store deleteNode removes only the node row) they stayed in the store and
    // the index, healing back as edges of a deleted node.
    // Schema-agnostic by design: an edge lives in these same shared maps and this same store
    // regardless of whether its other endpoint shares nid's schema tag, so cascade removes it either
    // way — a cross-schema edge left dangling after its endpoint is deleted is exactly the bug this
    // used to have (a since-removed `sameSchema(...)` filter excluded cross-schema edges here).
    private suspend fun cascadeEdgeRemovals(nid: NodeId): List<NodeOp.RemoveEdge> {
        val out = outAt(nid, type = null, needValue = false).toList()
            .map { NodeOp.RemoveEdge(it.fromId, it.toId, it.type) }
        // ponytail: ephemeral (TTL) edges are store-only since TODO 1.27 — not in edgesMap or the index — so
        // this cascade can't see them in either direction; they expire via TTL (unchanged by TODO 4.14).
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
            val fromEphemeral  = async(Dispatchers.IO) { ephemeralStore.loadNode(nid).getOrNull() }
            val p = fromPersistent.await()
            if (p?.first != null) p else fromEphemeral.await()
        } ?: return null
        node ?: return null
        if (remaining != null && remaining.inWholeSeconds <= 0) return null
        withContext(Dispatchers.IO) {
            if (remaining == null) nodesMap.set(nid, node)
            else nodesMap.set(nid, node, remaining.inWholeSeconds, TimeUnit.SECONDS)
        }
        return node
    }

    // Batched loadAndCacheNode: same persistent-wins-over-ephemeral merge, same expiry check, same
    // TTL-preserving write-back — one store round trip per side instead of one per id. Kept separate
    // from loadAndCacheNode rather than folded into it: the single-id path is the hot point-read and
    // doesn't deserve the collection allocations.
    private suspend fun loadAndCacheNodes(nids: List<NodeId>): Map<NodeId, NodeLike<*>> {
        val (persistent, ephemeral) = coroutineScope {
            val p = async(Dispatchers.IO) { persistentStore?.loadNodes(nids)?.getOrNull() ?: emptyMap() }
            val e = async(Dispatchers.IO) { ephemeralStore.loadNodes(nids).getOrNull() ?: emptyMap() }
            p.await() to e.await()
        }
        return buildMap {
            for (nid in nids) {
                val p = persistent[nid]
                val (node, remaining) = (if (p?.first != null) p else ephemeral[nid]) ?: continue
                node ?: continue
                if (remaining != null && remaining.inWholeSeconds <= 0) continue
                withContext(Dispatchers.IO) {
                    if (remaining == null) nodesMap.set(nid, node)
                    else nodesMap.set(nid, node, remaining.inWholeSeconds, TimeUnit.SECONDS)
                }
                put(nid, node)
            }
        }
    }

    private suspend fun loadAndCacheEdge(fromNid: NodeId, toNid: NodeId, type: String): EdgeLike<*, *>? {
        val (edge, remaining) = coroutineScope {
            val fromPersistent = async(Dispatchers.IO) { persistentStore?.loadEdge(fromNid, toNid, type)?.getOrNull() }
            val fromEphemeral  = async(Dispatchers.IO) { ephemeralStore.loadEdge(fromNid, toNid, type).getOrNull() }
            val p = fromPersistent.await()
            if (p?.first != null) p else fromEphemeral.await()
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
