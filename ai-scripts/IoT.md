# High-Write / IoT Concurrency Findings

Question: can a huge volume of writes cause problems — specifically two coroutines
creating the same node concurrently, and the "million IoT devices each report their
state" ingest pattern?

Tested against the live Yugabyte container (`docker-yugabyte-1`,
`yugabytedb/yugabyte:2025.1.0.1-b3`). Isolation reports `read committed`; YB maps this
to snapshot semantics by default. Method: concurrent `ysqlsh` transactions replaying
Abyss's actual SQL shapes (`commitYsql`'s `INSERT … ON CONFLICT (id) DO UPDATE`).

## Write path, per `transaction { addNode(x) }`

1. Ops buffered in a per-call `BufferedTransaction` — no shared mutable state, coroutine-safe.
2. `integrityError`: for `AddEdge`, reads endpoints via `readNode` on **separate**
   connections, **before** the commit.
3. `expandCascades`: reads adjacency for removes — a pre-commit snapshot.
4. `persistentStore.transaction { commitYsql(ops) }`: one JDBC connection,
   `autoCommit=false`, one `INSERT … ON CONFLICT (id) DO UPDATE` per op **in caller
   order**, `commit()`.
5. Ephemeral delete fanout.
6. `populateCache`: `nodesMap.setAsync` + adjacency `addAsync`, unordered vs. the DB
   commit, optionally fire-and-forget (`asyncCachePopulation`).

## What is safe (measured)

- **Two coroutines create the same node id — nobody errors.** 30 concurrent single-op
  upsert txns on one key → 0 failures. Both `INSERT … ON CONFLICT DO UPDATE` succeed;
  the PK row-lock serializes them; last committer's `data` wins; `tags` are
  **union-merged** (`ARRAY(SELECT DISTINCT UNNEST(n.tags || EXCLUDED.tags))`, monotonic
  — never loses a tag); `created_at` preserved, `updated_at` overwritten. Correct
  idempotent semantic for "device reports its state". There is no "one wins, one fails".
- **N concurrent multi-op txns on shared hot keys, consistent op order** → 40/40 succeed.
  `commitYsql` preserves caller order, so identical staging order just queues on row-locks.
- **Adjacency mutations** go through the `AdjacencyMutationProcessor` EntryProcessor —
  atomic per key. Concurrent edge-adds to the same node's set don't corrupt it.

## What bites at scale

### 1. Multi-op transactions with inconsistent key ordering → deadlock flood, non-retryable

25 pairs of 2-op txns touching `{m1,m2}` in opposite orders → **30/50 failed**:
`deadlock detected (query layer retry isn't possible because this is not the first
command in the transaction)`. `commitYsql` / `commitYsqlBatched` execute ops in whatever
order the caller staged them. Any two callers staging the same nodes/edges in different
order — common once a shared "gateway" or "config" node exists that many devices edge to
— deadlock under load, surfaced as `AbyssError.Unexpected`. Nothing retries.

**Fix:** sort `ops` by a deterministic key (node-id bytes, then edge key) before
executing, in both commit paths. Small, safe, eliminates the entire deadlock class.
Does not help the serialization-conflict case, but kills deadlocks.

### 2. `modifyNode` under concurrency = silent lost update

20 concurrent SELECT-then-UPDATE txns → **19/20 failed**: `could not serialize access
due to concurrent update … retry isn't possible because this is not the first command`.
Abyss sidesteps this failure — `modifyNode` reads on a separate connection at
buffer-build time, and `commitYsql` only ever writes, so the commit is a first-statement
blind upsert that YB auto-retries. The cost of dodging the error: two coroutines doing
`modifyNode(sensor) { it.copy(reading = …) }` both read the pre-state, both blind-write,
last wins, **no signal**. Fine when each device owns its node. A lost-update generator
for any shared/aggregate/counter node. No version column, no `updated_at` guard, no CAS.

