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
import kotlinx.coroutines.flow.map
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import pl.iqtech.abyss.dsl.AbyssEngineLike
import pl.iqtech.abyss.dsl.AbyssEphemeralTransactionLike
import pl.iqtech.abyss.dsl.AbyssTransactionLike
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.TraversalBuilderLike
import pl.iqtech.abyss.graph.traversal.TraversalBuilder
import pl.iqtech.abyss.graph.serialization.EdgeKeySerializer
import pl.iqtech.abyss.graph.serialization.EdgeLikeHzSerializer
import pl.iqtech.abyss.graph.serialization.NodeIdSerializer
import pl.iqtech.abyss.graph.serialization.NodeLikeHzSerializer
import pl.iqtech.abyss.graph.serialization.ReverseEdgeKeySerializer
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreLike
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.EdgeAdapter
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.KeyAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeKeyEncoding
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.SchemaEdgeLike
import kotlin.time.Duration

/**
 * A typed, single-schema view (`AbyssEngineLike<ID>`) over an untyped [AbyssSchemaWorker]. It converts
 * domain `ID` ⇄ [NodeId] at the boundary via its [adapter] and delegates every operation to the worker;
 * the worker owns the maps, the store, and the whole commit/traversal engine.
 *
 * Standalone (single-schema) use constructs its own worker over the given maps/stores. Inside an
 * [AbyssGraph] container, the container builds facades over one shared worker.
 */
