# RFC: Node history table (event log beside the graph)

Status: **parked**. Discussed and measured 2026-09-24; we'll come back to it later. Depends on
[`VersionGuardPlan.md`](VersionGuardPlan.md) only for the fallback option (§4.2).

## 1. Problem

A long-lived entity (for example an IoT device's event) is one node, updated in place by every
subsequent event with the same id. The past states have to be kept somewhere. The graph-native way,
a `has-next` chain of nodes and edges, can't be processed under load:
- appending costs a new node, a new edge and a rewrite of the tail pointer, with a hot lock on the tail;
- reading the history costs O(n) hops;
- the chain competes with the live graph for Hazelcast cache.

A 10k-link chain per entity doesn't scale to a million events a minute.

**Direction:** keep the node as live state only, and append history to a dedicated YSQL table in
the same store transaction as the node write. It's not a user-facing `sql()`/`insert()`: raw SQL
was rejected because it can go around the cache (making it incoherent), breaks `LOCK_ORDER`, leaks
the SQL dialect into a store-agnostic API, and can cross tenant schemas.

## 2. Chosen direction: history = the incoming event, keyed by Kafka `(timestamp, offset)`

Every incoming event lands in history, the first one included. History is the **event log**, not
"old versions archived on overwrite": the "archive only on modify" framing had no real justification.

```sql
CREATE TABLE IF NOT EXISTS abyss.node_history (
    node_id       BYTEA       NOT NULL,   -- raw NodeId bytes, same as nodes.id
    kafka_ts      BIGINT      NOT NULL,   -- record timestamp (ms): the ordering
    kafka_offset  BIGINT      NOT NULL,   -- record offset: tie-breaker + uniqueness
    type          TEXT        NOT NULL,   -- event @SerialName discriminator
    data          JSONB       NOT NULL,   -- the event payload (store stays JSON)
    PRIMARY KEY (node_id HASH, kafka_ts ASC, kafka_offset ASC)
);
```

Kafka stays out of the API: the caller passes an opaque ordering key `(Long, Long)`. Abyss
documents the contract ("unique per node, ordered by k1 then k2") and doesn't try to enforce it.

**Why this shape**
- The key is already on the record: no DB state, no read, no hot row, no change to `nodes`.
- The history insert reads nothing, so none of the read-committed anomalies in §5 apply. It's a
  blind append in the same transaction.
- Time-range reads (`kafka_ts BETWEEN`) are a single-tablet range scan.
- Time is in the PK, so YSQL range partitioning by time is allowed, and retention can drop old
  partitions. That's impossible with the `version` or plain `seq` keys.
- A redelivered record repeats `(ts, offset)`, so `ON CONFLICT DO NOTHING` dedupes history.
- If the producer stamps the device's time, a late retransmission lands in its correct place in the past (IoT finding B).
- Unlike a bare offset, it's unaffected by repartitioning or topic recreation. Two different records
  collide only if they share both the ms and the offset.

**Why not the timestamp alone:** measured in §5.3; timestamps repeat heavily for a single key.

## 3. Open questions

1. **Scope:** is ingest always Kafka-fed? If not, non-Kafka write paths (`modifyNode` from app logic,
   cascades, REST ingest, `batchTransaction` imports) have no key. Either the history is Kafka-only,
   or those paths fall back to §4.2.
2. **History vs node divergence:** history is in Kafka order, the node in commit order. If the
   consumer ever applies events out of order, the node's final state stops matching the "latest"
   history row. Keeping them consistent needs the event-time guard (VersionGuardPlan Phase 3).
3. **Node-side dedup:** a redelivery is dropped from history, but the node upsert still applies it
   again, unless the node write is gated on the history insert:
   ```sql
   WITH h AS (INSERT INTO abyss.node_history (...) VALUES (...) ON CONFLICT DO NOTHING RETURNING node_id)
   INSERT INTO abyss.nodes AS n (...) SELECT ... FROM h ON CONFLICT (id) DO UPDATE SET ...;
   ```
   **Unverified on YB.** After the anomaly in §5.2 no CTE semantics may be assumed; this needs a live test.
4. **Write cost is unmeasured:** the extra row lands on a second tablet, so it's a distributed commit
   like N's (−15%, §5.4). It's expected to be close to that, but it hasn't been measured.
5. **Opt-in:** per node type (a type-level annotation, my lean) or per call.
6. **Erase:** when a node is erased, does its history cascade (a single-tablet `DELETE WHERE node_id = ?`) or stay as an audit trail?
7. **Read API:** is a paged `history(nodeId, from, limit)` Abyss's job, or is the table only for downstream consumers?
8. **Persistent store only:** cache-only and ephemeral (YCQL) modes can't support history, so
   registering a type with history without a persistent store should fail at wiring time.

## 4. Alternatives (kept, not chosen)

### 4.1 Rejected
| Shape | Why not |
|---|---|
| **S1**: `INSERT INTO history SELECT … FROM nodes`, then upsert (two statements) | Correct only under snapshot isolation. Under real read committed it **silently loses versions** (§5.2) |
| **S3**: S1 with `FOR UPDATE` on the archive | Also loses versions under read committed |
| **S2**: one statement, CTE with `FOR UPDATE` + upsert | YB internal error `Expected row lock buffer to be empty` |
| **S4**: one statement, CTE without a lock + upsert | **Archives the new row, not the old one** (YB sees the statement's own write) |
| YSQL `TIMEUUID` | The type doesn't exist in YSQL (YCQL only) |
| `uuid_generate_v1()` | Doesn't sort by time: `time_low` comes first and wraps every ~429.5 s |
| YB sequences | min cache 100 (`CACHE 1` silently overridden), cache per connection: two pooled connections got 601, 701, 602, 702… Not ordered per node, and a cluster-wide hot row without the cache |
| Kafka timestamp alone as the key | Collides (§5.3). With `DO NOTHING` a collision silently loses an event; without it, the consumer retries forever |

### 4.2 Fallback: N, the per-node `version` counter
```sql
WITH u AS (INSERT INTO abyss.nodes AS n (...) VALUES (...)
           ON CONFLICT (id) DO UPDATE SET ..., version = n.version + 1
           RETURNING id, version, type, data)
INSERT INTO abyss.node_history (node_id, version, type, data) SELECT id, version, type, data FROM u;
```
- Exact under read committed contention (0 gaps). History holds v1 through the current version.
- Works for **every** write path, Kafka or not. History can't diverge from the node.
- **Requires a `version` column on `abyss.nodes`**, which doesn't exist today. That's
  [`VersionGuardPlan.md`](VersionGuardPlan.md) Phase 1: a migration plus a backfill, and
  `version = n.version + 1` in both `commitYsql` and `commitYsqlBatched`. Any path that misses the
  increment breaks history silently. YSQL only.
- No redelivery dedup; no time-partitioned retention (time isn't in the PK).
- **If we ever pick it, test it deeply first.** (Also mentioned: an optional caller key with this as the
  fallback for writes without one. That's one table and two write paths.)

### 4.3 Other exact shapes measured (all needed the `version` column)
| Shape | 16 writers, each on its own id | Note |
|---|---|---|
| plain upsert, no history (baseline) | ~3170/s | — |
| **N**: new version via `RETURNING` | ~2690/s (−15%) | recommended if we take the `version` route |
| **P**: `SET prev_data = n.data`, `RETURNING prev_data` | ~2610/s (−18%) | second JSONB copy on every node row |
| **Trigger**: `AFTER UPDATE`, inserts `OLD` | ~2580/s (−19%) | exact; logic lives in the DB |
| **C**: version CAS + retry | ~2210/s (−30%) | needs a read; natural on the `modifyNode` path (VersionGuardPlan §3.4) |
| S1 (rejected) | ~2100/s (−34%) | loses versions under read committed |

Also possible, not measured: CDC with before-images (logical replication, `REPLICA IDENTITY FULL`).
It needs no API, but it's asynchronous and outside the transaction.

## 5. Evidence (live YB 2025.1.0.1 / PG 15.12, 2026-09-24)

### 5.1 The isolation level is not what `SHOW` says
`SHOW transaction_isolation` reported `read committed` while the tserver ran
`yb_enable_read_committed_isolation=false`, which really means snapshot isolation. Verify with a
mid-transaction visibility test, never with `SHOW`. **Abyss sets no isolation level itself**, so it
inherits the cluster's. The dev container now runs true read committed (flag set in sniper's
`docker-compose.yaml`, with the hostname pinned to `63961330d42b`).

### 5.2 Why S1 loses versions under read committed
Each statement gets its own snapshot. The archive reads N−2; another writer commits N−1;
`ON CONFLICT DO UPDATE` acts on the latest committed row and produces N. Logging each transaction
showed long runs archiving `new_v − 2`. The history PK only catches two transactions archiving the
same stale version, so a shifted chain never collides, and holes appear where the shift collapses.
The first-insert race on a fresh id is what knocks the chain out of step. Under snapshot isolation
the same shape is correct: its correctness depends on a server flag Abyss doesn't control.

### 5.3 Kafka timestamps (throwaway Kafka 4.0 broker, 1 partition, 1 key)
| Scenario | Records | Distinct timestamps | Most in one ms |
|---|---|---|---|
| burst, `LogAppendTime` | 1000 | 2 | 970 (the broker stamps a whole batch with one time) |
| burst, `CreateTime` | 1000 | 34 | 52 |
| one event every ~20 ms, `CreateTime` | 300 | 233 | 17 (the input pipe delivered lines in clumps, like a gateway does) |

No timestamp went backwards here; an NTP step or producer failover can cause that with `CreateTime`.

### 5.4 Hot-id ceiling (applies to Abyss today, not only to history)
Any contended write to one existing row (plain `UPDATE`, plain upsert, in or out of an explicit
transaction) runs at **~16–25/s, p50 ~1 s** with 16 writers. ASH is dominated by
`ConflictResolution_WaitOnConflictingTxns`. About 93% of the slow single-tablet writes finish 0–9 ms
after a whole multiple of 100 ms, which points to `wait_queue_poll_interval_ms=100`. **Not confirmed:**
the flag isn't settable at runtime; confirming it needs a restart with a lower value. N makes it
about 2× worse (6–8/s) because the commit spans two tablets. Per-id single-writer ingest is
unaffected. Worth its own TODO: any hot node (a counter or aggregate, IoT.md) hits this ceiling.

## 6. Next steps (when we come back)
1. Decide §3.1 (Kafka-only or not): it picks between §2 alone and §2 plus the §4.2 fallback.
2. Verify on live YB: the gated CTE (§3.3) with a redelivery storm, and single-writer throughput
   compared with the plain upsert and N.
3. Confirm the hot-id cause (`wait_queue_poll_interval_ms=10`, restart, rerun) and file the ceiling as a TODO.
