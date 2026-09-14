# Batched node loading (`nodesAt`) — TODO 2.30

## Problem

Every node read in the engine funnels through `NodeIdEngine.nodeAt(nid)`:

```kotlin
suspend fun readNode(nid: NodeId): NodeLike<*>? =
    nodesMap.getAsync(nid).asDeferred().await() ?: loadAndCacheNode(nid)   // AbyssSchemaWorker.kt:169
```

One Hazelcast `getAsync` per id, and on a cache miss one store point-read per id
(`loadAndCacheNode`, `:570`). Edges already have the batched counterpart — `resolveEdges` →
`IMap.getAll` (`AbyssSchemaWorker.kt:325`) — and the adjacency index reads in `getAll` windows
(`ShardedAdjacencyIndex.kt:47`). Nodes never got it.

Everywhere a traversal holds a *set* of NodeIds and wants their values, it currently pays N round
trips (fanned out, or worse, serially).

## Measured, on the live YB container

YugabyteDB 2025.1.0.1, the real `abyss.nodes` DDL, 20k rows, 128 ids, `EXPLAIN (ANALYZE, DIST)`:

| read                              | storage read requests | ms/iter |
|-----------------------------------|----------------------:|--------:|
| `WHERE id = ANY($1::bytea[])`     |                   **1** |  4.91 |
| 128 × `WHERE id = $1`             |                 **128** | 25.07 |

Plan for the batched form is `Index Scan using nodes_pkey`, `Index Cond: (id = ANY (...))` — YB
batches the hash-PK lookups, no seq scan, no fallback. 5.1× is the **floor**: both numbers were
measured inside the database (PL/pgSQL loop), so the serial path isn't charged for its 128 JDBC
network round trips. Over the wire it gets worse for serial, not better.

Hazelcast side needs no probe — `getAll` groups keys by partition and issues one operation per
member; it is already the established pattern in this codebase for edges and adjacency shards.

## Call sites

**Batchable (set-shaped, the whole set is materialized anyway):**

| site | current |
|---|---|
| `TraversalBuilder.kt:210` `flushFrontierNodes` | `for (nid in frontier) engine.nodeAt(nid)` — plain sequential loop |
| `:145` `filterFrontierByNode` | `async` fan-out of single gets over `toFetch` |
| `:232` `collectSubgraph` | `async` fan-out over `candidates` |
| `:252` `exhaustReachable` | `async` fan-out over `visited` |
| `:313` `paths` | origin set |
| `:451` / `:465` `pathTo` | origin set; per-level neighbour fan-out (nested inside the per-entry fan-out) |

**Deliberately NOT batched** — `addNodeHop` (`:120`), `filterFrontierByEdgeType` (`:176`),
`dfsLoop`/`bfsLoop` (`:353`, `:396`). Those `nodeAt` calls sit inside a streaming
`.filter` / `.firstOrNull` over a hop flow, where short-circuiting is the whole point; batching
would force full materialization of a supernode's neighbours to save round trips it may never make.
Parallel fan-out (TODO 4.12) stays the right fix there.

## Design

### 1. `NodeIdEngine.nodesAt` — with a default impl

```kotlin
// NodeIdEngine.kt — mirrors resolveEdges
suspend fun nodesAt(ids: Collection<NodeId>): Map<NodeId, NodeLike<*>> = coroutineScope {
    ids.map { nid -> async(hopDispatcher) { nodeAt(nid)?.let { nid to it } } }.awaitAll()
}.filterNotNull().toMap()
```

The default matters for two reasons, not one:

- **Four test fakes implement `NodeIdEngine` directly** (`PathToPerformanceTest`,
  `AdjacencyPreloadPerformanceTest`, `DetectCycleDepthTest`, `TypedNodeFilterFetchTest`). A default
  keeps them compiling untouched.
- **`TypedNodeFilterFetchTest` counts `nodeAt` calls and asserts exact numbers** (`:68`, `:79`,
  `:90`). Routing the default through `nodeAt` keeps every one of those assertions valid — which is
  correct, because batching changes *how many round trips*, never *which nodes get fetched*. The
  tag-based fetch-avoidance invariant is untouched by this work.