**Fix:** decide the contract. Either document "last-write-wins, no lost-update
protection" loudly, or add a version column + `WHERE version = ?` and surface
`AbyssError.Conflict`. Do **not** fix it by moving the read into the commit txn — that
buys the 19/20 failure rate.

### 3. Cache and DB can disagree on who won

`populateCache` → `nodesMap.setAsync(id, node)` with no ordering vs. the DB commit and
no version check. A commits to YB last; B's `setAsync` lands last in Hazelcast → DB has
A, cache serves B until eviction/TTL/reload. With `asyncCachePopulation=true` the two
writes are fully decoupled and a failed cache write is a logged warning. At a million
writes/min the divergence window never closes.

**Fix:** carry `updatedAt`/version into `populateCache`; use an EntryProcessor or
`replace`-with-staleness-check so an older write can't clobber a newer cached value.

### 4. Edge integrity check is TOCTOU, no DB backstop

`integrityError` reads endpoints pre-commit; the schema has **no FK** (`commitYsql` DDL
is pure `INSERT … ON CONFLICT` / `DELETE`). Concurrent `removeNode(X)` + `addEdge(X→Y)`:
the check sees X, the delete lands, the edge commits → dangling edge persists.
`cascadeEdgeRemovals` snapshots adjacency at expand time, so an edge added concurrently
with the node delete also escapes the cascade. `checkIntegrity` is advisory only.

**Fix:** FK constraint, or an explicit "dangling edges tolerated" stance plus a periodic
sweep.

### 5. Throughput ceilings — the "million devices" part, even with zero contention

- Hikari: `maximumPoolSize` / `minimumIdle` default **20**. Every `transaction{}` holds a
  connection for the commit round-trip. 1M/min ≈ 16.7k/s; at ~3 ms/commit that saturates
  ~50 connections, then coroutines block in `withContext(Dispatchers.IO)` on
  `getConnection()` → 30 s timeout → `SQLTransientConnectionException`.
- `Dispatchers.IO` default parallelism **64** — a second wall on concurrent blocking JDBC.
- The design's answer is `batchTransaction` (chunks of 1000). Per-event `transaction{}`
  at IoT rates is the wrong entry point.

**Fix for the ingest path:** buffer device reports and coalesce into `batchTransaction`;
raise `ysqlMaxPoolSize` and the IO dispatcher to match target concurrency.
`transaction{}` gives no back-pressure signal short of the timeout.

## Ranked

| # | Change | Effort | Payoff |
|---|--------|--------|--------|
| 1 | Deterministic op sort in `commitYsql` + `commitYsqlBatched` | tiny | kills the deadlock class |
| 2 | Explicit `modifyNode` concurrency contract (doc or version-guard) | small–med | removes silent data loss |
| 3 | Version-guarded `populateCache` writes | med | cache stops lying about the winner |
| 5 | Ingest buffer + `batchTransaction`, bigger pool/dispatcher | med | the actual scale path |
| 4 | FK or dangling-edge sweep | small–med | integrity under races |

## Test transcript (reproducible)

```
TEST 1: 30 concurrent single-op same-key upserts (BEGIN; upsert; COMMIT)
  → 0 failures. Final row = last committer. tags union-merged.

TEST 2: 20 concurrent read-modify-write increments (BEGIN; SELECT; UPDATE; COMMIT)
  → 19/20 failed: "could not serialize access due to concurrent update
     (query layer retry isn't possible because this is not the first command
      in the transaction)". Final counter = 1.

TEST 3: 25 pairs of 2-op txns, keys {m1,m2} staged in OPPOSITE order
  → 30/50 failed: "deadlock detected (query layer retry isn't possible ...)".

TEST 4: 40 concurrent 3-op txns, keys {s1,s2,s3} staged in SAME order
  → 40/40 succeeded.
```

## Feature-level gaps (repo sweep, 2026-08-28)

