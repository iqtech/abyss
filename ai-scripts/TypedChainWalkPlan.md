# Typed chain walk — `walkOut<E>()` / `walkIn<E>()`

Streaming, unbounded-depth walk along a typed edge chain (`A -hasNext-> B -hasNext-> C -> …`),
emitting nodes as they are reached. Two directions, two public methods, one shared body.

## Why not `paths()`

`paths()` can already express the walk
(`edgeVisitor = { _, e -> e is HasNext }`, `nodeEvaluator = { _, _ -> INCLUDE_AND_CONTINUE }`),
but it is the wrong instrument for a degree-1 chain at 10k length:

1. **Emits once, at the end.** Contract is one `Path` per *maximal* path
   (`TraversalBuilderLike.kt:80-90`). A 10k chain produces a single `Path` holding 10k materialized
   nodes. `.take(10)` still walks to the end.
2. **No edge-type push-down.** `TraversalBuilder.edgesFrom` (`:342-348`) hardcodes
   `engine.outAt(fromNid, null)`. Every step reads every edge of every type and discards the misses
   in `edgeVisitor` — after deserializing each one's value (`hop.edge!!` at `:369`, `:447`).
3. **Both loops degrade on exactly this shape.**
   - DFS recurses (`:386`, `:391`) → 10k chained continuations, the pathology TODO 3.12 fixed for
     `detectCycle` and never applied here.
   - BFS copies a growing `visited` set (`:440`) and a growing `Path` (`:433`) per link → ~100M
     element copies over 10k links. O(n²). 4.12's `FETCH_BATCH` windowing buys nothing at degree 1.

## Settled decisions

| decision | value | rationale |
|---|---|---|
| surface | **two methods: `walkOut<E>()`, `walkIn<E>()`** | `needValue` inverts between directions (below), so one method with a `direction` flag would carry a branch whose halves contradict each other at the call site. Two names, two documented cost contracts, one shared body. |
| fork | **>1 edge of type `E` in the walked direction → throw** | A forked chain is corrupt data; silently picking one hides it. Free to detect in both directions — see below. |
| loop shape | **iterative `while`** | TODO 3.12's lesson applied before it bites. |
| emission | **`Flow<NodeLike<*>>`, one node per link** | Streams; `.take(n)` genuinely stops. |
| node values | **batched `FETCH_BATCH` behind the id chase** | The chase needs only ids, so value fetches decouple and amortize (10k → ~79 `nodesAt` calls). |

`walkIn` mirrors the invariant, not the topology: walking IN along `hasNext` from the tail, a healthy
node has exactly one *incoming* `hasNext`; >1 means two nodes both claim to precede it. Emission is in
reverse chain order.

### The asymmetry: `needValue` inverts

`outAtPersistent` (`AbyssSchemaWorker.kt:265-271`) gates a partition-local `edgesMap` predicate fast
path on `type != null && needValue` — **asking for the edge value is what unlocks type filtering at
the map level.** `inAt` (`:274-275`) has no such path: it always takes `adjacencyHopFlow`.

| | call | route | map ops/link (warm) |
|---|---|---|---|
| `walkOut` | `outAt(cur, E, needValue = true)` | `edgesMap` partition predicate, type-filtered in the map | **~2.5** |
| `walkIn` | `inAt(cur, E, needValue = false)` | adjacency index, type **post**-filtered at `ShardedAdjacencyIndex:49` | **~3.5** |

The half op is the warm-check. `ShardedAdjacencyIndex.isEmpty` (`:58-65`) reads shards 0–7 and
stops only if that window holds an entry; otherwise it pays a second `getAll` over 8–15. Cheap for a
dense node — but a degree-1 chain node's single edge lands in 8–15 half the time, so the probe
averages ~1.5 `getAll`, not 1. Break-down: `walkOut` = probe ~1.5 + `entrySet` 1;
`walkIn` = probe ~1.5 + `read` 2 windows (all 16 shards, see fork detection below).

Neither reads `hop.edge`. `walkOut` asks for the value it does not want, to buy the route;
`walkIn` declines it, because there is no route to buy and the value fetch would be pure cost.
Both halves look wrong in isolation and need a comment saying so.

