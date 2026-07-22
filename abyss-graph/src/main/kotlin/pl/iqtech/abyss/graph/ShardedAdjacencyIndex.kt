package pl.iqtech.abyss.graph

import com.hazelcast.map.IMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import pl.iqtech.abyss.store.api.NodeId
import java.util.concurrent.CompletionStage

/**
 * The original Set-per-shard structure (`ai-scripts/ShardedAdjacencyIndexRFC.md`, TODO 2.21) behind the
 * [AdjacencyIndex] seam. Writes are unchanged — the [AdjacencyMutationProcessor] per `(owner, shard)`
 * key, shard chosen by `murmur(neighbor) % shardCount` for write-contention spreading.
 *
 * The read walks shards in bounded **windows** ([readWindow] keys per `getAll`) instead of `getAll`-ing
 * every shard at once, so a supernode streams in window-sized batches rather than materializing its
 * whole neighbor set (TODO 1.26). All of a node's shards co-locate in its partition ([AdjacencyKey] is
 * `PartitionAware` on the owner), so each window is a single-partition batched read. Shard order is
 * hash order — complete and non-overlapping (each neighbor lives in exactly one shard), which matches
 * the unordered contract adjacency reads already had.
 */
internal class ShardedAdjacencyIndex(
    private val adjacencyMap: IMap<AdjacencyKey, AdjacencyValue>,
    private val shardCount: Int,
    private val readWindow: Int = 8,
    private val partitionKeyOf: (NodeId) -> Any,
) : AdjacencyIndex {

    private fun mutationKey(owner: NodeId, direction: AdjacencyDirection, neighbor: NodeId) =
        AdjacencyKey(owner, packShard(direction, shardIndexOf(neighbor, shardCount)), partitionKeyOf(owner))

    private fun shardKeys(owner: NodeId, direction: AdjacencyDirection, from: Int, to: Int, pk: Any) =
        (from until to).map { AdjacencyKey(owner, packShard(direction, it), pk) }.toSet()

    override fun addAsync(owner: NodeId, direction: AdjacencyDirection, entry: AdjacencyEntry): CompletionStage<*> =
        adjacencyMap.submitToKey(mutationKey(owner, direction, entry.neighborId), AdjacencyMutationProcessor(AdjacencyMutation.Add(entry)))

    override fun removeAsync(owner: NodeId, direction: AdjacencyDirection, neighborId: NodeId, edgeTypeTag: Short): CompletionStage<*> =
        adjacencyMap.submitToKey(mutationKey(owner, direction, neighborId), AdjacencyMutationProcessor(AdjacencyMutation.Remove(neighborId, edgeTypeTag)))

    override fun read(owner: NodeId, direction: AdjacencyDirection, edgeTypeTag: Short?): Flow<AdjacencyEntry> = flow {
        val pk = partitionKeyOf(owner)
        var start = 0
        while (start < shardCount) {
            val end = minOf(start + readWindow, shardCount)
            val window = withContext(Dispatchers.IO) { adjacencyMap.getAll(shardKeys(owner, direction, start, end, pk)) }
            for (value in window.values) for (entry in value.entries) {
                if (edgeTypeTag == null || entry.edgeTypeTag == edgeTypeTag) emit(entry)
            }
            start = end
        }
    }

    // Fast path: a non-empty first window proves warm without loading the whole node. Only when the
    // first window is empty (necessarily a sparse or cold node — a dense node fills window 0) do we
    // pay the definitive full-shard check, and for a sparse node that getAll is cheap.
    override suspend fun isEmpty(owner: NodeId, direction: AdjacencyDirection): Boolean {
        val pk = partitionKeyOf(owner)
        val firstEnd = minOf(readWindow, shardCount)
        val first = withContext(Dispatchers.IO) { adjacencyMap.getAll(shardKeys(owner, direction, 0, firstEnd, pk)) }
        if (first.values.any { it.entries.isNotEmpty() }) return false
        if (firstEnd == shardCount) return true
        val rest = withContext(Dispatchers.IO) { adjacencyMap.getAll(shardKeys(owner, direction, firstEnd, shardCount, pk)) }
        return rest.values.all { it.entries.isEmpty() }
    }
}