The findings above cover the write path. A second sweep — API surface (`AbyssEngineLike`/
`AbyssStoreLike`), `YugabytePersistentStore`, TODO backlog, README positioning — against what an
IoT application asks of its state layer. Abyss's honest IoT niche is the **device twin / topology
graph** (device→gateway→site→tenant, presence, temporary grants). It is not, and cannot cheaply
become, the telemetry store — and several gaps undermine even the twin niche.

### Eliminators

#### A. No telemetry/time-series story — the structural one

A node is latest-state-only: `data` JSONB, `updatedAt` overwritten, no history, no versioned
reads, no time-range anything (`Model.kt` carries just `createdAt`/`updatedAt`). IoT is mostly
append-only readings keyed by (device, time). Modeling readings as nodes is actively hostile to
the architecture: TODO 1.27 made the adjacency index never-evicted and authoritative, so
reading-nodes edged to their device grow resident RAM forever, and `PagedAdjacencyIndex`
(TODO 3.11) is open and gated.

**Verdict:** not a fixable gap — positioning. Twin in Abyss, telemetry in a TSDB alongside; the
README should say so explicitly.

#### B. Stale-report clobber — no event-time guard

Distinct from finding 2 (concurrent lost update): even with zero concurrency, a delayed
retransmit overwrites newer state, because last-write-wins resolves by commit order, not
device-reported time. Devices retransmit and networks reorder — that's the normal case. There is
no conditional write ("apply only if `reportedAt` > stored").

**Fix:** same mechanical fix as finding 2's version guard — one CAS/version column buys both.

#### C. No property/range queries — fleet operations are impossible

The read surface is: point-get by id, exact-tag GIN scan, unfiltered hash-range sweep
(`scanNodeIds`), and traversal from a known seed. "All sensors with battery < 20%", "devices
silent for 1h", any geo query — full scan plus client-side filter, per query, over the whole
fleet. TODO 2.12 (`@AbyssStoreColumn` + `queryNodeIds`) is still open and scoped to equality
only.

**Fix:** build 2.12, extended to ranges (`<`, `>`, `BETWEEN`) — its own open question already
asks whether equality is enough; for IoT the answer is no.

#### D. No change notifications

No listener, subscription, or CDC surface anywhere in the codebase. Rule engines and alerting
("device went offline", "threshold crossed") must poll. Hazelcast `EntryListener`s exist one
layer down, but cache writes are best-effort, async, and unordered vs. the DB commit (finding 3)
— a listener on the `IMap` yields a stream that can skip events and report the losing writer.
Sharpest miss: ephemeral TTL expiry is the natural "presence lost" trigger, and it fires
silently.

**Fix:** a post-commit event hook on the transaction path (fires after the store commit, carrying
the committed ops) — not raw `IMap` listeners; plus an exposed TTL-expiry event for ephemeral
elements.

### Aggravators (hurt, don't eliminate alone)

#### E. Topology must fit in RAM

Index-always-alive is the default with a boot-time fail-fast if eviction is configured on the
adjacency map. The math holds at 1M devices × a few edges (low GB) — but it's a hard sizing
wall, and the paged escape hatch is unbuilt (TODO 3.11). Plus TODO 2.1: no real cluster-topology
story yet.

#### F. Zero observability

No metrics seam — no Micrometer, no counters. Every failure mode measured above (pool
exhaustion, deadlock floods, cache divergence) surfaces as an opaque timeout or a logged
warning. At 16.7k writes/s the Hikari saturation in finding 5 is invisible until the 30 s
timeouts start.

#### G. Ephemeral asymmetries sit exactly where IoT models presence

Ephemeral and persistent ops can't share an atomic transaction; ephemeral edges are
outgoing-only (`inEdges` returns empty — "which devices are on gateway G" needs
caller-maintained reverse edges); YCQL ephemeral multi-op has no rollback. All documented, all
workable — all sharp edges on the one feature (`ephemeral{}`) that is Abyss's best IoT selling
point.

### Ranked, merged with the write-path list

