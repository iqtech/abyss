package pl.iqtech.abyss.graph.loader

import com.hazelcast.map.MapLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.NodeLike
import kotlin.uuid.toKotlinUuid

private val log = LoggerFactory.getLogger(NodeMapLoader::class.java)

// MapLoader key type must be java.util.UUID — Hazelcast requires a natively serializable key type.
// Convert to kotlin.uuid.Uuid before delegating to the store.
class NodeMapLoader(private val store: AbyssStoreLike) : MapLoader<java.util.UUID, NodeLike> {

    override fun load(key: java.util.UUID): NodeLike? = runBlocking {
        log.debug("Cache miss: loading node {} from store", key)
        store.loadNode(key.toKotlinUuid()).getOrNull()
    }

    override fun loadAll(keys: Collection<java.util.UUID>): Map<java.util.UUID, NodeLike> = runBlocking {
        keys.map { key -> async { store.loadNode(key.toKotlinUuid()).getOrNull()?.let { key to it } } }
            .awaitAll()
            .filterNotNull()
            .toMap()
    }

    // null = no preloading on startup; the graph is loaded on demand
    override fun loadAllKeys(): Iterable<java.util.UUID>? = null
}
