package pl.iqtech.abyss.graph

import com.hazelcast.map.IMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import pl.iqtech.abyss.store.api.NodeId
import java.util.concurrent.CompletionStage

/**
 * The original Set-per-shard structure (`ai-scripts/ShardedAdjacencyIndexRFC.md`, TODO 2.21) behind the
 * [AdjacencyIndex] seam. Writes are unchanged — the [AdjacencyMutationProcessor] per `(owner, shard)`
 * key, shard chosen by `murmur(neighbor) % shardCount` for write-contention spreading.
 *
 * The read pulls shards in **windows** of [readWindow] keys per `getAll` (TODO 1.26). All of a node's
 * shards co-locate in its partition ([AdjacencyKey] is `PartitionAware` on the owner), so each window is
 * a single-partition batched read. Shard order is hash order — complete and non-overlapping (each
 * neighbor lives in exactly one shard), which matches the unordered contract adjacency reads already had.
 *
 * Default window = every shard, i.e. ONE `getAll` per read (TODO 4.14). Windowing a Set-per-shard layout
 * caps peak heap by at most shardCount/readWindow (2x at 8 of 16) — every shard's full Set still
 * materializes — while each extra window is another invocation: measured, the 8-key windows plus a
 * separate warm probe made a sparse-node hop ~3.8 getAlls instead of 1, and Astronomy 3-hop 27-44% slower.
 * Real bounded heap for supernodes is the paged K-page [AdjacencyIndex] implementation's job.
 */
internal class ShardedAdjacencyIndex(
    private val adjacencyMap: IMap<AdjacencyKey, AdjacencyValue>,
    private val shardCount: Int,
    private val readWindow: Int = shardCount,
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
        emitWindows(owner, direction, edgeTypeTag, 0, partitionKeyOf(owner))
    }

    // Shard 0 (the loaded flag's home) is always in the first window, so the check rides the read's own first
    // getAll — the fetched window is emitted as-is, not read again.
    override suspend fun readIfLoaded(owner: NodeId, direction: AdjacencyDirection, edgeTypeTag: Short?): Flow<AdjacencyEntry>? {
        val pk = partitionKeyOf(owner)
        val end = minOf(readWindow, shardCount)
        val first = withContext(Dispatchers.IO) { adjacencyMap.getAll(shardKeys(owner, direction, 0, end, pk)) }
        if (first[headKey(owner, direction, pk)]?.loaded != true) return null
        return flow {
            emitEntries(first.values, edgeTypeTag)
            emitWindows(owner, direction, edgeTypeTag, end, pk)
        }
    }

    private suspend fun FlowCollector<AdjacencyEntry>.emitWindows(owner: NodeId, direction: AdjacencyDirection, edgeTypeTag: Short?, from: Int, pk: Any) {
        var start = from
        while (start < shardCount) {
            val end = minOf(start + readWindow, shardCount)
            val window = withContext(Dispatchers.IO) { adjacencyMap.getAll(shardKeys(owner, direction, start, end, pk)) }
            emitEntries(window.values, edgeTypeTag)
            start = end
        }
    }

    private suspend fun FlowCollector<AdjacencyEntry>.emitEntries(values: Collection<AdjacencyValue>, edgeTypeTag: Short?) {
        for (value in values) for (entry in value.entries) {
            if (edgeTypeTag == null || entry.edgeTypeTag == edgeTypeTag) emit(entry)
        }
    }

    // One getAll over every shard — all shards share the owner's partition, so it's a single round trip.
    // ponytail: deserializes every entry just to count them. An EntryProcessor count was measured (TODO 4.14)
    // and lost at low degree (runs on all 16 keys) while gaining only 4-9% at high degree, since the member
    // still deserializes in process(). A flat binary AdjacencyValue layout is the real fix if this shows up.
    override suspend fun count(owner: NodeId, direction: AdjacencyDirection, edgeTypeTag: Short?): Int? {
        val pk = partitionKeyOf(owner)
        val all = withContext(Dispatchers.IO) { adjacencyMap.getAll(shardKeys(owner, direction, 0, shardCount, pk)) }
        if (all[headKey(owner, direction, pk)]?.loaded != true) return null
        return all.values.sumOf { v -> if (edgeTypeTag == null) v.entries.size else v.entries.count { it.edgeTypeTag == edgeTypeTag } }
    }

    override fun markLoadedAsync(owner: NodeId, direction: AdjacencyDirection): CompletionStage<*> =
        adjacencyMap.submitToKey(headKey(owner, direction, partitionKeyOf(owner)), AdjacencyLifecycleProcessor(AdjacencyLifecycle.MARK_LOADED))

    // Every key co-locates in the owner's partition: one submitToKeys invocation for both directions.
    override fun dropAsync(owner: NodeId): CompletionStage<*> {
        val pk = partitionKeyOf(owner)
        val keys = shardKeys(owner, AdjacencyDirection.OUT, 0, shardCount, pk) + shardKeys(owner, AdjacencyDirection.IN, 0, shardCount, pk)
        return adjacencyMap.submitToKeys(keys, AdjacencyLifecycleProcessor(AdjacencyLifecycle.DROP))
    }

    private fun headKey(owner: NodeId, direction: AdjacencyDirection, pk: Any) = AdjacencyKey(owner, packShard(direction, 0), pk)
}
