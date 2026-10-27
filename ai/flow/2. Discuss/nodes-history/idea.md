# Node history via the per-node version counter

Created: 2026-10-27

Keep every committed state of a node in `abyss.node_history`, written in the same statement as the
node upsert. Ordering comes from a per-node `version` counter on `abyss.nodes`, so it needs no Kafka
and covers every write path (`modifyNode`, cascades, REST, `batchTransaction`).

This is option "N" from `ai-scripts/NodeHistoryRFC.md` §4.2, promoted from fallback to base design.
It's the same column as Phase 1 of `ai-scripts/VersionGuardPlan.md`, so this work is also the first
step of the lost-update fix. The Kafka `(ts, offset)` key from RFC §2 becomes an optional later
addition, used for dropping redeliveries and for time-based retention.

## Target statement

Replaces the node upsert in `commitYsql` and `commitYsqlBatched`
(`YugabytePersistentStore.kt:309`, `:369`):

```sql
WITH u AS (
    INSERT INTO abyss.nodes AS n (id, type, data, tags, created_at, updated_at)
    VALUES (?, ?, ?, ?, ?, ?)
    ON CONFLICT (id) DO UPDATE SET type = EXCLUDED.type, data = EXCLUDED.data,
        tags = ARRAY(SELECT DISTINCT UNNEST(n.tags || EXCLUDED.tags)),
        updated_at = EXCLUDED.updated_at,
        version = n.version + 1
    RETURNING id, version, type, data, updated_at
)
INSERT INTO abyss.node_history (node_id, version, type, data, updated_at)
SELECT id, version, type, data, updated_at FROM u;
```

The first insert takes `version` from the column default (1), so the first state is recorded too.

## What a first working solution needs

1. **Schema** (`ysql-schema.sql` plus a migration for existing deployments):
   - `ALTER TABLE abyss.nodes ADD COLUMN version BIGINT NOT NULL DEFAULT 1`. 1-based, 0 stays
     reserved as VersionGuardPlan §3.2 says. Existing rows read as 1 and have no history row; that's
     accepted (history starts at the migration).
   - The history table (shape decided 2026-10-27):
     ```sql
     CREATE TABLE IF NOT EXISTS abyss.node_history (
         node_id    BYTEA       NOT NULL,   -- raw NodeId bytes, same as nodes.id
         version    BIGINT      NOT NULL,   -- nodes.version at commit, 1-based
         type       TEXT        NOT NULL,   -- stored for consumers; not keyed, not indexed
         data       JSONB       NOT NULL,   -- the committed node state (store stays JSON)
         updated_at TIMESTAMPTZ NOT NULL,
         PRIMARY KEY (node_id HASH, version ASC)
     );
     ```
     Every access already has the id: per-node reads, the item 3 cascade
     `DELETE … WHERE node_id = ?`, and `ORDER BY version DESC LIMIT 1` for the upgrade path. All
     single-tablet, with one row per insert and no secondary index.
     Rejected:
     - an index on `type`: every history insert also writes an index entry. Measured on `nodes`:
       +18% at 8 concurrent connections, +8% in batches (`TypeIndexScanPlan.md` §5.4). It also
       hashes on `type`, so all index entries of one type land on a single tablet.
     - `(type, node_id)` as the hash key: lookup by `node_id` alone can't compute the hash, so it's
       a full scan, and it still doesn't serve `WHERE type = ?`.
     - `type` alone as the hash key: every write of a type goes to one tablet. That kills
       scale-out.
     Deleting every node of a type doesn't need type access on history: take the node ids from
     `nodes` through `idx_nodes_type` (the TODO 2.34 type scan), and item 3 cascades each one by
     `node_id`.
2. **Both commit paths** switch to the statement above. If any path still uses the old upsert, history
   silently loses versions, so both paths change in the same commit.
3. **History is deleted with the node** (decided 2026-10-27; closes RFC open question 6). The counter
   lives on the `nodes` row, so deleting a node resets it. Without this rule, re-creating the id inserts
   at `version` 1, collides with the old `(node_id, 1)` in history, and every later write to that id
   rolls back. (Found by reading the SQL, not run yet.) So `DeleteNode` on a `@WithHistory` type also
   runs `DELETE FROM abyss.node_history WHERE node_id = ?` in the same transaction: one statement on
   one tablet, and nothing extra on the write path. A re-created node starts a new history at v1.
   Deleting a node also erases its past, which suits decommissioning and GDPR-style erase, but this
   isn't an audit trail.
   - **Upgrade path if history must outlive the node:** on the insert branch only, start the
     version at `(SELECT COALESCE(MAX(version), 0) + 1 FROM node_history WHERE node_id = ?)`. That's a
     single-tablet read of the newest key. Keep that read off the update branch, or every save pays
     for it. Rejected: a generation column (it lives on the node row and is lost in the same delete),
     and soft-delete tombstones (every read path would have to filter them).
4. **Opt-in per node type: `@WithHistory`** (decided 2026-10-27; closes RFC open question 5). History
   doubles node writes, so only annotated types get it. A class-level annotation in `abyss-store-api`,
   next to `@TypeTag`, `@EdgeConstraint` and `@CrossSchemaEdge`. A node never changes type, so the
   flag is resolved once per class at schema registration, never per write. `PersistentOp.SaveNode`
   carries the resolved `history: Boolean`, and the store picks the plain upsert or the CTE statement
   per op.
