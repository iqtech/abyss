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
import pl.iqtech.abyss.graph.serialization.NodeLikeHzSerializer
import pl.iqtech.abyss.graph.serialization.ReverseEdgeKeySerializer
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreLike
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreTransactionLike
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreTransactionLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import java.util.concurrent.TimeUnit
import kotlin.reflect.full.findAnnotation
import kotlin.time.Duration
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

private val log = LoggerFactory.getLogger(AbyssGraph::class.java)

class AbyssGraph(
    private val hazelcast: HazelcastInstance,
    val nodesMapName: String,
    val edgesMapName: String,
    val persistentStore: AbyssStoreLike? = null,
    val ephemeralStore: AbyssEphemeralStoreLike? = null,
    private val asyncCachePopulation: Boolean = false
) : AbyssEngineLike {

    // Hazelcast IMap keys are java.util.UUID — natively supported by Hazelcast serialization.
    // All public methods accept kotlin.uuid.Uuid and convert at the boundary.
    private val nodesMap: IMap<java.util.UUID, NodeLike>
    private val edgesMap: IMap<EdgeKey, EdgeLike>
    private val reverseEdgesMap: IMap<ReverseEdgeKey, Unit>
    init {
        nodesMap = hazelcast.getMap(nodesMapName)
        edgesMap = hazelcast.getMap(edgesMapName)
        reverseEdgesMap = hazelcast.getMap("${edgesMapName}-reverse")
        log.info("AbyssGraph started [nodes={}, edges={}, persistentStore={}, ephemeralStore={}]",
            nodesMapName, edgesMapName,
            persistentStore?.javaClass?.simpleName ?: "none",
            ephemeralStore?.javaClass?.simpleName ?: "none")
    }

    override fun allNodeIds(): Flow<Uuid> = flow {
        nodesMap.keys.forEach { emit(it.toKotlinUuid()) }
    }

    override suspend fun node(id: Uuid): Either<AbyssError, NodeLike> =
        Either.catch { nodesMap.getAsync(id.toJavaUuid()).asDeferred().await() ?: loadAndCacheNode(id) }
            .mapLeft { AbyssError.Unexpected(it) }
            .flatMap { it?.right() ?: AbyssError.NodeNotFound(id).left() }

    override suspend fun edge(fromId: Uuid, toId: Uuid, type: String): Either<AbyssError, EdgeLike> =
        Either.catch { edgesMap.getAsync(EdgeKey(fromId, toId, type)).asDeferred().await() ?: loadAndCacheEdge(fromId, toId, type) }
            .mapLeft { AbyssError.Unexpected(it) }
            .flatMap { it?.right() ?: AbyssError.EdgeNotFound(fromId, toId, type).left() }

    override suspend fun nodeExists(id: Uuid): Either<AbyssError, Boolean> =
        Either.catch { nodesMap.getAsync(id.toJavaUuid()).asDeferred().await() != null || loadAndCacheNode(id) != null }
            .mapLeft { AbyssError.Unexpected(it) }

    override suspend fun edgeExists(fromId: Uuid, toId: Uuid, type: String): Either<AbyssError, Boolean> =
        Either.catch { edgesMap.getAsync(EdgeKey(fromId, toId, type)).asDeferred().await() != null || loadAndCacheEdge(fromId, toId, type) != null }
            .mapLeft { AbyssError.Unexpected(it) }

    override fun outEdges(nodeId: Uuid, pageSize: Int): Flow<EdgeLike> =
        outEdgeFlow(nodeId, eq("__key.fromId", nodeId.toString()))

    override fun outEdges(nodeId: Uuid, type: String, pageSize: Int): Flow<EdgeLike> =
        outEdgeFlow(nodeId, Predicates.and(eq("__key.fromId", nodeId.toString()), eq("__key.type", type)))

    override fun inEdges(nodeId: Uuid, pageSize: Int): Flow<EdgeLike> =
        inEdgeFlow(nodeId)

    override fun inEdges(nodeId: Uuid, type: String, pageSize: Int): Flow<EdgeLike> =
        inEdgeFlow(nodeId, type)

    private fun eq(attr: String, value: String): Predicate<EdgeKey, EdgeLike> = Predicates.equal(attr, value)

    private val edgeOrder = Comparator<Map.Entry<EdgeKey, EdgeLike>> { a, b ->
        compareValuesBy(a.key, b.key, { it.fromId.toString() }, { it.toId.toString() }, { it.type })
    }

    // EdgeKey is PartitionAware on fromId, so all outgoing edges for a node land on the same
    // partition — partitionPredicate routes the query there without a cluster-wide scatter.
    private fun outEdgeFlow(nodeId: Uuid, predicate: Predicate<EdgeKey, EdgeLike>): Flow<EdgeLike> = flow {
        withContext(Dispatchers.IO) {
            persistentStore?.loadEdges(nodeId)?.getOrNull()?.forEach { (edge, _) ->
                edgesMap.putIfAbsent(EdgeKey(edge.fromId, edge.toId, edgeType(edge)), edge)
            }
            ephemeralStore?.loadEdges(nodeId)?.getOrNull()?.forEach { (edge, remaining) ->
                val key = EdgeKey(edge.fromId, edge.toId, edgeType(edge))
                when {
                    remaining == null -> edgesMap.putIfAbsent(key, edge)
                    remaining.inWholeSeconds > 0 -> edgesMap.putIfAbsent(key, edge, remaining.inWholeSeconds, TimeUnit.SECONDS)
                    // remaining <= 0: expired — skip
                }
            }
        }
        val partitioned = Predicates.partitionPredicate<EdgeKey, EdgeLike>(nodeId.toJavaUuid(), predicate)
        withContext(Dispatchers.IO) { edgesMap.values(partitioned) }.forEach { emit(it) }
    }

    // ReverseEdgeKey is PartitionAware on toId — all incoming-edge index entries for a node land on
    // the same partition, making this a single-partition key-set query followed by point-lookups.
    private fun inEdgeFlow(nodeId: Uuid, typeFilter: String? = null): Flow<EdgeLike> = flow {
        withContext(Dispatchers.IO) {
            persistentStore?.loadInEdges(nodeId)?.getOrNull()?.forEach { (edge, _) ->
                val type = edgeType(edge)
                edgesMap.putIfAbsent(EdgeKey(edge.fromId, edge.toId, type), edge)
                reverseEdgesMap.putIfAbsent(ReverseEdgeKey(edge.toId, edge.fromId, type), Unit)
            }
            ephemeralStore?.loadInEdges(nodeId)?.getOrNull()?.forEach { (edge, remaining) ->
                val type = edgeType(edge)
                val key = EdgeKey(edge.fromId, edge.toId, type)
                val revKey = ReverseEdgeKey(edge.toId, edge.fromId, type)
                when {
                    remaining == null -> {
                        edgesMap.putIfAbsent(key, edge)
                        reverseEdgesMap.putIfAbsent(revKey, Unit)
                    }
                    remaining.inWholeSeconds > 0 -> {
                        edgesMap.putIfAbsent(key, edge, remaining.inWholeSeconds, TimeUnit.SECONDS)
                        reverseEdgesMap.putIfAbsent(revKey, Unit, remaining.inWholeSeconds, TimeUnit.SECONDS)
                    }
                    // remaining <= 0: expired — skip
                }
            }
        }
        val revPredicate = Predicates.partitionPredicate<ReverseEdgeKey, Unit>(
            nodeId.toJavaUuid(), Predicates.equal("__key.toId", nodeId.toString())
        )
        val revKeys = withContext(Dispatchers.IO) { reverseEdgesMap.keySet(revPredicate) }
        val filtered = if (typeFilter != null) revKeys.filter { it.type == typeFilter } else revKeys
        val edgeKeys = filtered.map { EdgeKey(it.fromId, it.toId, it.type) }.toSet()
        if (edgeKeys.isNotEmpty()) {
            withContext(Dispatchers.IO) { edgesMap.getAll(edgeKeys) }.values.forEach { emit(it) }
        }
    }

    private fun edgeFlow(predicate: Predicate<EdgeKey, EdgeLike>, pageSize: Int): Flow<EdgeLike> = flow {
        val paging = Predicates.pagingPredicate(predicate, edgeOrder, pageSize)
        while (true) {
            val page = withContext(Dispatchers.IO) { edgesMap.values(paging) }
            page.forEach { emit(it) }
            if (page.size < pageSize) break
            paging.nextPage()
        }
    }

    override suspend fun <T> from(nodeId: Uuid, block: suspend TraversalBuilderLike.() -> T): Either<AbyssError, T> =
        Either.catch { TraversalBuilder(this, setOf(nodeId)).block() }
            .mapLeft { AbyssError.Unexpected(it) }

    override suspend fun ephemeral(ttl: Duration, checkIntegrity: Boolean, block: suspend AbyssEphemeralTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        val buffer = BufferedEphemeralTransaction(
            ttl = ttl,
            readNode = { id -> nodesMap.getAsync(id.toJavaUuid()).asDeferred().await() ?: loadAndCacheNode(id) },
            readEdge = { f, t, type -> edgesMap.getAsync(EdgeKey(f, t, type)).asDeferred().await() ?: loadAndCacheEdge(f, t, type) }
        )
        try { buffer.block() } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }

        val ops = withContext(Dispatchers.IO) {
            buffer.ops.flatMap { op ->
                if (op is Op.RemoveNode) listOf(op) + cascadeEdgeRemovals(op.id)
                else listOf(op)
            }
        }

        if (checkIntegrity) {
            val addedInTx = ops.filterIsInstance<Op.AddNode>().associate { it.node.id to it.node }
            val error = withContext(Dispatchers.IO) {
                ops.filterIsInstance<Op.AddEdge>().firstNotNullOfOrNull { op ->
                    val fromNode = addedInTx[op.edge.fromId] ?: nodesMap[op.edge.fromId.toJavaUuid()]
                    val toNode   = addedInTx[op.edge.toId]   ?: nodesMap[op.edge.toId.toJavaUuid()]
                    when {
                        fromNode == null -> AbyssError.IntegrityError("Node ${op.edge.fromId} (fromId) not found")
                        toNode   == null -> AbyssError.IntegrityError("Node ${op.edge.toId} (toId) not found")
                        else             -> schemaCheck(op.edge, fromNode, toNode)
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

    override suspend fun transaction(checkIntegrity: Boolean, block: suspend AbyssTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        val buffer = BufferedTransaction(
            readNode = { id -> nodesMap.getAsync(id.toJavaUuid()).asDeferred().await() ?: loadAndCacheNode(id) },
            readEdge = { f, t, type -> edgesMap.getAsync(EdgeKey(f, t, type)).asDeferred().await() ?: loadAndCacheEdge(f, t, type) }
        )
        try { buffer.block() } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }

        val ops = withContext(Dispatchers.IO) {
            buffer.ops.flatMap { op ->
                if (op is Op.RemoveNode) listOf(op) + cascadeEdgeRemovals(op.id)
                else listOf(op)
            }
        }

        if (checkIntegrity) {
            val addedInTx = ops.filterIsInstance<Op.AddNode>().associate { it.node.id to it.node }
            val error = withContext(Dispatchers.IO) {
                ops.filterIsInstance<Op.AddEdge>().firstNotNullOfOrNull { op ->
                    val fromNode = addedInTx[op.edge.fromId] ?: nodesMap[op.edge.fromId.toJavaUuid()]
                    val toNode   = addedInTx[op.edge.toId]   ?: nodesMap[op.edge.toId.toJavaUuid()]
                    when {
                        fromNode == null -> AbyssError.IntegrityError("Node ${op.edge.fromId} (fromId) not found")
                        toNode   == null -> AbyssError.IntegrityError("Node ${op.edge.toId} (toId) not found")
                        else             -> schemaCheck(op.edge, fromNode, toNode)
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

    private fun cascadeEdgeRemovals(nodeId: Uuid): List<Op.RemoveEdge> {
        val out = edgesMap.entrySet(Predicates.partitionPredicate(nodeId.toJavaUuid(), eq("__key.fromId", nodeId.toString())))
            .map { Op.RemoveEdge(it.key.fromId, it.key.toId, it.key.type) }
        val inc = reverseEdgesMap.keySet(Predicates.partitionPredicate<ReverseEdgeKey, Unit>(
            nodeId.toJavaUuid(), Predicates.equal("__key.toId", nodeId.toString())
        )).map { Op.RemoveEdge(it.fromId, it.toId, it.type) }
        return (out + inc).distinctBy { Triple(it.fromId, it.toId, it.type) }
    }

    private fun AbyssStoreTransactionLike.applyPersistentOp(op: Op) = when (op) {
        is Op.AddNode    -> saveNode(op.node)
        is Op.RemoveNode -> deleteNode(op.id)
        is Op.AddEdge    -> saveEdge(op.edge)
        is Op.RemoveEdge -> deleteEdge(op.fromId, op.toId, op.type)
    }

    private fun AbyssEphemeralStoreTransactionLike.applyEphemeralOp(op: Op) = when (op) {
        is Op.AddNode    -> saveNode(op.node, op.ttl!!)
        is Op.RemoveNode -> deleteNode(op.id)
        is Op.AddEdge    -> saveEdge(op.edge, op.ttl!!)
        is Op.RemoveEdge -> deleteEdge(op.fromId, op.toId, op.type)
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

    private fun applyToCacheAsync(op: Op): List<CompletionStage<*>> = when (op) {
        is Op.AddNode -> if (op.ttl != null)
            listOf(nodesMap.setAsync(op.node.id.toJavaUuid(), op.node, op.ttl.inWholeSeconds, TimeUnit.SECONDS))
        else
            listOf(nodesMap.setAsync(op.node.id.toJavaUuid(), op.node))
        is Op.RemoveNode -> listOf(nodesMap.removeAsync(op.id.toJavaUuid()))
        is Op.AddEdge -> {
            val key    = EdgeKey(op.edge.fromId, op.edge.toId, edgeType(op.edge))
            val revKey = ReverseEdgeKey(op.edge.toId, op.edge.fromId, edgeType(op.edge))
            if (op.ttl != null) listOf(
                edgesMap.setAsync(key, op.edge, op.ttl.inWholeSeconds, TimeUnit.SECONDS),
                reverseEdgesMap.setAsync(revKey, Unit, op.ttl.inWholeSeconds, TimeUnit.SECONDS)
            ) else listOf(
                edgesMap.setAsync(key, op.edge),
                reverseEdgesMap.setAsync(revKey, Unit)
            )
        }
        is Op.RemoveEdge -> listOf(
            edgesMap.removeAsync(EdgeKey(op.fromId, op.toId, op.type)),
            reverseEdgesMap.removeAsync(ReverseEdgeKey(op.toId, op.fromId, op.type))
        )
    }

    private suspend fun loadAndCacheNode(id: Uuid): NodeLike? {
        val (node, remaining) = coroutineScope {
            val fromPersistent = async(Dispatchers.IO) { persistentStore?.loadNode(id)?.getOrNull() }
            val fromEphemeral  = async(Dispatchers.IO) { ephemeralStore?.loadNode(id)?.getOrNull() }
            fromPersistent.await() ?: fromEphemeral.await()
        } ?: return null
        node ?: return null
        if (remaining != null && remaining.inWholeSeconds <= 0) return null
        val jId = id.toJavaUuid()
        withContext(Dispatchers.IO) {
            if (remaining == null) nodesMap.set(jId, node)
            else nodesMap.set(jId, node, remaining.inWholeSeconds, TimeUnit.SECONDS)
        }
        return node
    }

    private suspend fun loadAndCacheEdge(fromId: Uuid, toId: Uuid, type: String): EdgeLike? {
        val (edge, remaining) = coroutineScope {
            val fromPersistent = async(Dispatchers.IO) { persistentStore?.loadEdge(fromId, toId, type)?.getOrNull() }
            val fromEphemeral  = async(Dispatchers.IO) { ephemeralStore?.loadEdge(fromId, toId, type)?.getOrNull() }
            fromPersistent.await() ?: fromEphemeral.await()
        } ?: return null
        edge ?: return null
        if (remaining != null && remaining.inWholeSeconds <= 0) return null
        val key = EdgeKey(fromId, toId, type)
        withContext(Dispatchers.IO) {
            if (remaining == null) edgesMap.set(key, edge)
            else edgesMap.set(key, edge, remaining.inWholeSeconds, TimeUnit.SECONDS)
        }
        return edge
    }

    private fun edgeType(edge: EdgeLike): String =
        edge::class.findAnnotation<SerialName>()?.value ?: error("${edge::class} missing @SerialName")

    private fun schemaCheck(edge: EdgeLike, from: NodeLike, to: NodeLike): AbyssError? {
        val c = edge::class.findAnnotation<EdgeConstraint>() ?: return null
        if (c.fromTypes.isNotEmpty() && from !is UnknownNode && from::class !in c.fromTypes)
            return AbyssError.SchemaError("Edge ${edgeType(edge)}: fromId is ${from::class.simpleName}, expected ${c.fromTypes.map { it.simpleName }}")
        if (c.toTypes.isNotEmpty() && to !is UnknownNode && to::class !in c.toTypes)
            return AbyssError.SchemaError("Edge ${edgeType(edge)}: toId is ${to::class.simpleName}, expected ${c.toTypes.map { it.simpleName }}")
        return null
    }
}

// Call before creating the HazelcastInstance — serialization config is immutable after startup.
// Pass the consuming project's SerializersModule so concrete NodeLike/EdgeLike types are known.
fun Config.registerAbyssSerializers(module: SerializersModule = EmptySerializersModule()): Config = apply {
    serializationConfig.compactSerializationConfig.addSerializer(EdgeKeySerializer())
    serializationConfig.compactSerializationConfig.addSerializer(ReverseEdgeKeySerializer())
    serializationConfig.addSerializerConfig(SerializerConfig().setTypeClass(NodeLike::class.java).setImplementation(NodeLikeHzSerializer(module)))
    serializationConfig.addSerializerConfig(SerializerConfig().setTypeClass(EdgeLike::class.java).setImplementation(EdgeLikeHzSerializer(module)))
}


private sealed interface Op {
    data class AddNode(val node: NodeLike, val ttl: Duration?) : Op
    data class RemoveNode(val id: Uuid) : Op
    data class AddEdge(val edge: EdgeLike, val ttl: Duration?) : Op
    data class RemoveEdge(val fromId: Uuid, val toId: Uuid, val type: String) : Op
}

private class BufferedTransaction(
    private val readNode: suspend (Uuid) -> NodeLike?,
    private val readEdge: suspend (Uuid, Uuid, String) -> EdgeLike?
) : AbyssTransactionLike {
    val ops = mutableListOf<Op>()
    override fun addNode(node: NodeLike)                            { ops += Op.AddNode(node, null) }
    override fun removeNode(id: Uuid)                               { ops += Op.RemoveNode(id) }
    override fun addEdge(edge: EdgeLike)                            { ops += Op.AddEdge(edge, null) }
    override fun removeEdge(fromId: Uuid, toId: Uuid, type: String) { ops += Op.RemoveEdge(fromId, toId, type) }
    override suspend fun modifyNode(id: Uuid, transform: (NodeLike?) -> NodeLike) {
        ops += Op.AddNode(transform(readNode(id)), null)
    }
    override suspend fun modifyEdge(fromId: Uuid, toId: Uuid, type: String, transform: (EdgeLike?) -> EdgeLike) {
        ops += Op.RemoveEdge(fromId, toId, type)
        ops += Op.AddEdge(transform(readEdge(fromId, toId, type)), null)
    }
}

private class BufferedEphemeralTransaction(
    private val ttl: Duration,
    private val readNode: suspend (Uuid) -> NodeLike?,
    private val readEdge: suspend (Uuid, Uuid, String) -> EdgeLike?
) : AbyssEphemeralTransactionLike {
    val ops = mutableListOf<Op>()
    override fun addNode(node: NodeLike)                            { ops += Op.AddNode(node, ttl) }
    override fun removeNode(id: Uuid)                               { ops += Op.RemoveNode(id) }
    override fun addEdge(edge: EdgeLike)                            { ops += Op.AddEdge(edge, ttl) }
    override fun removeEdge(fromId: Uuid, toId: Uuid, type: String) { ops += Op.RemoveEdge(fromId, toId, type) }
    override suspend fun modifyNode(id: Uuid, transform: (NodeLike?) -> NodeLike) {
        ops += Op.AddNode(transform(readNode(id)), ttl)
    }
    override suspend fun modifyEdge(fromId: Uuid, toId: Uuid, type: String, transform: (EdgeLike?) -> EdgeLike) {
        ops += Op.RemoveEdge(fromId, toId, type)
        ops += Op.AddEdge(transform(readEdge(fromId, toId, type)), ttl)
    }
}