Fork detection is free in both, for different reasons: `walkOut`'s predicate returns every match in
one shot (`.size`), and `walkIn`'s adjacency read walks all 16 shards regardless of how early a match
appears, so counting costs nothing extra.

## The IN direction — what makes it a different machine

**There is no reverse edges map.** `find` for `*everse*` returns nothing; the two surviving
references (`AdjacencyKey.kt:19`, `AdjacencyKeySerializer.kt:9`) are stale comments describing a
structure the adjacency index replaced. And `EdgeKey.kt:11` pins `pk = fromId.toString()`, so
`edgesMap` is partitioned by `fromId` — a node's in-edges are scattered across every partition and a
partition-local predicate structurally cannot find them. No fast path exists, and adding one is
bigger than this plan.

**Type filtering degrades from index-level to post-filter.** `walkOut` filters inside the map
predicate. `walkIn` reads every neighbour of every type off the adjacency shards and discards
(`ShardedAdjacencyIndex:49`). On a chain node carrying 500 unrelated in-edges, that is 500 entries
moved per link to follow one.

**Cold store cost — measured against the live container** (`abyss_test_graph`, real keys, 429 edges /
13,647 nodes present), with the exact `LEFT JOIN` shape `queryEdgesYsql` emits:

| | storage read requests | rows scanned | plan |
|---|---|---|---|
| `loadEdges(from_id)` | **2** | 2 | `Index Scan using edges_pkey` |
| `loadInEdges(to_id)` | **3** | 4 | `Index Scan using idx_edges_to_id` + fetch-back |

`edges_pkey` is `(from_id HASH, to_id ASC, type ASC)` — the primary table *is* distributed by
`from_id`, so a node's out-edges are colocated in one tablet. `to_id` is only a secondary index
(`lsm (to_id HASH)`, no `type` column), so IN pays an index read, a fetch-back into the main table,
then the batched node read.

Two caveats on that table, both load-bearing: the probe degree was **2**, so it does not predict
degree 500; and YB batches the fetch-back (the `ARRAY[…, $1023]` in the plan), so the extra cost grows
in steps, not per row. The thing actually worth knowing came out fine — **both plans are Index Scans.
`loadInEdges` is not a sequential scan.**

**Ephemeral in-edges are invisible, by contract.** `YugabyteEphemeralStore:90-92` and
`HazelcastEphemeralStore:95` both return empty from `loadInEdges` unconditionally — ephemeral edges
are outgoing-only (TODO 1.13/1.27), deliberately, with the intended workaround named in the comment:
model an explicit opposite outgoing edge. `preloadIn` (`:373-381`) has no ephemeral branch at all.
This is the one asymmetry that yields a *wrong answer* rather than a slow one, so it belongs in
`walkIn`'s KDoc, not a footnote.

## Small defaults (flip in one line if wrong)

Apply to both directions:

- **Origin not emitted** — the flow is what comes *next*; the caller already holds it.
- **Cycle → throw**, consistent with fork: a chain that loops is the same class of corruption.
  (`visited` is retained for this: ~500KB at 10k, versus Floyd's tortoise/hare which would save the
  memory and double the round trips — trading the cheap resource for the expensive one.)
- **Single origin required** — `require(frontier.size == 1)`.
- **Fork error type**: plain `IllegalStateException` with a precise message.
  `ponytail:` upgrade to a dedicated exception class only if a caller needs to branch on it.

## API

Two public methods as asked; one raw entry point, since the loop body is shared and
`TraversalBuilderLike` is impl-facing, not caller-facing. Flip to two raw methods if you want the
directions to diverge further later.

