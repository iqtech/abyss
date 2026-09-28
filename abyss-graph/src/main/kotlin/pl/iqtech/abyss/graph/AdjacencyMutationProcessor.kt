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
        if (updated != current) entry.setValue(AdjacencyValue(updated, entry.value?.loaded ?: false))
        return null
    }
}

// Per-(owner, direction) lifecycle, separate from AdjacencyMutationProcessor on purpose: that serializer decodes
// an unknown kind as Remove, so an older member would misread a new kind. A new Compact type fails to
// deserialize there instead — the flag just isn't set, and the node preloads again (the safe direction).
enum class AdjacencyLifecycle { MARK_LOADED, DROP }

class AdjacencyLifecycleProcessor(val action: AdjacencyLifecycle) : EntryProcessor<AdjacencyKey, AdjacencyValue, Void> {
    override fun process(entry: MutableMap.MutableEntry<AdjacencyKey, AdjacencyValue>): Void? {
        when (action) {
            // Creates shard 0 when absent: a zero-degree node is marked loaded too (no store hit per read).
            AdjacencyLifecycle.MARK_LOADED -> if (entry.value?.loaded != true) entry.setValue(AdjacencyValue(entry.value?.entries ?: emptySet(), loaded = true))
            // setValue(null) is Hazelcast's in-processor delete; the map's V is non-null, hence the cast.
            @Suppress("UNCHECKED_CAST")
            AdjacencyLifecycle.DROP -> if (entry.value != null) (entry as MutableMap.MutableEntry<AdjacencyKey, AdjacencyValue?>).setValue(null)
        }
        return null
    }
}
