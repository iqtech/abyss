package pl.iqtech.abyss.graph

import com.hazelcast.map.EntryProcessor
import pl.iqtech.abyss.store.api.NodeId

sealed interface AdjacencyMutation {
    data class Add(val entry: AdjacencyEntry) : AdjacencyMutation
    data class Remove(val neighborId: NodeId, val edgeTypeTag: Short) : AdjacencyMutation
}

// Hazelcast guarantees process() runs under a per-key lock, so this read-modify-setValue is safe
// where client-side get-modify-put would race under concurrent edge writes landing on the same
// (nodeId, shard) key (same class of bug already fixed once for cross-schema edges, commit f517536).
class AdjacencyMutationProcessor(val mutation: AdjacencyMutation) : EntryProcessor<AdjacencyKey, AdjacencyValue, Void> {
    override fun process(entry: MutableMap.MutableEntry<AdjacencyKey, AdjacencyValue>): Void? {
        val current = entry.value?.entries ?: emptySet()
        val updated = when (mutation) {
            is AdjacencyMutation.Add -> current + mutation.entry
            is AdjacencyMutation.Remove -> current.filterNot {
                it.neighborId == mutation.neighborId && it.edgeTypeTag == mutation.edgeTypeTag
            }.toSet()
        }
        if (updated != current) entry.setValue(AdjacencyValue(updated))
        return null
    }
}