```kotlin
// abyss-dsl/TraversalBuilderLike.kt  (raw)
fun walkChain(edgeType: String, direction: HopDirection, maxLength: Int = Int.MAX_VALUE): Flow<NodeLike<*>>

// abyss-dsl/TraversalScope.kt  (passthrough, like paths/collectSubgraph)
fun walkChain(edgeType: String, direction: HopDirection, maxLength: Int = Int.MAX_VALUE): Flow<NodeLike<*>> =
    raw.walkChain(edgeType, direction, maxLength)

// abyss-dsl/Extensions.kt  (reified sugar — calls the member, same as countEdges does)
inline fun <reified E : EdgeLike<*, *>> TraversalScope<*>.walkOut(maxLength: Int = Int.MAX_VALUE) =
    walkChain(E::class.serialName(), HopDirection.OUTGOING, maxLength)

inline fun <reified E : EdgeLike<*, *>> TraversalScope<*>.walkIn(maxLength: Int = Int.MAX_VALUE) =
    walkChain(E::class.serialName(), HopDirection.INCOMING, maxLength)
```

Non-suspend, returning a cold `Flow` — matches `paths()` (`TraversalScope.kt:31`). Collected after
`from {}` returns, the pattern `TraversalScope.kt:17-20` already sanctions.

## Implementation sketch (`TraversalBuilder.kt`)

```kotlin
override fun walkChain(edgeType: String, direction: HopDirection, maxLength: Int): Flow<NodeLike<*>> = flow {
    require(frontier.size == 1) { "walkChain needs exactly one origin (got ${frontier.size})" }
    var cur = frontier.single()
    val visited = mutableSetOf(cur)
    val window = ArrayList<NodeId>(FETCH_BATCH)

    suspend fun FlowCollector<NodeLike<*>>.flush() {          // same shape as adjacencyHopFlow's flush
        if (window.isEmpty()) return
        val nodes = engine.nodesAt(window)
        for (id in window) nodes[id]?.let { emit(it) }        // chain order preserved
        window.clear()
    }

    var n = 0
    while (n++ < maxLength) {
        // needValue inverts by direction — see "The asymmetry" in TypedChainWalkPlan.md.
        // OUTGOING: true routes to outAtPersistent's partition-local edgesMap predicate (type-filtered
        //   at the map level, 2 RTs). The edge value itself is unused; we buy the route with it.
        // INCOMING: inAt has no such path, so a value fetch would be pure cost — key-only hops, 3 RTs.
        val hops = when (direction) {
            HopDirection.OUTGOING -> engine.outAt(cur, edgeType, needValue = true)
            HopDirection.INCOMING -> engine.inAt(cur, edgeType, needValue = false)
        }.toList()

        if (hops.isEmpty()) break                             // natural end of chain
        check(hops.size == 1) { "chain fork at $cur: ${hops.size} '$edgeType' edges ($direction)" }
        val next = hops.single().let { if (direction == HopDirection.OUTGOING) it.toId else it.fromId }
        check(next !in visited) { "chain cycle at $cur → $next" }
        visited += next
        window += next
        if (window.size >= FETCH_BATCH) flush()
        cur = next
    }
    flush()
}
```

## Expected cost — arithmetic, not evidence

Constants: `adjacencyShardCount = 16`, `readWindow = 8`, `valueFetchBatch = FETCH_BATCH = 128`.

| | RTs/link | 10k chain | shape damage |
|---|---|---|---|
| `paths()` today | ~5.5 | ~55,000 | 10k-deep recursion (DFS) or O(n²) copying (BFS); 10k nodes held live |
| `walkOut<E>()` | ~2.5 (+79 total) | ~25,079 | iterative; O(n) `visited`; nothing accumulated |
| `walkOut<E>()` + warm-check inversion | 1 | ~10,079 | — |
| `walkIn<E>()` | ~3.5 (+79 total) | ~35,079 | same shape; no fast path, type post-filtered |

`paths()` per link: probe ~1.5 + adjacency `read` 2 windows + edge-value `getAll` 1 + node fetch 1.
The ~.5 on every row is the degree-1 probe cost explained under "The asymmetry" — itself a
correction to an earlier draft that assumed 1, which is the argument for counting ops, not deriving them.

Every number above is derived by reading the code — only the *store-side* table in the IN section is
measured. The two wins are **different in kind** and **no single existing harness sees both**:

- **Structural win** (recursion, O(n²) copying, node-fetch batching, laziness) — visible at the
  `NodeIdEngine` boundary.
- **Route-selection win** (~5.5 → ~2.5 RTs) — invisible there; needs Hazelcast map-operation counts.

