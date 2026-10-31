# Plan: retry YSQL commits on 40001 / 40P01

Source issue: `ai/flow/0. Issues/ysql-commit-retry-on-conflict/issue.md` (filed 2026-10-28).

## Context

Several writers on one id sometimes get their transaction aborted by YB under read committed:
- 40001 (serialization failure) on the plain upsert;
- 40P01 (deadlock) on the history CTE.

The rate measured on 2026-10-28 was 0.1–0.5% from 8 writers up. Neither commit path retries, so the
caller gets a `Left` even though running the same ops again would succeed. This happens in production
today, without history. History is slower under contention, which widens the window, so this ships
before nodes-history v1. Outcome: transient aborts are invisible to callers; any other error fails
exactly as it does today.

## Design

All changes are in `abyss-store-yugabyte/.../YugabytePersistentStore.kt`. The worker doesn't change:
`populateCache` already runs only after the store returns `Right`, and `committedOps` stays exact.

1. **Classifier:** `isTransientAbort(e: Throwable)` walks `cause` and `SQLException.nextException`,
   and matches SQLState `40001` or `40P01`. The chain walk matters because a batched failure arrives as
   a `BatchUpdateException`, and the real state can sit in `nextException`. Verify how pgjdbc/YB
   actually wraps it before relying on that.
2. **One retry helper**, used by both paths:
   `private suspend inline fun <T> retryTransientAbort(attempt: () -> T): T`.
   - Up to `MAX_ATTEMPTS = 3`, with `delay(Random.nextLong(10, 50))` between attempts.
   - Rethrows the last error unchanged once attempts run out, so the error mapping stays as it is
     (`Unexpected`, or `BatchPartiallyCommitted`).
   - `log.debug` on each retry. There's no metrics hook yet; the AbyssMetrics callback (TODO 2.35) can
     count retries later.
   - The constants are private. `ponytail:` comment: starting values from the issue, measure before
     tuning.
3. **`commitYsql`** becomes `suspend`, which is safe because `use` is inline and the caller is already
   in `withContext(IO)`. The existing `try { prepare… execute… commit } catch { rollback; throw }`
   moves inside `retryTransientAbort`, on the same connection. Every attempt starts from a rolled-back
   transaction. The commit issues no reads, and the ops are upserts and unconditional deletes, so
   re-running them is exact. Tag union and the future `version + 1` apply once per committed attempt.
4. **`commitYsqlBatched`** becomes `suspend`. Retry is per chunk, because the chunk is the
   transaction:
   - Wrap the body of each chunk (grouping loop plus `commit`) in `retryTransientAbort`.
   - On a transient abort: roll back, `clearBatch()` the four prepared statements so nothing from the
     failed attempt is queued twice, then retry from the start of the chunk.
   - Already committed chunks are never re-run, and `committed += chunk.size` happens only after a
     successful commit.
   - Once attempts run out, the code still throws `PartialBatchFailure(committed, e)` as today.
5. **Comment fix** at `:291`. The claim that single-op transactions can't deadlock holds for the plain
   upsert (it gets 40001, not 40P01), but the history CTE hit 40P01 in single-statement transactions.
   Point that comment at the retry.

The connection stays held during the 10–50 ms backoff. That's acceptable at a 0.1–0.5% abort rate,
and releasing it would need a re-acquire path.

## Tests (red first): new `CommitRetryTest.kt`, live container

Reuse `FailAfterNCommitsDataSource` (`LoadTest.kt:677`):
- change it from `private` to `internal`;
- add `sqlState: String? = null` and `failCount: Int = 1`, so it fails commits
  `failAtCommit .. failAtCommit+failCount-1`;
- add a `commits` counter that tests can read.

Its existing callers keep the defaults. The store is built with the existing public constructor,
`YugabytePersistentStore(ds, module, schema)`.

| # | case | expect | red today? |
|---|---|---|---|
| 1 | `transaction`, commit #1 throws 40001 | `Right`, node stored, 2 commits | yes |
| 2 | same, 40P01 | same | yes |
| 3 | `batchTransaction`, batch 4 over 7 ops, chunk 2's commit throws 40001 once | `Right`, all 7 stored, chunk 1 committed once (3 commits in total) | yes (`BatchPartiallyCommitted(4)` today) |
| 4 | commit throws `BatchUpdateException` (state null) with `nextException` 40001 | `Right` | yes |
| 5 | commit #1 throws 08006 | `Left Unexpected`, exactly 1 commit | no (regression guard) |
| 6 | `transaction`, always 40001 | `Left` after exactly 3 commits | no (bounded) |
| 7 | batched, chunk 2 always 40001 | `BatchPartiallyCommitted(committedOps = 4)`, 1 + 3 commits | no |
| 8 | stochastic: real store, 16 coroutines on one id, both paths | all `Right`, final state correct | confirmation only, not a guard |

Inputs: odd op counts, and a batch size that doesn't divide the op count.

**Guard check:** temporarily make `retryTransientAbort` call `attempt()` once. Tests 1–4 must go red,
then restore.

**Probe before writing #4:** confirm on the live container how a real 40001 from `executeBatch`
arrives: its SQLState, `nextException` and wrapping. The classifier and #4 must match what the driver
does, not what I assume.

## Verification

1. `./gradlew :abyss-store-yugabyte:test --tests "*CommitRetryTest"`: red on today's code, with the
   failures written down.
2. Implement it, run the same tests green, then do the guard check.
3. Full `:abyss-store-yugabyte:test` and `:abyss-graph:test`. Known red: `NodeVersionTest`, from
   `1b677c2`, until history v1 lands. Everything else must stay green.
4. Re-run `HistoryVsChainWriteCostTest` "one id under 2-4-8-16 writers" (`-Pperf`) and report aborts
   before and after (raw JDBC there, so it measures YB, not our retry). Then run #8 at 16 writers.

## Records

- Move the item through the flow pipeline with `/cane:flow next` and save this plan beside
  `issue.md` as `plan.md`.
- Add a changelog entry in the item dir, as `batch-duplicate-id-upsert` has.
- Write a commit message with the mechanism and the numbers, then commit on `dev`. Push only when
  asked.

## Changes from the plan, found while implementing (2026-10-29)

- **No cause-chain walk.** Verified on the live container: a real abort out of a rewritten `executeBatch`
  arrives as `BatchUpdateException` with `sqlState=40P01` on the exception itself (`nextException` and
  `cause` repeat it). Hikari and `withContext` don't wrap it, so the classifier is
  `(e as? SQLException)?.sqlState in {40001, 40P01}`. The probe became the test
  `driver - deadlock out of executeBatch carries 40P01 on the BatchUpdateException`, which guards that
  assumption.
- **Test #4 uses the real shape:** a `BatchUpdateException` carrying 40P01 at the top level, not a null
  state with `nextException`.
- **Stochastic #8 runs only with `-Pperf`.** It takes ~62 s (672 contended commits, ~11/s). The first
  version gave every save a unique tag and took 206 s: the growing `tags` array is re-unioned and its GIN
  entries rewritten on every update.
