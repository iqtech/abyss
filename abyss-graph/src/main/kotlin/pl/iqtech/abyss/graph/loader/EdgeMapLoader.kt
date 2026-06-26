package pl.iqtech.abyss.graph.loader

import com.hazelcast.map.MapLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.EdgeLike

class EdgeMapLoader(private val store: AbyssStoreLike) : MapLoader<EdgeKey, EdgeLike> {

    override fun load(key: EdgeKey): EdgeLike? = runBlocking {
        store.loadEdge(key.fromId, key.toId, key.type).getOrNull()
    }

    override fun loadAll(keys: Collection<EdgeKey>): Map<EdgeKey, EdgeLike> = runBlocking {
        keys.map { key -> async { store.loadEdge(key.fromId, key.toId, key.type).getOrNull()?.let { key to it } } }
            .awaitAll()
            .filterNotNull()
            .toMap()
    }

    override fun loadAllKeys(): Iterable<EdgeKey>? = null
}
