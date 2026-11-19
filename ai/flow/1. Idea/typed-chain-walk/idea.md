# Typed chain walk — `walkOut<E>()` / `walkIn<E>()`

Created: 2026-11-19

From TODO 2.32. Full design plan (moved here from `ai-scripts/TypedChainWalkPlan.md`).
Phase 0′ re-measurement results: `paths-re-measuring.md` in this folder.

Streaming, unbounded-depth walk along one typed edge chain (`A -hasNext-> B -hasNext-> C -> …`),
emitting nodes as they are reached. Two public methods, one shared body.

**Rewritten 2026-11-19.** The first plan (Phase 0 baseline 2026-09-16) was costed against a read path
that `888b870` (2026-09-28, adjacency loaded flag, TODO 4.14) replaced, and against a recursive
`dfsLoop` that TODO 1.34 replaced. Its central design argument (`needValue` inverts between directions)
no longer holds, and its Phases 2–3 already shipped. Everything below is re-derived from current
source; each claim is tagged **[read]** (verified by reading source) or **[measured]** (verified by
execution, with date). Old numbers are kept in the appendix, labelled pre-4.14.

---

## 1. What drifted

| old plan said | today | evidence |
|---|---|---|
| `walkOut` must pass `needValue = true` to buy `outAtPersistent`'s partition-local predicate route (~2.5 ops/link) | That route is now `cachedOutScan`: `entrySet(pred)` **plus** `adjacency.count`, a full 16-shard `getAll`. With a store: **2 ops/link**, and ships an unused edge value. `needValue = false` takes `readIfLoaded`: **1 op/link**. | [read] `AbyssSchemaWorker.kt:244-267, 315-326`; `ShardedAdjacencyIndex.kt:53-62, 84-89` |
| `walkIn` ~3.5 ops/link (probe ~1.5 + read 2 windows) | **1 op/link**: `readWindow` defaults to `shardCount` (16), and the loaded flag rides the read's own `getAll` | [read] `ShardedAdjacencyIndex.kt:31, 53-62`; worker passes no `readWindow` (`AbyssSchemaWorker.kt:92`) |
| "`needValue` inverts", so two methods | Both directions want `needValue = false` and are the **same machine**. Two methods stay, but for contract reasons (§3). | follows from the two rows above |
| Phase 2: invert `ensureOutWarm`'s warm-check | Done by 4.14: `ensureOutWarm` is gone; `adjacencyHopFlow` reads first and warms only when not loaded | [read] `AbyssSchemaWorker.kt:338-348` |
| Phase 3: double window-0 fetch | Done by 4.14: `readIfLoaded` emits the fetched first window as-is | [read] `ShardedAdjacencyIndex.kt:51-62` |
| DFS recurses 10k deep → OOM / silent hang | Fixed by TODO 1.34: explicit frame stack. **Still O(n²) CPU** on a chain: `snapshot()` copies the whole path twice per hop | [read] `TraversalBuilder.kt:394, 415, 419` |
| Phase 0 route baseline: `paths()` 5.5 ops/link | Measured pre-4.14, **and cache-only** (§6). Not a valid "before" for anything now. | `ChainWalkRoutePerformanceTest.kt:71` (no store) |

Still true:
- `edgesFrom` hardcodes `outAt(nid, null)` / `inAt(nid, null)`, so `paths()` reads every edge type and
  fetches every value (`needValue` defaults to `true`). [read] `TraversalBuilder.kt:343-349`, `NodeIdEngine.kt:45-46`
- BFS copies `visited` (`toMutableSet`, `visited + nextNid`) and the `Path` per link: O(n²). [read] `TraversalBuilder.kt:455, 472, 479`
- Store-side IN/OUT table (§5): edge queries unchanged since it was measured. [read] `git log -S queryEdgesYsql --since=2026-09-16` is empty.
- Ephemeral `loadInEdges` returns empty in both ephemeral stores. [read] `YugabyteEphemeralStore.kt:93-94`, `HazelcastEphemeralStore.kt:99`

## 2. Why not `paths()`

1. **It emits once, at the end.** The contract is one `Path` per *maximal* path
   (`TraversalBuilderLike.kt:80-90`). A 10k chain yields one `Path` holding 10k nodes, and `.take(10)` still walks to the end.
2. **No edge-type push-down, plus a value fetch per hop.** `edgesFrom` passes `type = null` with
   `needValue = true`. Per link: adjacency `getAll` + `resolveEdges` `getAll` + node read = **~3 ops** [read, arithmetic].
