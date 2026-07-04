package pl.iqtech.abyss.graph

import com.hazelcast.core.HazelcastInstance
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreLike
import pl.iqtech.abyss.store.api.AbyssStoreLike
import pl.iqtech.abyss.store.api.KeyAdapter

/**
 * The zero-overhead single-schema tier (TODO 1.19): [pl.iqtech.abyss.store.api.NodeId] is raw
 * adapter-encoded bytes, no 1.15 header byte, nothing to decode per key. Thin factory over
 * [AbyssGraphSchema]'s standalone constructor — there is exactly one schema by definition, so
 * there's no registration state to hold (unlike [HomogeneousSchemaGraph]/[HeterogeneousSchemaGraph],
 * which can register more than one tag).
 */
fun <ID> SingleSchemaGraph(
    adapter: KeyAdapter<ID>,
    hazelcast: HazelcastInstance,
    nodesMapName: String,
    edgesMapName: String,
    persistentStore: AbyssStoreLike? = null,
    ephemeralStore: AbyssEphemeralStoreLike? = null,
    asyncCachePopulation: Boolean = false,
): AbyssGraphSchema<ID> =
    AbyssGraphSchema(adapter, hazelcast, nodesMapName, edgesMapName, persistentStore, ephemeralStore, asyncCachePopulation)
