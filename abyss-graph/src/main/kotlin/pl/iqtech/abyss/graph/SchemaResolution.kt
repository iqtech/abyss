package pl.iqtech.abyss.graph

import pl.iqtech.abyss.store.api.EdgeAdapter
import pl.iqtech.abyss.store.api.HeaderlessMultiSchemaAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeKey
import pl.iqtech.abyss.store.api.NodeKeyKind
import pl.iqtech.abyss.store.api.SchemaDescriptor
import pl.iqtech.abyss.store.api.SchemaTagWidth

// The one thing that differs across the three container tiers (TODO 1.19): how a worker resolves
// the EdgeAdapter for a key. Everything else in AbyssSchemaWorker is identical underneath all three
// tiers.
internal interface SchemaResolution {
    fun edgeAdapterOf(nid: NodeId): EdgeAdapter
}

// Single tier: one fixed adapter, no other schema exists in these maps by construction
// (single-schema graphs don't support cross-schema edges).
internal class SingleSchemaResolution(private val edgeAdapter: EdgeAdapter) : SchemaResolution {
    override fun edgeAdapterOf(nid: NodeId): EdgeAdapter = edgeAdapter
}

// Homogeneous tier: the container's tagWidth AND kind are both known at construction (every schema
// shares one adapter shape), so keys carry no header at all — the descriptor never needs deriving
// from a header nibble. Zero NodeKey header parsing, zero branching per key (contrast
// HeterogeneousSchemaResolution, which still reads a header because its shapes DO vary per tag).
internal class HomogeneousSchemaResolution(tagWidth: SchemaTagWidth, kind: NodeKeyKind) : SchemaResolution {
    private val edgeAdapter: EdgeAdapter = HeaderlessMultiSchemaAdapter(tagWidth, kind)
    override fun edgeAdapterOf(nid: NodeId): EdgeAdapter = edgeAdapter
}

// Heterogeneous tier: today's unchanged general case — derive per key, every call, from the
// self-describing header. No stored state at all (stateless singleton).
internal object HeterogeneousSchemaResolution : SchemaResolution {
    override fun edgeAdapterOf(nid: NodeId): EdgeAdapter = SchemaDescriptor.of(nid).edgeAdapter
}