## Measurement harnesses

| harness | boundary | sees | blind to |
|---|---|---|---|
| `CountingFakeEngine` (`NodeBatchLoadPerformanceTest`) | `NodeIdEngine` | structural win; engine-call counts; `nodesAt` batching | **route selection — its `outAt` ignores `type` and charges 1 RT per call whatever the route; its `inAt` returns `emptyFlow()` (`:75`), so it cannot exercise IN at all** |
| `DelayedFakeStore` (`AdjacencyPreloadPerformanceTest`) | worker/store | store `loadEdges` round trips (preload, self-heal) | Hazelcast map ops |
| real in-JVM Hazelcast (`graphTestHz`, per `SupernodeTraversalTest`) | full worker | correctness at scale; map ops *if* counted | wall-clock — single member means local partitions, so `getAll`/`entrySet` are in-process and latency deltas are ~0 |
| live YB container (`abyss_test_graph`) | store | `EXPLAIN (ANALYZE, DIST)` storage request counts | anything cache-resident |

**Two gaps this plan must close before Phase 0 means anything:**
1. **The fake is blind to the route, and to IN completely.** Its `outAt` never reads `type`, so
   `paths()`'s `outAt(nid, null)` and `walkOut`'s `outAt(nid, "hasNext")` score identically. That
   misattributes rather than under-reports: under the fake `paths()` costs outAt + nodeAt = 2/link and
   `walkOut` ≈ 1.01, a tidy ~2x credited entirely to node batching with the type push-down worth zero.
   Its `inAt` is worse — `emptyFlow()` means a `walkIn` over 10k links ends at link 0 with no error, so
   every IN test passes green while exercising nothing.
   **Fix (test-only, ~30 lines):** a `parents` map so `inAt` mirrors `outAt`, both filtering emissions
   by `type`. **Deliberately no per-route cost model** — charging 2 or 3 by route would encode this
   plan's arithmetic into the fake and replay it back with a passing test attached (the probe
   correction above shows that arithmetic was already off). The fake counts engine calls; only real
   Hazelcast prices them.
2. **Nothing counted Hazelcast map operations generally** — only two single-map perf-test proxies — and
   wall-clock cannot stand in: on a single in-JVM member every partition is local, so a `getAll` is
   microseconds under JIT/GC noise, while in production it is a network round trip. Phases 2–3 are
   *entirely* "one fewer map op". Skip this and Phase 1 prints a real ~2x from the fake, Phase 2 ships
   on arithmetic, and the type push-down and all of IN go unmeasured.

Map-op counter (gap 2) — resolved (`getLocalMapStats()` deltas rejected: member-wide, includes background traffic):
- **Built: `MapOpCounter`** (abyss-graph test sources). Correction to gap 2 as first written: the repo
  *did* count map ops, twice — `OutEdgePagingPerformanceTest` and `HopStreamingPerformanceTest` each
  carry a bespoke single-map `java.lang.reflect.Proxy`. `MapOpCounter` generalizes that pattern: every
  `IMap` op on every map, per map name + method. Proxy rather than `IMap by` delegation — an `IMap by`
  probe compiled only after matching Hazelcast's nullability annotations (`getAll(MutableSet<K>?)`,
  `get(K & Any)`), and still counted only the ops it overrode. Blind to `TransactionContext` maps
  (only `HazelcastEphemeralStore` uses them).
- **First measurement** (`MapOpCounterTest`, one in-edge, real Hazelcast): `inEdges` costs adjacency
  `getAll` **3** when the entry sits in shards 0–7 and **4** in shards 8–15, plus edges `getAll` 1 —
  confirming the ~1.5-op warm-check correction in "The asymmetry".

## Phases

