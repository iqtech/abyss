# Batched upsert fails when one chunk saves the same id twice

Created: 2026-10-28

## Symptom

`batchTransaction` fails the whole chunk with `ERROR: ON CONFLICT DO UPDATE command cannot affect row
a second time` (`BatchPartiallyCommitted(committedOps=0)`) when two saves of the same node land in
the same rewritten block. The op-by-op path (`transaction()` / `commitYsql`) handles the same ops
fine: the last write wins and tags are unioned.

## Mechanism

- `commitYsqlBatched` groups consecutive ops of the same class into one `executeBatch()`.
- Production sets `reWriteBatchedInserts=true`, so pgjdbc turns that batch into multi-row
  `INSERT … VALUES (…), (…) ON CONFLICT DO UPDATE` statements, sent in blocks sized in powers of two.
- PostgreSQL/YSQL rejects a multi-row `ON CONFLICT DO UPDATE` that touches one row twice.
- Whether it fails depends on where the duplicate lands: `[A,B,A]` splits as `(A,B)+(A)` and passes;
  `[A,A]` and `[A,B,C,A]` share a block and fail. It can pass in staging and fail on a real ingest.
- Reachable from the graph API: `AbyssSchemaWorker.batchTransaction` passes ops through without
  deduping (verified by reading), so an add plus a modify of one node in one batch hits it.
- Edge saves have the same upsert shape keyed on `(from_id, to_id, type)`; expected to fail the same
  way (verified by reading, not executed).

## Evidence

`BatchDuplicateIdTest` (normal suite, live YB container), through the real
`YugabytePersistentStore.batchTransaction`, 2026-10-28:

| order | result |
|---|---|
| `[A,B,A]` | pass (duplicate split across blocks) |
| `[A,A]` | fail, ON CONFLICT error |
| `[A,B,C,A]` | fail, ON CONFLICT error |

## Fix

In `commitYsqlBatched`, cut the `executeBatch()` group when an op's key is already in the current
group: execute the group as it stands, start a new one with that op. Op order is preserved.

```
chunk:  SaveNode A, B, C, A, D, B, A
today:  [A B C A D B A]          one executeBatch, fails when two A's share a block
fixed:  [A B C] [A D B] [A]      three executeBatch calls
```

- Key per op: `SaveNode` → `id`; `SaveEdge` → `(from_id, to_id, type)`; deletes → none (pgjdbc
  rewrites only `INSERT`; a repeated `DELETE` is harmless). `PersistentOp` has exactly these four
  variants (`bind`'s exhaustive `when`). Written as "key of the op, null for deletes" so a future
  insert op only declares its key.
- Cut regardless of the driver's block layout: that layout is a driver internal, not a contract.
- The chunk boundary is untouched: still one commit per chunk, same atomicity, same `committedOps`.
- Cost: one extra round trip per cut, none for a chunk without duplicates. Worst case `[A A A …]` is
  one row per round trip, the same as the unrewritten path.

Rejected:
- Collapse duplicates (keep last): has to re-implement the tag union, and drops versions once
  `@WithHistory` exists (each save is a version).
- `reWriteBatchedInserts=false`: 6× slower batched writes (`HistoryBatchRewriteTest`: 1394 vs
  8442 ms at 10007 ops).

## Tests

- Red first: `[A,A]` and `[A,B,C,A]` in `BatchDuplicateIdTest` fail on the current code by assertion.
- Add an edge case: the same `(from, to, type)` saved twice in one block.
- After the fix: all green, full suite, then remove the cut and confirm the duplicate cases go red.

## Independent of nodes-history

The bug exists today without history. The fix keys on op identity, not on which SQL runs. The
history work touches the same loop for a different reason (grouping by op class vs. bound
statement), recorded in `ai/flow/2. Discuss/nodes-history/idea.md`.