3. **BFS is O(n²) CPU** (per-link `HashSet` copies): ~4.3 s at 10k [measured 2026-11-19, fake engine].
   DFS is asymptotically O(n²) too (`snapshot()` copies `ArrayList`s), but that's an array copy: ~170 ms at 10k [measured].

A primitive is justified even if (2) were fixed. Streaming plus O(n) is a different contract from maximal paths.

## 3. Settled decisions (re-validated) and new ones

| decision | value | rationale |
|---|---|---|
| surface | **two methods: `walkOut<E>()`, `walkIn<E>()`** over one raw `walkChain` | Not cost any more. The contracts differ: `walkIn` emits in reverse chain order and cannot see ephemeral links (§5). Two names put each contract in its own KDoc. |
| hop call | **`needValue = false` in both directions, unconditionally** | 1 op/link vs 2, store-backed [measured 2026-11-19, `paths-re-measuring.md` §2]. The walk never reads `hop.edge`. Cache-only: ops tie and `true` would return 1 typed value instead of every adjacency entry; a mode branch was rejected as machinery for an unmeasured payload cost. Pinned by a map-op guard test so nobody "optimises" it back. |
| fork | **>1 edge of `E` in the walked direction → throw** | A forked chain is corrupt data. Free: the hop list is materialised anyway (`.toList()`). |
| cycle | **throw** | Same class of corruption. `visited` HashSet: ~10k ids at 10k length. Floyd would save that memory but double the round trips. |
| loop | **iterative `while`** | — |
| emission | **`Flow<NodeLike<*>>`, one node per link, origin not emitted** | Streams; the caller already holds the origin. |
| node values | **batched behind the id chase, window doubling 1 → 2 → 4 … → `FETCH_BATCH`** | **New.** A fixed 128-window means the first emission waits for 128 sequential link reads, so `.take(10)` pays 128 links. Doubling emits after link 1, costs ~7 extra `nodesAt` calls total (~86 vs ~79 at 10k), and `.take(n)` over-chases by at most ~n links. One line of state. |
| origin | `require(frontier.size == 1)` | Multi-origin has no defined order. |
| error type | plain `IllegalStateException` (`check`) with node + direction + type in the message | `ponytail:` dedicated exception only if a caller needs to branch on it. |
| ephemeral | **`walkOut(includeEphemeral = false)`**, OUT only (decided 2026-11-19) | Mirrors `outgoing<E>(includeEphemeral)`. When set, every link adds one ephemeral-store `loadEdges` RT (`ephemeralStoreHops`, type filtered in memory) on top of the 1 map op. Fork and cycle checks cover persistent + ephemeral hops together. `walkIn` has no flag: ephemeral edges are outgoing-only (§5). |

Rejected: running the value fetch concurrently with the chase. It saves ~79 RTs of ~10k (<1%) at the cost of a channel and a second coroutine.

## 4. Cost model today — arithmetic [read], to be confirmed in Phase 0′

Per link, warm, degree-1 chain node. "op" = one Hazelcast map operation (one network RT in a cluster).

| | store-backed (production) | cache-only (no store) |
|---|---|---|
| `paths()` DFS / BFS | ~3 (index 1 + `resolveEdges` 1 + node 1) | ~3 |
| walk, `needValue = false`, OUT or IN | **1** + 1/window (amortised `nodesAt`) ≈ **1.01** | ≈ 1.01 |
| walk OUT, `needValue = true` (old plan) | **2** (`entrySet` ∥ `count`) + 0.01 | ≈ 1.01 (`entrySet` only, no count), but ships the value |

At 10k: `paths()` ~30,000 ops + O(n²) CPU; walk ~10,090 ops, O(n) CPU and heap.

- **Unrelated edges on chain nodes** (`others` per direction): the 1-op read deserialises and ships
  every entry of every type in that direction, then post-filters (`ShardedAdjacencyIndex.kt:74-78`).
  This is the remaining per-link cost and it is symmetric for OUT and IN. The `needValue = true` route
  doesn't avoid it: its `count` reads the same 16 shards. Fixing it needs a type dimension in the
  adjacency layout, which belongs to 1.26's pluggable `AdjacencyIndex`, not here.
