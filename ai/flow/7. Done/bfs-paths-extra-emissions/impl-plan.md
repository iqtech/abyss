# Impl plan: BFS `paths()` extra emissions

Issue: `issue.md` (this folder). TODO 1.35 item (2). Date: 2026-11-19, on 0.37.2 (`8b86a59`).

## Decisions in force

- **Fix:** a `Head` per included prefix with `pending` and `emitted` (issue.md, "Proposed fix"). The
  per-entry `produced` flag goes away.
- **Oracle:** `specPaths` in `PathsSilentDropAuditTest`, written from the KDoc and the README. It is
  independent of both loops. Its `walk(...).ifEmpty { listOf(p2) }` for include-and-continue is exactly
  `Head.emitted == false`, and exclude-and-continue returns only its subtree, so the oracle needs no change.
- **BFS order:** the fuzz compares BFS as multisets, as today. A natural terminal moves later only when its
  prefix has several excluded branches. [read] No existing BFS test uses exclude-and-continue (`PathsTraversalTest`
  uses it only with DFS, at `:98` and `:271`).
- **Out of scope:**
  - TODO 1.35 item (3): parallel-edge collapse and the misaligned `Path` when a node is excluded. Part 1b keeps
    printing those.
  - BFS cost: 3.00 ops/link and the quadratic `visited` copies.
  - Part 2 of the probe (F1, gated by `StoreReadFailureTest` under 1.38).

## Files

| File | Change |
|---|---|
| `abyss-graph/src/test/.../PathsSilentDropAuditTest.kt` | Part 1 asserts; part 1b asserts the two BFS repros; header comment says part 1 is a gate |
| `abyss-graph/src/main/.../traversal/TraversalBuilder.kt` | `bfsLoop`: `Head`, `Entry` without `path`, `depth < maxDepth` wrapper, emission at region drain |
| `ai-scripts/paths-problem-ISSUE.md` | Status line: F1 fixed (1.38), F2 fixed (this issue) |
| `TODO.md` | 1.35 narrowed to item (3) |

## Steps

1. **Red: the gate.** In `PathsSilentDropAuditTest`:
   - Part 1, after the prints:
     - `assertEquals(0, drops[s] ?: 0)` and `assertEquals(0, extras[s] ?: 0)` for each strategy, with the
       first example as the message;
     - `assertEquals(0, dfsOrderDiff)`.
   - Part 1b, the BFS-min loop: assert the BFS result equals the expected list:
     - "with c" expects `["a>b>c|a-b,b-c"]`;
     - "x1+x2, no c" expects `["a>b|a-b"]`.
     - DFS is asserted to the same lists, so the two repros pin both strategies.
   - Header comment: part 1 and the BFS repros are a gate; part 1b's collapse/gap lines and part 2 still
     only print.
   - Run it. Expected red: part 1 on BFS extras = 481, part 1b on both repros. DFS assertions pass.
2. **Fix `bfsLoop`** as in issue.md:
   - add `Head(path)` with `pending = 1` and `emitted = false`;
   - `Entry(head, headNid, fromNid, visited, depth)`, with `currentPath = head.path`;
   - prune or cap emit sets `head.emitted`;
   - an include-and-continue enqueue makes `Head(extendedPath)` and sets the parent's `head.emitted`;
   - an exclude-and-continue enqueue reuses `head` with `pending++`;
   - after expansion, `--pending == 0 && !emitted && size > 1` emits `head.path`;
   - `depth < maxDepth` wraps the expansion, replacing the `continue` guard.
   - Replace the comment about `produced` with the "DFS `emitted` done as a counter" note.
   - Run the gate: green.
3. **Mutation checks**, each run separately and reverted:
   - A: drop `it.pending++`. Expect red, because a dead-end region drains early.
   - B: drop `head.emitted = true` on the included enqueue. Expect red, from the shorter prefixes.
   - C: drop `head.emitted = true` on the prune/cap emit. Expect red: repro 1 emits `a>b`.
   - D: emit per entry again (`if (!emitted…)` with no `pending` check). Expect red, from the duplicates.
4. **Full suite** (`./gradlew build`), plus the `-Pperf` BFS scenarios in `NodeBatchLoadPerformanceTest` to
   confirm round trips are unchanged (the 4.12 window is untouched).
5. **Records:**
   - `paths-problem-ISSUE.md`: change the status line.
   - TODO 1.35: drop item (2), leaving a "fixed, see issue" pointer.
   - The issue: record the results.

## Tests

| # | Test | Red on today's code | Guards |
|---|---|---|---|
| 1 | part 1, BFS 0 drops / 0 extras over 36,000 cases | yes, 481 | mutations A, B, C, D |
| 2 | part 1, DFS 0 drops / 0 extras / 0 order differences | no (stays green) | DFS untouched |
| 3 | part 1b repro "with c" → `[a>b>c]` for both strategies | yes (BFS) | B, C |
| 4 | part 1b repro "x1+x2, no c" → `[a>b]` once for both strategies | yes (BFS) | A, D |

## Risks

- **Fuzz runtime:** part 1 already runs in the normal suite (not `-Pperf`), so the gate adds only the asserts.
- **Multi-origin:** each origin owns its `Head` (the fuzz hits it on `seed % 3 == 0`). Origins never share
  a region, because origin heads have `size == 1` and never emit.
- **Cancellation (`.take(n)`):** an unresolved head never emits. That is correct: the consumer stopped first.
- **`maxDepth = 0`:** the origin isn't expanded; `pending` drops 1 → 0 and `size == 1`, so nothing is emitted,
  as today and as the oracle expects.

## Results (2026-11-19) [measured]

- **Red:** part 1 failed on `BFS extras` (first example seed 14, BOTH, maxDepth 3, extra `n0>n5`); part 1b
  failed on `with c BFS` (`[a>b>c, a>b]`). The DFS assertions run first and passed.
- **Green:** part 1 gives 0/0 for both strategies, DFS order differences 0. Both repros give `[a>b>c]` / `[a>b]`
  for both strategies.
- **Deviation from step 2:** the `depth >= maxDepth` `continue` guard was kept instead of wrapping the expansion
  (smaller diff). Only an origin reaches it, and an origin's `Head` never emits, so the skipped decrement
  is harmless. Commented in place.
- **Mutations**, each reverted:

  | | mutation | part 1 (fuzz) | part 1b |
  |---|---|---|---|
  | A | no `pending++` | red | green |
  | B | no `emitted` on included enqueue | red | green |
  | C | no `emitted` on prune/cap emit | red | red |
  | D | `pending >= 0` (emit per entry) | red | red |

  **Correction to the tests table:** the repros do not guard A or B. Neither repro enqueues an included
  child, so B is invisible to them. Under A, `b`'s own expansion drains the region first and emits `a>b`
  once by accident. Only the fuzz guards A and B. Part 1b stops at its first failing case, so for C and D
  it is not shown which repro caught them.
- **Full suite:** `./gradlew build`, 521 tests, 0 failures, 0 errors.
- **`-Pperf` round trips unchanged:**
  - `paths BFS over a single supernode`: 5, the same as after 4.12.
  - 10k chain BFS and DFS: 20,000 each.
  - BFS chain wall time ~4.8 s: the quadratic `visited` copies, out of scope.
