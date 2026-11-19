# BFS `paths()` emits shorter, incomplete paths and duplicates

Created: 2026-11-19

From TODO 1.35 item (2). Origin: `ai-scripts/paths-problem-ISSUE.md` Finding 2 (audit 2026-09-24, 0.35.0).
The fable 5.2 audit (2026-11-14) excluded `paths {}` as already known and found the iterative DFS clean.

## Defect

The contract (KDoc `TraversalBuilderLike.paths` and the README's "Node uniqueness" section) says intermediate
prefixes are never emitted. BFS violates it. Nothing is lost: the extras are shorter incomplete paths and
duplicates. DFS is exact.

## Evidence (re-run 2026-11-19 on 0.37.2, `PathsSilentDropAuditTest`, prints only)

- Part 1, oracle fuzz: 36,000 cases, 26,168 oracle paths.
  - DFS: 0 drops, 0 extras, 0 order differences.
  - **BFS: 0 drops, 481 cases with extras.** Same count as on 0.35.0.
- Part 1b, minimal repros (fake engine, OUT, IC = include-and-continue, EC = exclude-and-continue,
  IP = include-and-prune):
  - a→b (IC); b→x1 (EC, dead end); b→c (IP). DFS gives `[a>b>c]`. BFS gives `[a>b>c, a>b]`, with `a>b` an
    incomplete prefix.
  - a→b (IC); b→x1 and b→x2 (both EC, dead ends). DFS gives `[a>b]`. BFS gives `[a>b, a>b]`, a duplicate.
- Example from the fuzz: seed 14, BOTH, maxDepth 3. The extra path is `n0>n5`, although n5's subtree
  emitted `n0>n5>n6` and `n0>n5>n1>n6`.

## Cause [read]

`TraversalBuilder.bfsLoop`, natural-terminal line `if (!produced && currentPath.nodes.size > 1) emit(currentPath)`.
- An EC entry carries its parent's path.
- When it dead-ends, it emits that path on its own. It doesn't know whether a sibling branch already emitted
  a longer path.
- Two dead ends emit the same path twice.
- DFS gets this right with the per-frame `emitted` flag, passed up through excluded frames. BFS has nothing
  like it.

Not covered by any test: the oracle fuzz only prints, and `IterativeDfsPrototypeTest` covers DFS only.

## Proposed fix (not implemented)

There is one `Head` per included prefix. It is shared by:
- the entry that included the node;
- every EC entry below it, all of which already carry its path.

Its fields:
- `pending`: the region's entries not yet expanded.
- `emitted`: becomes true once any included node is produced anywhere in the region. An included node
  always leads to at least one emission: on prune, at the depth cap, or as its own natural terminal.

The natural terminal is emitted once: when the region's last entry has been expanded and `emitted` is still
false. This is DFS's flag done as a counter, because BFS interleaves the region with other entries in the queue.

```kotlin
class Head(val path: Path) { var pending = 1; var emitted = false }
data class Entry(val head: Head, val headNid: NodeId, val fromNid: NodeId, val visited: Set<NodeId>, val depth: Int)

for ((nid, node) in origin) queue += Entry(Head(Path(listOf(node), emptyList())), nid, nid, setOf(nid), 0)
while (queue.isNotEmpty()) {
    val (head, headNid, fromNid, visited, depth) = queue.removeFirst()
    val currentPath = head.path
    if (depth < maxDepth) {
        // seen / window / edgesFrom as today; in flushWindow:
        if (eval == INCLUDE_AND_PRUNE || (eval == INCLUDE_AND_CONTINUE && nextDepth >= maxDepth)) {
            emit(extendedPath); head.emitted = true
        }
        if (nextDepth < maxDepth && (eval == INCLUDE_AND_CONTINUE || eval == EXCLUDE_AND_CONTINUE)) {
            val childHead = if (included) Head(extendedPath).also { head.emitted = true } else head.also { it.pending++ }
            queue += Entry(childHead, nextHeadNid, nextNid, visited + nextNid, nextDepth)
        }
    }
    if (--head.pending == 0 && !head.emitted && currentPath.nodes.size > 1) emit(currentPath)
}
```

What the change removes:
- `Entry.path`, since it is always `head.path`.
- The `produced` flag.
- The `continue` guard at the top of the loop. The `depth < maxDepth` wrapper replaces it, and the origin
  still resolves its own `Head`.

Traced by hand:
- Repro 1 gives `[a>b>c]`: c sets `emitted`.
- Repro 2 emits `a>b` once: `pending` reaches 0 only after the second dead end.

Side effects:
- Order: a head with several excluded branches now emits its natural terminal after the last branch is
  drained, not after the first. The fuzz compares BFS as multisets.
- Cost: one small `Head` object per included entry. `Entry` loses its `Path`. The 4.12 batching and hop
  order are unchanged.

## Steps (red first)

1. Red: make `PathsSilentDropAuditTest` assert.
   - Part 1: 0 drops and 0 extras for both strategies, plus the DFS order check.
   - Part 1b: the two BFS repros.
   - Expected: red on BFS (481 cases), green on DFS. The file becomes the permanent gate for both strategies.
2. Fix. Green, then the full suite.
3. Mutation checks. Each must go red:
   - remove `pending++`;
   - remove `head.emitted = true` on an included enqueue;
   - restore the per-entry emission.
4. Records:
   - In `paths-problem-ISSUE.md`, change the status line: F1 fixed by 1.38, F2 fixed here.
   - Narrow TODO 1.35 to item (3).

## Out of scope

- Item (3) of TODO 1.35, contract decisions: parallel-edge collapse and the misaligned `Path` when a node is
  excluded.
- BFS cost, to be filed separately:
  - 3.00 map ops/link, because every edge value is read (17 per link at 16 unrelated edges);
  - about 4.6 s CPU on a 10k chain, because `visited` is copied twice per entry, which is O(n²);
  - per-entry `Path`/`visited` held in the queue (unmeasured).
