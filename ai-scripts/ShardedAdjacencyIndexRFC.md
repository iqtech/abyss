# RFC — Sharded adjacency index for edge traversal (TODO 2.21)

## Context

`TraversalBuilder.addHop` (`TraversalBuilder.kt:57-67`) launches one `async{}` per frontier node in a
single `coroutineScope{}.awaitAll()` wave, regardless of frontier size. 2.19 covers the straightforward
fix (chunk that fan-out into batches). This RFC is a heavier, complementary change: restructure the
underlying edge-adjacency storage itself, replacing `reverseEdgesMap` and giving `outAt` a real index
for the first time, so most traversal reads become parallel-shard batch reads instead of ad hoc scans.

Today: `edgesMap: IMap<EdgeKey, EdgeLike<*,*>>` holds one entry per edge (source of truth for type +
properties); `reverseEdgesMap: IMap<ReverseEdgeKey, Unit>` is an existence-only incoming index. `outAt`
partition-scans `edgesMap` directly via `Predicates.partitionPredicate` — no index at all. `inAt` scans
`reverseEdgesMap` for keys, then batch-`getAll`s `edgesMap` for values — always 2 round trips.

**Status: design finalized, ready to implement.** See "Prerequisite" for the one still-open gate.

## Design

### Key and value shape

One new Hazelcast map (`abyss-edges-adjacency`), composite key `(NodeId, Shard: Byte)`, `PartitionAware`
pinned to `NodeId` (same trick `EdgeKey`/`ReverseEdgeKey` use to co-locate with their endpoint's
partition). `NodeId` here is the **owner**: the from-node for an `OUT` entry, the to-node for an `IN`
entry.

`Shard` packs two values into one byte, mirroring 1.15's header-byte idiom:
- bit 7: direction (`OUT` / `IN`)
- bits 0-6: shard index, `0..127`

