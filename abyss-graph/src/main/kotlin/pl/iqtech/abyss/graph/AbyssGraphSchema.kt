package pl.iqtech.abyss.graph

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import com.hazelcast.config.Config
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.query.Predicate
import com.hazelcast.query.Predicates
import com.hazelcast.config.SerializerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import pl.iqtech.abyss.dsl.AbyssEngineLike
import pl.iqtech.abyss.dsl.AbyssEphemeralTransactionLike
import pl.iqtech.abyss.dsl.AbyssTransactionLike
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.TraversalBuilderLike
import pl.iqtech.abyss.graph.traversal.TraversalBuilder
import pl.iqtech.abyss.graph.serialization.AdjacencyEntrySerializer
import pl.iqtech.abyss.graph.serialization.AdjacencyKeySerializer
import pl.iqtech.abyss.graph.serialization.AdjacencyMutationProcessorSerializer
import pl.iqtech.abyss.graph.serialization.AdjacencyValueSerializer
import pl.iqtech.abyss.graph.serialization.EdgeKeySerializer
import pl.iqtech.abyss.graph.serialization.EdgeLikeHzSerializer
import pl.iqtech.abyss.graph.serialization.NodeIdSerializer
import pl.iqtech.abyss.graph.serialization.NodeLikeHzSerializer
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreLike
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.EdgeAdapter
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.HeaderlessKeyAdapter
import pl.iqtech.abyss.store.api.HeaderlessSchemaKeyAdapter
import pl.iqtech.abyss.store.api.KeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeKeyEncoding
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.SchemaKeyAdapter
import pl.iqtech.abyss.store.api.SchemaTagWidth
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A typed, single-schema view (`AbyssEngineLike<ID>`) over an untyped [AbyssSchemaWorker]. It converts
 * domain `ID` ⇄ [NodeId] at the boundary via its [adapter] and delegates every operation to the worker;
 * the worker owns the maps, the store, and the whole commit/traversal engine.
 *
 * Standalone (single-schema) use constructs its own worker over the given maps/stores. Inside a
 * [HomogeneousSchemaGraph]/[HeterogeneousSchemaGraph] container, the container builds facades over
 * one shared worker.
 */
