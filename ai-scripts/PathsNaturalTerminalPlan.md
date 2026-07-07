# Fix `paths()` — emit natural-terminal INCLUDE_AND_CONTINUE paths

## Context

`paths()` (graph traversal, Flow<Path>) currently emits a path only in two situations:

1. a node evaluated **`INCLUDE_AND_PRUNE`** (emit immediately), and
2. a node evaluated **`INCLUDE_AND_CONTINUE`** that lands exactly at the **depth cap** (`nextDepth >= maxDepth`).

The missing case is a **natural terminal**: a node evaluated `INCLUDE_AND_CONTINUE`, *below* `maxDepth`, whose expansion follows no further edge — edges exhausted, all neighbours already visited, `edgeVisitor` rejects them all, or every continuation is `EXCLUDE_AND_PRUNE`. The loop recurses/enqueues, that expansion emits nothing, and **the full terminal path is silently dropped**.

Concrete failure: chain `a → b → c`, every node `INCLUDE_AND_CONTINUE`, `maxDepth` large (e.g. default `MAX`). Today `paths()` emits **nothing**; it should emit `[a, b, c]`.

Fix: emit a path when an included node is a traversal leaf, *without* emitting a path for every intermediate prefix (that would break terminal-path semantics). The clean shape: **have `dfsLoop` report whether its subtree emitted anything; if an `INCLUDE_AND_CONTINUE` node's expansion emitted nothing, emit its own path.** Mirror the same idea in `bfsLoop` with a per-entry "produced a continuation" flag.

Both loops live in `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/traversal/TraversalBuilder.kt` (`dfsLoop` ~226-263, `bfsLoop` ~265-298). `Evaluation` and the `paths()` contract are in `abyss-dsl/src/main/kotlin/pl/iqtech/abyss/dsl/TraversalBuilderLike.kt`.

## Emission semantics after the fix

A path is emitted exactly once, when its head (an **included**, non-origin node) is **maximal** — it cannot be extended because of one of:
- **prune** — head is `INCLUDE_AND_PRUNE`;
- **depth cap** — head is `INCLUDE_AND_CONTINUE` and `nextDepth >= maxDepth`;
- **natural terminal** — head is `INCLUDE_AND_CONTINUE`, below the cap, but its expansion follows no further edge / all continuations dead-end.

Excluded nodes never emit their own path (they don't advance the head); a lone origin (`path.nodes.size == 1`) is never emitted.

## Changes

### 1. `dfsLoop` — return `Boolean` ("did this subtree emit anything?")

`TraversalBuilder.kt`, `dfsLoop`. Change the return type to `Boolean`, track a local `var emitted = false`, and restructure the per-hop tail (current lines 258-261) as a `when` on `eval`:

- `INCLUDE_AND_PRUNE` → `emit(extendedPath); emitted = true`
- `INCLUDE_AND_CONTINUE && nextDepth >= maxDepth` → `emit(extendedPath); emitted = true` (cap, unchanged)
- `INCLUDE_AND_CONTINUE` (below cap) → recurse; `if (!childEmitted) emit(extendedPath)` (natural terminal); `emitted = true`
- `EXCLUDE_AND_CONTINUE && nextDepth < maxDepth` → recurse; `if (child) emitted = true` (excluded nodes never emit their own path; propagate subtree emission upward)
- else (`EXCLUDE_AND_PRUNE`, or `EXCLUDE_AND_CONTINUE` at cap) → nothing

`if (depth >= maxDepth) return false` at entry; `return emitted` at the end. The origin call in `paths()` (line 216) ignores the return — no change needed there. `included`/`extendedPath`/`nextHeadNid`/`seen` logic stays byte-for-byte identical.

Why `emitted` propagates: the parent uses it to decide whether *it* is a terminal. If a child branch emitted anything, the parent's own path is a strict prefix and must **not** be emitted — that's what prevents per-prefix emission.

### 2. `bfsLoop` — per-entry "produced continuation" flag + cap-guarded enqueue

`TraversalBuilder.kt`, `bfsLoop`. Two coupled edits:

- **Guard the enqueue with `nextDepth < maxDepth`** (mirror DFS line 260). Currently BFS enqueues `INCLUDE_AND_CONTINUE`/`EXCLUDE_AND_CONTINUE` unconditionally and relies on the `depth >= maxDepth` dequeue-skip; that skip drops the terminal path for an `EXCLUDE_AND_CONTINUE` node sitting exactly at the cap. With the guard, cap handling happens at expansion time exactly like DFS.
- **Track `var produced = false`** per dequeued entry, set `true` whenever this entry's expansion emits (I&P or I&C-at-cap) **or** enqueues a child. After the edge loop, if `!produced && currentPath.nodes.size > 1`, `emit(currentPath)` — the natural terminal (its head is the last included node; `size > 1` excludes the lone origin and matches DFS behaviour for all-excluded branches).

The existing eager emits (I&P, I&C-at-cap, lines 292-293) stay. The `if (depth >= maxDepth) continue` line stays as a safety guard (with the enqueue guard it only ever fires for `maxDepth == 0`).

### 3. Document emission on the `paths()` contract

`TraversalBuilderLike.kt`, add a KDoc block on `paths()` (lines 57-63) stating emission fires on **prune, depth cap, and natural terminals**, and that intermediate prefixes are not emitted. Optionally extend the inline comment above `dfsLoop` to mention the `Boolean` return meaning.

### 4. Regression tests

`abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/PathsTraversalTest.kt`. Add, following the existing `putNode`/`putEdge`/`from(...)` pattern:

- **DFS natural terminal** — chain `a → b → c`, `nodeEvaluator = { _, _ -> INCLUDE_AND_CONTINUE }`, `maxDepth = 10` (> chain length). Assert exactly one path, `nodes == [a, b, c]`. (This test fails on current code — it emits nothing.)
- **BFS natural terminal** — same graph/evaluator with `TraversalStrategy.BFS`. Assert one path `[a, b, c]`.
- (Optional) **no per-prefix emission** — same graph, assert the single result is the full-length path, guarding against re-introducing intermediate-node emission.

Existing tests must stay green — verified by hand against the rewrite: `maxDepth limits path length` (cap emit `[a,b]`), `EXCLUDE_AND_CONTINUE skips…` (`[a,c]`), `EXCLUDE_AND_PRUNE stops…` (empty), single-hop/fork/cyclic/IN/OUT/`toEitherList` (all `INCLUDE_AND_PRUNE`, emit-immediately path unchanged), and `loop BFS emits shorter paths before longer ones` (`maxDepth = MAX`, enqueue guard always true; order preserved).

## Verification

From `/home/cane/work/abyss`:

```
./gradlew :abyss-graph:test --tests 'pl.iqtech.abyss.graph.PathsTraversalTest'
```

Expect the two/three new tests to pass and all 12 existing ones to stay green. The DFS/BFS natural-terminal tests are the direct regression proof (both fail before the change). Also run the broader traversal suites (`TraversalTest`, `UniverseTraversalTest`) to confirm no collateral change:

```
./gradlew :abyss-graph:test
```
