package pl.iqtech.abyss.graph

import arrow.core.Either
import com.hazelcast.config.Config
import com.hazelcast.config.MapStoreConfig
import com.hazelcast.config.SerializerConfig
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import kotlinx.coroutines.flow.Flow
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

    override suspend fun node(id: UUID): Either<AbyssError, NodeLike> = TODO()
    override suspend fun edge(fromId: UUID, toId: UUID, type: String): Either<AbyssError, EdgeLike> = TODO()
    override suspend fun nodeExists(id: UUID): Either<AbyssError, Boolean> = TODO()
    override suspend fun edgeExists(fromId: UUID, toId: UUID, type: String): Either<AbyssError, Boolean> = TODO()

    override fun outEdges(nodeId: UUID, pageSize: Int): Flow<EdgeLike> = TODO()
    override fun outEdges(nodeId: UUID, type: String, pageSize: Int): Flow<EdgeLike> = TODO()
    override fun inEdges(nodeId: UUID, pageSize: Int): Flow<EdgeLike> = TODO()
    override fun inEdges(nodeId: UUID, type: String, pageSize: Int): Flow<EdgeLike> = TODO()

    override suspend fun <T> from(nodeId: UUID, block: suspend TraversalBuilderLike.() -> T): Either<AbyssError, T> = TODO()

    override suspend fun transaction(block: suspend AbyssTransactionLike.() -> Unit): Either<AbyssError, Unit> = TODO()
}

// Call before creating the HazelcastInstance — serialization config is immutable after startup.
fun Config.registerAbyssSerializers(): Config = apply {
    serializationConfig.compactSerializationConfig.addSerializer(EdgeKeySerializer())
    serializationConfig.addSerializerConfig(SerializerConfig().setTypeClass(NodeLike::class.java).setImplementation(NodeLikeHzSerializer()))
    serializationConfig.addSerializerConfig(SerializerConfig().setTypeClass(EdgeLike::class.java).setImplementation(EdgeLikeHzSerializer()))
}
