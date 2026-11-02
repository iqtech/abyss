# modifyEdge deletes and re-inserts when the key doesn't change

Created: 2026-11-02

## Symptom

`modifyEdge` always emits `RemoveEdge` + `AddEdge`, in both buffers (`AbyssGraphSchema.kt:352`, `:375`).
That is only needed when the transform moves the key. It was built for retargeting (TODO 1.4, "A→B
becomes A→C"), where `(from_id, to_id, type)` really does change, and has had this shape since the initial
commit. Editing a value on the same key took the same path because nothing compares the keys.

## What the delete costs on a same-key modify

- **Store:** 2965 ms against 2037 ms, 1001 events on one edge, one transaction each. The upsert is **31%
  faster** (`EdgeHistoryProbeTest` "modifyEdge shape", `-Pperf`, dev YB, median of 5, tag held stable).
- **`version` and history:** the delete resets the row, so edges can't carry a version and any edge history
  would hold one row. This is VersionGuard Q3, and it blocks both edge history and the edge guard.
- **Cache (by reading):** `AddEdge` already overwrites `edgesMap`, and the adjacency add goes into a set
  with the same entry. Remove then add does the work twice, on the same entry, through `populateCache`.
- **Other layer (by reading):** the removal fans out (`AbyssSchemaWorker.kt:478`, `:536`). That is how a
  persistent `modifyEdge` deletes the ephemeral copy, and an `ephemeral` one deletes the persistent edge:
  `ai/flow/0. Issues/modifynode-crosses-layers-unguarded`.

## Fix

Compute the new edge, then compare its `(from, to, type)` with the key the caller passed:

- Unchanged: emit `AddEdge` only.
- Moved: `RemoveEdge` + `AddEdge`, as today.
- A transform returning another class counts as moved, because its type differs.
- `transform(null)`, creating the edge: emit `AddEdge` only, there's nothing to remove.

## Behaviour changes

- **Tags union instead of replace** (executed: delete+insert ends with 1 tag, upsert with 2). This matches
  `modifyNode`. **Open:** cane's call, the fix ships only if accepted.
- **`created_at` keeps its first value** instead of taking the new edge's (by reading the upsert).
- **The other layer's copy is left alone** on a same-key modify. The delete half of the gap 2 edge cases
  goes away; the promotion to disk stays.

## Tests

- Red first: a same-key `modifyEdge` through the real store must not issue a delete, and must keep the
  row's tags as a union. Add one that fails on today's shape by assertion.
- Retargets keep working: `GraphTest` "modifyEdge retargets toId" and "retargets fromId", and the old key
  is gone. `transform` returning another edge class still removes the old key.
- Guard: force the moved branch for a same-key modify, and the new test must go red.
- `GraphTest` "replacement AddEdge carries the given tags" asserts through `FakeStore`, so it stays green
  under either shape and doesn't guard this.
- Before and after through the real store: same-key modify, and a retarget, which must not get slower.
