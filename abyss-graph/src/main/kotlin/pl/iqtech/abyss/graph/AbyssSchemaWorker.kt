package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.hazelcast.query.Predicate
import com.hazelcast.query.Predicates
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import org.slf4j.LoggerFactory
import pl.iqtech.abyss.dsl.EdgeKey
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
import kotlin.reflect.full.findAnnotation
import kotlin.time.Duration

// A NodeId-level unit of work. The typed facade converts its domain ops into these (ids -> NodeId via
// its adapter) before handing them to the worker.
internal sealed interface NodeOp {
    val ttl: Duration?
    data class AddNode(val id: NodeId, val node: NodeLike<*>, override val ttl: Duration?) : NodeOp
    data class RemoveNode(val id: NodeId) : NodeOp { override val ttl: Duration? get() = null }
    data class AddEdge(val fromId: NodeId, val toId: NodeId, val edge: EdgeLike<*, *>, override val ttl: Duration?) : NodeOp
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
    private val persistentStore: AbyssStoreLike? = null,
    private val ephemeralStore: AbyssEphemeralStoreLike? = null,
    private val asyncCachePopulation: Boolean = false,
    private val resolution: SchemaResolution,
) : NodeIdEngine {

    private val log = LoggerFactory.getLogger(AbyssSchemaWorker::class.java)

    private val nodesMap: IMap<NodeId, NodeLike<*>> = hazelcast.getMap(nodesMapName)
    private val edgesMap: IMap<EdgeKey, EdgeLike<*, *>> = hazelcast.getMap(edgesMapName)
    private val reverseEdgesMap: IMap<ReverseEdgeKey, Unit> = hazelcast.getMap("$edgesMapName-reverse")

    private fun edgeAdapterOf(nid: NodeId) = resolution.edgeAdapterOf(nid)

    private fun edgeKey(fromNid: NodeId, toNid: NodeId, type: String) =
        EdgeKey(fromNid, toNid, type, edgeAdapterOf(fromNid).partitionKey(fromNid))

    private fun revKey(toNid: NodeId, fromNid: NodeId, type: String) =
        ReverseEdgeKey(toNid, fromNid, type, edgeAdapterOf(toNid).partitionKey(toNid))

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

    fun outEdges(nid: NodeId): Flow<EdgeLike<*, *>> =
        outEdgeFlow(nid, keyEq<EdgeKey, EdgeLike<*, *>>("fromId", nid))

    fun outEdges(nid: NodeId, type: String): Flow<EdgeLike<*, *>> =
        outEdgeFlow(nid, Predicates.and(keyEq<EdgeKey, EdgeLike<*, *>>("fromId", nid), Predicates.equal<EdgeKey, EdgeLike<*, *>>("__key.type", type)))

    fun inEdges(nid: NodeId, type: String? = null): Flow<EdgeLike<*, *>> = inEdgeFlow(nid, type)

    private fun edgeType(edge: EdgeLike<*, *>): String =
        edge::class.findAnnotation<SerialName>()?.value ?: error("${edge::class} missing @SerialName")

    // --- NodeIdEngine (endpoints as NodeId, so a walk can span schemas over the shared edge map) ----

    override suspend fun nodeAt(nid: NodeId): NodeLike<*>? = readNode(nid)

    @Suppress("UNCHECKED_CAST")
    override suspend fun outAt(nid: NodeId, type: String?, needValue: Boolean): List<Hop> {
        preloadOut(nid)
        val base = keyEq<EdgeKey, Any>("fromId", nid)
        val pred = if (type == null) base else Predicates.and(base, Predicates.equal<EdgeKey, Any>("__key.type", type))
        val part = Predicates.partitionPredicate<EdgeKey, Any>(partitionKey(nid), pred)
        val map = edgesMap as IMap<EdgeKey, Any>
        if (!needValue) return withContext(Dispatchers.IO) { map.keySet(part) }.map { Hop(it.fromId, it.toId, it.type, null) }
        return withContext(Dispatchers.IO) { map.entrySet(part) }.map { Hop(it.key.fromId, it.key.toId, it.key.type, it.value as EdgeLike<*, *>) }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun inAt(nid: NodeId, type: String?, needValue: Boolean): List<Hop> {
        preloadIn(nid)
        val revKeys = withContext(Dispatchers.IO) {
            reverseEdgesMap.keySet(Predicates.partitionPredicate<ReverseEdgeKey, Unit>(
                partitionKey(nid), keyEq<ReverseEdgeKey, Unit>("toId", nid)
            ))
        }
        val filtered = if (type != null) revKeys.filter { it.type == type } else revKeys
        if (!needValue) return filtered.map { Hop(it.fromId, it.toId, it.type, null) }
        val keys = filtered.map { edgeKey(it.fromId, it.toId, it.type) }.toSet()
        if (keys.isEmpty()) return emptyList()
        val map = edgesMap as IMap<EdgeKey, Any>
        return withContext(Dispatchers.IO) { map.getAll(keys) }.entries.map { Hop(it.key.fromId, it.key.toId, it.key.type, it.value as EdgeLike<*, *>) }
    }

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

    private fun outEdgeFlow(nid: NodeId, predicate: Predicate<EdgeKey, EdgeLike<*, *>>): Flow<EdgeLike<*, *>> = flow {
        preloadOut(nid)
        val partitioned = Predicates.partitionPredicate<EdgeKey, EdgeLike<*, *>>(partitionKey(nid), predicate)
        withContext(Dispatchers.IO) { edgesMap.values(partitioned) }.forEach { emit(it) }
    }

    private fun inEdgeFlow(nid: NodeId, typeFilter: String? = null): Flow<EdgeLike<*, *>> = flow {
        preloadIn(nid)
        val revKeys = withContext(Dispatchers.IO) {
            reverseEdgesMap.keySet(Predicates.partitionPredicate<ReverseEdgeKey, Unit>(
                partitionKey(nid), keyEq<ReverseEdgeKey, Unit>("toId", nid)
            ))
        }
        val filtered = if (typeFilter != null) revKeys.filter { it.type == typeFilter } else revKeys
        val edgeKeys = filtered.map { edgeKey(it.fromId, it.toId, it.type) }.toSet()
        if (edgeKeys.isNotEmpty()) {
            withContext(Dispatchers.IO) { edgesMap.getAll(edgeKeys) }.values.forEach { emit(it) }
        }
    }

    // Store -> cache preload for a node's outgoing edges (self-healing on cache miss). The store
    // returns both endpoint NodeIds (from its PK columns), so the cache key rebuilds untyped.
    private suspend fun preloadOut(nid: NodeId) = withContext(Dispatchers.IO) {
        persistentStore?.loadEdges(nid)?.getOrNull()?.forEach { e ->
            edgesMap.putIfAbsent(edgeKey(e.fromId, e.toId, edgeType(e.edge)), e.edge)
        }
        ephemeralStore?.loadEdges(nid)?.getOrNull()?.forEach { e ->
            val key = edgeKey(e.fromId, e.toId, edgeType(e.edge))
            val remaining = e.remaining
            when {
                remaining == null -> edgesMap.putIfAbsent(key, e.edge)
                remaining.inWholeSeconds > 0 -> edgesMap.putIfAbsent(key, e.edge, remaining.inWholeSeconds, TimeUnit.SECONDS)
                // remaining <= 0: expired — skip
            }
        }
    }

    // Only persistent edges have a reverse index to warm (ephemeral edges are outgoing-only, TODO 1.13).
    private suspend fun preloadIn(nid: NodeId) = withContext(Dispatchers.IO) {
        persistentStore?.loadInEdges(nid)?.getOrNull()?.forEach { e ->
            val type = edgeType(e.edge)
            edgesMap.putIfAbsent(edgeKey(e.fromId, e.toId, type), e.edge)
            reverseEdgesMap.putIfAbsent(revKey(e.toId, e.fromId, type), Unit)
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

    private fun sameSchema(a: NodeId, b: NodeId): Boolean = resolution.sameSchema(a, b)

    // TODO 1.20 fix: preloadOut/preloadIn warm the cache from the store first (same self-heal
    // preloadOut/preloadIn already give outAt/inAt), so a cold cache after a restart or partition
    // eviction can't make this scan silently miss a node's durable edges and leave them dangling.
    private suspend fun cascadeEdgeRemovals(nid: NodeId): List<NodeOp.RemoveEdge> {
        preloadOut(nid)
        preloadIn(nid)
        val pk = partitionKey(nid)
        val out = withContext(Dispatchers.IO) {
            edgesMap.entrySet(Predicates.partitionPredicate(pk, keyEq<EdgeKey, EdgeLike<*, *>>("fromId", nid)))
        }.filter { sameSchema(it.key.toId, nid) }.map { NodeOp.RemoveEdge(it.key.fromId, it.key.toId, it.key.type) }
        // ponytail: ephemeral edges are outgoing-only (TODO 1.13) — no reverse index, so deleting the
        // TO-node can't cascade them; they expire via TTL. Deleting the FROM-node still cascades (out).
        val inc = withContext(Dispatchers.IO) {
            reverseEdgesMap.keySet(Predicates.partitionPredicate<ReverseEdgeKey, Unit>(pk, keyEq<ReverseEdgeKey, Unit>("toId", nid)))
        }.filter { sameSchema(it.fromId, nid) }.map { NodeOp.RemoveEdge(it.fromId, it.toId, it.type) }
        return (out + inc).distinctBy { Triple(it.fromId, it.toId, it.type) }
    }

    private fun AbyssStoreTransactionLike.applyPersistentOp(op: NodeOp) = when (op) {
        is NodeOp.AddNode    -> saveNode(op.id, op.node)
        is NodeOp.RemoveNode -> deleteNode(op.id)
        is NodeOp.AddEdge    -> saveEdge(op.fromId, op.toId, op.edge)
        is NodeOp.RemoveEdge -> deleteEdge(op.fromId, op.toId, op.type)
    }

    private fun AbyssEphemeralStoreTransactionLike.applyEphemeralOp(op: NodeOp) = when (op) {
        is NodeOp.AddNode    -> saveNode(op.id, op.node, op.ttl!!)
        is NodeOp.RemoveNode -> deleteNode(op.id)
        is NodeOp.AddEdge    -> saveEdge(op.fromId, op.toId, op.edge, op.ttl!!)
        is NodeOp.RemoveEdge -> deleteEdge(op.fromId, op.toId, op.type)
    }

    private suspend fun populateCache(ops: List<NodeOp>, warnMsg: String) {
        val stages = ops.flatMap { applyToCacheAsync(it) }
        if (asyncCachePopulation) {
            // ponytail: fire-and-forget — no thread pinned during network wait
            stages.forEach { it.exceptionally { ex -> log.warn(warnMsg, ex); null } }
        } else {
            Either.catch { stages.map { it.asDeferred() }.awaitAll() }
                .fold(ifLeft = { log.warn(warnMsg, it) }, ifRight = {})
        }
    }

    private fun applyToCacheAsync(op: NodeOp): List<CompletionStage<*>> = when (op) {
        is NodeOp.AddNode ->
            if (op.ttl != null) listOf(nodesMap.setAsync(op.id, op.node, op.ttl.inWholeSeconds, TimeUnit.SECONDS))
            else listOf(nodesMap.setAsync(op.id, op.node))
        is NodeOp.RemoveNode -> listOf(nodesMap.removeAsync(op.id))
        is NodeOp.AddEdge -> {
            val type   = edgeType(op.edge)
            val key    = edgeKey(op.fromId, op.toId, type)
            val revKey = revKey(op.toId, op.fromId, type)
            // Ephemeral (TTL) edges are outgoing-only (TODO 1.13): no reverse index, matching the store.
            if (op.ttl != null) listOf(edgesMap.setAsync(key, op.edge, op.ttl.inWholeSeconds, TimeUnit.SECONDS))
            else listOf(edgesMap.setAsync(key, op.edge), reverseEdgesMap.setAsync(revKey, Unit))
        }
        is NodeOp.RemoveEdge -> listOf(
            edgesMap.removeAsync(edgeKey(op.fromId, op.toId, op.type)),
            reverseEdgesMap.removeAsync(revKey(op.toId, op.fromId, op.type))
        )
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
        val key = edgeKey(fromNid, toNid, type)
        withContext(Dispatchers.IO) {
            if (remaining == null) edgesMap.set(key, edge)
            else edgesMap.set(key, edge, remaining.inWholeSeconds, TimeUnit.SECONDS)
        }
        return edge
    }

    private fun schemaCheck(edge: EdgeLike<*, *>, from: NodeLike<*>, to: NodeLike<*>): AbyssError? {
        val c = edge::class.findAnnotation<EdgeConstraint>() ?: return null
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
