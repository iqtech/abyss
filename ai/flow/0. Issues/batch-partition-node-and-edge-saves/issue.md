# Batched saves alternating nodes and edges run 15.5x slower

Created: 2026-11-02

## Symptom

A `batchTransaction` shaped as one node save plus one edge save per event, the natural ingest shape, runs
at 498 ops/s. The same pairs with each chunk's node saves ahead of its edge saves run at 7712 ops/s.

`EdgeHistoryProbeTest` "alternating node and edge saves in a batch", real store, `-Pperf`, dev YB, 5003
pairs, chunks of 1000, median of 5 interleaved rounds after a warm-up:

| order | median | ops/s |
|---|---|---|
| alternating, as the caller staged them | 20109 ms | 498 |
| each chunk's nodes, then its edges | 1298 ms | 7712 |

15.5x. This is in production today, plain types included. It predates history.

## Mechanism

- `commitYsqlBatched` runs one `executeBatch()` per group of consecutive ops on the same statement.
- Alternating `SaveNode`, `SaveEdge` makes every group one op, so the batch rewrite never applies and each
  op costs a round trip.
- nodes-history added `statementOrder`, which stably partitions runs of saves by statement, but only runs
  of `SaveNode`, to fix the same trap between history and plain node saves (8.2x, `idea.md`). The plan
  said "runs of saves". It was narrowed to node saves while building, with the note that an edge between
  node saves "costs one more group". On this shape that is every op, not one group.

## Fix

Widen the run in `statementOrder` to every save, node and edge, and partition it by statement, first seen
first. Grouping by `statementOf` does it in one line and also covers a third statement, so edge history
needs nothing more here.

```
chunk:  S(n1) E(e1) S(n2) E(e2) S(n3) E(e3)
today:  [n1] [e1] [n2] [e2] [n3] [e3]     six round trips
fixed:  [n1 n2 n3] [e1 e2 e3]             two
```

Why reordering is safe (by reading):

- Per-row order holds: a node row and an edge row are never the same row, and each row always binds one
  statement. Node type is immutable, and an edge's type is part of its key.
- The tables have no FKs or triggers, and a commit issues no reads, the reasoning `commitYsql`'s
  `LOCK_ORDER` sort already relies on.
- Ops stay inside their chunk, so the worker's `ops.take(committed)` replay stays exact.
- Deletes stay in place as run boundaries.

Known limit: a chunk alternating saves and deletes still gets one group per op. That one can't be
partitioned without proving per-row order across the delete, and it's out of scope here.

## Tests

- Red first: no test fails on this today, because the bug is cost, not correctness. The red is the probe's
  ratio, so a test counting `executeBatch` calls or groups through a counting `DataSource` is the guard
  to add: alternating pairs in one chunk must run 2 groups, not one per op.
- Correctness: mixed node, history and edge saves with a delete in the middle keep per-row order, in the
  style of `NodeHistoryTest` #5, and edges land on the right statement.
- Before and after through the real store: alternating and grouped, plus plain-only and history-only, to
  show nothing else moved. Grouped should not get slower.
