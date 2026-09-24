# paths {} — silent-drop audit findings (ISSUE)

Status: OPEN — findings only, nothing fixed. Audit date: 2026-09-24 (on 0.35.0, `c048725`).
Probe: `abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/PathsSilentDropAuditTest.kt` (prints `AUDIT ...`
lines, asserts nothing — a probe, not a gate).

Question audited: can `paths {}` quietly drop something from the response?

Answer: the walk itself doesn't — DFS matches an independent contract oracle exactly. What does drop
silently is the **store read-through beneath it**: a failed store read is indistinguishable from "absent".

Out of scope: ephemeral edges (`paths` never passes `includeEphemeral`) — known, tracked separately.

## Method

1. **Differential fuzz vs a contract oracle.** Existing `IterativeDfsPrototypeTest` gate 1 compares
   iterative DFS against a *recursive rewrite of the same algorithm* — a spec-level bug shared by both
   would pass. The probe adds an oracle written from the KDoc (`TraversalBuilderLike.paths`) + README
   "Node uniqueness" contract as immutable recursion, and compares BOTH strategies as multisets
   (missing vs extra). 2,000 random graphs (cycles, self-loops, parallel edges of distinct types,
   dangling endpoints), route-dependent visitors, all directions × maxDepth {0,1,2,3,4,∞} = 36,000 cases,
   26,168 oracle paths.
2. **Real Hazelcast + a store with a kill switch** (`FlakyStore.failing` → every load returns `Left`),
   chain a→b→c, `INCLUDE_AND_CONTINUE`, OUT.

## Finding 1 — store failure becomes a silently shorter result (REAL silent drop)

| Scenario | DFS and BFS both return |
|---|---|
| S0 warm | `Right([a>b>c])` ✅ |
| S1 edge values evicted, store up | `Right([a>b>c])` ✅ (TODO 1.34 heal) |
| S2 edge values evicted, store DOWN | `Right([])` ❌ |
| S3 node b evicted, store DOWN | `Right([])` ❌ |
| S4 only leaf c evicted, store DOWN | `Right([a>b])` ❌ — plausible, well-formed, **wrong** |
| S6 cold index (restart), store DOWN | `Right([])` ❌ |
| S5 / S7 store back up | `Right([a>b>c])` ✅ |

S4 is the dangerous one: losing c makes b a "natural terminal", so a truncated path is emitted as if
it were complete.

**Root cause** — every read-through collapses `Left` (failed) into `null` (absent) via `.getOrNull()`,
all in `AbyssSchemaWorker.kt`:
- `loadAndCacheNode` (persistent + ephemeral loads)
- `loadAndCacheNodes` (batched)
- `loadAndCacheEdge`
- `preloadOut` / `preloadIn` (cold adjacency warm → node looks edge-less)
- plus `AbyssStoreLike.loadNodes` default impl (per-id `loadNode(id).getOrNull()`)

Consumers then treat the null as "gone": `dfsLoop` `engine.nodeAt(next) ?: continue`, `bfsLoop`
`nodes[nextNid] ?: continue`, `adjacencyHopFlow.flush` `values[hop]?.let`, origin `nodesAt(frontier)`.

**Relation to eviction:** trigger, not cause. The drop needs a cache miss AND a failed store read. Misses
come from eviction (`abyss-nodes` is LRU + max-idle, so misses are routine in prod) or a cold start /
new member (S6 — no eviction involved). Disabling eviction would not fix it. Warm cache + dead store is
harmless.

**Scope:** not paths-specific — every traversal primitive and read that falls through to the store has
the same blind spot (`from {}` hops, `pathTo`, `exhaustReachable`, `node()`, `outEdges`, …).

**Fix direction (proposal, to verify):** keep "absent" (`Right(null)`) and "failed" (`Left`) distinct in
the read-through; on `Left` raise a typed failure that surfaces as `Left` from `from {}` / the facade.
Check first how `from {}` maps exceptions today. Possibly the same thread as the open TODO 1.34 note
"swallowed-error catch site".

## Finding 2 — BFS emits non-maximal prefixes and duplicates (contract violation, nothing lost)

Fuzz: DFS 0 missing / 0 extra, identical emission order. **BFS 0 missing / 481 cases with extras.**

Minimal repros (fake engine, OUT):
- a→b (IC); b→x1 (EXCLUDE_AND_CONTINUE, dead end); b→c (INCLUDE_AND_PRUNE)
  - DFS `[a>b>c]` · BFS `[a>b>c, a>b]` — `a>b` is an intermediate prefix; KDoc: "Intermediate prefixes
    are never emitted".
- a→b (IC); b→x1, b→x2 (both EXCLUDE_AND_CONTINUE dead ends)
  - DFS `[a>b]` · BFS `[a>b, a>b]` — duplicate.

**Cause:** `TraversalBuilder.bfsLoop` natural-terminal line
`if (!produced && currentPath.nodes.size > 1) emit(currentPath)`. An `EXCLUDE_AND_CONTINUE` entry
carries its parent's path; when it dead-ends it emits that path on its own, with no knowledge whether a
sibling branch already emitted an extension (DFS has the per-frame `emitted` flag propagated up through
excluded frames; BFS has no equivalent).

**Fix direction:** track "emitted" per included-prefix (e.g. keyed by the entry that last included a
node) and resolve natural terminals once that prefix's whole subtree is drained; then add BFS to the
oracle fuzz as a permanent gate (BFS is currently gated against nothing).

## Finding 3 — contract-level information loss (design decisions)

- **Parallel edges collapse.** a→b `owns` + a→b `likes` (+ b→a under BOTH) → one path, `owns` only.
  Covered by README per-parent dedup, but routes differing only by edge type are gone.
- **Excluded-gap `Path` misaligns.** a→x(EXCLUDE_AND_CONTINUE)→b→c → `nodes=[a,b,c]`, `edges=[b-c]`;
  `Path.toEitherList()` zips `nodes[i]`/`edges[i]` → `a [b-c] b c` (b→c rendered between a and b). The
  gap position is unrecoverable by the caller. Options: nullable edge slots (`edges.size == nodes.size-1`
  always), or document `toEitherList()` as invalid when a node was excluded.

## Suggested order

1. Finding 1 (data correctness, affects all reads).
2. Finding 2 + promote the probe's oracle fuzz to a DFS+BFS gate.
3. Finding 3 — decide the contract.
