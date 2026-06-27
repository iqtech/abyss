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
import pl.iqtech.abyss.dsl.AbyssTransactionLike
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.reflect.full.findAnnotation
import kotlin.time.Duration

private val log = LoggerFactory.getLogger(AbyssGraph::class.java)

class AbyssGraph(
    private val hazelcast: HazelcastInstance,
    val nodesMapName: String,
    val edgesMapName: String,
    val store: AbyssStoreLike? = null
) : AbyssEngineLike {

    private val nodesMap: IMap<UUID, NodeLike>
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

    override suspend fun node(id: UUID): Either<AbyssError, NodeLike> =
        Either.catch { withContext(Dispatchers.IO) { nodesMap[id] } }
            .mapLeft { AbyssError.Unexpected(it) }
            .flatMap { it?.right() ?: AbyssError.NodeNotFound(id).left() }

    override suspend fun edge(fromId: UUID, toId: UUID, type: String): Either<AbyssError, EdgeLike> =
        Either.catch { withContext(Dispatchers.IO) { edgesMap[EdgeKey(fromId, toId, type)] } }
            .mapLeft { AbyssError.Unexpected(it) }
            .flatMap { it?.right() ?: AbyssError.EdgeNotFound(fromId, toId, type).left() }

    override suspend fun nodeExists(id: UUID): Either<AbyssError, Boolean> =
        Either.catch { withContext(Dispatchers.IO) { nodesMap.containsKey(id) } }
            .mapLeft { AbyssError.Unexpected(it) }

    override suspend fun edgeExists(fromId: UUID, toId: UUID, type: String): Either<AbyssError, Boolean> =
        Either.catch { withContext(Dispatchers.IO) { edgesMap.containsKey(EdgeKey(fromId, toId, type)) } }
            .mapLeft { AbyssError.Unexpected(it) }

    override fun outEdges(nodeId: UUID, pageSize: Int): Flow<EdgeLike> =
        outEdgeFlow(nodeId, eq("__key.fromId", nodeId.toString()))

    override fun outEdges(nodeId: UUID, type: String, pageSize: Int): Flow<EdgeLike> =
        outEdgeFlow(nodeId, Predicates.and(eq("__key.fromId", nodeId.toString()), eq("__key.type", type)))

    override fun inEdges(nodeId: UUID, pageSize: Int): Flow<EdgeLike> =
        inEdgeFlow(nodeId)

    override fun inEdges(nodeId: UUID, type: String, pageSize: Int): Flow<EdgeLike> =
        inEdgeFlow(nodeId, type)

    private fun eq(attr: String, value: String): Predicate<EdgeKey, EdgeLike> = Predicates.equal(attr, value)

    private val edgeOrder = Comparator<Map.Entry<EdgeKey, EdgeLike>> { a, b ->
        compareValuesBy(a.key, b.key, { it.fromId.toString() }, { it.toId.toString() }, { it.type })
    }

    // EdgeKey is PartitionAware on fromId, so all outgoing edges for a node land on the same
    // partition — partitionPredicate routes the query there without a cluster-wide scatter.
    private fun outEdgeFlow(nodeId: UUID, predicate: Predicate<EdgeKey, EdgeLike>): Flow<EdgeLike> = flow {
        withContext(Dispatchers.IO) {
            store?.loadEdges(nodeId)?.getOrNull()?.forEach { edge ->
                edgesMap.putIfAbsent(EdgeKey(edge.fromId, edge.toId, edgeType(edge)), edge)
            }
        }
        val partitioned = Predicates.partitionPredicate<EdgeKey, EdgeLike>(nodeId, predicate)
        withContext(Dispatchers.IO) { edgesMap.values(partitioned) }.forEach { emit(it) }
    }

    // ReverseEdgeKey is PartitionAware on toId — all incoming-edge index entries for a node land on
    // the same partition, making this a single-partition key-set query followed by point-lookups.
    private fun inEdgeFlow(nodeId: UUID, typeFilter: String? = null): Flow<EdgeLike> = flow {
        withContext(Dispatchers.IO) {
            store?.loadInEdges(nodeId)?.getOrNull()?.forEach { edge ->
                val type = edgeType(edge)
                edgesMap.putIfAbsent(EdgeKey(edge.fromId, edge.toId, type), edge)
                reverseEdgesMap.putIfAbsent(ReverseEdgeKey(edge.toId, edge.fromId, type), Unit)
            }
        }
        val revPredicate = Predicates.partitionPredicate<ReverseEdgeKey, Unit>(
            nodeId, Predicates.equal("__key.toId", nodeId.toString())
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

    override suspend fun <T> from(nodeId: UUID, block: suspend TraversalBuilderLike.() -> T): Either<AbyssError, T> =
        Either.catch { TraversalBuilder(this, setOf(nodeId)).block() }
            .mapLeft { AbyssError.Unexpected(it) }

    override suspend fun transaction(block: suspend AbyssTransactionLike.() -> Unit): Either<AbyssError, Unit> {
        val buffer = BufferedTransaction()
        try { buffer.block() } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }

        val ops = withContext(Dispatchers.IO) {
            buffer.ops.flatMap { op ->
                if (op is Op.RemoveNode) listOf(op) + cascadeEdgeRemovals(op.id)
                else listOf(op)
            }
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

    private fun cascadeEdgeRemovals(nodeId: UUID): List<Op.RemoveEdge> {
        val out = edgesMap.entrySet(Predicates.partitionPredicate(nodeId, eq("__key.fromId", nodeId.toString())))
            .map { Op.RemoveEdge(it.key.fromId, it.key.toId, it.key.type) }
        val inc = reverseEdgesMap.keySet(Predicates.partitionPredicate<ReverseEdgeKey, Unit>(
            nodeId, Predicates.equal("__key.toId", nodeId.toString())
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
            nodesMap.set(op.node.id, op.node, op.ttl.inWholeSeconds, TimeUnit.SECONDS)
        else
            nodesMap.set(op.node.id, op.node)
        is Op.RemoveNode -> nodesMap.delete(op.id)
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
    data class RemoveNode(val id: UUID) : Op
    data class AddEdge(val edge: EdgeLike, val ttl: Duration?) : Op
    data class RemoveEdge(val fromId: UUID, val toId: UUID, val type: String) : Op
}

private class BufferedTransaction : AbyssTransactionLike {
    val ops = mutableListOf<Op>()
    override fun addNode(node: NodeLike, ttl: Duration?)          { ops += Op.AddNode(node, ttl) }
    override fun removeNode(id: UUID)                              { ops += Op.RemoveNode(id) }
    override fun addEdge(edge: EdgeLike, ttl: Duration?)           { ops += Op.AddEdge(edge, ttl) }
    override fun removeEdge(fromId: UUID, toId: UUID, type: String) { ops += Op.RemoveEdge(fromId, toId, type) }
}
