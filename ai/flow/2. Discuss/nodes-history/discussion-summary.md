History now costs almost nothing in both shapes that matter: the single-writer ingest path and the batched path. It only gets expensive when several writers hit one id. Every number below comes from running the tests on the single dev YB container, under true read committed, between 2026-10-27 and 2026-10-28. None of it comes from a real cluster.

## What we measured

**1. Single-op path (one event per transaction).** Here the history write is the per-row statement: one `WITH … RETURNING` that upserts the node and inserts its history row.

| workload | plain | history | chain |
|---|---|---|---|
| 1 writer, 1 entity | 1954 ms | **+2.3%** (second run: +3.6%) | +233% |
| 16 writers, own id each | 852 ms | **+19–24%** (noisy) | +337% |

The graph-native chain (event nodes linked by `has-next` edges) costs 3.3–4.4× plain, before any Hazelcast cost. It's out.

**2. Batched path (`batchTransaction`, chunks of 1000).** The per-row statement can't use the driver's insert rewrite (it starts with `WITH` and has `RETURNING`), so it ran 6.6× plain. The multi-row version, the same statement fed by `unnest()` of six arrays, fixes that:

| 10007 ops | time |
|---|---|
| plain upsert, rewritten (production) | 1375 ms |
| per-row history statement | 9198 ms |
| trigger | 12062 ms (rejected: slower everywhere) |
| **multi-row history statement** | **1138 ms**, 17% under plain |

**3. Many writers on one id.** History stayed exact in every run: no gaps, history rows = commits.

| writers | plain commits/s | history commits/s |
|---|---|---|
| 2 | 199–213 | 144–255 |
| 4 | 182–409 | 29–51 |
| 16 | 31–50 | 7–15 |

- History costs 4–25× plain from 4 writers up.
- Aborts happen with or without history, at 0.1–0.5% from 8 writers (40001 for plain, 40P01 for history), and nothing retries them.
- With one processor per id, history runs at full speed.

**4. Correctness fixes along the way:**
- Two saves of one id in one batch failed the whole chunk ("cannot affect row a second time"). This was a live production bug; fixed by cutting the group (`bcc43a4`).
- Tags in the multi-row statement must be built with the driver's quoting. Joining them by hand corrupts some tags silently (`8946235`).
- Test pools leaked connections: 216 at peak, now 23 (`5d7d1f4`).

## Decided by these numbers

- Single op: the per-row history statement.
- Batches: the multi-row history statement.
- The trigger form and the chain are rejected.
- History is opt-in through `@WithHistory`, and deleting a node deletes its history.
- Hot ids should stay off history until contention has its own fix.

## Next steps, in the order I'd take them

1. **Retry on 40001/40P01** (`0. Issues`). Small, specified, red-first test ready. It fixes a production problem today, with or without history, and history widens the abort window. Do it first.
2. **Two cheap probes before planning history:**
   - **Migration:** does `ADD COLUMN version BIGINT NOT NULL DEFAULT 1` rewrite a large `nodes` table on YB? Nobody has checked; a big rewrite would change the migration plan.
   - **Mixed batch:** time a 50/50 mix of `@WithHistory` and plain types in one batch. Alternating types breaks one batch into many small ones.
3. **Move nodes-history from Discuss to Plan**, then implement v1 with the red-first tests already listed in `idea.md`:
   - schema plus migration;
   - `@WithHistory`, resolved per class into `SaveNode.history`;
   - both commit paths changed in one commit;
   - delete cascade;
   - batched path grouped by the bound statement, not the op class. Otherwise a mixed group silently loses rows.
4. **Separate idea, not part of history:** move the plain batched upsert (nodes and edges) to `unnest`. That's about −17% on bulk loads, inferred from the multi-row run; it needs its own measurement through the real store.
5. **Before calling it viable:** measure on a real multi-node cluster at about 16.7k events/s, with and without history. Until then, the single box doesn't show whether history is viable at that scale.

Parked, out of v1: a history read API, retention (no TTL in YSQL, so it would be scan-and-delete), dropping Kafka redeliveries, and the version check against lost updates (VersionGuardPlan phases 2–4).