- **Phase 0 — baseline (do first, no production code).**
  - ~~Map-op counter (gap 2)~~ — done, `MapOpCounter`.
  - ~~Fake engine type filter + `inAt` (gap 1)~~ — done: `parents` inverted from `children`, `edgeTypeOf` defaulting to `"test_edge"`; the five `NodeBatchLoadPerformanceTest` scenarios measured identical round trips before/after (4 / 307 / 5 / 3 / 1).
  - *Structural*: 10k chain through the fake (`children` takes it trivially), measuring `paths()`
    DFS and BFS.
  - *Route*: seed a 10k chain into real Hazelcast the way `SupernodeTraversalTest.seedHub` does
    (bulk `putAll` into `g-nodes`/`g-edges`/`g-edges-adjacency` — 10k sequential `transaction {}`
    commits would dominate the run), and record `getAll`/`entrySet` counts for `paths()`.
  - *Store*: re-run the IN/OUT `EXPLAIN (ANALYZE, DIST)` comparison at realistic degree (seed a node
    with ~500 in-edges) — the measured table above used degree 2 and does not predict degree 500.
  - **Baseline recorded (2026-09-16)** — see "Phase 0 results" below.
- **Phase 1 — both primitives.** Implement + tests, re-run every Phase 0 measurement, report
  pre/post per direction.
- **Phase 2 — warm-check inversion (separate commit, own measurement).**
  `ensureOutWarm` probes `isEmpty` *before* the scan, costing ~1.5 RTs on every degree-1 call including
  the 9,999 warm ones. Invert: scan first, warm-and-retry only on an empty result. Warm path ~2.5 → 1
  RT; cold path gets worse (an extra scan before the warm). Steady state is warm. **Shared by every caller of `outAt`'s fast path, not just the
  walk** — hence its own commit and numbers. Needs a cold-node regression test, since this phase
  makes the cold path strictly worse.
- **Phase 3 — double window-0 fetch (separate commit).** `adjacencyHopFlow` calls `isEmpty` (reads
  shards 0-7, and 8-15 too when window 0 is empty), then `read` immediately re-reads them. Affects every `inAt` — so every
  `walkIn` link — and every untyped read. Same counter, same caveat: in-JVM latency will not move,
  the operation count is the evidence.

## Phase 0 results (baseline, before any walk code)

**Shape — fake engine, 10k chain, latency 0** (`NodeBatchLoadPerformanceTest`, `-Pperf`):

| `paths()` | OUT | IN |
|---|---|---|
| DFS | **DNF** — `StackOverflowError` after 1,151 round trips (~575 links), 31 ms | **DNF** — `StackOverflowError` after 915 round trips, 13 ms |
| BFS | 5,079 ms, 20,000 round trips, 1 path / 10,000 nodes | 5,267 ms, 20,000 round trips, 1 path / 10,000 nodes |

BFS: 2 round trips/link (hop + node window of 1 — `FETCH_BATCH` buys nothing at degree 1); ~5 s of
pure CPU is the O(n²) `visited`/`Path` copying. DFS: the fake never suspends (`delay(0)`), so the
recursion lives on the real stack.

**Route — real Hazelcast + `MapOpCounter`, warm 2,000-link chain** (`ChainWalkRoutePerformanceTest`, `-Pperf`).
Identical for DFS/BFS and OUT/IN except the node read (DFS `getAsync` per link, BFS `getAll` window of 1):

| others/direction | map ops/link | adjacency `getAll`/link | adjacency entries returned/link | edge values returned/link |
|---|---|---|---|---|
| 0 | **5.50** | 3.50 (probe ~1.5 + read 2) | ~2 | 1 |
| 16 | **5.00** | 3.00 (window 0 never empty → probe 1) | **~25.5** | **17** |

The ~1.5-op probe correction is now measured, not derived. Op count *drops* with unrelated edges while
payload grows 13–17× — which is why `MapOpCounter` records `<op>.returned`: call counts alone would
have scored the dense variant as cheaper.

**DFS `paths()` at depth on real Hazelcast — out of memory, sometimes as a silent hang** (`DfsDepthHangReproTest`, own
instance, Hazelcast logging on, default uncaught handler, default 512 MB test heap):

| DFS length | outcome | peak heap | GC |
|---|---|---|---|
| 2,000 | ok | ~134–213 MB | ~100 ms |
| 3,000 | ok 9/10 runs; 1 hang (first run, not reproduced in 8 repeats) | ~280–326 MB | ~200 ms |
| 4,000 | ok | ~464 MB | 667 ms |
| 5,000 | `OutOfMemoryError` | ~510 MB | 13 s |
| 10,000 | `OutOfMemoryError` 4/6 fresh JVMs; **silent hang 2/6** | ~510 MB | 6–17 s |
| 10,000 **BFS (control)** | ok, 16.6 s | **~94 MB** | 214 ms |

