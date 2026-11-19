# Phase 0′ — `paths()` re-measured on today's code

Date: 2026-11-19 (HEAD `973a3e8` + test-only changes below). All numbers **[measured]**.

## Harness changes (test sources only)

`abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/ChainWalkRoutePerformanceTest.kt`:
- `seedChain(…, loaded: Boolean = false)`: marks each chain node's head shard loaded, both directions.
  The three other callers (`IterativeDfsPrototypeTest`, `DfsDepthHangReproTest`) keep the default.
- Two modes: `CACHE_ONLY` (no store, as before) and `STORE_WARM` (`persistentStore` = a load-counting
  wrapper over `PartialProbeStore`, chain seeded loaded).
- **Guard:** every `STORE_WARM` run asserts **0 store loads**. A load would mean the seed is cold and the
  numbers measure the preload path. All 24 store-backed runs passed it.
- New test `chain walk probe - hop route ops per link by needValue`: a bare id chase over
  `traversalEngine.outAt/inAt(cur, "test_edge", needValue)`, with no node fetches, so the count is the hop route alone.
- Each `paths()` run asserts that it covers the whole chain; each probe asserts it chases `length − 1` links.

Run: `./gradlew :abyss-graph:test --tests "…ChainWalkRoutePerformanceTest" -Pperf`, then read the output from
`build/test-results/test/TEST-…xml` (Gradle doesn't echo test stdout). Single in-JVM member, so op counts
are the metric, not wall time. Full suite **not** run (only test sources changed).

## 1. `paths()` route — real Hazelcast, warm 2,000-link chain, map ops per link

| `others` per direction | mode | DFS OUT / IN | BFS OUT / IN |
|---|---|---|---|
| 0 | CACHE_ONLY | 3.00 / 3.00 | 3.00 / 3.00 |
| 0 | STORE_WARM | 3.00 / 3.00 | 3.00 / 3.00 |
| 16 | CACHE_ONLY | 3.00 / 3.00 | 3.00 / 3.00 |
| 16 | STORE_WARM | 3.00 / 3.00 | 3.00 / 3.00 |

Breakdown, identical in every row: adjacency `getAll` 1 + edges `getAll` 1 (`resolveEdges`) + node read 1
(DFS `getAsync`, BFS `getAll` window of 1). Payload at `others = 16`: adjacency returns **17.01 entries/link**
and edges `getAll` returns **17.01 values/link**. `paths()` fetches the value of every edge of every type
and only then filters in `edgeVisitor`.

**vs the pre-4.14 baseline (2026-09-16, cache-only):** 5.50 → **3.00** at `others = 0`, 5.00 → **3.00** at 16.
4.14 removed the ~1.5-op warm probe and the second shard window. Store-backed and cache-only now cost the same for `paths()`.

## 2. Decision probe — hop route only, map ops per link

| `others` | mode | OUT `needValue=true` | OUT `false` | IN `true` | IN `false` |
|---|---|---|---|---|---|
| 0 | CACHE_ONLY | 1.00 (`entrySet`) | 1.00 (adj `getAll`) | 2.00 | 1.00 |
| 0 | STORE_WARM | **2.00** (`entrySet` + adj `getAll`) | **1.00** | 2.00 | **1.00** |
| 16 | CACHE_ONLY | 1.00, **1** entry returned | 1.00, **17.01** entries | 2.00 | 1.00, 17.01 |
| 16 | STORE_WARM | **2.00**, adj returns 17.01 + 1 value | **1.00**, 17.01 | 2.00 | **1.00**, 17.01 |

**The plan's prediction holds exactly** (idea.md §4): in production (store-backed), `needValue = false` costs
1 op/link in both directions; `true` costs 2 on OUT (`cachedOutScan` = `entrySet` + `count`) and 2 on IN
(index + `resolveEdges`). The decision "`needValue = false` in both directions" stands.

**Nuance the plan missed:** in **cache-only** mode OUT `needValue = true` ties on ops (1, since there's no
`count` without a store) and **wins on payload**: the partition predicate returns 1 value of the wanted type
where the adjacency read returns all 17 entries. So the best OUT route depends on whether a store is configured:

| deployment | best OUT route | why |
|---|---|---|
| store-backed (production) | `needValue = false` | 1 op vs 2; the payload is only adjacency entries (small) |
| cache-only | `needValue = true` | 1 op either way; 1 typed value vs every entry of every type |

My recommendation: keep `false` unconditionally. Ops are network round trips in a cluster; 17 small
adjacency entries in one response are not. A mode-dependent branch would be new machinery for a payload
difference nobody has measured as a cost. Open for cane.

## 3. `paths()` shape — fake engine, 10k chain, latency 0 (`NodeBatchLoadPerformanceTest`, 2 runs)

| | run 1 | run 2 | round trips | result |
|---|---|---|---|---|
| DFS OUT | 425 ms | 407 ms | 20,000 | 1 path, 10,000 nodes |
| DFS IN | 171 ms | 167 ms | 20,000 | 1 path, 10,000 nodes |
| BFS OUT | 4,239 ms | 4,411 ms | 20,000 | 1 path, 10,000 nodes |
| BFS IN | 4,318 ms | 4,411 ms | 20,000 | 1 path, 10,000 nodes |

- **DFS finishes now** (pre-1.34: `StackOverflowError` after ~575 links). DFS OUT runs first in the test, so
  its higher time likely includes JIT warm-up. That isn't controlled for; DFS IN is the cleaner number.
- **Correction to idea.md §1/§2:** the plan calls DFS "still O(n²) CPU" from `snapshot()`. Asymptotically true,
  but measured at ~170 ms for 10k: copying `ArrayList` references is an array copy. **BFS is the real CPU
  problem**, ~4.3 s, from its per-link `HashSet` copies (`visited.toMutableSet()`, `visited + nextNid`).
  The case for the primitive stands on streaming, 3 → ~1 ops/link, and the value-per-edge payload, not on DFS CPU.
- 2 engine round trips per link in both strategies (hop + node; the fake doesn't price the edge-value fetch
  separately). Real Hazelcast says 3 (§1).

## 4. What this sets as the Phase 1 "before"

| | ops/link (store-backed warm) | payload/link at `others = 16` | 10k CPU (fake) |
|---|---|---|---|
| `paths()` DFS | 3.00 | 17 adj entries + 17 edge values + 1 node | ~170 ms |
| `paths()` BFS | 3.00 | same | ~4.3 s |
| target: walk (`needValue = false`) | ~1.01 (1 + node batch) | 17 adj entries + 1/window nodes | O(n) |

## 5. Plan follow-ups (not applied, for cane)

1. idea.md §2.3 and §1's DFS row: soften "O(n²) CPU" for DFS to "~170 ms at 10k (measured); BFS ~4.3 s".
2. idea.md §4 table: confirmed. Add the cache-only payload nuance (§2 above) and the "keep `false`" decision once agreed.
3. The new probe is the natural home for Phase 1's map-op guard: add a third route, `walkChain`, and assert ≤ 1.05 ops/link.

## 6. Phase 1 post — `walkChain` vs `paths()` (2026-11-19, same harnesses, single run)

Real Hazelcast, warm 2,000-link chain, map ops per link (`chain walk post - walkChain map ops per link`).
Every row is identical across CACHE_ONLY / STORE_WARM and OUT / IN, with 0 store loads (asserted):

| `others` | `paths()` (§1) | `walkChain` | breakdown | payload/link |
|---|---|---|---|---|
| 0 | 3.00 | **1.01** | adjacency `getAll` 1.00 + nodes `getAll` 0.01 | 1 adj entry; 0 edge values |
| 16 | 3.00 | **1.01** | same | 17 adj entries (post-filter, expected); **0 edge values** (paths: 17) |

Fake engine, 10k chain, latency 0 (`chain walk post - walkChain over a 10k chain`):

| | wall | engine round trips |
|---|---|---|
| `paths()` DFS OUT / IN | 204 / 176 ms | 20,000 |
| `paths()` BFS OUT / IN | 4,695 / 4,599 ms | 20,000 |
| `walkChain` OUT / IN | **20 / 17 ms** | **10,085** = 10,000 hop reads + 85 `nodesAt` (7 doubling windows + 78 of 128) |

Map ops: 3.00 → 1.01 per link (−66%). CPU at 10k: BFS ~4.6 s → ~20 ms; DFS ~190 ms → ~20 ms.