class AbyssGraphSchema<ID> internal constructor(
    private val adapter: KeyAdapter<ID>,
    private val worker: AbyssSchemaWorker,
) : AbyssEngineLike<ID> {

    // Standalone single-schema (TODO 1.19's SingleSchemaGraph tier): owns a worker over the given
    // maps and optional shared store. Headerless — NodeId is raw adapter-encoded bytes, no 1.15
    // header byte, since there is exactly one schema and nothing to self-describe against.
    constructor(
        adapter: KeyAdapter<ID>,
        hazelcast: HazelcastInstance,
        nodesMapName: String,
        edgesMapName: String,
        edgesAdjacencyMapName: String = "$edgesMapName-adjacency",
        persistentStore: AbyssStoreLike? = null,
        ephemeralStore: AbyssEphemeralStoreLike? = null,
        asyncCachePopulation: Boolean = false,
        module: SerializersModule = EmptySerializersModule(),
        adjacencyShardCount: Int = 16,
        hopFanoutParallelism: Int = 256,
    ) : this(
        HeaderlessKeyAdapter(adapter),
        AbyssSchemaWorker(
            hazelcast, nodesMapName, edgesMapName, edgesAdjacencyMapName, persistentStore, ephemeralStore, asyncCachePopulation,
            SingleSchemaResolution(HeaderlessKeyAdapter(adapter)), module, adjacencyShardCount, hopFanoutParallelism,
        ),
    )

    // Engine a traversal from this schema runs against: the owning container (so a walk can cross
    // schemas over the shared edge map) when registered in one, else this schema's own worker.
    internal var traversalEngine: NodeIdEngine = worker

    // Set by the owning Homogeneous/HeterogeneousSchemaGraph on register()/forTag(); null for a
    // standalone (SingleSchemaGraph) schema, which has no cross-schema concept at all.
    internal var crossEdgeGate: (() -> AbyssError?)? = null
    internal var crossEdgeTagCheck: ((NodeId, NodeId) -> AbyssError?)? = null

    // Scoped to this schema's own nodes: worker.allNodeIds() enumerates every key in the shared
    // nodesMap across all registered tags in a Homogeneous/HeterogeneousSchemaGraph container;
    // ownsNodeId filters out every other schema's keys before they reach adapter.fromNodeId.
    override fun allNodeIds(): Flow<ID> = worker.allNodeIds().filter { adapter.ownsNodeId(it) }.map { adapter.fromNodeId(it) }

    // TODO 1.23: DB-backed scan — unlike allNodeIds() above (Hazelcast-cache-only, misses cold/evicted
    // nodes), this reaches the store and won't drop rows just because they aren't warm in the cache.
    // Same ownership-filter + typed-conversion shape as allNodeIds(), sourced from worker.scanNodeIds()
    // instead of worker.allNodeIds(). Deliberately not part of AbyssEngineLike<ID> (plain method on the
    // concrete class only, same as the container-level scanNodeIds/scanEdgeIds) — exportGraphLines is
    // its first caller. worker.scanNodeIds() also scans the ephemeral store; an ephemeral-only id that
    // slips through the ownership filter has no persistent row, so node(id) below reads it as
    // NodeNotFound and it's silently skipped — a wasted round trip, not a correctness issue.
    fun scanNodeIds(tag: String? = null, parallelism: Int = 4): Flow<ID> =
        worker.scanNodeIds(tag, parallelism).filter { adapter.ownsNodeId(it) }.map { adapter.fromNodeId(it) }

    override suspend fun node(id: ID): Either<AbyssError, NodeLike<ID>> =
        Either.catch { worker.readNode(adapter.toNodeId(id)) }
            .mapLeft { AbyssError.Unexpected(it) }
            .flatMap { n -> @Suppress("UNCHECKED_CAST") (n as NodeLike<ID>?)?.right() ?: AbyssError.NodeNotFound(id as Any).left() }

    override suspend fun edge(fromId: ID, toId: ID, type: String): Either<AbyssError, EdgeLike<ID, ID>> =
        Either.catch { worker.readEdge(adapter.toNodeId(fromId), adapter.toNodeId(toId), type) }
            .mapLeft { AbyssError.Unexpected(it) }
            .flatMap { e -> @Suppress("UNCHECKED_CAST") (e as EdgeLike<ID, ID>?)?.right() ?: AbyssError.EdgeNotFound(fromId as Any, toId as Any, type).left() }

    override suspend fun nodeExists(id: ID): Either<AbyssError, Boolean> =
        Either.catch { worker.nodeExists(adapter.toNodeId(id)) }.mapLeft { AbyssError.Unexpected(it) }

    override suspend fun edgeExists(fromId: ID, toId: ID, type: String): Either<AbyssError, Boolean> =
        Either.catch { worker.edgeExists(adapter.toNodeId(fromId), adapter.toNodeId(toId), type) }.mapLeft { AbyssError.Unexpected(it) }

    override fun outEdges(nodeId: ID, pageSize: Int, includeEphemeral: Boolean): Flow<EdgeLike<ID, ID>> =
        worker.outEdges(adapter.toNodeId(nodeId), pageSize = pageSize, includeEphemeral = includeEphemeral).typed()
    override fun outEdges(nodeId: ID, type: String, pageSize: Int, includeEphemeral: Boolean): Flow<EdgeLike<ID, ID>> =
        worker.outEdges(adapter.toNodeId(nodeId), type, pageSize, includeEphemeral).typed()
    override fun inEdges(nodeId: ID, pageSize: Int): Flow<EdgeLike<ID, ID>> =
        worker.inEdges(adapter.toNodeId(nodeId), pageSize = pageSize).typed()
    override fun inEdges(nodeId: ID, type: String, pageSize: Int): Flow<EdgeLike<ID, ID>> =
        worker.inEdges(adapter.toNodeId(nodeId), type, pageSize).typed()

    @Suppress("UNCHECKED_CAST")
    private fun Flow<EdgeLike<*, *>>.typed(): Flow<EdgeLike<ID, ID>> = this as Flow<EdgeLike<ID, ID>>

    override suspend fun <T> from(nodeId: ID, block: suspend TraversalBuilderLike<ID>.() -> T): Either<AbyssError, T> =
        Either.catch { TraversalBuilder(traversalEngine, setOf(adapter.toNodeId(nodeId)), adapter).block() }
            .mapLeft { AbyssError.Unexpected(it) }

    override suspend fun <T> from(nodeIds: Set<ID>, block: suspend TraversalBuilderLike<ID>.() -> T): Either<AbyssError, T> =
        Either.catch { TraversalBuilder(traversalEngine, nodeIds.map(adapter::toNodeId).toSet(), adapter).block() }
            .mapLeft { AbyssError.Unexpected(it) }

    override suspend fun transaction(checkIntegrity: Boolean, block: suspend AbyssTransactionLike<ID>.() -> Unit): Either<AbyssError, Unit> {
        val buffer = newBuffer()
        try { buffer.block() } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }
        val headerless = adapter is HeaderlessSchemaKeyAdapter<*>
        val width = crossEdgeTagWidth()
        crossEdgeCheck(buffer.ops, checkIntegrity, width, headerless)?.let { return it.left() }
        return worker.transaction(toNodeOps(buffer.ops), checkIntegrity)
    }

    // Shared with MultiSchemaTransactionBuffer.on(schema): lets a container-level transaction stage
    // ops against this schema through the same buffering/conversion path a solo transaction{} uses.
    internal fun newBuffer(): BufferedTransaction<ID> = BufferedTransaction(
        readNode = { @Suppress("UNCHECKED_CAST") (worker.readNode(adapter.toNodeId(it)) as NodeLike<ID>?) },
        readEdge = { f, t, type -> @Suppress("UNCHECKED_CAST") (worker.readEdge(adapter.toNodeId(f), adapter.toNodeId(t), type) as EdgeLike<ID, ID>?) }
    )

    internal fun toNodeOps(ops: List<Op>): List<NodeOp> {
        val headerless = adapter is HeaderlessSchemaKeyAdapter<*>
        val width = crossEdgeTagWidth()
        return ops.map { it.toNodeOp(adapter, width, headerless) }
    }

    override suspend fun batchTransaction(
        batchSize: Int,
        checkIntegrity: Boolean,
        block: suspend AbyssTransactionLike<ID>.() -> Unit
    ): Either<AbyssError, Unit> {
        val buffer = BufferedTransaction<ID>(
            readNode = { @Suppress("UNCHECKED_CAST") (worker.readNode(adapter.toNodeId(it)) as NodeLike<ID>?) },
            readEdge = { f, t, type -> @Suppress("UNCHECKED_CAST") (worker.readEdge(adapter.toNodeId(f), adapter.toNodeId(t), type) as EdgeLike<ID, ID>?) }
        )
        try { buffer.block() } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }
        val headerless = adapter is HeaderlessSchemaKeyAdapter<*>
        val width = crossEdgeTagWidth()
        crossEdgeCheck(buffer.ops, checkIntegrity, width, headerless)?.let { return it.left() }
        return worker.batchTransaction(buffer.ops.map { it.toNodeOp(adapter, width, headerless) }, batchSize, checkIntegrity)
    }

    override suspend fun ephemeral(ttl: Duration, checkIntegrity: Boolean, block: suspend AbyssEphemeralTransactionLike<ID>.() -> Unit): Either<AbyssError, Unit> {
        // Sub-second TTL floors to 0 in both YCQL (USING TTL 0) and Hazelcast (setAsync ttl=0), where
        // 0 means "never expire" — so <1s would make ephemeral data immortal. Reject it at the door.
        require(ttl >= 1.seconds) { "ephemeral TTL must be >= 1s (got $ttl); sub-second TTLs never expire" }
        val buffer = BufferedEphemeralTransaction<ID>(
            ttl = ttl,
            readNode = { @Suppress("UNCHECKED_CAST") (worker.readNode(adapter.toNodeId(it)) as NodeLike<ID>?) },
            readEdge = { f, t, type -> @Suppress("UNCHECKED_CAST") (worker.readEdge(adapter.toNodeId(f), adapter.toNodeId(t), type) as EdgeLike<ID, ID>?) }
        )
        try { buffer.block() } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }
        val headerless = adapter is HeaderlessSchemaKeyAdapter<*>
        val width = crossEdgeTagWidth()
        crossEdgeCheck(buffer.ops, checkIntegrity, width, headerless)?.let { return it.left() }
        return worker.ephemeral(buffer.ops.map { it.toNodeOp(adapter, width, headerless) }, checkIntegrity)
    }

    // A container fixes one SchemaTagWidth for its whole lifetime, already carried on this schema's
    // own (Homogeneous/Heterogeneous) adapter — reconstructed here rather than duplicated per edge
    // class. Only ever consulted when cross ops are present (see crossEdgeCheck), where adapter is
    // guaranteed to be one of these two (a standalone schema has no crossEdgeGate and bails first).
    private fun crossEdgeTagWidth(): SchemaTagWidth = when (val a = adapter) {
        is SchemaKeyAdapter<*> -> a.width
        is HeaderlessSchemaKeyAdapter<*> -> a.width
        else -> SchemaTagWidth.NONE
    }

    // Cross-edge ops (if any) need the owning container's gate/tag-check, since a standalone schema
    // has neither. Gate runs unconditionally (mirrors HeterogeneousSchemaGraph's existing
    // regardless-of-checkIntegrity allowCrossSchemaEdges check); the tag check only runs when
    // checkIntegrity is requested (mirrors both containers' existing asymmetry).
    private fun crossEdgeCheck(ops: List<Op>, checkIntegrity: Boolean, width: SchemaTagWidth, headerless: Boolean): AbyssError? {
        val crossOps = ops.filter { it is Op.AddCrossEdge || it is Op.RemoveCrossEdge }
        if (crossOps.isEmpty()) return null
        val gate = crossEdgeGate
            ?: return AbyssError.IntegrityError("addCrossEdge requires a HomogeneousSchemaGraph/HeterogeneousSchemaGraph container")
        gate()?.let { return it }
        if (!checkIntegrity) return null
        for (op in crossOps) {
            val (fromNid, toNid) = when (op) {
                is Op.AddCrossEdge -> crossSchemaEndpoints(op.edge, width, headerless)
                is Op.RemoveCrossEdge -> crossSchemaEndpoints(op.edgeClass, op.fromId, op.toId, width, headerless)
                else -> error("unreachable")
            }
            crossEdgeTagCheck?.invoke(fromNid, toNid)?.let { return it }
        }
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
        val tagEq = Predicates.and<K, V>(
            Predicates.equal<K, V>("__key.${field}TagHi", enc.tag.hi),
            Predicates.equal<K, V>("__key.${field}TagLo", enc.tag.lo)
        )
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
// Pass the consuming project's SerializersModule so concrete NodeLike/EdgeLike types are known.
// The adapter must match the KeyAdapter used by every AbyssGraphSchema<ID> sharing this HazelcastInstance:
// EdgeKey compact serialization is bound to one adapter's native field encoding.
//
// Also validates every registered NodeLike/EdgeLike class's @TypeTag eagerly, here, before the
// HazelcastInstance even exists — the earliest possible point to fail loudly on a missing or
// colliding tag (TypeTagRegistry.of throws; its result is otherwise unused here).
fun Config.registerAbyssSerializers(adapter: EdgeAdapter, module: SerializersModule = EmptySerializersModule()): Config = apply {
    TypeTagRegistry.of(module)
    serializationConfig.compactSerializationConfig.addSerializer(NodeIdSerializer())
    serializationConfig.compactSerializationConfig.addSerializer(EdgeKeySerializer(adapter))
    serializationConfig.compactSerializationConfig.addSerializer(AdjacencyKeySerializer())
    serializationConfig.compactSerializationConfig.addSerializer(AdjacencyEntrySerializer())
    serializationConfig.compactSerializationConfig.addSerializer(AdjacencyValueSerializer())
    serializationConfig.compactSerializationConfig.addSerializer(AdjacencyMutationProcessorSerializer())
    serializationConfig.addSerializerConfig(SerializerConfig().setTypeClass(NodeLike::class.java).setImplementation(NodeLikeHzSerializer(module)))
    serializationConfig.addSerializerConfig(SerializerConfig().setTypeClass(EdgeLike::class.java).setImplementation(EdgeLikeHzSerializer(module)))
}

// Typed op collectors: buffer the domain-level calls, then the facade maps each to a NodeId-level
// NodeOp (via its adapter) for the worker. modify* fetch the old value through the worker at read time.
internal sealed interface Op {
    data class AddNode(val node: NodeLike<*>, val ttl: Duration?, val tags: Set<String>) : Op
    data class RemoveNode(val id: Any?) : Op
    data class AddEdge(val edge: EdgeLike<*, *>, val ttl: Duration?, val tags: Set<String>) : Op
    data class RemoveEdge(val fromId: Any?, val toId: Any?, val type: String) : Op
    data class AddCrossEdge(val edge: EdgeLike<*, *>, val ttl: Duration?, val tags: Set<String>) : Op
    data class RemoveCrossEdge(val edgeClass: KClass<out EdgeLike<*, *>>, val fromId: Any?, val toId: Any?) : Op
}

@Suppress("UNCHECKED_CAST")
internal fun <ID> Op.toNodeOp(adapter: KeyAdapter<ID>, width: SchemaTagWidth, headerless: Boolean): NodeOp = when (this) {
    is Op.AddNode    -> NodeOp.AddNode(adapter.toNodeId((node as NodeLike<ID>).id), node, ttl, tags)
    is Op.RemoveNode -> NodeOp.RemoveNode(adapter.toNodeId(id as ID))
    is Op.AddEdge    -> NodeOp.AddEdge(adapter.toNodeId((edge as EdgeLike<ID, ID>).fromId), adapter.toNodeId(edge.toId), edge, ttl, tags)
    is Op.RemoveEdge -> NodeOp.RemoveEdge(adapter.toNodeId(fromId as ID), adapter.toNodeId(toId as ID), type)
    is Op.AddCrossEdge -> crossSchemaEndpoints(edge, width, headerless).let { (f, t) -> NodeOp.AddEdge(f, t, edge, ttl, tags) }
    is Op.RemoveCrossEdge -> crossSchemaEndpoints(edgeClass, fromId, toId, width, headerless).let { (f, t) -> NodeOp.RemoveEdge(f, t, crossSchemaEdgeType(edgeClass)) }
}

internal class BufferedTransaction<ID>(
    private val readNode: suspend (ID) -> NodeLike<ID>?,
    private val readEdge: suspend (ID, ID, String) -> EdgeLike<ID, ID>?
) : AbyssTransactionLike<ID> {
    val ops = mutableListOf<Op>()
    override fun addNode(node: NodeLike<ID>, tags: Set<String>)                          { ops += Op.AddNode(node, null, tags) }
    override fun removeNode(id: ID)                                    { ops += Op.RemoveNode(id) }
    override fun addEdge(edge: EdgeLike<ID, ID>, tags: Set<String>)                          { ops += Op.AddEdge(edge, null, tags) }
    override fun removeEdge(fromId: ID, toId: ID, type: String)       { ops += Op.RemoveEdge(fromId, toId, type) }
    override fun addCrossEdge(edge: EdgeLike<*, *>, tags: Set<String>)                    { ops += Op.AddCrossEdge(edge, null, tags) }
    override fun removeCrossEdge(edgeClass: KClass<out EdgeLike<*, *>>, fromId: Any?, toId: Any?) {
        ops += Op.RemoveCrossEdge(edgeClass, fromId, toId)
    }
    override suspend fun modifyNode(id: ID, tags: Set<String>, transform: (NodeLike<ID>?) -> NodeLike<ID>) {
        ops += Op.AddNode(transform(readNode(id)), null, tags)
    }
    override suspend fun modifyEdge(fromId: ID, toId: ID, type: String, tags: Set<String>, transform: (EdgeLike<ID, ID>?) -> EdgeLike<ID, ID>) {
        ops += Op.RemoveEdge(fromId, toId, type)
        ops += Op.AddEdge(transform(readEdge(fromId, toId, type)), null, tags)
    }
}

private class BufferedEphemeralTransaction<ID>(
    private val ttl: Duration,
    private val readNode: suspend (ID) -> NodeLike<ID>?,
    private val readEdge: suspend (ID, ID, String) -> EdgeLike<ID, ID>?
) : AbyssEphemeralTransactionLike<ID> {
    val ops = mutableListOf<Op>()
    override fun addNode(node: NodeLike<ID>, tags: Set<String>)                          { ops += Op.AddNode(node, ttl, tags) }
    override fun removeNode(id: ID)                                    { ops += Op.RemoveNode(id) }
    override fun addEdge(edge: EdgeLike<ID, ID>, tags: Set<String>)                          { ops += Op.AddEdge(edge, ttl, tags) }
    override fun removeEdge(fromId: ID, toId: ID, type: String)       { ops += Op.RemoveEdge(fromId, toId, type) }
    override fun addCrossEdge(edge: EdgeLike<*, *>, tags: Set<String>)                    { ops += Op.AddCrossEdge(edge, ttl, tags) }
    override fun removeCrossEdge(edgeClass: KClass<out EdgeLike<*, *>>, fromId: Any?, toId: Any?) {
        ops += Op.RemoveCrossEdge(edgeClass, fromId, toId)
    }
    override suspend fun modifyNode(id: ID, tags: Set<String>, transform: (NodeLike<ID>?) -> NodeLike<ID>) {
        ops += Op.AddNode(transform(readNode(id)), ttl, tags)
    }
    override suspend fun modifyEdge(fromId: ID, toId: ID, type: String, tags: Set<String>, transform: (EdgeLike<ID, ID>?) -> EdgeLike<ID, ID>) {
        ops += Op.RemoveEdge(fromId, toId, type)
        ops += Op.AddEdge(transform(readEdge(fromId, toId, type)), ttl, tags)
    }
}
