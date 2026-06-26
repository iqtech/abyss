package pl.iqtech.abyss.graph.loader

import com.hazelcast.map.MapLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.NodeLike
import java.util.UUID

class NodeMapLoader(private val store: AbyssStoreLike) : MapLoader<UUID, NodeLike> {

    override fun load(key: UUID): NodeLike? = runBlocking {
        store.loadNode(key).getOrNull()
    }

    override fun loadAll(keys: Collection<UUID>): Map<UUID, NodeLike> = runBlocking {
        keys.map { key -> async { store.loadNode(key).getOrNull()?.let { key to it } } }
            .awaitAll()
            .filterNotNull()
            .toMap()
    }

    // null = no preloading on startup; the graph is loaded on demand
    override fun loadAllKeys(): Iterable<UUID>? = null
}