| # | Gap | Verdict |
|---|-----|---------|
| A | No telemetry store, architecture resists one | Position, don't fix: twin-only, TSDB alongside |
| C | No property/range queries (2.12 open, equality-only) | Eliminator for fleet ops; 2.12 needs ranges |
| D | No change notifications / TTL-expiry events | Eliminator for rules/alerting |
| B + 2 | No event-time/version guard | One version column fixes both |
| 1, 5 | Deadlock ordering, ingest ceilings | Ranked above; 1 still the cheapest win |
| F | No metrics | Prereq for operating any of the above at rate |
| E, G | RAM ceiling, ephemeral asymmetry | Document as constraints |

**Short verdict:** Abyss loses IoT bids not on the write path but on the read-and-react side —
no queries over state, no events out of it, no history behind it. Close C and D and add the one
version column, and the twin niche is defensible; A stays out of scope by design and should be
stated as such.

## Cluster impact — 3-node Abyss + separate 3-node YB (RF=3), with a 5-node estimate (2026-08-30)

Scenario: 3 Abyss JVMs as embedded Hazelcast members (Abyss takes a caller-provided
`HazelcastInstance`; it validates only eviction config on its maps — backup count, merge
policy, split-brain protection are all the deployer's, undocumented), YB as its own 3-node
RF=3 cluster. Assumptions: intra-DC RTT ~0.3–0.5 ms, single-row upsert commit at RF=3
~3–5 ms (leader + quorum round-trip), Hazelcast default `backup-count=1` (sync).

### New cluster-only findings

#### H. All JDBC connections funnel to one YB node — FIXED (TODO 1.31)

`YugabytePersistentStore.create` used plain `org.postgresql.Driver` with a single
`ysqlUrl` — no YB smart driver, no `load-balance=true`, no multi-host URL. All 60 (100 at
5 nodes) connections landed on whichever tserver the URL named: that node did all YSQL
parsing/coordination and was a connectivity SPOF, even though storage writes still fan out
to tablet leaders. **Fixed:** `create` now builds the Hikari pool over the YugabyteDB smart
driver (`com.yugabyte:jdbc-yugabytedb`, `driverClassName = com.yugabyte.Driver`,
`jdbc:yugabytedb://` scheme) with `load-balance=true` by default (`ysqlLoadBalance` param)
and an optional `ysqlTopologyKeys` placement filter. `ysqlUrl` takes a comma-separated
multi-host seed list. The driver learns the full tserver set from `yb_servers()` and
distributes / fails over connections across live nodes.

#### I. Every cache touch grows a network hop

Partition ownership spreads over members: (N−1)/N of key touches are remote — **67% at 3
nodes, 80% at 5**. Adjacency reads stay one hop per window (shards co-locate via
`PartitionAware`), and `AdjacencyMutationProcessor` stays atomic cluster-wide (executes on
the owner) — correctness holds, but per-hop traversal latency goes from local-memory to
~0.5–1 ms per remote window, and every cache write pays owner hop + sync backup hop.
Traversal-heavy reads feel the cluster far more than the write path does.

#### J. Membership loss is survivable — verified self-heal path

All of a node's adjacency shards co-locate in one partition, so partition loss is
all-or-nothing per graph node: the warm-check (`isEmpty` → `preloadOut`) sees a genuinely
cold node and reloads from YB, and nodes/edges maps read-through. Lose 1 member of 3:
backups promote, no loss, one rebalance storm. Lose 2 (or lose the backup mid-rebalance):
no corruption, but a **cold-read stampede against YB exactly when degraded** — and with a
memory-only Hazelcast ephemeral store, that ephemeral data (presence!) is gone by design.
Split-brain merge can resurrect stale cache entries — amplifies finding 3, never durable
loss (YB stays authoritative). Nothing here needs code; it needs a runbook line and the
metrics from F to see the stampede.

### Amplification of the existing findings

| # | Single-node finding | At 3 Abyss nodes | At 5 |
|---|--------------------|------------------|------|
| 1 | Deadlock flood | Concurrent txns ×3 (up to 60) → deadlock probability grows superlinearly; sort fix works cross-JVM (deterministic order) — urgency up | ×5 (100), worse |
| 2 | `modifyNode` lost update | Cross-JVM racers, and the read side can now start from a stale cluster cache (finding 3) — two ways to lose | window wider |
| 3 | Cache/DB disagree | Divergence is now **cluster-visible**: one losing `setAsync` poisons all members' reads until reload; two racing writers are on different JVMs so ordering is never local | blast radius 5 nodes |
| 4 | TOCTOU integrity | Check on node A, delete from node B — window now includes cross-node scheduling | same, wider |
| 5 | Throughput ceiling | See math below | see below |
| F | No metrics | Now spans 3 JVMs + 3 tservers; pool saturation, rebalance storms, divergence — all invisible | prereq, full stop |

### Throughput math at 1M/min (16.7k tx/s), per-event `transaction{}`

- **Connection ceiling:** capacity ≈ pools × 1000/commit-ms. 3×20 = 60 conns: @3 ms → 20k/s
  (zero headroom), @4–5 ms (realistic RF=3) → **12–15k/s: the default pools do not carry
  1M/min at 3 nodes**. 5×20 = 100 conns @4 ms → 25k/s — holds, no headroom for spikes.
  For 2× headroom at 3 nodes: `ysqlMaxPoolSize` ≈ 35–40/node (105–120 total; YB default
  max connections ~300/tserver → fine, now that H (TODO 1.31) spreads them).
- **`Dispatchers.IO` = 64/node:** steady state needs ~30 blocked JDBC threads/node — fits;
  any YB latency spike (leader election, compaction) pushes past it. Second wall stands.
- **YB capacity:** 16.7k/s of JSONB upserts each paying GIN tag-index maintenance, ×3
  replication, on 3 tservers ≈ 5.6k coordinated txns/s/node — top of the realistic range
  for mid-size (8–16 vcpu) tservers. Per-event ingest at 1M/min is marginal on 3 YB nodes
  even with everything above fixed. `batchTransaction` (1000/chunk, `reWriteBatchedInserts`
  already set) turns it into ~17 commits/s — trivial. The batching answer from finding 5
  is not an optimization here; it is the only shape that fits this hardware.
- **YB failure mode:** 3-node RF=3 tolerates exactly one tserver down; per-tablet leader
  re-election stalls commits for seconds → coroutines pile onto Hikari → the 30 s timeout
  cliff, still with no back-pressure signal. More Abyss nodes make the pile-on bigger, not
  smaller.

### RAM (aggravator E, clustered)

1M devices, ~3 edges each → ~6M adjacency entries (both directions); with key/entry
overhead ~1–1.5 GB, ×2 for one sync backup ≈ **2–3 GB cluster-wide for adjacency alone**,
~1 GB/member at 3 nodes, ~0.6 GB at 5. `nodesMap`/`edgesMap` dominate beyond that and
scale with JSONB payload size (also ×2 backup). 3→5 members cuts per-member share 40% and
is the cheap fix for E — the ceiling becomes a wall only when payloads are fat.

### Cluster verdict

3 Abyss + 3 YB runs correctly — EntryProcessor atomicity, deterministic self-heal, and YB
authority all survive clustering. What changes: contention findings (1–4) get 3–5× more
probable and cluster-visible, per-event 1M/min ingest stops fitting the default pools *and*
is marginal for 3 YB tservers (batching becomes mandatory, not advisable), and two new
items appear — the single-tserver JDBC funnel (H, now fixed in TODO 1.31) and the undocumented
"Hazelcast config is your problem" surface (backup count, split-brain policy, quorum).
Going 3→5 Abyss nodes buys RAM headroom and connection capacity; it buys nothing on YB
capacity, worsens the remote-read ratio to 80%, and amplifies every contention finding.
Scale YB, not Abyss, if per-event writes must stay.