- **Cold link** (store-backed, never preloaded): `readIfLoaded` 1 (miss) + store `loadEdges`/`loadInEdges` 1
  + `addAsync` × degree, **awaited one at a time** + `markLoaded` 1 + re-read 1. So ~4 ops + 1 store RT at degree 1.
  A fully cold 10k chain is 10k sequential store reads. [read] `AbyssSchemaWorker.kt:418-446`.
  Side finding, out of scope: serial `addAsync` awaits make a cold degree-500 node cost ~500 sequential
  RTs in every traversal, not just the walk. Worth its own TODO and measurement.

## 5. The IN direction

**No reverse edges map.** `EdgeKey.kt:11` pins `pk = fromId`, so `edgesMap` is partitioned by
`fromId`, and in-edges are only reachable through the adjacency index. Since 4.14 that's also OUT's
cheapest route, so warm IN costs the same as warm OUT.

**Cold store cost** [measured 2026-09-16, live YB, `EXPLAIN (ANALYZE, DIST)`, `queryEdgesYsql` shape, single runs; query unchanged since]:

| | read requests | rows scanned | storage read time |
|---|---|---|---|
| OUT deg 2 / IN deg 2 | 2 / 3 | 4 / 6 | 0.60 / 0.70 ms |
| OUT deg 500 / IN deg 500 | 2 / 3 | 1,000 / 1,500 | 5.0 / 10.7 ms |

`edges_pkey` is `(from_id HASH, to_id ASC, type ASC)`; `idx_edges_to_id` is `lsm (to_id HASH)` with no
`type`. IN pays the index read plus a fetch-back, ~2× storage time at degree 500. Both are Index Scans.
Only the cold path (first visit per node) sees this.

**Ephemeral in-edges are invisible, by contract** (TODO 1.13/1.27): both ephemeral stores return empty
from `loadInEdges`, and `preloadIn` has no ephemeral branch. A chain with an ephemeral link ends early under
`walkIn` and reports no error. That's a *wrong answer*, not a slow one, so it goes in `walkIn`'s KDoc and gets a pinning test.

## 6. API

```kotlin
// abyss-dsl/.../TraversalBuilderLike.kt (raw). includeEphemeral is OUTGOING-only, ignored for INCOMING (same as addHop).
fun walkChain(edgeType: String, direction: HopDirection, maxLength: Int = Int.MAX_VALUE, includeEphemeral: Boolean = false): Flow<NodeLike<*>>

// abyss-dsl/.../TraversalScope.kt (passthrough, like paths)
fun walkChain(edgeType: String, direction: HopDirection, maxLength: Int = Int.MAX_VALUE, includeEphemeral: Boolean = false): Flow<NodeLike<*>> =
    raw.walkChain(edgeType, direction, maxLength, includeEphemeral)

// abyss-dsl/.../Extensions.kt (reified sugar, same shape as countEdges)
inline fun <reified E : EdgeLike<*, *>> TraversalScope<*>.walkOut(maxLength: Int = Int.MAX_VALUE, includeEphemeral: Boolean = false) =
    walkChain(E::class.serialName(), HopDirection.OUTGOING, maxLength, includeEphemeral)
inline fun <reified E : EdgeLike<*, *>> TraversalScope<*>.walkIn(maxLength: Int = Int.MAX_VALUE) =
    walkChain(E::class.serialName(), HopDirection.INCOMING, maxLength)
```

Non-suspend, cold `Flow`, like `paths()` (`TraversalScope.kt:31`). `maxLength` counts **links chased**, not
nodes emitted (they differ only on broken links, §8).

Containers: `HomogeneousSchemaGraph`/`HeterogeneousSchemaGraph` delegate `outAt`/`inAt`/`nodesAt` to the
shared worker (`HeterogeneousSchemaGraph.kt:77-80`) [read], so a walk follows a cross-schema link with no
extra code. One test covers it.

## 7. Implementation sketch (`abyss-graph/.../traversal/TraversalBuilder.kt`)

