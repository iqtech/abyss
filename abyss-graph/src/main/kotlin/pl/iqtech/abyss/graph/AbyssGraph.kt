package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import com.hazelcast.config.Config
import com.hazelcast.config.MapStoreConfig
import com.hazelcast.config.SerializerConfig
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.hazelcast.query.Predicate
import com.hazelcast.query.Predicates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
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
import pl.iqtech.abyss.graph.loader.EdgeMapLoader
import pl.iqtech.abyss.graph.loader.NodeMapLoader
import pl.iqtech.abyss.graph.serialization.EdgeKeySerializer
import pl.iqtech.abyss.graph.serialization.EdgeLikeHzSerializer
import pl.iqtech.abyss.graph.serialization.NodeLikeHzSerializer
import pl.iqtech.abyss.graph.serialization.ReverseEdgeKeySerializer
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
    val store: AbyssStoreLike? = null
) : AbyssEngineLike {

    // Hazelcast IMap keys are java.util.UUID — natively supported by Hazelcast serialization.
    // All public methods accept kotlin.uuid.Uuid and convert at the boundary.
    private val nodesMap: IMap<java.util.UUID, NodeLike>
    private val edgesMap: IMap<EdgeKey, EdgeLike>
    private val reverseEdgesMap: IMap<ReverseEdgeKey, Unit>

    init {
        if (store != null) {
            hazelcast.config.getMapConfig(nodesMapName).mapStoreConfig.apply {
                isEnabled = true
                setImplementation(NodeMapLoader(store))
                initialLoadMode = MapStoreConfig.InitialLoadMode.LAZY
            }
            hazelcast.config.getMapConfig(edgesMapName).mapStoreConfig.apply {
                isEnabled = true
                setImplementation(EdgeMapLoader(store))
                initialLoadMode = MapStoreConfig.InitialLoadMode.LAZY
            }
        }
        nodesMap = hazelcast.getMap(nodesMapName)
        edgesMap = hazelcast.getMap(edgesMapName)
        reverseEdgesMap = hazelcast.getMap("${edgesMapName}-reverse")
        log.info("AbyssGraph started [nodes={}, edges={}, store={}]",
            nodesMapName, edgesMapName, store?.javaClass?.simpleName ?: "none")
    }

    override suspend fun node(id: Uuid): Either<AbyssError, NodeLike> =
        Either.catch { withContext(Dispatchers.IO) { nodesMap[id.toJavaUuid()] } }
            .mapLeft { AbyssError.Unexpected(it) }
            .flatMap { it?.right() ?: AbyssError.NodeNotFound(id).left() }

    override suspend fun edge(fromId: Uuid, toId: Uuid, type: String): Either<AbyssError, EdgeLike> =
        Either.catch { withContext(Dispatchers.IO) { edgesMap[EdgeKey(fromId, toId, type)] } }
            .mapLeft { AbyssError.Unexpected(it) }
            .flatMap { it?.right() ?: AbyssError.EdgeNotFound(fromId, toId, type).left() }

    override suspend fun nodeExists(id: Uuid): Either<AbyssError, Boolean> =
        Either.catch { withContext(Dispatchers.IO) { nodesMap.containsKey(id.toJavaUuid()) } }
            .mapLeft { AbyssError.Unexpected(it) }

    override suspend fun edgeExists(fromId: Uuid, toId: Uuid, type: String): Either<AbyssError, Boolean> =
        Either.catch { withContext(Dispatchers.IO) { edgesMap.containsKey(EdgeKey(fromId, toId, type)) } }
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
            store?.loadEdges(nodeId)?.getOrNull()?.forEach { edge ->
                edgesMap.putIfAbsent(EdgeKey(edge.fromId, edge.toId, edgeType(edge)), edge)
            }
        }
        val partitioned = Predicates.partitionPredicate<EdgeKey, EdgeLike>(nodeId.toJavaUuid(), predicate)
        withContext(Dispatchers.IO) { edgesMap.values(partitioned) }.forEach { emit(it) }
    }

    // ReverseEdgeKey is PartitionAware on toId — all incoming-edge index entries for a node land on
    // the same partition, making this a single-partition key-set query followed by point-lookups.
    private fun inEdgeFlow(nodeId: Uuid, typeFilter: String? = null): Flow<EdgeLike> = flow {
        withContext(Dispatchers.IO) {
            store?.loadInEdges(nodeId)?.getOrNull()?.forEach { edge ->
                val type = edgeType(edge)
                edgesMap.putIfAbsent(EdgeKey(edge.fromId, edge.toId, type), edge)
                reverseEdgesMap.putIfAbsent(ReverseEdgeKey(edge.toId, edge.fromId, type), Unit)
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
        val buffer = BufferedEphemeralTransaction(ttl)
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

        if (store != null) {
            val storeResult = store.transaction { ops.forEach { applyToStore(it) } }
            if (storeResult.isLeft()) {
                log.error("Ephemeral store commit failed; cache unchanged [nodes={}, edges={}]", nodesMapName, edgesMapName)
                return storeResult
            }
        }

        Either.catch { withContext(Dispatchers.IO) { ops.forEach { applyToCache(it) } } }
            .fold(ifLeft = { log.warn("Cache update failed after ephemeral store commit; cache may be stale", it) }, ifRight = {})

        log.debug("Ephemeral committed [{} op(s), ttl={}, nodes={}, edges={}]", ops.size, ttl, nodesMapName, edgesMapName)
        return Unit.right()
    }

    override suspend fun transaction(checkIntegrity: Boolean, block: suspend AbyssTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        val buffer = BufferedTransaction()
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

        if (store != null) {
            val storeResult = store.transaction { ops.forEach { applyToStore(it) } }
            if (storeResult.isLeft()) {
                log.error("Store transaction failed; cache unchanged [nodes={}, edges={}]", nodesMapName, edgesMapName)
                return storeResult
            }
        }

        // Cache failure after a successful store commit is logged but not propagated: the store is
        // the source of truth and the cache self-heals on the next miss via MapLoader.
        Either.catch { withContext(Dispatchers.IO) { ops.forEach { applyToCache(it) } } }
            .fold(ifLeft = { log.warn("Cache update failed after store commit; cache may be stale", it) }, ifRight = {})

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

    private fun AbyssStoreTransactionLike.applyToStore(op: Op) = when (op) {
        is Op.AddNode    -> saveNode(op.node, op.ttl)
        is Op.RemoveNode -> deleteNode(op.id)
        is Op.AddEdge    -> saveEdge(op.edge, op.ttl)
        is Op.RemoveEdge -> deleteEdge(op.fromId, op.toId, op.type)
    }

    private fun applyToCache(op: Op) = when (op) {
        is Op.AddNode -> if (op.ttl != null)
            nodesMap.set(op.node.id.toJavaUuid(), op.node, op.ttl.inWholeSeconds, TimeUnit.SECONDS)
        else
            nodesMap.set(op.node.id.toJavaUuid(), op.node)
        is Op.RemoveNode -> nodesMap.delete(op.id.toJavaUuid())
        is Op.AddEdge -> {
            val key    = EdgeKey(op.edge.fromId, op.edge.toId, edgeType(op.edge))
            val revKey = ReverseEdgeKey(op.edge.toId, op.edge.fromId, edgeType(op.edge))
            if (op.ttl != null) {
                edgesMap.set(key, op.edge, op.ttl.inWholeSeconds, TimeUnit.SECONDS)
                reverseEdgesMap.set(revKey, Unit, op.ttl.inWholeSeconds, TimeUnit.SECONDS)
            } else {
                edgesMap.set(key, op.edge)
                reverseEdgesMap.set(revKey, Unit)
            }
        }
        is Op.RemoveEdge -> {
            edgesMap.delete(EdgeKey(op.fromId, op.toId, op.type))
            reverseEdgesMap.delete(ReverseEdgeKey(op.toId, op.fromId, op.type))
        }
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

private class BufferedTransaction : AbyssTransactionLike {
    val ops = mutableListOf<Op>()
    override fun addNode(node: NodeLike)                            { ops += Op.AddNode(node, null) }
    override fun removeNode(id: Uuid)                               { ops += Op.RemoveNode(id) }
    override fun addEdge(edge: EdgeLike)                            { ops += Op.AddEdge(edge, null) }
    override fun removeEdge(fromId: Uuid, toId: Uuid, type: String) { ops += Op.RemoveEdge(fromId, toId, type) }
    override fun modifyEdge(old: EdgeLike, new: EdgeLike) {
        ops += Op.RemoveEdge(old.fromId, old.toId, old::class.findAnnotation<SerialName>()!!.value)
        ops += Op.AddEdge(new, null)
    }
}

private class BufferedEphemeralTransaction(private val ttl: Duration) : AbyssEphemeralTransactionLike {
    val ops = mutableListOf<Op>()
    override fun addNode(node: NodeLike)                            { ops += Op.AddNode(node, ttl) }
    override fun removeNode(id: Uuid)                               { ops += Op.RemoveNode(id) }
    override fun addEdge(edge: EdgeLike)                            { ops += Op.AddEdge(edge, ttl) }
    override fun removeEdge(fromId: Uuid, toId: Uuid, type: String) { ops += Op.RemoveEdge(fromId, toId, type) }
    override fun modifyEdge(old: EdgeLike, new: EdgeLike) {
        ops += Op.RemoveEdge(old.fromId, old.toId, old::class.findAnnotation<SerialName>()!!.value)
        ops += Op.AddEdge(new, ttl)
    }
}
