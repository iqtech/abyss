# YSQL commits don't retry serialization and deadlock aborts

Created: 2026-10-28

## Symptom

Concurrent writers on one id occasionally get their transaction aborted by YB under read committed:
SQLState 40001 (`could not serialize access due to concurrent update`) or 40P01 (`deadlock detected
… READ COMMITTED transaction was aborted to break a deadlock cycle`). Neither commit path retries, so
the abort reaches the caller as a failed write (`Left`), although retrying the same ops would succeed.

## Evidence

`HistoryVsChainWriteCostTest` "one id under 2-4-8-16 writers" (`-Pperf`, dev YB, raw JDBC, one
transaction per event, 21 events per writer, 4 runs, 2026-10-28):

| writers on one id | aborts, plain upsert | aborts, history CTE |
|---|---|---|
| 2 | 0 of 168 | 0 of 168 |
| 4 | 0 of 336 | 0 of 336 |
| 8 | 1 of 672 (40001) | 1 of 672 (40P01) |
| 16 | 3 of 1344 (40001) | 6 of 1344 (40P01) |

- It happens today, without history: the plain production upsert aborts with 40001.
- Aborts leave nothing behind: the whole transaction rolls back (history rows = commits, gap-free).
- Processor-per-id (one writer per id) never hit it. The exposure is paths that write one id
  concurrently: a REST `modifyNode` during event processing, a `batchTransaction` load, a Kafka
  rebalance overlap, or parallel event processing per id.

Verified by reading: no 40001/40P01 handling or retry anywhere in `abyss-store-yugabyte` or
`abyss-graph` main code.

## Fix

Retry inside the store, which owns the transaction and has the op list in memory:

- `commitYsql`: on SQLState 40001 or 40P01, roll back and re-run the whole transaction, up to N
  attempts with jittered backoff; then surface the error as today.
- `commitYsqlBatched`: same per chunk (the chunk is the transaction). Chunks already committed stay
  committed, and `committedOps` stays exact.
- Safe to re-run: the transaction rolled back as a whole, the commit issues no reads, and the ops are
  upserts and unconditional deletes. Tag union and `version = n.version + 1` are applied once per
  committed attempt, so a retry doesn't double-count.
- Retry only those two SQLStates. Everything else (connection loss, constraint errors) fails as today.
- N and the backoff: start small (e.g. 3 attempts, 10–50 ms jitter); measure before tuning.

The worker needs no change: cache population runs after the store commit returns `Right`.

## Comment to correct

`YugabytePersistentStore.kt:291` says "Single-op transactions can't deadlock, so they skip the sort
entirely — that is the whole per-event ingest path." True for the plain upsert (it aborts with 40001,
not 40P01), but the history CTE got 40P01 in single-statement transactions. The lock-order sort still
isn't the fix there (one statement, nothing to reorder); the retry is.

## Tests

- Red first, deterministic: wrap the DataSource so the first `commit()` throws an `SQLException` with
  SQLState 40001 (the `FailAfterNCommitsDataSource` pattern in `LoadTest`). Assert `transaction` and
  `batchTransaction` return `Right` and the data landed once. Same for 40P01. Red on today's code.
- Another SQLState (e.g. 08006) must still fail without retry.
- Attempts exhausted: always-40001 surfaces `Left` after N attempts, not an endless loop.
- Stochastic check: through the real store, 16 coroutines on one id, assert all `Right` and the final
  state. Not a red-first guard (aborts are 0.1–0.5%), only confirmation.
- Remove the retry and confirm the deterministic tests go red.

## Related

Independent of nodes-history, but history makes contended commits slower (4–25× from 4 writers on one
id), which widens the abort window. Recorded in `ai/flow/2. Discuss/nodes-history/idea.md`.
