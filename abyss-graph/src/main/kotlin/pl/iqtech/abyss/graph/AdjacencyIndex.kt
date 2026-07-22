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
     * Bounded, streamed read. Emits entries in internally-bounded batches so heap stays bounded
     * regardless of degree (a supernode never materializes its whole neighbor set at once). An
     * [edgeTypeTag] restricts to a single edge type.
     */
    fun read(owner: NodeId, direction: AdjacencyDirection, edgeTypeTag: Short? = null): Flow<AdjacencyEntry>

    /**
     * Warm signal for the worker's self-heal: true when this owner/direction holds no cached entries.
     * Must be **bounded** — it may not materialize the whole adjacency just to answer.
     */
    suspend fun isEmpty(owner: NodeId, direction: AdjacencyDirection): Boolean
}
