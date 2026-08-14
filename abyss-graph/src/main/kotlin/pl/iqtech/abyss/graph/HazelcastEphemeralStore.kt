package pl.iqtech.abyss.graph

import arrow.core.Either
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.hazelcast.query.Predicates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import pl.iqtech.abyss.dsl.EdgeKey
import pl.iqtech.abyss.dsl.serialName
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreLike
import pl.iqtech.abyss.store.api.AbyssEphemeralStoreTransactionLike
import pl.iqtech.abyss.store.api.AbyssError
import pl.iqtech.abyss.store.api.EdgeLike
import pl.iqtech.abyss.store.api.NodeId
import pl.iqtech.abyss.store.api.NodeLike
import pl.iqtech.abyss.store.api.StoredEdge
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// Default ephemeral backing when a schema configures no explicit AbyssEphemeralStoreLike (RFC:
// ai-scripts/HazelcastEphemeralStoreRFC.md). Memory-only by construction: dedicated maps, no
// MapStore (enforced below, not just documented), TTL is the map's native expiry. Internal to
// abyss-graph — not a general-purpose reusable store, only ever built by AbyssSchemaWorker's
// null-fallback with derived map names.
internal class HazelcastEphemeralStore(
    private val hazelcast: HazelcastInstance,
    private val ephEdgesMapName: String,
    private val ephNodesMapName: String,
) : AbyssEphemeralStoreLike {

    private val ephEdges: IMap<EdgeKey, EdgeLike<*, *>> = hazelcast.getMap(ephEdgesMapName)
    private val ephNodes: IMap<NodeId, NodeLike<*>> = hazelcast.getMap(ephNodesMapName)

    init {
        // Mandatory, not documentation-only: MapStore on either map would carry secrets to disk,
        // which is the exact thing ephemeral-via-Hazelcast exists to prevent. Same idiom as the
        // adjacency-map eviction guard (AbyssSchemaWorker's init block).
        listOf(ephEdgesMapName, ephNodesMapName).forEach { name ->
            runCatching { hazelcast.config.findMapConfig(name) }.getOrNull()?.let { cfg ->
                require(cfg.mapStoreConfig?.isEnabled != true) {
                    "MapStore is configured on ephemeral map '$name' — ephemeral data must never persist to disk. " +
                    "Remove the MapStore config from this map."
                }
            }
        }
    }

    // Hazelcast's own no-expiry sentinel is Long.MAX_VALUE, not null; an entry with no TTL set
    // still returns an EntryView, just with that sentinel expirationTime.
    private fun remainingTtl(expirationTime: Long?): Duration? {
        if (expirationTime == null || expirationTime == Long.MAX_VALUE) return null
        return (expirationTime - System.currentTimeMillis()).milliseconds
    }

    override suspend fun loadNode(id: NodeId): Either<AbyssError, Pair<NodeLike<*>?, Duration?>> = Either.catch {
        withContext(Dispatchers.IO) {
            val node = ephNodes[id] ?: return@withContext null to null
            val remaining = remainingTtl(ephNodes.getEntryView(id)?.expirationTime)
            if (remaining != null && remaining <= Duration.ZERO) null to null else node to remaining
        }
    }.mapLeft { AbyssError.Unexpected(it) }

    override suspend fun loadEdge(fromId: NodeId, toId: NodeId, type: String): Either<AbyssError, Pair<EdgeLike<*, *>?, Duration?>> = Either.catch {
        withContext(Dispatchers.IO) {
            val key = EdgeKey(fromId, toId, type)
            val edge = ephEdges[key] ?: return@withContext null to null
            val remaining = remainingTtl(ephEdges.getEntryView(key)?.expirationTime)
            if (remaining != null && remaining <= Duration.ZERO) null to null else edge to remaining
        }
    }.mapLeft { AbyssError.Unexpected(it) }

    // Single-partition scan (EdgeKey's default partition key is fromId-derived), then a client-side
    // filter — deliberately simpler than the persistent edgesMap's native-field predicate (which
    // needs a schema EdgeAdapter this store doesn't have and doesn't need): ephemeral volume
    // (session keys, tokens) doesn't call for index-level filtering.
    override suspend fun loadEdges(fromId: NodeId): Either<AbyssError, List<StoredEdge>> = Either.catch {
        withContext(Dispatchers.IO) {
            val part = Predicates.partitionPredicate<EdgeKey, EdgeLike<*, *>>(fromId.toString(), Predicates.alwaysTrue())
            ephEdges.entrySet(part)
                .filter { it.key.fromId == fromId }
                .mapNotNull { (key, edge) ->
                    val remaining = remainingTtl(ephEdges.getEntryView(key)?.expirationTime)
                    if (remaining != null && remaining <= Duration.ZERO) null
                    else StoredEdge(key.fromId, key.toId, edge, remaining)
                }
        }
    }.mapLeft { AbyssError.Unexpected(it) }

    // Ephemeral is outgoing-only (TODO 1.13) — same contract as YugabyteEphemeralStore.
    override suspend fun loadInEdges(toId: NodeId): Either<AbyssError, List<StoredEdge>> = Either.Right(emptyList())

    // Admin/orphan-sweep scan (TODO 1.23). NodeLike<ID> carries no tags field (TODO 1.24 moved tags
    // off domain objects into the DB table only) — a tag is structurally unfilterable from this
    // cache, so tag != null returns empty rather than pretending to honor it.
    override fun scanNodeIds(tag: String?, parallelism: Int): Flow<NodeId> =
        if (tag != null) emptyFlow()
        else flow { withContext(Dispatchers.IO) { ephNodes.keys }.forEach { emit(it) } }

    // Real Hazelcast transaction (TransactionalMap), not a plain IMap.set/remove loop — a failure
    // partway through the block rolls back everything already applied, matching the atomicity
    // YugabytePersistentStore (JDBC commit/rollback) and YugabyteEphemeralStore (one CQL logged
    // batch) already give. Trades per-key locking for the transaction's duration for that guarantee.
    override suspend fun transaction(block: suspend AbyssEphemeralStoreTransactionLike.() -> Unit): Either<AbyssError, Unit> = Either.catch {
        withContext(Dispatchers.IO) {
            val ctx = hazelcast.newTransactionContext()
            ctx.beginTransaction()
            val txNodes = ctx.getMap<NodeId, NodeLike<*>>(ephNodesMapName)
            val txEdges = ctx.getMap<EdgeKey, EdgeLike<*, *>>(ephEdgesMapName)
            val receiver = object : AbyssEphemeralStoreTransactionLike {
                override fun saveNode(id: NodeId, node: NodeLike<*>, ttl: Duration, tags: Set<String>) {
                    txNodes.put(id, node, ttl.inWholeSeconds, TimeUnit.SECONDS)
                }
                override fun saveEdge(fromId: NodeId, toId: NodeId, edge: EdgeLike<*, *>, ttl: Duration, tags: Set<String>) {
                    txEdges.put(EdgeKey(fromId, toId, edge::class.serialName()), edge, ttl.inWholeSeconds, TimeUnit.SECONDS)
                }
                override fun deleteNode(id: NodeId) { txNodes.remove(id) }
                override fun deleteEdge(fromId: NodeId, toId: NodeId, type: String) { txEdges.remove(EdgeKey(fromId, toId, type)) }
            }
            try {
                receiver.block()
            } catch (e: Throwable) {
                ctx.rollbackTransaction()
                throw e
            }
            ctx.commitTransaction()
        }
    }.mapLeft { AbyssError.Unexpected(it) }
}
