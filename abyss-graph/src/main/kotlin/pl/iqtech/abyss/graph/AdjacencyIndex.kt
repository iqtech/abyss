package pl.iqtech.abyss.graph

import kotlinx.coroutines.flow.Flow
import pl.iqtech.abyss.store.api.NodeId
import java.util.concurrent.CompletionStage

/**
 * Pluggable adjacency storage behind [AbyssSchemaWorker]. Storage-agnostic: no [AdjacencyKey], `IMap`,
 * or shard detail crosses this seam, so the index *shape* is swappable — the sharded Set today
 * ([ShardedAdjacencyIndex]), a paged K-page shape later (see `ai-scripts/AdjacencyIndexInterfaceRFC.md`).
 *
 * The worker keeps what only it can own: warm orchestration (it holds the store), edge-value fetch
 * (`edgesMap`), and the tag registry. This owns only how adjacency entries are stored and streamed.
 */
internal interface AdjacencyIndex {
    /**
     * Async single-entry mutation. Returns the in-flight stage so the caller can batch it with the
     * matching `edgesMap` write and await / fire-and-forget them together, preserving the worker's
     * existing write model.
     */
    fun addAsync(owner: NodeId, direction: AdjacencyDirection, entry: AdjacencyEntry): CompletionStage<*>
    fun removeAsync(owner: NodeId, direction: AdjacencyDirection, neighborId: NodeId, edgeTypeTag: Short): CompletionStage<*>

    /**
     * Streamed read of every entry for this owner/direction; an [edgeTypeTag] restricts to a single edge
     * type. How much it materializes at once is the implementation's trade-off: [ShardedAdjacencyIndex]
     * reads all shards in one round trip by default (TODO 4.14); a paged implementation bounds heap for
     * supernodes. Unchecked: says nothing about completeness — see [readIfLoaded].
     */
    fun read(owner: NodeId, direction: AdjacencyDirection, edgeTypeTag: Short? = null): Flow<AdjacencyEntry>

    /**
     * [read], but only when this owner/direction was [markLoadedAsync]-ed: null means never preloaded from the
     * store (cold, or partial — writes add entries without making the index complete), so the worker preloads.
     * A non-empty index is NOT a completeness signal: after a cold start one write makes it non-empty.
     * Must cost no extra round trip over [read].
     */
    suspend fun readIfLoaded(owner: NodeId, direction: AdjacencyDirection, edgeTypeTag: Short? = null): Flow<AdjacencyEntry>?

    /**
     * Entry count for this owner/direction, restricted to one edge type when [edgeTypeTag] is set; null when not
     * loaded (see [readIfLoaded]). The worker's completeness check for a cached value scan (TODO 4.14): the index
     * is authoritative, so a scan that returns fewer values than this count lost some to cache eviction.
     */
    suspend fun count(owner: NodeId, direction: AdjacencyDirection, edgeTypeTag: Short? = null): Int?

    /** Record that this owner/direction now holds every store edge (called after a successful preload). */
    fun markLoadedAsync(owner: NodeId, direction: AdjacencyDirection): CompletionStage<*>

    /** Drop everything for [owner], both directions, entries and loaded flag (node removal). */
    fun dropAsync(owner: NodeId): CompletionStage<*>
}