class AbyssGraphSchema<ID> internal constructor(
    private val adapter: KeyAdapter<ID>,
    private val worker: AbyssSchemaWorker,
) : AbyssEngineLike<ID> {

    // Standalone single-schema: owns a worker over the given maps and optional shared store.
    constructor(
        adapter: KeyAdapter<ID>,
        hazelcast: HazelcastInstance,
        nodesMapName: String,
        edgesMapName: String,
        persistentStore: AbyssStoreLike? = null,
        ephemeralStore: AbyssEphemeralStoreLike? = null,
        asyncCachePopulation: Boolean = false,
    ) : this(adapter, AbyssSchemaWorker(hazelcast, nodesMapName, edgesMapName, persistentStore, ephemeralStore, asyncCachePopulation))

    // Engine a traversal from this schema runs against: the owning container (so a walk can cross
    // schemas over the shared edge map) when registered in one, else this schema's own worker.
    internal var traversalEngine: NodeIdEngine = worker

    override fun allNodeIds(): Flow<ID> = worker.allNodeIds().map { adapter.fromNodeId(it) }

    override suspend fun node(id: ID): Either<AbyssError, NodeLike<ID>> =
        Either.catch { worker.readNode(adapter.toNodeId(id)) }
            .mapLeft { AbyssError.Unexpected(it) }
            .flatMap { n -> @Suppress("UNCHECKED_CAST") (n as NodeLike<ID>?)?.right() ?: AbyssError.NodeNotFound(id as Any).left() }

    override suspend fun edge(fromId: ID, toId: ID, type: String): Either<AbyssError, SchemaEdgeLike<ID>> =
        Either.catch { worker.readEdge(adapter.toNodeId(fromId), adapter.toNodeId(toId), type) }
            .mapLeft { AbyssError.Unexpected(it) }
            .flatMap { e -> @Suppress("UNCHECKED_CAST") (e as SchemaEdgeLike<ID>?)?.right() ?: AbyssError.EdgeNotFound(fromId as Any, toId as Any, type).left() }

    override suspend fun nodeExists(id: ID): Either<AbyssError, Boolean> =
        Either.catch { worker.nodeExists(adapter.toNodeId(id)) }.mapLeft { AbyssError.Unexpected(it) }

    override suspend fun edgeExists(fromId: ID, toId: ID, type: String): Either<AbyssError, Boolean> =
        Either.catch { worker.edgeExists(adapter.toNodeId(fromId), adapter.toNodeId(toId), type) }.mapLeft { AbyssError.Unexpected(it) }

    override fun outEdges(nodeId: ID, pageSize: Int): Flow<SchemaEdgeLike<ID>> =
        worker.outEdges(adapter.toNodeId(nodeId)).typed()
    override fun outEdges(nodeId: ID, type: String, pageSize: Int): Flow<SchemaEdgeLike<ID>> =
        worker.outEdges(adapter.toNodeId(nodeId), type).typed()
    override fun inEdges(nodeId: ID, pageSize: Int): Flow<SchemaEdgeLike<ID>> =
        worker.inEdges(adapter.toNodeId(nodeId)).typed()
    override fun inEdges(nodeId: ID, type: String, pageSize: Int): Flow<SchemaEdgeLike<ID>> =
        worker.inEdges(adapter.toNodeId(nodeId), type).typed()

    @Suppress("UNCHECKED_CAST")
    private fun Flow<SchemaEdgeLike<*>>.typed(): Flow<SchemaEdgeLike<ID>> = this as Flow<SchemaEdgeLike<ID>>

    override suspend fun <T> from(nodeId: ID, block: suspend TraversalBuilderLike<ID>.() -> T): Either<AbyssError, T> =
        Either.catch { TraversalBuilder(traversalEngine, setOf(adapter.toNodeId(nodeId)), adapter).block() }
            .mapLeft { AbyssError.Unexpected(it) }

    override suspend fun transaction(checkIntegrity: Boolean, block: suspend AbyssTransactionLike<ID>.() -> Unit): Either<AbyssError, Unit> {
        val buffer = BufferedTransaction<ID>(
            readNode = { @Suppress("UNCHECKED_CAST") (worker.readNode(adapter.toNodeId(it)) as NodeLike<ID>?) },
            readEdge = { f, t, type -> @Suppress("UNCHECKED_CAST") (worker.readEdge(adapter.toNodeId(f), adapter.toNodeId(t), type) as SchemaEdgeLike<ID>?) }
        )
        try { buffer.block() } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }
        return worker.transaction(buffer.ops.map { it.toNodeOp(adapter) }, checkIntegrity)
    }

    override suspend fun ephemeral(ttl: Duration, checkIntegrity: Boolean, block: suspend AbyssEphemeralTransactionLike<ID>.() -> Unit): Either<AbyssError, Unit> {
        val buffer = BufferedEphemeralTransaction<ID>(
            ttl = ttl,
            readNode = { @Suppress("UNCHECKED_CAST") (worker.readNode(adapter.toNodeId(it)) as NodeLike<ID>?) },
            readEdge = { f, t, type -> @Suppress("UNCHECKED_CAST") (worker.readEdge(adapter.toNodeId(f), adapter.toNodeId(t), type) as SchemaEdgeLike<ID>?) }
        )
        try { buffer.block() } catch (e: Throwable) { return AbyssError.Unexpected(e).left() }
        return worker.ephemeral(buffer.ops.map { it.toNodeOp(adapter) }, checkIntegrity)
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

// Typed op collectors: buffer the domain-level calls, then the facade maps each to a NodeId-level
// NodeOp (via its adapter) for the worker. modify* fetch the old value through the worker at read time.
private sealed interface Op {
    data class AddNode(val node: NodeLike<*>, val ttl: Duration?) : Op
    data class RemoveNode(val id: Any?) : Op
    data class AddEdge(val edge: SchemaEdgeLike<*>, val ttl: Duration?) : Op
    data class RemoveEdge(val fromId: Any?, val toId: Any?, val type: String) : Op
}

@Suppress("UNCHECKED_CAST")
private fun <ID> Op.toNodeOp(adapter: KeyAdapter<ID>): NodeOp = when (this) {
    is Op.AddNode    -> NodeOp.AddNode(adapter.toNodeId((node as NodeLike<ID>).id), node, ttl)
    is Op.RemoveNode -> NodeOp.RemoveNode(adapter.toNodeId(id as ID))
    is Op.AddEdge    -> NodeOp.AddEdge(adapter.toNodeId((edge as SchemaEdgeLike<ID>).fromId), adapter.toNodeId(edge.toId), edge, ttl)
    is Op.RemoveEdge -> NodeOp.RemoveEdge(adapter.toNodeId(fromId as ID), adapter.toNodeId(toId as ID), type)
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
