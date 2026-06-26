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
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import pl.iqtech.abyss.dsl.AbyssEngineLike
import pl.iqtech.abyss.dsl.AbyssTransactionLike
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.TraversalBuilderLike
import pl.iqtech.abyss.graph.loader.EdgeMapLoader
import pl.iqtech.abyss.graph.loader.NodeMapLoader
import pl.iqtech.abyss.graph.serialization.EdgeKeySerializer
import pl.iqtech.abyss.graph.serialization.EdgeLikeHzSerializer
import pl.iqtech.abyss.graph.serialization.NodeLikeHzSerializer
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeLike
import java.util.UUID

class AbyssGraph(
    private val hazelcast: HazelcastInstance,
    val nodesMapName: String,
    val edgesMapName: String,
    val store: AbyssStoreLike? = null
) : AbyssEngineLike {

    private val nodesMap: IMap<UUID, NodeLike>
    private val edgesMap: IMap<EdgeKey, EdgeLike>

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
        edgeFlow(eq("__key.fromId", nodeId.toString()), pageSize)

    override fun outEdges(nodeId: UUID, type: String, pageSize: Int): Flow<EdgeLike> =
        edgeFlow(Predicates.and(eq("__key.fromId", nodeId.toString()), eq("__key.type", type)), pageSize)

    override fun inEdges(nodeId: UUID, pageSize: Int): Flow<EdgeLike> =
        edgeFlow(eq("__key.toId", nodeId.toString()), pageSize)

    override fun inEdges(nodeId: UUID, type: String, pageSize: Int): Flow<EdgeLike> =
        edgeFlow(Predicates.and(eq("__key.toId", nodeId.toString()), eq("__key.type", type)), pageSize)

    private fun eq(attr: String, value: String): Predicate<EdgeKey, EdgeLike> = Predicates.equal(attr, value)

    private val edgeOrder = Comparator<Map.Entry<EdgeKey, EdgeLike>> { a, b ->
        compareValuesBy(a.key, b.key, { it.fromId.toString() }, { it.toId.toString() }, { it.type })
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

    override suspend fun <T> from(nodeId: UUID, block: suspend TraversalBuilderLike.() -> T): Either<AbyssError, T> = TODO()

    override suspend fun transaction(block: suspend AbyssTransactionLike.() -> Unit): Either<AbyssError, Unit> = TODO()
}

// Call before creating the HazelcastInstance — serialization config is immutable after startup.
// Pass the consuming project's SerializersModule so concrete NodeLike/EdgeLike types are known.
fun Config.registerAbyssSerializers(module: SerializersModule = EmptySerializersModule()): Config = apply {
    serializationConfig.compactSerializationConfig.addSerializer(EdgeKeySerializer())
    serializationConfig.addSerializerConfig(SerializerConfig().setTypeClass(NodeLike::class.java).setImplementation(NodeLikeHzSerializer(module)))
    serializationConfig.addSerializerConfig(SerializerConfig().setTypeClass(EdgeLike::class.java).setImplementation(EdgeLikeHzSerializer(module)))
}