```kotlin
override fun walkChain(edgeType: String, direction: HopDirection, maxLength: Int, includeEphemeral: Boolean): Flow<NodeLike<*>> = flow {
    require(frontier.size == 1) { "walkChain needs exactly one origin (got ${frontier.size})" }
    var cur = frontier.single()
    val visited = hashSetOf(cur)
    var cap = 1                                                   // doubles to FETCH_BATCH: first node after link 1
    val window = ArrayList<NodeId>(FETCH_BATCH)

    suspend fun FlowCollector<NodeLike<*>>.flush() {
        if (window.isEmpty()) return
        val nodes = engine.nodesAt(window)
        for (id in window) nodes[id]?.let { emit(it) }            // chain order; absent (broken link) skipped
        window.clear()
        cap = minOf(cap * 2, FETCH_BATCH)
    }

    var links = 0
    while (links < maxLength) {
        // needValue = false in BOTH directions: one adjacency getAll per link. needValue = true on OUT would
        // route to cachedOutScan (entrySet + count = 2 ops) for an edge value this walk never reads.
        // includeEphemeral appends ephemeral-store hops (OUT only; hops() ignores it for IN).
        val hops = hops(cur, direction, edgeType, needValue = false, includeEphemeral).toList()
        if (hops.isEmpty()) break                                 // natural end of chain
        check(hops.size == 1) { "chain fork at $cur: ${hops.size} '$edgeType' edges ($direction)" }
        val next = hops.single().target(direction)
        check(visited.add(next)) { "chain cycle at $cur -> $next ('$edgeType', $direction)" }
        links++
        window += next
        if (window.size >= cap) flush()
        cur = next
    }
    flush()
}
```

Reuses the existing `hops(...)` and `Hop.target(direction)` helpers (`TraversalBuilder.kt:77-91`), so there's no new engine surface.

## 8. Error semantics (the known wart, now wider)

`from {}` is `Either.catch { block() }` (`AbyssGraphSchema.kt:186-192`). The walk is a cold flow collected
**after** `from` returns, so whatever it throws reaches the collector raw, not an `Either.Left`:
- fork / cycle → `IllegalStateException`
- store outage mid-walk → `StoreUnavailableException` (TODO 1.38: engine methods throw it)
- unregistered edge type → `error("Edge type '…' has no registered @TypeTag")` on the first link (`TypeTagRegistry.kt:29`)

`flushFrontierNodes` and `paths` already behave this way. The default is to **document it in the KDoc and not fix it**.
The alternative is collecting inside `from`, which kills streaming. It's open whether a new public primitive should take on the wart (§11).

**Broken link** (a `hasNext` edge to an absent node): `nodesAt` omits the node, so it isn't emitted; the chase continues
through its adjacency, which is normally empty after a delete fanout. Default: skip silently, consistent with `nodesAt`/`flushFrontierNodes`.

**Absent origin**: its adjacency is empty, so the flow is empty and nothing is thrown.

## 9. Measurement harnesses — what has to change

| harness | state | gap |
|---|---|---|
| `MapOpCounter` | built (Phase 0) | — |
| `CountingFakeEngine` (`NodeBatchLoadPerformanceTest`) | type filter + `inAt` added (Phase 0) | counts engine calls only; prices nothing, by design |
| `ChainWalkRoutePerformanceTest` | exists, **cache-only** (`AbyssGraphSchema(UuidKeyAdapter, counter.hz, …)`, no store) | Cache-only skips the loaded check and `cachedOutScan`'s `count`, so it **can't tell `needValue = true` from `false`**. Needs a **store-backed variant** (a fake store, e.g. `PartialProbeStore` from `AdjacencyLoadedFlagTest`), and `seedChain` must set `AdjacencyValue(loaded = true)` on each node's shard-0 head key, both directions. Otherwise every link measures the cold preload. |
| same test | **prints, doesn't assert** | Fine for the `paths()` baseline. The walk's numbers must be **assertions** (§10 guard). |

## 10. Phases

- **Phase 0′ — re-baseline on today's code (no production code).** **Done 2026-11-19**: `paths-re-measuring.md`.
  Implementation steps for Phase 1: `impl-plan.md`.
  1. `seedChain`: add the loaded flag (head key, both directions) as an option.
  2. `ChainWalkRoutePerformanceTest`: run `paths()` DFS/BFS × OUT/IN × `others` {0, 16} in **both** modes, cache-only
     and store-backed warm. Record ops/link and `<op>.returned`.
  3. **Decision probe for §3 "hop call"**: in store-backed warm mode, count ops/link for a bare loop of
     `outAt(cur, E, needValue = true)` vs `needValue = false`. Expected 2 vs 1. If the measurement disagrees, the plan is wrong again: stop and rethink.
  4. Fake engine, 10k: `paths()` BFS wall time + round trips (CPU baseline after 1.34; DFS is now iterative, so measure it too).