- **Cause of the memory (controlled):** DFS retains per-level copies — `visited.toMutableSet()`, `seen.toSet()` for
  the child, `currentPath.nodes + nextNode` — on the suspended recursion, O(depth²). BFS on the same chain holds one
  queue entry and stays at ~94 MB.
- **Hang = the OOM landing where nobody reports it.** Captured hang: progress frozen at 4,192 hops, heap full,
  every Hazelcast operation thread idle, coroutine workers parked, collector parked in `runBlocking` — and **no error
  on any thread** despite logging + uncaught handler. When the OOM does surface it hits arbitrary threads
  (`MetricsRegistry`, `Keep-Alive-Timer`, Hazelcast scheduled tasks, or the collector).
- **Where the lost error is swallowed: unidentified.** Not `CompletionStage.asDeferred` — its completion catch
  forwards to `handleCoroutineException` (kotlinx-coroutines 1.9.0 source).
- The fake-engine DNF above is a different mechanism (real-stack `StackOverflowError`, because `delay(0)` never
  suspends); on Hazelcast every level suspends, so the stack is not the limit — heap is.

**Store — live YB, one hub, `EXPLAIN (ANALYZE, DIST)` in a rolled-back txn, `queryEdgesYsql` shape** (single runs):

| | read requests | rows scanned | storage read time |
|---|---|---|---|
| OUT deg 2 / IN deg 2 | 2 / 3 | 4 / 6 | 0.60 / 0.70 ms |
| OUT deg 500 / IN deg 500 | 2 / 3 | 1,000 / 1,500 | 5.0 / 10.7 ms |

Request count is flat in degree; IN's cost is the `idx_edges_to_id` index read (+500 rows) and ~2×
storage time at degree 500. Both remain Index Scans.

## Tests

`ChainWalkTest`, each case run for both directions unless noted:
- 5-node chain emits the far nodes in order; origin absent. `walkIn` from the tail yields reverse
  chain order.
- Chain end terminates the flow (no error).
- Fork (2 edges of `E` in the walked direction) throws, message names the node and direction.
- Cycle (tail → head) throws.
- `maxLength` truncates mid-chain.
- Chain nodes carrying many other edge types emit the same result — pins the type filter in both the
  map-predicate (OUT) and post-filter (IN) routes.
- `.take(2)` on a long chain stops early — assert the fake's round-trip count, not just the output.
- Multi-origin frontier throws.
- **IN only:** an ephemeral edge into a chain node is *not* followed — pins the documented
  outgoing-only contract so it fails loudly if that ever silently changes.

## Non-goals / the ceiling

~10,000 sequential round trips is irreducible for `walkOut` — link N+1's id is unknowable until link
N is read. 3–5s for a 10k chain at partition-local latency, and no traversal-layer work changes that.
If 10k chains are walked on a hot path, the structural answer is an ordinal column on the chain nodes
plus one indexed range scan (TODO 2.12 `@AbyssStoreColumn`, or 1.33's type-index scan) — O(1) round
trips instead of O(n). These primitives are the honest general case, not a substitute for that.

If `walkIn` becomes a hot path in its own right, the money is not in the walk either: it is in
restoring a `toId`-partitioned reverse map (giving IN the predicate route OUT already enjoys), or
extending the secondary index to `(to_id, type)` so the store-side typed IN lookup is indexed.
Both are larger than this plan and should be driven by a measured IN workload, not anticipated.

"Whole chain containing X" now composes without new machinery: `walkIn<E>()` reversed, then
`walkOut<E>()` — each half keeping the strict degree-1 invariant rather than blurring both into one
weak check.

## Known wart

`from {}`'s `Either.catch` wraps the *block*, and the flow is cold — so a fork at link 5000 throws
into the collector, not into an `Either.Left`. `flushFrontierNodes` already behaves this way.
Documented, not fixed.