`Homogeneous`/`HeterogeneousSchemaGraph` get a one-line delegate each (`worker.nodesAt(ids)`),
exactly like their existing `nodeAt`/`resolveEdges` forwards.

### 2. `AbyssSchemaWorker.nodesAt` — the real one

```kotlin
override suspend fun nodesAt(ids: Collection<NodeId>): Map<NodeId, NodeLike<*>> {
    if (ids.isEmpty()) return emptyMap()
    val distinct = ids.toSet()
    val cached = withContext(Dispatchers.IO) { nodesMap.getAll(distinct) }
    val missing = distinct - cached.keys
    return if (missing.isEmpty()) cached else cached + loadAndCacheNodes(missing)
}
```

`loadAndCacheNodes` is the batched twin of `loadAndCacheNode` (`:570`) and must keep its three
behaviours intact:

- persistent store queried first, ephemeral store in parallel, persistent hit wins;
- a `remaining <= 0` entry is treated as absent (expired);
- write-back into `nodesMap` with the TTL when `remaining != null`, without when null.

Chunking: fold `distinct` into `valueFetchBatch`-sized (128) chunks, the constant that already
bounds the edge-value fetch (`AbyssSchemaWorker.kt:95`). Bounds peak heap and the `ANY()` array
size in one move, no new knob.

### 3. `AbyssStoreLike.loadNodes` — default loop, YSQL override

```kotlin
// AbyssStoreLike.kt — same pattern as batchTransaction's default: correct, not faster
suspend fun loadNodes(ids: Collection<NodeId>): Either<AbyssError, Map<NodeId, Pair<NodeLike<*>?, Duration?>>>
```

Default implementation loops `loadNode` per id, so `HazelcastEphemeralStore`, `YugabyteEphemeralStore`
and every fake store in the test suite need no change at all. Only `YugabytePersistentStore`
overrides it:

```sql
SELECT id, data FROM <schema>.nodes WHERE id = ANY(?)
```

bound via `conn.createArrayOf("bytea", ids.map { it.bytes }.toTypedArray())` — the same
`createArrayOf` idiom already used for the `tags @>` scan (`YugabytePersistentStore.kt:143`).
Returns `remaining = null` for every row, matching `loadNode`'s existing contract.

`Pair<NodeLike<*>?, Duration?>` is kept verbatim from `loadNode` so per-node ephemeral TTL survives
the batch — the one correctness trap in this change.

## Verification

Per the performance workflow: test first, baseline, fix, re-measure.

1. **Isolation test** — `NodeBatchLoadPerformanceTest`, modelled on `PathToPerformanceTest`'s
   `DelayedFakeEngine`: a fake `NodeIdEngine` charging a fixed `delay(latencyMs)` per engine call,
   overriding `nodesAt` to charge **one** delay per batch (exactly how `resolveEdges` is already
   modelled in that file — that is what `getAll` and one `ANY()` query actually cost). Drive a
   wide frontier (128–512 nodes) through `flushFrontierNodes` and `collectSubgraph`. Gate on
   `-Pperf` like the rest.
2. **Baseline** on today's serial/fan-out code.
3. Implement.
4. **Re-measure** the same test, report pre/post.
5. **Correctness**: full `abyss-graph` suite, with `TypedNodeFilterFetchTest`'s fetch counters as
   the canary that fetch-avoidance semantics didn't drift, plus a `LoadTest`-level check that the
   YSQL `ANY(?)` override returns the same values as N `loadNode` calls, including the absent-id case.

## Blast radius

`NodeIdEngine.kt`, `AbyssSchemaWorker.kt`, `HomogeneousSchemaGraph.kt`,
`HeterogeneousSchemaGraph.kt`, `AbyssStoreLike.kt`, `YugabytePersistentStore.kt`,
`traversal/TraversalBuilder.kt` — 7 files. New public surface: two methods, both with defaults, so
no existing implementor breaks.
