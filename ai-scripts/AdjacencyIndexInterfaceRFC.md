# RFC — `AdjacencyIndex` seam + Sharded implementation (TODO 1.26)

## Context

`adjacencyRead` (`AbyssSchemaWorker.kt:164`) returns an unbounded `List<Hop>`: it `getAll`s **every**
shard for a node, flattens all neighbors into one list, and for `needValue=true` does a single `getAll`
over every edge key. The public API advertises paging it doesn't deliver —
`AbyssEngineLike.outEdges/inEdges(nodeId, pageSize=100)` is implemented by `AbyssGraphSchema`
(`AbyssGraphSchema.kt:115`) with `pageSize` dropped, and the returned `Flow` is backed by a
fully-materialized Hazelcast `values(predicate)` / whole-`adjacencyRead` list. A supernode drags every
edge (plus an N-key `getAll`) into one member's heap before the first emit.

**Root cause:** the adjacency index has no *bounded* read. The Set-per-shard structure (from the
[ShardedAdjacencyIndexRFC](ShardedAdjacencyIndexRFC.md), TODO 2.21) is read whole, always.

**Goal (this RFC):** put a thin **`AdjacencyIndex` seam** between `AbyssSchemaWorker` and the Hazelcast
adjacency storage, so the index shape becomes a pluggable strategy. Ship one implementation now — the
existing sharded structure, unchanged on disk, but read **one shard-window at a time** so
`outEdges`/`inEdges` become genuinely bounded and `pageSize` is honored. A second, paging-native shape
is left as a future implementation behind the same seam (two sentences below), not built here.

