# Plan: `scanNodeIds`/`scanEdgeIds` store-scan capability (TODO 1.23)

## Context

`AbyssStoreLike` has no enumerate/scan capability at all (point-gets and writes only), which makes
`allNodeIds()` cache-only and therefore incomplete for admin/orphan sweeps and tag-based lookup —
see `ai-scripts/StoreScanCapabilityRFC.md` for the original motivation and scoping. Before designing
this, every open feasibility question in the RFC was verified empirically against the live
YugabyteDB container (not assumed):

- `abyss-store-yugabyte/src/test/kotlin/pl/iqtech/abyss/store/yugabyte/TokenRangeScanFeasibilityTest.kt`
  — YCQL's `TokenMap`/`TokenRange` API works as expected (`com.yugabyte:java-driver-core`); proved
  blocking `execute()`, async `executeAsync()`-as-`Flow`, and a `channelFlow` fan-out across
  coroutines, all enumerating every row exactly once, no `ALLOW FILTERING`.
- `abyss-store-yugabyte/src/test/kotlin/pl/iqtech/abyss/store/yugabyte/YsqlPartitionScanFeasibilityTest.kt`
  — YugabyteDB's `yb_hash_code()` (YSQL's `token()` analog) exists, is deterministic, bounded
  0..65535; proved the same `channelFlow` fan-out over N pooled JDBC connections (JDBC has no async
  API and `Connection` isn't thread-safe, unlike YCQL's shared `CqlSession` — this is why YSQL needs
  a connection pool sized to the fan-out count and YCQL doesn't).
- The same file also proved a **negative** result worth designing around: combining
  `yb_hash_code(id) BETWEEN ? AND ?` with the GIN tag filter (`tags @> ARRAY[?]`) does not
  parallelize a tag-filtered scan — `EXPLAIN ANALYZE` showed the GIN index scan fetches *every*
  tag-matching row globally and applies the hash-code condition as a plain post-filter, so N
  coroutines running this combination would each redundantly re-scan the whole tag match. Root
  cause is structural (YugabyteDB's secondary indexes are sharded by the indexed expression, not the
  base table's key), not a missed optimization — confirmed and recorded in `TODO.md`'s 1.23 entry.

Design decisions already settled with the user (not to be re-litigated during implementation):
- API lives **only at the container level** (`HomogeneousSchemaGraph`/`HeterogeneousSchemaGraph`),
  raw `NodeId`, deliberately **unscoped by schema tag** — matches the RFC's actual motivating use
  case (a cross-tenant admin sweep), and is a genuinely different capability from the existing
  per-schema, tag-scoped `allNodeIds()`. No addition to `AbyssGraphSchema`/`AbyssEngineLike<ID>` or
  `SingleSchemaGraph`.
- `scanEdgeIds(): Flow<Pair<NodeId, NodeId>>` — bare `(fromId, toId)` pairs, no type/payload. Sized
  specifically to replace the adjacency-index "connectedNodes" idea that got killed earlier (unsafe
  after a cluster restart, cache-only) — this is the real, store-backed way to answer "does this
  node have at least one edge" for an orphan sweep. Persistent (YSQL) only; ephemeral edges are
  TTL'd/outgoing-only (TODO 1.13) and aren't part of the orphan concept.
- `scanNodeIds` **does** span both persistent and ephemeral stores, merged into one stream at the
  worker level via `channelFlow` — the caller gets one `Flow<NodeId>` covering both, not two flows
  to combine themselves. This means three implementations, not two: `YugabytePersistentStore`,
  `YugabyteEphemeralStore`, and `HazelcastEphemeralStore` (the latter is relevant because
  `AbyssSchemaWorker.ephemeralStore` is never null — it defaults to `HazelcastEphemeralStore` per
  earlier work this session).
- `scanNodeIds`/`scanEdgeIds` are **not** added to the `NodeIdEngine` interface — that seam is
  traversal plumbing (`TraversalBuilder`'s engine abstraction); scan is a distinct, admin-facing
  capability, so it gets its own plain methods on the concrete classes that need them.

## API surface

**`abyss-store-api/.../AbyssStoreLike.kt`** — safe defaults (mirrors `batchTransaction`'s pattern:
only `YugabytePersistentStore` overrides). Flow-returning, non-suspend, non-`Either`-wrapped —
deliberately different shape from this interface's other members, since a streaming operation's
errors propagate through the `Flow` itself (or via `.catch{}`), not a single wrapped result:

```kotlin
fun scanNodeIds(tag: String? = null, parallelism: Int = 4): Flow<NodeId> = emptyFlow()
fun scanEdgeIds(parallelism: Int = 4): Flow<Pair<NodeId, NodeId>> = emptyFlow()
```

`AbyssEphemeralStoreLike` gains the same `scanNodeIds` safe default (no `scanEdgeIds`).

`parallelism` is a per-call parameter (like `outEdges`/`inEdges`'s existing `pageSize`), not a
constructor setting — this is a rare, admin-triggered operation, not a per-traversal tuning knob.

## Implementation per store

**`YugabytePersistentStore`** — the two paths are genuinely different code, per the proven finding:
- `tag == null`: unfiltered — chunk `yb_hash_code(id)`'s 0..65535 range into `parallelism` disjoint
  slices, one pooled JDBC connection per slice (reuse the existing injected `ysql: DataSource` — the
  pool just needs enough headroom for `parallelism` concurrent long-lived connections alongside
  normal point-query traffic; not building a separate dedicated pool), merged via `channelFlow`
  exactly like `YsqlPartitionScanFeasibilityTest`'s proven shape.
- `tag != null`: a single plain `WHERE tags @> ARRAY[?]` query, **no** hash-range fan-out at all —
  combining them doesn't compose, so don't pretend otherwise in the two-path branch.
- `scanEdgeIds`: same fan-out shape over `abyss.edges`, `yb_hash_code(from_id)`, emitting
  `NodeId(from_id) to NodeId(to_id)` — no tag parameter (edges don't need one for this use case).

**`YugabyteEphemeralStore`** — token-range fan-out over `ephemeral_nodes`
(`TokenRangeScanFeasibilityTest`'s proven shape: `TokenMap.getTokenRanges()`, `.unwrap()`,
`.splitEvenly(parallelism)` if needed to guarantee enough ranges, grouped into `parallelism`
quarters). No GIN-equivalent index exists on this table at all (TTL/`transactions=true` tradeoff,
per the RFC), so tagged and untagged both go through the same token-range scan — `tag` is just an
extra `WHERE` clause / client-side filter alongside it, not a different code path.

**`HazelcastEphemeralStore`** — new `scanNodeIds`, and deliberately simple: unlike the killed
adjacency-index idea, an empty `ephNodes` map after a restart is *correct* here (nothing ephemeral
is meant to survive one), so there's no false-positive risk to design around. Ephemeral data is
TTL-bounded and expected to be small (session tokens, not millions of nodes) — a plain
`ephNodes.keys` enumeration (matching the `withContext(Dispatchers.IO) { ... }.forEach { emit(it) }`
idiom from this session's TODO 3.13 fix) is proportionate. Not reaching for `PagingPredicate` here
unless real usage shows these maps get large.

## Worker + container wiring

**`AbyssSchemaWorker`** (new methods, not part of `NodeIdEngine`):

```kotlin
fun scanNodeIds(tag: String? = null, parallelism: Int = 4): Flow<NodeId> = channelFlow {
    persistentStore?.let { store -> launch { store.scanNodeIds(tag, parallelism).collect { send(it) } } }
    launch { ephemeralStore.scanNodeIds(tag, parallelism).collect { send(it) } }
}

fun scanEdgeIds(parallelism: Int = 4): Flow<Pair<NodeId, NodeId>> =
    persistentStore?.scanEdgeIds(parallelism) ?: emptyFlow()
```

With no `persistentStore` configured: `scanNodeIds(tag = null)` still gets the ephemeral-store
contribution (always present); `scanNodeIds(tag != null)` should fail loudly rather than silently
drop the tag — tags aren't cached anywhere today, so a tag filter with no persistent store to ask
can't be honored. `scanEdgeIds()` with no store returns `emptyFlow()` — no Hazelcast-side edge-scan
mechanism is being built for this (would just recreate the "looks complete, isn't" problem the RFC
opened by criticizing).

**`HomogeneousSchemaGraph`/`HeterogeneousSchemaGraph`** — direct delegation, each gets:

```kotlin
fun scanNodeIds(tag: String? = null, parallelism: Int = 4): Flow<NodeId> = worker.scanNodeIds(tag, parallelism)
fun scanEdgeIds(parallelism: Int = 4): Flow<Pair<NodeId, NodeId>> = worker.scanEdgeIds(parallelism)
```

## Files touched

1. `abyss-store-api/src/main/kotlin/pl/iqtech/abyss/store/api/AbyssStoreLike.kt` — add both safe
   defaults to `AbyssStoreLike`, `scanNodeIds` to `AbyssEphemeralStoreLike`.
2. `abyss-store-yugabyte/.../YugabytePersistentStore.kt` — implement `scanNodeIds`/`scanEdgeIds`.
3. `abyss-store-yugabyte/.../YugabyteEphemeralStore.kt` — implement `scanNodeIds`.
4. `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/HazelcastEphemeralStore.kt` — implement
   `scanNodeIds`.
5. `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AbyssSchemaWorker.kt` — add
   `scanNodeIds`/`scanEdgeIds`, merging via `channelFlow`.
6. `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/HomogeneousSchemaGraph.kt` and
   `HeterogeneousSchemaGraph.kt` — expose both, direct delegation.

## Not doing

- No `scanNodeIds`/`scanEdgeIds` on `AbyssGraphSchema`/`AbyssEngineLike<ID>`/`SingleSchemaGraph` —
  container-level, raw `NodeId` only, per the decision above.
- No `scanEdgeIds` on `AbyssEphemeralStoreLike` — not asked, and ephemeral edges aren't an orphan
  concept.
- No dedicated connection-pool infrastructure for scans — reuses the existing injected
  `DataSource`/`CqlSession`; pool sizing is an operational/config concern, not a code one.
- No `PagingPredicate`-based Hazelcast scan for `HazelcastEphemeralStore` — plain enumeration until
  proven insufficient.
- No revival of the adjacency-index "connectedNodes()" idea — stays dead; `scanEdgeIds` is its
  store-backed, restart-safe replacement.

## Verification

Extend the existing live-container test files (not new throwaway spikes — these are now testing the
real production methods):
- `YugabytePersistentStore`: `scanNodeIds(tag = null)` and `scanNodeIds(tag = "...")` each recover a
  known planted set correctly; `scanEdgeIds()` recovers known planted `(from, to)` pairs.
- `YugabyteEphemeralStore`: `scanNodeIds` (tagged and untagged) over `ephemeral_nodes`.
- `HazelcastEphemeralStore`: `scanNodeIds` against an embedded test Hazelcast instance, matching
  `HazelcastEphemeralStoreTest.kt`'s existing conventions.
- `AbyssSchemaWorker.scanNodeIds`: the important *new* behavior — plant distinct node ids in a fake
  persistent store and in the (real or fake) ephemeral store, confirm the merged `Flow` contains
  both sets, no duplicates, no drops.
- Container-level: register 2+ schemas on a `HomogeneousSchemaGraph`/`HeterogeneousSchemaGraph`,
  confirm `scanNodeIds`/`scanEdgeIds` return ids across *all* registered schemas (unscoped) —
  contrast with `allNodeIds()`'s existing per-schema scoping test.

Run `./gradlew :abyss-store-yugabyte:test :abyss-graph:test` against the live container for the full
picture; confirm nothing in the existing suite (`LoadTest.kt`, `AlgorithmsTest.kt`,
`HazelcastEphemeralStoreTest.kt`) regresses.