5. **Persistent store only.** Cache-only and ephemeral (YCQL) can't hold history. Registering a
   `@WithHistory` type without a YSQL store fails at wiring time (RFC open question 8).
6. **Out of v1:** a read API (`history(nodeId, from, limit)`), retention, dropping Kafka redeliveries,
   and the CAS guard itself (VersionGuardPlan Phases 2–4). Downstream reads the table directly.

## Tests (red first, on the dev container with true read committed)

- 16 coroutines write N updates each to the **same** id. Assert history holds exactly versions 1..total
  with no gaps or duplicates, and the last row's `data` equals the node's. Red on today's code: no
  column, no table.
- Repeat on distinct ids (odd counts, non-zero starting state).
- A plain (unannotated) type writes no history rows; a `@WithHistory` type does, in the same transaction.
- Every commit path: `transaction`, `batchTransaction` across chunk boundaries (batch size not a
  divisor of the op count), cascade writes. Each must increment by exactly 1 per save.
- Two saves of one id inside one transaction produce two history rows.
- Delete, then re-create: the delete leaves no history rows for the id; the re-create writes v1 and
  commits. Red with the cascade removed (the duplicate key rolls the transaction back).
- Guard check: put the old upsert back on one path and the gap assertion must fail.

## Performance doubts

Measured on 2026-09-24 (RFC §4.3, §5.4): live YB 2025.1.0.1, single dev container, 16 writers.

- **Distributed commit.** `nodes` and `node_history` are separate tables, so separate tablets, and
  every save becomes a two-tablet transaction. RFC measurement on distinct ids: 3170/s → 2690/s (−15%).
- **History vs the graph-native chain, measured 2026-10-27** (`HistoryVsChainWriteCostTest`, `-Pperf`,
  dev container, YB smart driver, one event per transaction, median of 5 interleaved rounds after a
  warm-up). Each variant has its own copy of `nodes`/`edges` with the production DDL and every index.
  The chain is: live upsert + event node + `has-next` edge + tail pointer moved as `RemoveEdge` +
  `AddEdge`. Database only, so the chain's Hazelcast puts and adjacency processors are **not**
  included and the chain comes out cheaper than it really is. Assertions: row counts per variant;
  each entity's history is exactly versions 1..n, with the node on version n.

  | workload | plain | history | chain |
  |---|---|---|---|
  | 1 writer, 1 entity, 1000 events | 512/s (1954 ms) | 500/s (1999 ms), **+2.3%** | 154/s (6512 ms), +233% |
  | 16 writers, own entity each, 200 events each | 3754/s (852 ms) | 3035/s (1054 ms), **+23.7%** | 860/s (3721 ms), +337% |

  - The single-writer case, the expected ingest shape, is nearly free: +2.3%, inside the noise of
    one dev box. That closes RFC §6 step 2's open measurement.
  - With concurrency it's +23.7%, worse than the RFC's −15% (that's +18% on time). The history range
    was wide (980..1450 ms) against plain's 834..889, so this number is noisy. Re-measure on a real
    cluster before reading anything into the gap.
  - The chain costs 3.3× plain with one writer and 4.4× with 16. It's 3.3× (one writer) to 3.5×
    (16 writers) history's time, before any cache cost.
- **Hot ids get worse.** Contended writes to one row already top out around 16–25/s at p50 ~1 s
  (`ConflictResolution_WaitOnConflictingTxns`, suspected `wait_queue_poll_interval_ms=100`, not
  confirmed). N measured 6–8/s, about 2× worse. Hot nodes (counters, aggregates) need their own fix
  first; history should probably stay off for them.
- **Batched path may lose insert rewriting.** Production sets `reWriteBatchedInserts=true`
  (`YugabytePersistentStore.kt:476`). pgjdbc rewrites plain `INSERT … VALUES` batches into
  multi-row inserts; a `WITH … INSERT … SELECT` statement is most likely not rewritten, so
  `batchTransaction` could fall back to one row per statement. Unverified: measure `commitYsqlBatched`
  before and after at 1k/10k ops.
- **Mixed types break up batches.** `commitYsqlBatched` groups consecutive ops that use the same
  statement into one `executeBatch()`. A transaction that mixes `@WithHistory` and plain node types
  alternates between the two statements and splits those groups into smaller batches. Measure an
  interleaved 50/50 workload alongside the rewrite check above.
- **Write amplification.** Every save copies the full JSONB once more. Storage grows without bound,
  with no time column in the key and no row TTL in YSQL, so retention is a scan plus delete. Long
  per-id histories grow one tablet's key range.
- **Scale gap.** The target is a million events a minute (~16.7k/s). 2.7k/s on one dev container
  says nothing about a multi-node cluster. Before calling it viable, measure on a real topology at
  that write rate, with and without history.
- **Migration cost.** `ADD COLUMN … NOT NULL DEFAULT 1` on a large `nodes` table: whether YB does
  this without rewriting the table is unverified.