Why a seam rather than a rewrite: the sharded structure is memory-optimal for supernodes (it amortizes
Hazelcast's ~100 B/entry record overhead across `degree/shardCount` neighbors and carries no index), it
just can't page. Windowed shard reads bound it with **zero storage change**. Fits Abyss's pluggable-store
philosophy and its multi-tenant / unknown-shape-graph framing: different graph shapes can pick different
index strategies from one deployment.

## The seam

Storage-agnostic — no `AdjacencyKey`, `IMap`, or shard byte leaks across it:

```kotlin
interface AdjacencyIndex {
    suspend fun add(owner: NodeId, dir: AdjacencyDirection, entry: AdjacencyEntry)
    suspend fun remove(owner: NodeId, dir: AdjacencyDirection, neighborId: NodeId, edgeTypeTag: Short)
    fun read(owner: NodeId, dir: AdjacencyDirection, edgeTypeTag: Short? = null): Flow<AdjacencyEntry>
    suspend fun isEmpty(owner: NodeId, dir: AdjacencyDirection): Boolean   // warm signal
}
```

`read` returns a **`Flow`**, so the implementation owns batching — it may emit a shard-window at a time,
a page at a time, whatever bounds its own heap. The worker keeps what only it can own: warm
orchestration (it holds the store), value fetch (`edgesMap`), and the `TypeTagRegistry`. `add`/`remove`
carry the tags so the neighbor-`@TypeTag` fetch-avoidance (1.25, see
[ShardedAdjacencyIndexRFC](ShardedAdjacencyIndexRFC.md)) survives.

### Warm ownership

Warming stays in the worker (decision **1A**). `preloadOut`/`preloadIn` co-warm `edgesMap` (values) and
the index from **one** store scan carrying `neighborType` — that single-scan join is the 1.25 win and
`edgesMap` is the worker's, not the index's. So the worker does `if (index.isEmpty(nid,dir))
preload(nid)` (writing values itself, calling `index.add` per entry); the engine never sees the store.
Cold miss still preloads the whole node (one unbounded store scan, exactly as today) — bounding the
*cold* path is a future concern, handled by injecting a page-loader + value-sink into the engine, not by
giving it the store. Steady-state cache reads are bounded now.

## Implementation now — `ShardedAdjacencyIndex`

The current structure behind the interface. `add`/`remove` = today's `AdjacencyMutationProcessor` via
`submitToKey(outKeyFor/inKeyFor)`; `isEmpty` = a `getAll` of the direction's shard keys is empty.

**`read` = shard-window walk.** Instead of `getAll(all shards)`, pull shards in bounded windows
(`getAll` of `W` shard keys per step, `W` a construction-time knob), emit their entries, advance to the
next window until all `shardCount` shards are covered. Every shard co-locates in the owner's partition
(`PartitionAware` on `nodeId`), so each window is a single-partition batched read of ≤ `W ·
degree/shardCount` entries. Type filter (`edgeTypeTag`) is applied per window before emit.

Properties: **bounded** per step (a *factor* `shardCount/W` below the whole set, not a constant),
**single-partition**, **symmetric OUT/IN**, **complete + non-overlapping** (each neighbor lives in
exactly one shard). Cursor is the shard index. Order is hash order (`murmur(neighborId) % shardCount`),
which matches today's unordered contract — `outEdges`/traversal never promised order.

### Wiring (as shipped)

- `adjacencyRead` → `adjacency.read(nid, dir, edgeTag).map { toHop }.toList()`, then the existing
  optional `needValue` `getAll`. List form, for `outAt`/`inAt`.
- **`inEdges` is bounded and paged**: `adjacencyEdgeFlow` streams `adjacency.read(IN)` and batches the
  edge-value `getAll` every `pageSize` hops — peak heap ~`pageSize` edges regardless of degree.
  `AbyssGraphSchema.inEdges(pageSize)` threads `pageSize` through as that batch size.
- **`outEdges` is unchanged (deferred).** OUT still scans `edgesMap` directly because ephemeral (TTL)
  edges are outgoing-only with **no adjacency entry** (`applyToCacheAsync`, `AbyssSchemaWorker.kt`), so
  routing OUT through `adjacency.read` would silently drop them. Bounding OUT (persistent via the index
  + ephemeral separately) is the immediate follow-up; `AbyssGraphSchema.outEdges` still receives
  `pageSize` but it is not yet honored.
- `outAt`/`inAt` (`NodeIdEngine`) keep returning `List<Hop>` via `read(...).toList()`; making them
  `Flow<Hop>` to stream the traversal frontier is the follow-on (lets `TraversalBuilder` consume
  incrementally instead of `flatten()`-ing every frontier node's hops).

### Warm-check boundedness

`AdjacencyIndex.isEmpty` must not re-materialize the node to answer (that would defeat the bounded
read). `ShardedAdjacencyIndex.isEmpty` checks the **first window** — non-empty ⇒ warm, done; only a
node whose first window is empty (necessarily sparse/cold — a dense node fills window 0) pays the
definitive full-shard check, and for a sparse node that `getAll` is cheap.

## Future implementation — `PagedAdjacencyIndex` (not built here)

Replaces hash-shards with ordered, degree-adaptive K-pages (`key = (owner, dir, pageNo)` pinned to the
owner; value = up to K entries), so a read is a bounded page-walk with a real keyset cursor and *lower*
memory than the Set at large degree (bigger chunks amortize per-entry overhead better). It carries a
heavier write path — page split/merge (ordered) or append + compaction (log-structured) — so it is
opt-in per graph, gated on measuring supernode write-hotness and delete rate before it earns its place.

## Files

- New: `abyss-graph/.../AdjacencyIndex.kt` (interface), `.../ShardedAdjacencyIndex.kt` (impl — absorbs
  `shardKeysFor`, `outKeyFor`/`inKeyFor`, the `submitToKey` mutations, `AdjacencyHash`/`AdjacencyKey`
  usage).
- `AbyssSchemaWorker.kt`: hold an `AdjacencyIndex`; `adjacencyRead`/`ensureOutWarm`/`preloadOut`/
  `preloadIn` route through it; drop direct `adjacencyMap` handling.
- `AbyssGraphSchema.kt`: `outEdges`/`inEdges` honor `pageSize`.

## Verification

- Existing traversal/adjacency suites stay green (behavioral parity of the Sharded impl).
- New: a supernode `outEdges(pageSize=k)` test on a Fake store (model `AdjacencyPreloadPerformanceTest`)
  asserting peak in-flight entries ≤ one shard-window and that `.take(k)` reads only enough windows —
  baseline (full materialization) first, then the windowed read, reported pre/post.

## Rejected alternatives (one line each)

- **Per-edge adjacency entries** — ~5× memory at 1B edges (entry-count explosion; throws away the Set's
  overhead amortization).
- **Double-side indexing `edgesMap` on `to_id`** — IN becomes cluster-wide scatter-gather (`edgesMap` is
  fromId-partitioned; Hazelcast indexes are partition-local).
