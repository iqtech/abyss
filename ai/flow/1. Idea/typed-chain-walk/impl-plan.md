# Implementation plan — `walkOut<E>()` / `walkIn<E>()` (TODO 2.32, Phase 1)

Date: 2026-11-19. Design: `idea.md`. Baseline: `paths-re-measuring.md`.

## Decisions in force

All from idea.md §3/§13 plus the Phase 0′ result:
- `needValue = false` in both directions, **unconditionally**. Measured store-backed: 1 vs 2 ops/link.
  Cache-only ties on ops; there `true` returns 1 typed value instead of every adjacency entry. Rejected as
  a mode branch: it's new machinery for an unmeasured payload cost. Flipping it later is a one-line change.
- Fork / cycle → `IllegalStateException`. Broken link → skipped silently. Origin not emitted. Single origin required.
- Node values fetched in windows doubling 1 → 2 → … → `FETCH_BATCH` (128).
- `walkOut(maxLength, includeEphemeral = false)`; `walkIn(maxLength)` has no ephemeral flag.
- Errors throw at the collector (cold flow, past `from {}`'s `Either.catch`). Documented in KDoc, not fixed.

## Files

| file | change |
|---|---|
| `abyss-dsl/.../TraversalBuilderLike.kt` | `fun walkChain(edgeType: String, direction: HopDirection, maxLength: Int = Int.MAX_VALUE, includeEphemeral: Boolean = false): Flow<NodeLike<*>>`, with KDoc stating the contract (order, fork/cycle/broken link, `maxLength` = links chased, errors at the collector, `includeEphemeral` OUT-only) |
| `abyss-dsl/.../TraversalScope.kt` | non-suspend passthrough, like `paths` |
| `abyss-dsl/.../Extensions.kt` | `walkOut<E>(maxLength, includeEphemeral)` and `walkIn<E>(maxLength)`, reified, next to `countEdges`. `walkIn` KDoc: reverse order; ephemeral links invisible (1.13/1.27), so the walk ends early with no error |
| `abyss-graph/.../traversal/TraversalBuilder.kt` | `override fun walkChain`, body per idea.md §7 (reuses `hops()`, `Hop.target()`, `engine.nodesAt`) |
| `abyss-graph/src/test/.../NodeBatchLoadPerformanceTest.kt` | `private class CountingFakeEngine` → `internal` (reused by `ChainWalkTest`) |
| `abyss-graph/src/test/.../ChainWalkTest.kt` | new, §Tests |
| `abyss-graph/src/test/.../ChainWalkRoutePerformanceTest.kt` | probe gains a `walkChain` route (post numbers) |

No engine, worker, or container change. `TraversalBuilder` is the only implementor of `TraversalBuilderLike` [read].

## Steps

1. **Stub, red.** Add the API surface. `TraversalBuilder.walkChain` = `emptyFlow()`. Write `ChainWalkTest`.
   Run it: every content test must fail on an **assertion** (expected nodes ≠ empty), not on compilation.
   The fork/cycle/multi-origin tests fail with "expected exception not thrown". Record the red run.
2. **Implement** §7 of idea.md (with `includeEphemeral` passed through). `ChainWalkTest` green.
3. **Guard checks** (each must go red, then revert):
   - `needValue = true` in the hop call → map-op guard red (store-backed: 2 ops/link).
   - fixed window (`cap = FETCH_BATCH`, no doubling) → `.take(2)` round-trip test red.
   - drop the `check(hops.size == 1)` → fork test red; drop `check(visited.add(next))` → cycle test red.
4. **Full suite** (`./gradlew test`). Report pass/fail counts.
5. **Post measurement:** `ChainWalkRoutePerformanceTest` probe with the walk route, both modes, `others` {0, 16};
   10k fake-engine walk OUT/IN next to `paths()` BFS/DFS. Append before/after to `paths-re-measuring.md`.
6. Update TODO 2.32 and idea.md with the outcome. **No commit** until cane asks.

## Tests — `ChainWalkTest`

Real Hazelcast (`graphTestHz`) unless noted. Each test uses its own map prefix. Inputs break symmetry: odd
lengths, a non-head origin, unrelated edges in **both** directions on chain nodes.

| # | test | asserts |
|---|---|---|
| 1 | 7-node `TestEdge` chain n1…n7, plus 3 `TypedEdge` out and in on every node; `walkOut<TestEdge>()` from n2 | ids == [n3…n7] in order |
| 2 | same; `walkIn<TestEdge>()` from n6 | ids == [n5…n1] |
| 3 | `maxLength = 3` from n2, OUT | [n3, n4, n5] |
| 4 | fork: extra `TestEdge` n4→x, OUT from n1 | `IllegalStateException`, message contains n4's NodeId, `test_edge`, `OUTGOING`; the nodes before the fork were emitted first (collect into a list in a `try`) |
| 5 | fork IN: extra `TestEdge` y→n4, IN from n7 | throws, `INCOMING` |
| 6 | cycle: `TestEdge` n7→n3, OUT from n1 | throws "cycle" |
| 7 | multi-origin `from(setOf(n1, n2))` | `IllegalArgumentException` when collected |
| 8 | broken link: n4 removed from the nodes map only (`getMap().delete`), OUT from n1 | [n2, n3, n5, n6, n7]: n4 skipped, chase continues |
| 9 | end of chain: OUT from n7 | empty, no error |
| 10 | store-backed cold heal: `PartialProbeStore`, chain written via transaction, then edges + adjacency maps cleared (nodes kept: the probe store's `loadNode` returns null) | full chain from the store |
| 11 | ephemeral OUT: n4→n5 written via `ephemeral(60.s)` with `FakeEphemeralStore` | default `walkOut` stops at n4; `includeEphemeral = true` reaches n7 |
| 12 | ephemeral + persistent `TestEdge` both leaving n4 with `includeEphemeral = true` | fork throws |
| 13 | ephemeral IN: same chain, `walkIn` from n7 | stops at n5 (n4→n5 invisible): [n6, n5] |
| 14 | cross-schema (Heterogeneous container, `CrossRefEdge`: long 1 → uuid u → long 2), `walkOut<CrossRefEdge>()` from long 1 | [u's node, long 2's node] |
| 15 | `CountingFakeEngine`, 1,000-link chain, `.take(2)` | 2 nodes; engine round trips ≤ 6 (2 hops + windows of 1 and 2 + slack). Fixed-128 would cost ≥ 129 |
| 16 | `CountingFakeEngine`, 1,000-link chain, full walk | 999 nodes; `nodesAt` calls ≤ ⌈log₂128⌉ + ⌈999/128⌉ + 1 |
| 17 | **map-op guard**: `seedChain(…, loaded = true, others = 16)` 257 links, store-backed (`PartialProbeStore`) + `MapOpCounter` | adjacency `getAll` ≤ 1.05 × links; **0** ops on the edges map; 0 store loads |

Test 17 runs in the normal suite (not `-Pperf`), so it's the permanent guard against the `needValue` flip.

## Risks / unknowns to verify while implementing

- #14: whether `CrossRefEdge` chains in the existing container fixture need both cross edges admitted
  (`allowCrossSchemaEdges`). If it isn't expressible with existing fixtures, drop it and say so. Containers
  delegate `outAt/inAt/nodesAt` to the worker [read], so no code path differs.
- #8: deleting from the nodes map alone leaves the store-less schema with no heal source, so `nodesAt` omits it.
  That's the intended broken-link shape.
- `paths()` and `walkChain` share `FETCH_BATCH`; the doubling state lives inside the flow body, not on the builder.
