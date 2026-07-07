package pl.iqtech.abyss.store.api

import kotlin.reflect.KClass

// Declares how to turn each endpoint's domain id into a NodeId, mirroring the SchemaKeyAdapter a
// container's register()/forTag() built for that schema — lets a cross-schema edge class be declared
// with real domain types (e.g. LivesIn(fromId: Uuid, toId: Long)) instead of pre-built NodeIds.
// fromTag/toTag are Long (SchemaTag's single-arg ctor, hi=0): covers BYTE/SHORT/INT/LONG tag widths.
// Ceiling: a genuine 128-bit tag (SchemaTagWidth.UUID with hi != 0) can't be expressed here — use the
// raw EdgeLike<NodeId,NodeId> addCrossEdge escape hatch for that rare case.
//
// No width here: a container fixes one SchemaTagWidth for its entire lifetime
// (HeterogeneousSchemaGraph.tagWidth / HomogeneousSchemaGraph.tagWidth), so the resolver reconstructs
// it from the container at the call site instead of duplicating it per edge class.
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CrossSchemaEdge(
    val fromTag: Long,
    val fromAdapter: KClass<out KeyAdapter<*>>,
    val toTag: Long,
    val toAdapter: KClass<out KeyAdapter<*>>,
)
