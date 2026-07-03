package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import com.hazelcast.config.Config
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.hazelcast.query.Predicate
import com.hazelcast.query.Predicates
import com.hazelcast.config.SerializerConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletionStage
import kotlinx.serialization.SerialName
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import org.slf4j.LoggerFactory
import pl.iqtech.abyss.dsl.AbyssEngineLike
import pl.iqtech.abyss.dsl.AbyssEphemeralTransactionLike
import pl.iqtech.abyss.dsl.AbyssTransactionLike
import pl.iqtech.abyss.graph.serialization.UnknownNode
import pl.iqtech.abyss.store.api.EdgeConstraint
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.TraversalBuilderLike
import pl.iqtech.abyss.graph.traversal.TraversalBuilder
import pl.iqtech.abyss.graph.serialization.EdgeKeySerializer
import pl.iqtech.abyss.graph.serialization.EdgeLikeHzSerializer
import pl.iqtech.abyss.graph.serialization.NodeIdSerializer
import pl.iqtech.abyss.graph.serialization.NodeLikeHzSerializer
import pl.iqtech.abyss.graph.serialization.ReverseEdgeKeySerializer
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreLike
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreTransactionLike
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeAdapter
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.SchemaEdgeLike
import pl.iqtech.abyss.store.api.KeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeKeyEncoding
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.SchemaTagWidth
import java.util.concurrent.TimeUnit
import kotlin.reflect.full.findAnnotation
import kotlin.time.Duration

private val log = LoggerFactory.getLogger(AbyssGraphSchema::class.java)

class SchemaDescriptor<SID, KID>(
    val keyAdapter: KeyAdapter<KID>,
    val schemaTagWidth: SchemaTagWidth,
    val schemaTag: SID
)


