package pl.iqtech.abyss.graph

import pl.iqtech.abyss.store.api.EdgeAdapter
import pl.iqtech.abyss.store.api.HeaderlessMultiSchemaAdapter
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeKey
import pl.iqtech.abyss.store.api.NodeKeyKind
import pl.iqtech.abyss.store.api.SchemaDescriptor
import pl.iqtech.abyss.store.api.SchemaTagWidth

// The one thing that differs across the three container tiers (TODO 1.19): how a worker resolves
// the EdgeAdapter for a key, and how it decides two NodeIds share a schema for cascade-delete
// scoping. Everything else in AbyssSchemaWorker is identical underneath all three tiers.
internal interface SchemaResolution {
    fun edgeAdapterOf(nid: NodeId): EdgeAdapter
    fun sameSchema(a: NodeId, b: NodeId): Boolean
}

// Single tier: one fixed adapter, no other schema exists in these maps by construction
// (single-schema graphs don't support cross-schema edges) — sameSchema is trivially true.
internal class SingleSchemaResolution(private val edgeAdapter: EdgeAdapter) : SchemaResolution {
    override fun edgeAdapterOf(nid: NodeId): EdgeAdapter = edgeAdapter
    override fun sameSchema(a: NodeId, b: NodeId): Boolean = true
}

// Homogeneous tier: the container's tagWidth AND kind are both known at construction (every schema
// shares one adapter shape), so keys carry no header at all — the descriptor never needs deriving
// from a header nibble, and sameSchema only needs the (headerless) tag, since width/kind can't vary
// within this container. Zero NodeKey header parsing, zero branching per key (contrast
// HeterogeneousSchemaResolution, which still reads a header because its shapes DO vary per tag).
internal class HomogeneousSchemaResolution(private val tagWidth: SchemaTagWidth, kind: NodeKeyKind) : SchemaResolution {
    private val edgeAdapter: EdgeAdapter = HeaderlessMultiSchemaAdapter(tagWidth, kind)
    override fun edgeAdapterOf(nid: NodeId): EdgeAdapter = edgeAdapter
    override fun sameSchema(a: NodeId, b: NodeId): Boolean =
        NodeKey.tagHeaderless(a, tagWidth) == NodeKey.tagHeaderless(b, tagWidth)
}

// Heterogeneous tier: today's unchanged general case — derive per key, every call, from the
// self-describing header. No stored state at all (stateless singleton).
internal object HeterogeneousSchemaResolution : SchemaResolution {
    override fun edgeAdapterOf(nid: NodeId): EdgeAdapter = SchemaDescriptor.of(nid).edgeAdapter
    override fun sameSchema(a: NodeId, b: NodeId): Boolean = taggedSameSchema(a, b)
}

// Two NodeIds belong to the same schema when their self-describing prefixes (tag width + tag)
// match. Cross-schema edges in the shared map have a foreign opposite endpoint; cascade skips them.
private fun taggedSameSchema(a: NodeId, b: NodeId): Boolean =
    runCatching { NodeKey.width(a) == NodeKey.width(b) && NodeKey.tag(a) == NodeKey.tag(b) }.getOrDefault(false)