- **Phase 1 — the primitive, red first.**
  1. Add `walkChain` to `TraversalBuilderLike`/`TraversalScope`/`Extensions` with a **stub `emptyFlow()`** in
     `TraversalBuilder`, so `ChainWalkTest` compiles and fails on real assertions (expected chain ≠ empty).
  2. Implement §7. Green. Full suite.
  3. Re-run every Phase 0′ measurement with the walk alongside `paths()`; report pre/post per direction and mode.
  4. Guard check: flip `needValue` to `true` → the map-op guard must go red (store-backed). Remove the doubling →
     the `.take(2)` round-trip test must go red.
- ~~Phase 2 (warm-check inversion)~~ and ~~Phase 3 (double window-0)~~: shipped in 4.14 / `888b870`.

## 11. Tests (`ChainWalkTest`, both directions unless noted)

Inputs break symmetry: odd chain length (e.g. 7), non-head origin, unrelated edges of other types on
chain nodes in **both** directions (so an OUT test can't pass by accident through IN entries).

- 7-node chain from node 2: emits nodes 3–7 in order; origin absent. `walkIn` from node 6 emits 5…1 (reverse order).
- End of chain terminates the flow cleanly.
- Fork (2 edges of `E` out of node 4) throws; message names node, type, direction.
- Cycle (tail → node 3) throws.
- `maxLength = 3` emits exactly 3.
- 16 unrelated edges per direction on every chain node: same output (pins the post-filter).
- `.take(2)` on a 1,000-link chain: assert engine round trips ≤ a small bound (doubling window), not just the output.
- Multi-origin `from(setOf(a, b))` throws.
- Broken link: chain with node 4 deleted from the nodes map only → node 4 not emitted, walk stops or continues per its adjacency (pins §8).
- Cross-schema link in a container is followed.
- **Store-backed, cold:** evict the chain's adjacency → walk heals from the store and emits the full chain.
- **IN only:** an ephemeral edge into a chain node is *not* followed (pins the 1.27 contract).
- **OUT only:** chain with an ephemeral link at node 4: default `walkOut` stops at 4; `includeEphemeral = true`
  continues to the end. A persistent and an ephemeral `E` both leaving one node → fork throws.
- **Map-op guard (store-backed warm, real Hazelcast + `MapOpCounter`):** assert adjacency `getAll` per link ≤ 1.05,
  **zero** `edgesMap` ops, `nodesAt` `getAll` count ≤ log₂(128) + links/128 + 1.

## 12. Non-goals / the ceiling

- **~10k sequential RTs is irreducible** in the cache: link N+1's id is unknown until link N is read. ~1 op/link warm
  is the floor; the walk reaches it.
- **Hot 10k chains** want an ordinal column plus one indexed range scan (TODO 2.12 `@AbyssStoreColumn`, or 2.34's
  `(type, id)` keyset scan): O(1) round trips.
- **Cold chains**, hypothesis, **unverified on YB**: a YSQL `WITH RECURSIVE` over `edges` (`from_id = ? AND type = ?`,
  joined on `to_id`) could walk a whole chain server-side in one statement. Test against the live container before
  writing a line. It bypasses the cache and has to agree with it.
- **Hot `walkIn`** at high in-degree: a `(to_id, type)` secondary index, or a type dimension in the adjacency layout (1.26).
- "Whole chain containing X" composes: `walkIn<E>()` reversed + `walkOut<E>()`.

## 13. Decided (cane, 2026-11-19)

1. Window doubling 1 → `FETCH_BATCH` (§3): **yes**.
2. Error wart (§8): **document in KDoc only**; streaming stays.
3. Broken link (§8): **skip silently**, consistent with `nodesAt`.
4. `includeEphemeral` on `walkOut`: **offered**, default `false` (§3, §6).

---

## Appendix — pre-4.14 measurements (2026-09-16, historical; do not use as baseline)

- Fake engine, 10k chain: `paths()` DFS → `StackOverflowError` (~575 links OUT); BFS 5,079 ms OUT / 5,267 ms IN,
  20,000 round trips. DFS since rewritten (1.34).
- Real Hazelcast, **cache-only**, warm 2,000-link chain: `paths()` 5.50 map ops/link at `others = 0` (adjacency
  `getAll` 3.5 = probe ~1.5 + 2 windows), 5.00 at `others = 16` with 13–17× payload. Both the probe and the 2-window read were removed by 4.14.
- DFS on real Hazelcast: OOM at ~5k depth, silent hang 2/6 at 10k; fixed in 1.34 (`DfsDepthHangReproTest`).