Shard count is a **write-concurrency knob**: more shards means more concurrent hub-node writers avoid
serializing behind the same `EntryProcessor` per-key lock. It is not a data-scale knob, and — per "Read
path" below — not a read-parallelism knob either, since reads are always one batched `getAll` regardless
of `N`. It is also independent of however many coroutines the app actually runs concurrently elsewhere
(2.19's hop-fanout chunk size, any `Dispatchers.IO.limitedParallelism(N)` bound) — those are separate
knobs tuned for their own reasons, not derived from shard count. Realistic shard-count values track core
count, comfortably under the 128-value ceiling this leaves.

Value: a `Set` of entries, each **`(neighborId: NodeId, nodeTypeTag: Short, edgeTypeTag: Short)`**:
- `neighborId` — the other endpoint (target for `OUT`, source for `IN`).
- `nodeTypeTag` — the neighbor's domain node-type discriminator (e.g. `Person` vs `Group`). Lets
  type-filtered frontier ops (1.7's `nodes<N>(filter)`, 1.8's `hasOutgoing<E,N>()`, 2.16/2.17's typed
  counts) drop a mismatched neighbor with zero `nodesMap` fetch.
- `edgeTypeTag` — the *edge's* type discriminator (e.g. `Knows` vs `Coworker`). This is the field that
  makes the index usable for typed traversal, not just untyped/mixed reads, and is what makes
  `RemoveEdge` exact (see below) — earlier drafts of this RFC omitted it and needed a reference-count
  read on every delete to avoid evicting a neighbor still connected via a different edge type. With
  `edgeTypeTag` present, `Set` uniqueness is effectively `(neighborId, edgeTypeTag)` — `nodeTypeTag` is
  a pure function of `neighborId` (a node's type never changes) carried along purely to save a fetch on
  read, not for disambiguation.

Both tags are resolved via a new `@TypeTag(value: Short)` annotation, developer-assigned like
`@SerialName`, applied to **both** `NodeLike` and `EdgeLike` classes (same annotation, two independent
namespaces — a node tagged `5` and an edge tagged `5` never collide, since they're compared in
different fields and never against each other). `Short` gives 65536 values per namespace, headroom
matched to the stated target (wiki-scale knowledge graphs).

Three requirements on `@TypeTag`, all mirroring `@SerialName`'s existing conventions:
1. **Collision guard**, one `Set`/registry per namespace, validated as classes are seen — same
   precedent as 1.16's container-level schema-tag duplicate guard.
2. **Route through the existing annotation cache** — `cachedAnnotation<A>()` (`AnnotationCache.kt`),
   not raw `findAnnotation`, since this sits on the same hot path (every edge write, every traversal
   read) that cache was built for.
3. **Mandatory, not optional-with-fallback.** A missing tag fails loudly rather than silently
   defaulting to a value the index then trusts as correct.

### Relationship to `edgesMap` / `reverseEdgesMap`

This cannot replace `edgesMap` — it's still the only place holding edge properties. But with
`edgeTypeTag` in the value, it fully replaces `reverseEdgesMap` (deleted outright, not left as an
unread write) and gives `outAt` a real index for every case except one:

| Query shape | Path | Round trips | Why |
|---|---|---|---|
| Outgoing, typed, value-needed (`outgoing<Knows>()`) | **unchanged** — direct `edgesMap` partition scan | 1 | `edgesMap` is partitioned by `fromId`; already optimal. Routing through the index would cost 2 (adjacency read, then `edgesMap.getAll`) for no gain — this is the hottest common-case DSL call, don't regress it. |
| Outgoing, typed, existence-only (`needValue=false`) | adjacency index | 1 | Same round-trip count either way; index adds shard-parallelism for hub nodes at no extra cost. |
| Outgoing, untyped/mixed (`type == null`) | adjacency index | 1 (existence) or 2 (values) | No existing index for this at all today. |
| Incoming, any (typed or not, value-needed or not) | adjacency index, replacing `reverseEdgesMap` | 1 or 2 (unchanged from today) | `edgesMap`'s partitioning by `fromId` means incoming never had a free single-query path; the index is a like-for-like swap with added shard-parallelism, no regression. |

So `outAt`/`inAt` are rewritten in place, not given new sibling methods — see "Read path" below. Because
untyped/mixed hops flow through the exact same `Hop`/`addHop` machinery as typed ones, they
automatically participate in `allTraversedHops`/`collectSubgraph`/`exhaustReachable` — no separate
bookkeeping gap to design around.

**Write cost**: today an edge write costs 2 index writes (`edgesMap` + `reverseEdgesMap`); this scheme
costs 3 (`edgesMap` + one adjacency write per direction). In exchange, one of the two removed structures
(`reverseEdgesMap`) is fully subsumed, and the third write now also serves `outAt`, which previously had
no index contribution at all.

### Shard assignment: deterministic, not insertion-order

Which shard a neighbor lands in must be a pure function of the neighbor's id — `hash(neighborId) % N`
— computed the same way on write and on delete, so a targeted removal addresses its shard directly
instead of scanning all N.

`NodeId.hashCode()` (`bytes.contentHashCode()`, a plain polynomial array hash) is **not** suitable: poor
avalanche for the structured `[header|tag|rawId]` byte layout means sequential domain ids (the common
case — e.g. `LongKeyAdapter`) would shard non-uniformly. Use a real mixing hash (Murmur3 x86_32,
vendored — see Components) over the full `NodeId.bytes`, then `% N`. Hashing the full bytes (not
`NodeKey.rawId()`) is fine here: unlike partition keys, shard assignment doesn't need cross-schema-tag
consistency, only per-`NodeId`-value determinism.

### Atomic mutation: `EntryProcessor`

The value is a `Set`, mutated by many concurrent edge writes landing on the same `(nodeId, shard)` key.
Client-side get-modify-put races and silently drops updates under concurrent inserts — the same class of
bug already fixed once for cross-schema edges in cascade-delete (`f517536`). There is **no existing
`EntryProcessor` usage anywhere in this codebase** (confirmed by repo-wide grep) — this is genuinely new
infrastructure, not a pattern to copy, but Hazelcast guarantees `process()` runs under a per-key lock, so
a read-modify-`setValue` inside it is safe where client-side code isn't.

`Add` is a Set union (idempotent — safe to reuse for cold-start self-heal in `preloadOut`/`preloadIn`
too). `Remove` now matches on `(neighborId, edgeTypeTag)` exactly, so removing one edge type between a
pair leaves any other-typed edge to the same neighbor untouched — no reference-count read against
`edgesMap` needed.

### Read path: batched, then resolved

Every shard key for a node shares that node's partition, and Hazelcast's `IMap.getAll(keys)` groups keys
by owning partition and issues one operation per partition — so fetching all N shards for one node costs
exactly one round trip, regardless of N:

```kotlin
withContext(Dispatchers.IO) { adjacencyMap.getAll(shardKeys) }   // one round trip, not N
    .values.flatMap { it.entries }
    .let { entries -> /* filter by edgeTypeTag if type != null, then resolve values from edgesMap if needed */ }
```

This settles the launch-ordering question that motivated shard count in the first place ("start N
readers, each reads its shard" vs. "read everything, then act"): reading first is free here, so always
batch-read, never blind-launch N independent readers (which would cost N round trips for a typical
low-degree node instead of the one query `outAt` needs today).

## Components

### 1. `@TypeTag` annotation
`abyss-store-api/src/main/kotlin/pl/iqtech/abyss/store/api/TypeTag.kt` (new), modeled on
`EdgeConstraint.kt`/`CrossSchemaEdge.kt`:
```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class TypeTag(val value: Short)
```

### 2. `typeTag()` resolver
`abyss-dsl/src/main/kotlin/pl/iqtech/abyss/dsl/AnnotationCache.kt`, next to `serialName()`:
```kotlin
fun KClass<*>.typeTag(): Short = cachedAnnotation<TypeTag>()?.value ?: error("$this missing @TypeTag")
```

### 3. Adjacency key/value/mutation types
New file `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AdjacencyKey.kt`:
```kotlin
enum class AdjacencyDirection { OUT, IN }

fun packShard(direction: AdjacencyDirection, index: Int): Byte
fun AdjacencyKey.direction(): AdjacencyDirection
fun AdjacencyKey.shardIndex(): Int

class AdjacencyKey(val nodeId: NodeId, val shard: Byte, pk: Any = nodeId.toString()) : PartitionAware<Any> {
    private val _pk = pk
    override fun getPartitionKey(): Any = _pk
    override fun equals(other: Any?) = other is AdjacencyKey && nodeId == other.nodeId && shard == other.shard
    override fun hashCode() = Objects.hash(nodeId, shard)
}

data class AdjacencyEntry(val neighborId: NodeId, val nodeTypeTag: Short, val edgeTypeTag: Short)
class AdjacencyValue(val entries: Set<AdjacencyEntry>)
```
No `EdgeAdapter`-driven native field decomposition needed (unlike `EdgeKey`/`ReverseEdgeKey`) — nothing
queries this map by predicate, only exact-key `getAll`/`submitToKey`, so `NodeId` nests as an opaque
Compact field.

New file `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AdjacencyHash.kt` — vendor a small (~20
line) Murmur3 x86_32 implementation rather than reaching into `com.hazelcast.internal.util.HashUtil`
(exists on the classpath but has no API stability guarantee):
```kotlin
internal fun shardIndexOf(neighborId: NodeId, shardCount: Int): Int =
    (murmur3_32(neighborId.bytes) and 0x7FFFFFFF) % shardCount
```

New file `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AdjacencyMutationProcessor.kt`:
```kotlin
sealed interface AdjacencyMutation {
    data class Add(val entry: AdjacencyEntry) : AdjacencyMutation
    data class Remove(val neighborId: NodeId, val edgeTypeTag: Short) : AdjacencyMutation
}

class AdjacencyMutationProcessor(private val mutation: AdjacencyMutation) : EntryProcessor<AdjacencyKey, AdjacencyValue, Void> {
    override fun process(entry: MutableMap.MutableEntry<AdjacencyKey, AdjacencyValue>): Void? {
        val current = entry.value?.entries ?: emptySet()
        val updated = when (mutation) {
            is AdjacencyMutation.Add    -> current + mutation.entry
            is AdjacencyMutation.Remove -> current.filterNot { it.neighborId == mutation.neighborId && it.edgeTypeTag == mutation.edgeTypeTag }.toSet()
        }
        if (updated != current) entry.setValue(AdjacencyValue(updated))
        return null
    }
}
```

### 4. Edge-type tag registry — bidirectional, populated eagerly

Two call sites need a direction `@TypeTag` resolution alone can't give:
- `NodeOp.RemoveEdge` only carries `type: String` (no live `EdgeLike` instance to call `::class.typeTag()`
  on) — needs `String → Short`.
- Reconstructing `Hop.type` for an untyped/mixed result only has the entry's `Short` tag in hand — needs
  `Short → String`.

**Populate both directions eagerly at worker construction, not lazily on first write.** A lazy,
write-triggered registry would reproduce exactly the class of bug TODO 1.20 already fixed elsewhere
(cache/registry state not surviving restart): after a cold restart, a `RemoveEdge` or type-filtered query
for an edge type that existed before the restart but hasn't been re-added yet in the new process would
fail to resolve its tag, even though matching edges already exist in the durable store. `@TypeTag`/
`@SerialName` are static, compile-time facts about registered classes — `AbyssSchemaWorker` already
receives the `SerializersModule` (`AbyssGraphSchema.kt:211`, threaded into `NodeLikeHzSerializer`/
`EdgeLikeHzSerializer`); use `SerializersModule.dumpTo(collector)` to walk every registered `NodeLike`/
`EdgeLike` polymorphic subclass once at construction and pre-populate both node- and edge-tag registries
from their annotations before any traffic arrives:
```kotlin
private val nodeTypeTagRegistry = ConcurrentHashMap<Short, KClass<*>>()   // collision guard
private val edgeTypeTagByTag = ConcurrentHashMap<Short, String>()        // tag -> serialName
private val edgeTypeTagByName = ConcurrentHashMap<String, Short>()       // serialName -> tag

private fun edgeTypeTagOf(type: String): Short = edgeTypeTagByName[type] ?: error("Edge type '$type' has no registered @TypeTag")
private fun edgeTypeNameOf(tag: Short): String = edgeTypeTagByTag[tag] ?: error("Unknown edge TypeTag $tag")
```

### 5. `AbyssSchemaWorker` — write path

`applyToCacheAsync` (`AbyssSchemaWorker.kt:326-343`) becomes `suspend` (its only caller, `populateCache`,
is already `suspend`; `List.flatMap` is `inline`, so this compiles with no other ripple). `populateCache`
gains an `addedInTx` map (same idiom `integrityError` already uses) so a node added in the same
transaction as its edge resolves its type tag correctly:

```kotlin
is NodeOp.AddEdge -> {
    val edgeWrites = /* unchanged edgesMap.setAsync(...) */
    if (op.ttl != null) edgeWrites else edgeWrites + listOf(
        adjacencyMap.submitToKey(outKeyFor(op.fromId, op.toId), AdjacencyMutationProcessor(
            AdjacencyMutation.Add(AdjacencyEntry(op.toId, resolveNodeTag(op.toId, addedInTx), op.edge::class.typeTag())))),
        adjacencyMap.submitToKey(inKeyFor(op.toId, op.fromId), AdjacencyMutationProcessor(
            AdjacencyMutation.Add(AdjacencyEntry(op.fromId, resolveNodeTag(op.fromId, addedInTx), op.edge::class.typeTag())))),
    )
}
is NodeOp.RemoveEdge -> listOf(edgesMap.removeAsync(edgeKey(op.fromId, op.toId, op.type))) + run {
    val tag = edgeTypeTagOf(op.type)
    listOf(
        adjacencyMap.submitToKey(outKeyFor(op.fromId, op.toId), AdjacencyMutationProcessor(AdjacencyMutation.Remove(op.toId, tag))),
        adjacencyMap.submitToKey(inKeyFor(op.toId, op.fromId), AdjacencyMutationProcessor(AdjacencyMutation.Remove(op.fromId, tag))),
    )
}
```
`reverseEdgesMap.setAsync`/`.removeAsync` calls are deleted, not left in place. Ephemeral (TTL) edges
still skip the adjacency index entirely — same "outgoing-only, TODO 1.13" rule `reverseEdgesMap` already
had, so `outgoing`-mixed traversal sees strictly less than plain `outAt` for TTL edges specifically
(documented limitation, not a new asymmetry — `reverseEdgesMap` already worked this way).

`cascadeEdgeRemovals` (`AbyssSchemaWorker.kt:286-299`) — the incoming half currently reads
`reverseEdgesMap.keySet(...)`; swap for a batched adjacency read on the IN shards, same shape as the new
`inAt`. No other change — cascaded ops are still ordinary `RemoveEdge`s flowing back through
`applyToCacheAsync`.

`preloadOut`/`preloadIn` (`AbyssSchemaWorker.kt:182-204`) — `preloadIn`'s `reverseEdgesMap.putIfAbsent`
becomes an `AdjacencyMutationProcessor(Add(...))` call (Set merge, not a scalar replace, so
`putIfAbsent` is the wrong primitive here); `preloadOut` gains a symmetric adjacency self-heal it never
needed before (there was no outgoing index to warm until now).

### 6. `AbyssSchemaWorker` — read path

`outAt`/`inAt` (`AbyssSchemaWorker.kt:117-142`) are rewritten per the table above. `outAt` keeps its
existing direct `edgesMap` scan as a fast-path branch when `type != null && needValue`; every other case
(including the previous `inAt` body's role) routes through a shared adjacency-read helper (batched
`getAll` across `0 until shardCount` shard keys, filter by `edgeTypeTag` when `type != null`, resolve
values from `edgesMap` when `needValue`).

### 7. `TraversalBuilderLike` / DSL — minimal surface change

`addHop`'s `edgeType: String` widens to `edgeType: String?` (`abyss-dsl/.../TraversalBuilderLike.kt`,
`abyss-graph/.../TraversalBuilder.kt`) — source-compatible, existing callers already pass a non-null
value. `hops()`/`engine.outAt`/`inAt` already accept nullable `type`, so no further change there. New DSL
sugar in `Extensions.kt`, next to `outgoing()`/`incoming()`:
```kotlin
suspend fun TraversalBuilderLike<*>.outgoingAny() = addHop(HopDirection.OUTGOING, null)
suspend fun TraversalBuilderLike<*>.incomingAny() = addHop(HopDirection.INCOMING, null)
```
No new `Neighbor` type, no `outNeighborsAt`/`inNeighborsAt` engine methods, no `addMixedHop` builder
method — untyped hops are just `addHop` with `edgeType = null`, so they flow through the existing
`Hop`/`allTraversedHops` bookkeeping automatically. Zero-fetch node-type filtering on top of
`outgoingAny()`/`incomingAny()` (using the entry's `nodeTypeTag` instead of a `nodesMap` fetch) is a
natural follow-on but out of scope here — not needed to close TODO 2.21, see "Not doing."

### 8. Config / registration

`abyss-graph/src/main/resources/hazelcast.yaml` — new block mirroring `abyss-edges`'s eviction, no
indexes (nothing queries this map by predicate):
```yaml
    abyss-edges-adjacency:
      max-idle-seconds: 86400
      eviction: { eviction-policy: LRU, max-size-policy: FREE_HEAP_PERCENTAGE, size: 25 }
```
`AbyssGraphSchema.kt:211-221`'s `registerAbyssSerializers` gains Compact serializers for
`AdjacencyKey`/`AdjacencyEntry`/`AdjacencyValue` (new files under `abyss-graph/.../serialization/`,
same ~24-line shape as `EdgeKeySerializer`/`ReverseEdgeKeySerializer` but without the `EdgeAdapter`
dependency) and for `AdjacencyMutationProcessor` itself (`EntryProcessor` already implements
`java.io.Serializable` by interface contract, so plain JVM serialization works with zero registration —
but for consistency with every other wire type in this codebase using Compact, register a Compact
serializer for it too rather than accepting `Serializable` as the one exception).

`ReverseEdgeKey.kt`/`ReverseEdgeKeySerializer.kt` are deleted outright, along with `reverseEdgesMap`'s
field declaration and its (already-missing) `hazelcast.yaml` block — dead code, not deprecated code.

## Files

- `abyss-store-api/src/main/kotlin/pl/iqtech/abyss/store/api/TypeTag.kt` (new)
- `abyss-dsl/src/main/kotlin/pl/iqtech/abyss/dsl/AnnotationCache.kt`
- `abyss-dsl/src/main/kotlin/pl/iqtech/abyss/dsl/TraversalBuilderLike.kt`
- `abyss-dsl/src/main/kotlin/pl/iqtech/abyss/dsl/Extensions.kt`
- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AdjacencyKey.kt` (new)
- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AdjacencyHash.kt` (new)
- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AdjacencyMutationProcessor.kt` (new)
- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AbyssSchemaWorker.kt`
- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/NodeIdEngine.kt` (no interface change needed —
  `outAt`/`inAt` signatures are unchanged, only their implementation)
- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/traversal/TraversalBuilder.kt`
- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AbyssGraphSchema.kt` (`registerAbyssSerializers`)
- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/serialization/` (3-4 new serializer files)
- `abyss-graph/src/main/resources/hazelcast.yaml`
- Deleted: `abyss-dsl/.../ReverseEdgeKey.kt` or wherever it resolves, `.../ReverseEdgeKeySerializer.kt`

## Verification

All tests run against the existing real-embedded-Hazelcast harness (`GraphTest.kt`, `TraversalTest.kt`)
— no mocked `NodeIdEngine` exists or is needed.

1. `@BeforeTest` cleanup: add `abyss-edges-adjacency` (and its per-test-map-name variants) to every
   existing raw-map-clear block; remove the now-dead `-reverse` map clears.
2. Fixture update: add `@TypeTag` to every `NodeLike`/`EdgeLike` test fixture class (`TestNode`,
   `TestEdge`, etc.) — mandatory annotation means these fail fast without it.
3. New `MixedTraversalTest.kt`:
   - `outgoingAny()`/`incomingAny()` return the union of neighbors across multiple edge *types*.
   - Removing one of two differently-typed edges between the same pair leaves the neighbor visible via
     the surviving type — direct regression test for the `(neighborId, edgeTypeTag)`-exact removal.
   - Removing the last edge between a pair evicts the neighbor entirely.
   - `outgoing<Knows>(needValue=false)`-equivalent existence check returns correct results via the
     index path, and `outgoing<Knows>()` (value-needed) still returns correct results via the preserved
     fast path — both need coverage since they're now genuinely different code paths.
   - Concurrent-writer stress test: many coroutines concurrently adding edges into the same hub node's
     shard; assert the final adjacency `Set` has exactly the expected neighbor count (exercises the
     `EntryProcessor`'s atomicity directly).
   - Cold-cache self-heal: seed only the store, call a mixed hop, assert the adjacency map warms
     (mirrors existing `outAt`/`inAt` preload tests).
   - Restart-equivalent tag resolution: construct a fresh worker against a store that already has edges
     of a given type, with no edge of that type added in the new process yet, and confirm
     `edgeTypeTagOf` resolves via eager registration rather than erroring (direct test for the
     restart-safety fix in Components §4).
4. `@TypeTag` collision guard test (per namespace): two `NodeLike` classes sharing a `Short` fail at
   registration; independently, two `EdgeLike` classes sharing a `Short` fail — and a `NodeLike` and
   `EdgeLike` class sharing the same numeric value do **not** conflict (proves namespace independence).
5. Hash-shard determinism test (no Hazelcast needed): same `NodeId` always maps to the same shard;
   sequential `Long`-adapter ids don't skew heavily toward one shard.

## Prerequisite

This is a materially heavier change than 2.19's chunking fix for the frontier-fan-out problem that
originally motivated it. The design has since grown to subsume `reverseEdgesMap` and improve `inAt`
unconditionally, which stands on its own merits independent of that original concurrency question — but
land 2.19 (or `Dispatchers.IO.limitedParallelism(N)`) first regardless, since it's the smaller, unrelated
fix for frontier-size fan-out specifically and shouldn't block on this.

## Not doing

- Zero-fetch node-type filtering on `outgoingAny()`/`incomingAny()` using the entry's `nodeTypeTag` —
  natural follow-on, not required to land this.
- No dynamic type-tag registry — `@TypeTag` is static, developer-assigned only, in both namespaces.
- Ephemeral (TTL) edges remain invisible to the adjacency index (outgoing-only rule, inherited from
  `reverseEdgesMap`/TODO 1.13) — not revisited here.