class AbyssGraphSchema<ID>(
    private val adapter: KeyAdapter<ID>,
    private val hazelcast: HazelcastInstance,
    val nodesMapName: String,
    val edgesMapName: String,
    val persistentStore: AbyssStoreLike<ID>? = null,
    val ephemeralStore: AbyssEphemeralStoreLike<ID>? = null,
    private val asyncCachePopulation: Boolean = false
) : AbyssEngineLike<ID>, NodeIdEngine {

    // Engine a traversal from this schema runs against: the owning container (so a walk can cross
    // schemas over the shared edge map) when registered in one, else this schema itself.
    internal var traversalEngine: NodeIdEngine = this

    // Hazelcast IMap keys are NodeId (compact-serialized ByteArray wrapper).
    // All public methods accept domain ID and convert at the boundary via adapter.
    private val nodesMap: IMap<NodeId, NodeLike<ID>>
    private val edgesMap: IMap<EdgeKey, SchemaEdgeLike<ID>>
    private val reverseEdgesMap: IMap<ReverseEdgeKey, Unit>
    init {
        nodesMap = hazelcast.getMap(nodesMapName)
        edgesMap = hazelcast.getMap(edgesMapName)
        reverseEdgesMap = hazelcast.getMap("${edgesMapName}-reverse")
        log.info("AbyssGraphSchema started [nodes={}, edges={}, persistentStore={}, ephemeralStore={}]",
            nodesMapName, edgesMapName,
            persistentStore?.javaClass?.simpleName ?: "none",
            ephemeralStore?.javaClass?.simpleName ?: "none")
    }

    override fun allNodeIds(): Flow<ID> = flow {
        nodesMap.keys.forEach { emit(adapter.fromNodeId(it)) }
    }

    override suspend fun node(id: ID): Either<AbyssError, NodeLike<ID>> =
        Either.catch { nodesMap.getAsync(adapter.toNodeId(id)).asDeferred().await() ?: loadAndCacheNode(id) }
            .mapLeft { AbyssError.Unexpected(it) }
            .flatMap { it?.right() ?: AbyssError.NodeNotFound(id as Any).left() }

    override suspend fun edge(fromId: ID, toId: ID, type: String): Either<AbyssError, SchemaEdgeLike<ID>> =
        Either.catch { edgesMap.getAsync(edgeKey(adapter.toNodeId(fromId), adapter.toNodeId(toId), type)).asDeferred().await() ?: loadAndCacheEdge(fromId, toId, type) }
            .mapLeft { AbyssError.Unexpected(it) }
            .flatMap { it?.right() ?: AbyssError.EdgeNotFound(fromId as Any, toId as Any, type).left() }

    override suspend fun nodeExists(id: ID): Either<AbyssError, Boolean> =
        Either.catch { nodesMap.getAsync(adapter.toNodeId(id)).asDeferred().await() != null || loadAndCacheNode(id) != null }
            .mapLeft { AbyssError.Unexpected(it) }

    override suspend fun edgeExists(fromId: ID, toId: ID, type: String): Either<AbyssError, Boolean> =
        Either.catch { edgesMap.getAsync(edgeKey(adapter.toNodeId(fromId), adapter.toNodeId(toId), type)).asDeferred().await() != null || loadAndCacheEdge(fromId, toId, type) != null }
            .mapLeft { AbyssError.Unexpected(it) }

    override fun outEdges(nodeId: ID, pageSize: Int): Flow<SchemaEdgeLike<ID>> {
        val nid = adapter.toNodeId(nodeId)
        return outEdgeFlow(nodeId, nid, keyEq<EdgeKey, SchemaEdgeLike<ID>>("fromId", nid))
    }

    override fun outEdges(nodeId: ID, type: String, pageSize: Int): Flow<SchemaEdgeLike<ID>> {
        val nid = adapter.toNodeId(nodeId)
        return outEdgeFlow(nodeId, nid, Predicates.and(keyEq<EdgeKey, SchemaEdgeLike<ID>>("fromId", nid), Predicates.equal<EdgeKey, SchemaEdgeLike<ID>>("__key.type", type)))
    }

    override fun inEdges(nodeId: ID, pageSize: Int): Flow<SchemaEdgeLike<ID>> = inEdgeFlow(nodeId)

    override fun inEdges(nodeId: ID, type: String, pageSize: Int): Flow<SchemaEdgeLike<ID>> = inEdgeFlow(nodeId, type)

    private fun <K, V> keyEq(field: String, nid: NodeId): Predicate<K, V> = nativeKeyEq(field, adapter.encodeKey(nid))

    private fun edgeKey(fromNid: NodeId, toNid: NodeId, type: String) =
        EdgeKey(fromNid, toNid, type, adapter.partitionKey(fromNid))

    private fun revKey(toNid: NodeId, fromNid: NodeId, type: String) =
        ReverseEdgeKey(toNid, fromNid, type, adapter.partitionKey(toNid))

    private val edgeOrder = Comparator<Map.Entry<EdgeKey, SchemaEdgeLike<ID>>> { a, b ->
        compareValuesBy(a.key, b.key, { it.fromId.toString() }, { it.toId.toString() }, { it.type })
    }

    // Store -> cache preload for a node's outgoing edges (self-healing on cache miss).
    private suspend fun preloadOut(nodeId: ID) = withContext(Dispatchers.IO) {
        persistentStore?.loadEdges(nodeId)?.getOrNull()?.forEach { (edge, _) ->
            edgesMap.putIfAbsent(edgeKey(adapter.toNodeId(edge.fromId), adapter.toNodeId(edge.toId), edgeType(edge)), edge)
        }
        ephemeralStore?.loadEdges(nodeId)?.getOrNull()?.forEach { (edge, remaining) ->
            val key = edgeKey(adapter.toNodeId(edge.fromId), adapter.toNodeId(edge.toId), edgeType(edge))
            when {
                remaining == null -> edgesMap.putIfAbsent(key, edge)
                remaining.inWholeSeconds > 0 -> edgesMap.putIfAbsent(key, edge, remaining.inWholeSeconds, TimeUnit.SECONDS)
                // remaining <= 0: expired — skip
            }
        }
    }

    // Only persistent edges have a reverse index to warm. Ephemeral edges are outgoing-only
    // (TODO 1.13) — no reverse rows exist to load, so incoming lookups never see them.
    private suspend fun preloadIn(nodeId: ID) = withContext(Dispatchers.IO) {
        persistentStore?.loadInEdges(nodeId)?.getOrNull()?.forEach { (edge, _) ->
            val type = edgeType(edge)
            edgesMap.putIfAbsent(edgeKey(adapter.toNodeId(edge.fromId), adapter.toNodeId(edge.toId), type), edge)
            reverseEdgesMap.putIfAbsent(revKey(adapter.toNodeId(edge.toId), adapter.toNodeId(edge.fromId), type), Unit)
        }
    }

    private fun outEdgeFlow(nodeId: ID, nid: NodeId, predicate: Predicate<EdgeKey, SchemaEdgeLike<ID>>): Flow<SchemaEdgeLike<ID>> = flow {
        preloadOut(nodeId)
        val partitioned = Predicates.partitionPredicate<EdgeKey, SchemaEdgeLike<ID>>(adapter.partitionKey(nid), predicate)
        withContext(Dispatchers.IO) { edgesMap.values(partitioned) }.forEach { emit(it) }
    }

    private fun inEdgeFlow(nodeId: ID, typeFilter: String? = null): Flow<SchemaEdgeLike<ID>> = flow {
        val nid = adapter.toNodeId(nodeId)
        preloadIn(nodeId)
        val revKeys = withContext(Dispatchers.IO) {
            reverseEdgesMap.keySet(Predicates.partitionPredicate<ReverseEdgeKey, Unit>(
                adapter.partitionKey(nid), keyEq<ReverseEdgeKey, Unit>("toId", nid)
            ))
        }
        val filtered = if (typeFilter != null) revKeys.filter { it.type == typeFilter } else revKeys
        val edgeKeys = filtered.map { edgeKey(it.fromId, it.toId, it.type) }.toSet()
        if (edgeKeys.isNotEmpty()) {
            withContext(Dispatchers.IO) { edgesMap.getAll(edgeKeys) }.values.forEach { emit(it) }
        }
    }

    // --- NodeIdEngine: NodeId-level views the traversal engine drives (endpoints from EdgeKey) -----

    override suspend fun nodeAt(nid: NodeId): NodeLike<*>? = node(adapter.fromNodeId(nid)).getOrNull()

    @Suppress("UNCHECKED_CAST")
    override suspend fun outAt(nid: NodeId, type: String?, needValue: Boolean): List<Hop> {
        preloadOut(adapter.fromNodeId(nid))
        val base = keyEq<EdgeKey, Any>("fromId", nid)
        val pred = if (type == null) base else Predicates.and(base, Predicates.equal<EdgeKey, Any>("__key.type", type))
        val part = Predicates.partitionPredicate<EdgeKey, Any>(adapter.partitionKey(nid), pred)
        val map = edgesMap as IMap<EdgeKey, Any>
        if (!needValue) return withContext(Dispatchers.IO) { map.keySet(part) }.map { Hop(it.fromId, it.toId, it.type, null) }
        return withContext(Dispatchers.IO) { map.entrySet(part) }.map { Hop(it.key.fromId, it.key.toId, it.key.type, it.value as EdgeLike<*, *>) }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun inAt(nid: NodeId, type: String?, needValue: Boolean): List<Hop> {
        preloadIn(adapter.fromNodeId(nid))
        val revKeys = withContext(Dispatchers.IO) {
            reverseEdgesMap.keySet(Predicates.partitionPredicate<ReverseEdgeKey, Unit>(
                adapter.partitionKey(nid), keyEq<ReverseEdgeKey, Unit>("toId", nid)
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

    override suspend fun <T> from(nodeId: ID, block: suspend TraversalBuilderLike<ID>.() -> T): Either<AbyssError, T> =
        Either.catch { TraversalBuilder(traversalEngine, setOf(adapter.toNodeId(nodeId)), adapter).block() }
            .mapLeft { AbyssError.Unexpected(it) }

    override suspend fun ephemeral(ttl: Duration, checkIntegrity: Boolean, block: suspend AbyssEphemeralTransactionLike<ID>.() -> Unit): Either<AbyssError, Unit> {
        val buffer = BufferedEphemeralTransaction<ID>(
            ttl = ttl,
            readNode = { id -> nodesMap.getAsync(adapter.toNodeId(id)).asDeferred().await() ?: loadAndCacheNode(id) },
            readEdge = { f, t, type -> edgesMap.getAsync(edgeKey(adapter.toNodeId(f), adapter.toNodeId(t), type)).asDeferred().await() ?: loadAndCacheEdge(f, t, type) }
        )
        try { buffer.block() } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }

        val ops = withContext(Dispatchers.IO) {
            buffer.ops.flatMap { op ->
                @Suppress("UNCHECKED_CAST")
                if (op is Op.RemoveNode) listOf(op) + cascadeEdgeRemovals(op.id as ID)
                else listOf(op)
            }
        }

        if (checkIntegrity) {
            val addedInTx = ops.filterIsInstance<Op.AddNode>().associate { it.node.id to it.node }
            val error = withContext(Dispatchers.IO) {
                @Suppress("UNCHECKED_CAST")
                ops.filterIsInstance<Op.AddEdge>().firstNotNullOfOrNull { addOp ->
                    val edge = addOp.edge as SchemaEdgeLike<ID>
                    val fromNode = addedInTx[edge.fromId] ?: nodesMap[adapter.toNodeId(edge.fromId)]
                    val toNode   = addedInTx[edge.toId]   ?: nodesMap[adapter.toNodeId(edge.toId)]
                    when {
                        fromNode == null -> AbyssError.IntegrityError("Node ${edge.fromId} (fromId) not found")
                        toNode   == null -> AbyssError.IntegrityError("Node ${edge.toId} (toId) not found")
                        else             -> schemaCheck(addOp.edge, fromNode, toNode)
                    }
                }
            }
            if (error != null) return error.left()
        }

        if (ephemeralStore != null) {
            val storeResult = ephemeralStore.transaction { ops.forEach { applyEphemeralOp(it) } }
            if (storeResult.isLeft()) {
                log.error("Ephemeral store commit failed; cache unchanged [nodes={}, edges={}]", nodesMapName, edgesMapName)
                return storeResult
            }
        }
        val deletes = ops.filter { it is Op.RemoveNode || it is Op.RemoveEdge }
        if (persistentStore != null && deletes.isNotEmpty()) {
            persistentStore.transaction { deletes.forEach { applyPersistentOp(it) } }
                .onLeft { log.warn("Persistent delete fanout failed during ephemeral commit; stale persistent data possible [nodes={}, edges={}]", nodesMapName, edgesMapName) }
        }

        populateCache(ops, "Cache update failed after ephemeral store commit; cache may be stale")

        log.debug("Ephemeral committed [{} op(s), ttl={}, nodes={}, edges={}]", ops.size, ttl, nodesMapName, edgesMapName)
        return Unit.right()
    }

    override suspend fun transaction(checkIntegrity: Boolean, block: suspend AbyssTransactionLike<ID>.() -> Unit): Either<AbyssError, Unit> {
        val buffer = BufferedTransaction<ID>(
            readNode = { id -> nodesMap.getAsync(adapter.toNodeId(id)).asDeferred().await() ?: loadAndCacheNode(id) },
            readEdge = { f, t, type -> edgesMap.getAsync(edgeKey(adapter.toNodeId(f), adapter.toNodeId(t), type)).asDeferred().await() ?: loadAndCacheEdge(f, t, type) }
        )
        try { buffer.block() } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }

        val ops = withContext(Dispatchers.IO) {
            buffer.ops.flatMap { op ->
                @Suppress("UNCHECKED_CAST")
                if (op is Op.RemoveNode) listOf(op) + cascadeEdgeRemovals(op.id as ID)
                else listOf(op)
            }
        }

        if (checkIntegrity) {
            val addedInTx = ops.filterIsInstance<Op.AddNode>().associate { it.node.id to it.node }
            val error = withContext(Dispatchers.IO) {
                @Suppress("UNCHECKED_CAST")
                ops.filterIsInstance<Op.AddEdge>().firstNotNullOfOrNull { addOp ->
                    val edge = addOp.edge as SchemaEdgeLike<ID>
                    val fromNode = addedInTx[edge.fromId] ?: nodesMap[adapter.toNodeId(edge.fromId)]
                    val toNode   = addedInTx[edge.toId]   ?: nodesMap[adapter.toNodeId(edge.toId)]
                    when {
                        fromNode == null -> AbyssError.IntegrityError("Node ${edge.fromId} (fromId) not found")
                        toNode   == null -> AbyssError.IntegrityError("Node ${edge.toId} (toId) not found")
                        else             -> schemaCheck(addOp.edge, fromNode, toNode)
                    }
                }
            }
            if (error != null) return error.left()
        }

        if (persistentStore != null) {
            val storeResult = persistentStore.transaction { ops.forEach { applyPersistentOp(it) } }
            if (storeResult.isLeft()) {
                log.error("Store transaction failed; cache unchanged [nodes={}, edges={}]", nodesMapName, edgesMapName)
                return storeResult
            }
        }
        val deletes = ops.filter { it is Op.RemoveNode || it is Op.RemoveEdge }
        if (ephemeralStore != null && deletes.isNotEmpty()) {
            ephemeralStore.transaction { deletes.forEach { applyEphemeralOp(it) } }
                .onLeft { log.warn("Ephemeral delete fanout failed during transaction; stale ephemeral data possible [nodes={}, edges={}]", nodesMapName, edgesMapName) }
        }

        // Cache failure after a successful store commit is logged but not propagated: the store is
        // the source of truth and the cache self-heals on the next miss.
        populateCache(ops, "Cache update failed after store commit; cache may be stale")

        log.debug("Transaction committed [{} op(s), nodes={}, edges={}]", ops.size, nodesMapName, edgesMapName)
        return Unit.right()
    }

    // True when a NodeId belongs to this schema (round-trips through the adapter). Cross-schema edges
    // in the shared map have a foreign opposite endpoint; cascade skips them (they're cache-only).
    private fun isHomeNode(x: NodeId): Boolean = runCatching { adapter.toNodeId(adapter.fromNodeId(x)) == x }.getOrDefault(false)

    private fun cascadeEdgeRemovals(nodeId: ID): List<Op.RemoveEdge> {
        val nid = adapter.toNodeId(nodeId)
        val pk  = adapter.partitionKey(nid)
        val out = edgesMap.entrySet(Predicates.partitionPredicate(pk, keyEq<EdgeKey, SchemaEdgeLike<ID>>("fromId", nid)))
            .filter { isHomeNode(it.key.toId) }
            .map { Op.RemoveEdge(adapter.fromNodeId(it.key.fromId), adapter.fromNodeId(it.key.toId), it.key.type) }
        // ponytail: ephemeral edges are outgoing-only (TODO 1.13) — no reverse index, so deleting the
        // TO-node can't cascade them; they expire via TTL. Deleting the FROM-node still cascades (out).
        val inc = reverseEdgesMap.keySet(Predicates.partitionPredicate<ReverseEdgeKey, Unit>(
            pk, keyEq<ReverseEdgeKey, Unit>("toId", nid)
        )).filter { isHomeNode(it.fromId) }.map { Op.RemoveEdge(adapter.fromNodeId(it.fromId), adapter.fromNodeId(it.toId), it.type) }
        return (out + inc).distinctBy { Triple(it.fromId, it.toId, it.type) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun AbyssStoreTransactionLike<ID>.applyPersistentOp(op: Op) = when (op) {
        is Op.AddNode    -> saveNode(op.node as NodeLike<ID>)
        is Op.RemoveNode -> deleteNode(op.id as ID)
        is Op.AddEdge    -> saveEdge(op.edge as SchemaEdgeLike<ID>)
        is Op.RemoveEdge -> deleteEdge(op.fromId as ID, op.toId as ID, op.type)
    }

    @Suppress("UNCHECKED_CAST")
    private fun AbyssEphemeralStoreTransactionLike<ID>.applyEphemeralOp(op: Op) = when (op) {
        is Op.AddNode    -> saveNode(op.node as NodeLike<ID>, op.ttl!!)
        is Op.RemoveNode -> deleteNode(op.id as ID)
        is Op.AddEdge    -> saveEdge(op.edge as SchemaEdgeLike<ID>, op.ttl!!)
        is Op.RemoveEdge -> deleteEdge(op.fromId as ID, op.toId as ID, op.type)
    }

    // ponytail: bridges CompletionStage → Deferred; avoids kotlinx-coroutines-jdk8 dependency
    private fun <T> CompletionStage<T>.asDeferred(): Deferred<T> = CompletableDeferred<T>().also { d ->
        whenComplete { v, ex -> if (ex != null) d.completeExceptionally(ex) else d.complete(v) }
    }

    private suspend fun populateCache(ops: List<Op>, warnMsg: String) {
        val stages = ops.flatMap { applyToCacheAsync(it) }
        if (asyncCachePopulation) {
            // ponytail: fire-and-forget — no thread pinned during network wait
            stages.forEach { it.exceptionally { ex -> log.warn(warnMsg, ex); null } }
        } else {
            Either.catch { stages.map { it.asDeferred() }.awaitAll() }
                .fold(ifLeft = { log.warn(warnMsg, it) }, ifRight = {})
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyToCacheAsync(op: Op): List<CompletionStage<*>> = when (op) {
        is Op.AddNode -> {
            val node = op.node as NodeLike<ID>
            if (op.ttl != null)
                listOf(nodesMap.setAsync(adapter.toNodeId(node.id), node, op.ttl.inWholeSeconds, TimeUnit.SECONDS))
            else
                listOf(nodesMap.setAsync(adapter.toNodeId(node.id), node))
        }
        is Op.RemoveNode -> listOf(nodesMap.removeAsync(adapter.toNodeId(op.id as ID)))
        is Op.AddEdge -> {
            val edge   = op.edge as SchemaEdgeLike<ID>
            val key    = edgeKey(adapter.toNodeId(edge.fromId), adapter.toNodeId(edge.toId), edgeType(edge))
            val revKey = revKey(adapter.toNodeId(edge.toId), adapter.toNodeId(edge.fromId), edgeType(edge))
            // Ephemeral (TTL) edges are outgoing-only (TODO 1.13): no reverse index, matching the
            // store — so incoming lookups can't see a cached ephemeral edge the store won't return.
            if (op.ttl != null) listOf(
                edgesMap.setAsync(key, edge, op.ttl.inWholeSeconds, TimeUnit.SECONDS)
            ) else listOf(
                edgesMap.setAsync(key, edge),
                reverseEdgesMap.setAsync(revKey, Unit)
            )
        }
        is Op.RemoveEdge -> {
            val fromId = op.fromId as ID
            val toId   = op.toId as ID
            listOf(
                edgesMap.removeAsync(edgeKey(adapter.toNodeId(fromId), adapter.toNodeId(toId), op.type)),
                reverseEdgesMap.removeAsync(revKey(adapter.toNodeId(toId), adapter.toNodeId(fromId), op.type))
            )
        }
    }

    private suspend fun loadAndCacheNode(id: ID): NodeLike<ID>? {
        val (node, remaining) = coroutineScope {
            val fromPersistent = async(Dispatchers.IO) { persistentStore?.loadNode(id)?.getOrNull() }
            val fromEphemeral  = async(Dispatchers.IO) { ephemeralStore?.loadNode(id)?.getOrNull() }
            fromPersistent.await() ?: fromEphemeral.await()
        } ?: return null
        node ?: return null
        if (remaining != null && remaining.inWholeSeconds <= 0) return null
        val nid = adapter.toNodeId(id)
        withContext(Dispatchers.IO) {
            if (remaining == null) nodesMap.set(nid, node)
            else nodesMap.set(nid, node, remaining.inWholeSeconds, TimeUnit.SECONDS)
        }
        return node
    }

    private suspend fun loadAndCacheEdge(fromId: ID, toId: ID, type: String): SchemaEdgeLike<ID>? {
        val (edge, remaining) = coroutineScope {
            val fromPersistent = async(Dispatchers.IO) { persistentStore?.loadEdge(fromId, toId, type)?.getOrNull() }
            val fromEphemeral  = async(Dispatchers.IO) { ephemeralStore?.loadEdge(fromId, toId, type)?.getOrNull() }
            fromPersistent.await() ?: fromEphemeral.await()
        } ?: return null
        edge ?: return null
        if (remaining != null && remaining.inWholeSeconds <= 0) return null
        val key = edgeKey(adapter.toNodeId(fromId), adapter.toNodeId(toId), type)
        withContext(Dispatchers.IO) {
            if (remaining == null) edgesMap.set(key, edge)
            else edgesMap.set(key, edge, remaining.inWholeSeconds, TimeUnit.SECONDS)
        }
        return edge
    }

    private fun edgeType(edge: SchemaEdgeLike<*>): String =
        edge::class.findAnnotation<SerialName>()?.value ?: error("${edge::class} missing @SerialName")

    private fun schemaCheck(edge: SchemaEdgeLike<*>, from: NodeLike<*>, to: NodeLike<*>): AbyssError? {
        val c = edge::class.findAnnotation<EdgeConstraint>() ?: return null
        if (c.fromTypes.isNotEmpty() && from !is UnknownNode && from::class !in c.fromTypes)
            return AbyssError.SchemaError("Edge ${edgeType(edge)}: fromId is ${from::class.simpleName}, expected ${c.fromTypes.map { it.simpleName }}")
        if (c.toTypes.isNotEmpty() && to !is UnknownNode && to::class !in c.toTypes)
            return AbyssError.SchemaError("Edge ${edgeType(edge)}: toId is ${to::class.simpleName}, expected ${c.toTypes.map { it.simpleName }}")
        return null
    }
}

// Builds a Hazelcast key predicate matching an endpoint field against a native encoding. Tagged
// (multi-schema) always includes the tag clause: it scopes the match to the endpoint's schema and
// disambiguates two schemas that share a raw shape.
internal fun <K, V> nativeKeyEq(field: String, enc: NodeKeyEncoding): Predicate<K, V> = when (enc) {
    is NodeKeyEncoding.Int32 -> Predicates.equal<K, V>("__key.$field", enc.value)
    is NodeKeyEncoding.Int64 -> Predicates.equal<K, V>("__key.$field", enc.value)
    is NodeKeyEncoding.Str -> Predicates.equal<K, V>("__key.$field", enc.value)
    is NodeKeyEncoding.Uuid -> Predicates.and<K, V>(
        Predicates.equal<K, V>("__key.${field}Hi", enc.hi),
        Predicates.equal<K, V>("__key.${field}Lo", enc.lo)
    )
    is NodeKeyEncoding.Tagged -> {
        val tagEq = Predicates.equal<K, V>("__key.${field}Tag", enc.tag)
        val valEq: Predicate<K, V> = when (val i = enc.inner) {
            is NodeKeyEncoding.Int32 -> Predicates.equal<K, V>("__key.${field}Lo", i.value.toLong())
            is NodeKeyEncoding.Int64 -> Predicates.equal<K, V>("__key.${field}Lo", i.value)
            is NodeKeyEncoding.Str -> Predicates.equal<K, V>("__key.${field}Str", i.value)
            is NodeKeyEncoding.Uuid -> Predicates.and<K, V>(
                Predicates.equal<K, V>("__key.${field}Hi", i.hi),
                Predicates.equal<K, V>("__key.${field}Lo", i.lo)
            )
            is NodeKeyEncoding.Tagged -> error("Tagged cannot nest")
        }
        Predicates.and<K, V>(tagEq, valEq)
    }
}

// Call before creating the HazelcastInstance — serialization config is immutable after startup.
// Pass the consuming project's SerializersModule so concrete NodeLike/SchemaEdgeLike types are known.
// The adapter must match the KeyAdapter used by every AbyssGraphSchema<ID> sharing this HazelcastInstance:
// EdgeKey/ReverseEdgeKey compact serialization is bound to one adapter's native field encoding.
fun Config.registerAbyssSerializers(adapter: EdgeAdapter, module: SerializersModule = EmptySerializersModule()): Config = apply {
    serializationConfig.compactSerializationConfig.addSerializer(NodeIdSerializer())
    serializationConfig.compactSerializationConfig.addSerializer(EdgeKeySerializer(adapter))
    serializationConfig.compactSerializationConfig.addSerializer(ReverseEdgeKeySerializer(adapter))
    serializationConfig.addSerializerConfig(SerializerConfig().setTypeClass(NodeLike::class.java).setImplementation(NodeLikeHzSerializer(module)))
    serializationConfig.addSerializerConfig(SerializerConfig().setTypeClass(EdgeLike::class.java).setImplementation(EdgeLikeHzSerializer(module)))
}


private sealed interface Op {
    data class AddNode(val node: NodeLike<*>, val ttl: Duration?) : Op
    data class RemoveNode(val id: Any?) : Op
    data class AddEdge(val edge: SchemaEdgeLike<*>, val ttl: Duration?) : Op
    data class RemoveEdge(val fromId: Any?, val toId: Any?, val type: String) : Op
}

private class BufferedTransaction<ID>(
    private val readNode: suspend (ID) -> NodeLike<ID>?,
    private val readEdge: suspend (ID, ID, String) -> SchemaEdgeLike<ID>?
) : AbyssTransactionLike<ID> {
    val ops = mutableListOf<Op>()
    override fun addNode(node: NodeLike<ID>)                          { ops += Op.AddNode(node, null) }
    override fun removeNode(id: ID)                                    { ops += Op.RemoveNode(id) }
    override fun addEdge(edge: SchemaEdgeLike<ID>)                          { ops += Op.AddEdge(edge, null) }
    override fun removeEdge(fromId: ID, toId: ID, type: String)       { ops += Op.RemoveEdge(fromId, toId, type) }
    override suspend fun modifyNode(id: ID, transform: (NodeLike<ID>?) -> NodeLike<ID>) {
        ops += Op.AddNode(transform(readNode(id)), null)
    }
    override suspend fun modifyEdge(fromId: ID, toId: ID, type: String, transform: (SchemaEdgeLike<ID>?) -> SchemaEdgeLike<ID>) {
        ops += Op.RemoveEdge(fromId, toId, type)
        ops += Op.AddEdge(transform(readEdge(fromId, toId, type)), null)
    }
}

private class BufferedEphemeralTransaction<ID>(
    private val ttl: Duration,
    private val readNode: suspend (ID) -> NodeLike<ID>?,
    private val readEdge: suspend (ID, ID, String) -> SchemaEdgeLike<ID>?
) : AbyssEphemeralTransactionLike<ID> {
    val ops = mutableListOf<Op>()
    override fun addNode(node: NodeLike<ID>)                          { ops += Op.AddNode(node, ttl) }
    override fun removeNode(id: ID)                                    { ops += Op.RemoveNode(id) }
    override fun addEdge(edge: SchemaEdgeLike<ID>)                          { ops += Op.AddEdge(edge, ttl) }
    override fun removeEdge(fromId: ID, toId: ID, type: String)       { ops += Op.RemoveEdge(fromId, toId, type) }
    override suspend fun modifyNode(id: ID, transform: (NodeLike<ID>?) -> NodeLike<ID>) {
        ops += Op.AddNode(transform(readNode(id)), ttl)
    }
    override suspend fun modifyEdge(fromId: ID, toId: ID, type: String, transform: (SchemaEdgeLike<ID>?) -> SchemaEdgeLike<ID>) {
        ops += Op.RemoveEdge(fromId, toId, type)
        ops += Op.AddEdge(transform(readEdge(fromId, toId, type)), ttl)
    }
}
