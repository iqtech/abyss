# TODO 1.33 — Type scan: stream whole persistent nodes of a given type

Status: **v1 agreed 2026-10-06.** Scope narrowed from "every layer, nodes and edges" to persistent
nodes only. The wider scan is parked in §7, not rejected.

## 1. v1 scope

```kotlin
graph.scanPersistentNodes<User>(): Either<AbyssError, Flow<User>>
```

Streams every node of one `@SerialName` type, as whole values, from the persistent store's type index.
Admin/bulk-shaped, like 1.23's `scanNodeIds`, not traversal plumbing.

Gives `idx_nodes_type` (`db/ysql-schema.sql`) its first reader. Until now it cost a write on every
upsert and was read by nothing.

Agreed decisions:

- **Persistent only.** The name carries the contract: ephemeral nodes of the type are not returned.
  This reverses the 2026-09-28 "store-less branch is in" decision. Reason: the all-layer scan needs
  cross-layer dedup, which depends on the open "gap 2" (cross-layer id uniqueness).
- **`Either` on the store seam, default `Left`.** A configured store that does not implement the scan
  returns `Left(ScanUnsupported)`, never an empty flow. An `emptyFlow()` default (1.23's shape) would be
  a silent drop.
- **New error `AbyssError.ScanUnsupported`.** One variant for both "no persistent store configured" and
  "the store cannot scan".
- **Exact class only.** The `type` column is the polymorphic class discriminator of the concrete runtime
  class (`YugabytePersistentStore.jsonPair`). A base type matches nothing. No node hierarchies exist, so
  subclass expansion (`type = ANY(?)`) is not built.
- **Type index path only (A).** Correct and O(matches). The pkey-hinted fan-out is faster above ~2%
  share (§5) and fits behind the same signature later. No `parallelism` parameter until it exists.
- **No cache write-through.** Rows are emitted straight from the store. A bulk sweep must not evict the
  hot working set.

## 2. Why the persistent store alone is a complete source

`AbyssSchemaWorker.transaction` commits to the persistent store first and populates the cache after; a
store failure returns before the cache is touched. YSQL is never behind `nodesMap` for a committed
transaction. `nodesMap` is an evictable partial copy and is not read by the scan.

## 3. API

### 3.1 Error (`abyss-store-api/.../AbyssError.kt`)

```kotlin
data class ScanUnsupported(val reason: String) : AbyssError
```

### 3.2 Store seam (`AbyssStoreLike` only, not `AbyssEphemeralStoreLike`)

```kotlin
fun scanNodesOfType(type: String): Either<AbyssError, Flow<Pair<NodeId, NodeLike<*>>>> =
    AbyssError.ScanUnsupported("...").left()
```

The untyped half: the store knows a type string and raw rows. It returns the `NodeId` with the value
because the typed facade needs it for `ownsNodeId`; `NodeLike` carries only the domain id. The `Left`
default means the test fakes implementing `AbyssStoreLike` need no change.

### 3.3 Worker (`AbyssSchemaWorker`)

```kotlin
fun scanPersistentNodes(type: String): Either<AbyssError, Flow<Pair<NodeId, NodeLike<*>>>> =
    persistentStore?.scanNodesOfType(type) ?: AbyssError.ScanUnsupported("no persistentStore configured").left()
```

### 3.4 Typed facade (`AbyssGraphSchema`)

```kotlin
fun <N : NodeLike<ID>> scanPersistentNodes(type: KClass<N>): Either<AbyssError, Flow<N>>
inline fun <reified N : NodeLike<ID>> scanPersistentNodes() = scanPersistentNodes(N::class)
```

Two functions because `worker` and `adapter` are private and a public inline function cannot reach
them. The `KClass` overload:

1. resolves `type.serialName()`; a class without `@SerialName` gives `Left(SchemaError)`,
2. asks the worker,
3. filters rows by `adapter.ownsNodeId` (the store is shared by every schema in a container),
4. casts to `N`.

Not on `AbyssEngineLike<ID>`, same boundary 1.23 drew. No container-level (unscoped) variant in v1.

### 3.5 Error contract

`Left` means the scan cannot start: `ScanUnsupported` or `SchemaError`. It is decided before any I/O.
Failures during collection (lost connection, undecodable row, snapshot too old) are thrown by the flow.

## 4. `YugabytePersistentStore` implementation

One streamed query on a pooled connection:

```sql
SELECT id, data FROM nodes WHERE type = ?
```

- `autoCommit = false` + `fetchSize`. Without autocommit off, pgjdbc and the YB smart driver ignore
  `fetchSize` and materialize the whole result (§5).
- `flow { … }.flowOn(Dispatchers.IO)`; the transaction is ended in `finally`, also on cancellation.
- Decode `data` per row. `type` already matched in the DB, so there is no client filter.

Ceilings, documented in the KDoc, not coded around:

- **One transaction, one snapshot.** YB keeps MVCC history for
  `timestamp_history_retention_interval_sec` (900 s on the dev container, `/varz`, 2026-09-28). A scan
  held open longer should fail with "snapshot too old". Expected, not yet reproduced.
- **One pooled connection for as long as the caller collects.** A slow collector, or several scans at
  once, compete with the write path for the pool.

## 5. Measurements

Setup: `TypeScanFeasibilityTest` (`-Pperf`), live YB 2025.1.0.1 dev container (single tserver), a
dedicated table with `abyss.nodes`' exact DDL, 200k rows, `data` ~300 B, `ANALYZE`d.

### 5.1 Streaming (2026-09-28, typed rows added 2026-10-06)

Heap held after `executeQuery` + first `next()`, `fetchSize = 500`. Typed queries use the 64.9% type.

| query | rows | driver | autoCommit on | autoCommit off |
|---|---|---|---|---|
| full table | 200,000 | pgjdbc | 91.4 MB | 0.2 MB |
| full table | 200,000 | YB smart | 89.8 MB | 0.2 MB |
| A: `WHERE type = ?` (index) | 129,800 | pgjdbc | 58.9 MB | 0.2 MB |
| A: `WHERE type = ?` (index) | 129,800 | YB smart | 59.3 MB | 0.2 MB |
| B: pkey-hinted hash range 1/4 + `type = ?` | 32,618 | pgjdbc | 14.7 MB | 0.2 MB |
| B: pkey-hinted hash range 1/4 + `type = ?` | 32,618 | YB smart | 14.7 MB | 0.2 MB |

A materialized result costs about 450 B per row in all three. Both candidate paths stream with
autocommit off. The probe asserts it: under 5 MB off, over 5 MB on.

1.23's scans run with Hikari's default (autocommit on), so each of their range queries materializes its
whole range. Logged as TODO 1.37.

### 5.2 Paths by selectivity (2026-09-28)

Median of 3 after a warm-up, full streamed consumption (autocommit off, `fetchSize = 1000`), pgjdbc, ms:

| type share | rows | A `type = ?` (index) | S seq (hinted) | B8 unhinted | B8 `SeqScan` hint | B4 pkey hint | **B8 pkey hint** |
|---|---|---|---|---|---|---|---|
| 0.1% | 200 | **5** | 194 | 4 | 273 | 58 | 42 |
| 5% | 10k | 114 | 212 | 91 | 276 | 64 | **45** |
| 30% | 60k | 552 | 304 | 493 | 369 | 96 | **76** |
| 64.9% | 130k | 1188 | 481 | 938 | 495 | 183 | **133** |

What the plans show (`EXPLAIN (ANALYZE, DIST)`):

- **Unhinted, the planner always picks the type index, even at 65%.** A `yb_hash_code` range then
  becomes a post-scan `Filter`, and each of the N workers reads every row of the type. That is 1.23's
  non-composition, reproduced for a B-tree index.
- **A `SeqScan` hint is the wrong hint.** It also forbids the pkey index, so the hash range stays a
  `Filter` and every worker scans all 200k rows.
- **A pkey `IndexScan` hint is the right one.** `yb_hash_code(id)` bounds become an `Index Cond` with
  real pruning (49,870 of 200k rows per 1/4 range), and `type = ?` becomes a DocDB `Storage Filter`.
- **The cost models differ.** The pkey fan-out is O(table) with a low constant: a floor of about 40 ms
  per 200k rows. The type index is O(matches) at about 9 ms per 1k rows. The crossover is near 2% of the
  table and scales with table size.
- **Untyped 1.23 `scanNodeIds` as shipped prunes correctly:** unhinted, it plans as a pkey `Index Cond`.

Caveats: a single tserver on a dev box sharing CPU with the client. On a real cluster the pkey fan-out
spreads over tablets while one type's index range sits on one index tablet, which should move the
crossover toward the fan-out. Unmeasured. B8 beat B4 at every share ≥5%; the best N is unmeasured.

## 6. Tests

`ScanCapabilityTest` (fake stores, through the container facade):

- store-less graph → `Left(ScanUnsupported)`;
- store configured but without a scan override → `Left(ScanUnsupported)`, not `Right(empty)`;
- returns the schema's own nodes of the type, typed, including a node never warmed into the cache;
- a row of the same type owned by another schema is dropped (asymmetric counts);
- a class without `@SerialName` → `Left(SchemaError)`.

`LoadTest` (live YB):

- recovers exactly the planted rows of one type, with other types present in the table;
- an unknown type gives `Right` with an empty flow;
- streams: heap held at the first emitted element stays bounded on a result that would be tens of MB
  materialized. Must go red with `autoCommit = false` removed.

Perf (`-Pperf`): `TypeScanFeasibilityTest` stays as the record.

## 7. Parked (not rejected)

Findings kept for when the wider scan is picked up.

- **Edges.** `scanPersistentEdges`, same shape, via `idx_edges_type`. Returns `StoredEdge`. A
  cross-schema edge has no single owning schema, so it is container-level.
- **Fan-out routing (old D4).** Route between the index and the pkey-hinted `yb_hash_code` fan-out
  (`/*+ IndexScan(nodes nodes_pkey) */`, `pg_hint_plan` is on by default in YB). The decision only needs
  "are matches above the threshold?", so a bounded probe
  `SELECT count(*) FROM (SELECT 1 … WHERE type = ? LIMIT k)` costs O(k), not O(matches). The threshold
  needs table size; `reltuples` is enough for a ~2% cut. Unmeasured.
- **Subclass expansion.** `type = ANY(?)` over a base type's registered concrete subclasses.
- **Store-less persistent layer.** Scan `nodesMap` / `edgesMap` directly; eviction is forbidden there by
  the startup guard. Values use the custom JSON `StreamSerializer`, so no Hazelcast predicate can see
  `type`: every value is deserialized. Needs `IMap.iterator(fetchSize)`; verify it exists on the 5.6
  API first.
- **Ephemeral layer.** YCQL has no secondary index (it cannot coexist with per-row TTL), so it is a
  token-range sweep comparing the plain `type` column before decode and skipping expired rows.
  `HazelcastEphemeralStore` iterates its maps and checks the class.
- **Cross-layer dedup (old D1).** Needed as soon as two layers are merged:
  - ephemeral nodes are also cached in `nodesMap` with a TTL (`applyToCacheAsync`,
    `loadAndCacheNode`), so a store-less scan would emit them twice; ephemeral edges are not cached;
  - the same id can exist in both layers (gap 2).

  Point reads are **persistent-wins** (`loadAndCacheNode`). A scan must match: buffer the ephemeral
  matches as `Map<NodeId, NodeLike>`, stream the persistent arm while removing hits, then emit the
  leftovers. The earlier "ephemeral-first seen-set" recommendation had the precedence backwards.
- **Paging past the 900 s ceiling.** `id > last LIMIT n` pages are short transactions, but there is no
  single snapshot across pages.

## 8. Files

- `abyss-store-api/.../AbyssError.kt`, `AbyssStoreLike.kt`
- `abyss-store-yugabyte/.../YugabytePersistentStore.kt`
- `abyss-graph/.../AbyssSchemaWorker.kt`, `AbyssGraphSchema.kt`
- Tests as §6. README scan section. TODO 1.33 updated with the result.
