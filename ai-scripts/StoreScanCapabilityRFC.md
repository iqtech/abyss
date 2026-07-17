# RFC: `AbyssStoreLike` DB scan/query capability

## Context

Surfaced during a scaling discussion (personal-app topology: `HomogeneousSchemaGraph` keyed
`(userId: tag, elementId: rawId)`, ~95% of edges stay within one user's tag). Two use cases came up:

1. A **global, cross-schema admin sweep** (e.g. finding orphaned nodes across every tenant as an
   integrity check).
2. **Tag-based lookup** (e.g. "all of user X's nodes").

Both dead-end on the same root gap, not two separate problems: `AbyssStoreLike` only exposes
point-gets (`loadNode`/`loadEdge`/`loadEdges`/`loadInEdges`) and writes (`transaction`/
`batchTransaction`) — it has **no scan/enumerate capability at all**, filtered or not, against either
store. `allNodeIds()` and a hypothetical `allTaggedNodes()` are both just callers of that one missing
capability.

Per-user enumeration via a dedicated per-tag secondary index was considered and set aside: the app
guarantees a single root node per user, created at login, and never creates orphans, so "give me user
X's data" is a traversal from that known root (`outgoing`/`pathTo`/`exhaustReachable`) — already
O(what's touched) at any scale, not an enumeration concern. The real remaining need is the
cross-tenant admin sweep.

## Current state: `allNodeIds()` is cache-only, so it's not just slow — it's incomplete

`AbyssSchemaWorker.allNodeIds()` (`AbyssSchemaWorker.kt:113`) is `nodesMap.keys.forEach { emit(it) }`
— Hazelcast cache only, never touching `persistentStore`/`ephemeralStore`, unlike every other read
path in this class (`readNode`/`readEdge`/`outAt`/`inAt` all self-heal from the store on a cache
miss). Combined with the idle/heap-pressure eviction already configured on `abyss-nodes`
(`max-idle-seconds`, `FREE_HEAP_PERCENTAGE` LRU — see `hazelcast.yaml`), a durable node that's simply
gone cold (evicted, or never queried since a restart) is silently invisible to `allNodeIds()` — and
therefore to `connectedComponents()`, `detectCycle`, `exhaustReachable`/`allReachable`, and schema
export/import (`GraphExport.kt:30`), all of which enumerate starting points through it.

For the orphan-finding admin use case this is disqualifying: a cold orphan is exactly the node most
likely to be silently skipped by the one tool meant to catch it.

## YSQL: both use cases are cheap to add

- **Unfiltered scan**: `SELECT id FROM abyss.nodes`. `id` is the raw `NodeId.bytes`
  (`ysql-schema.sql:21-28`, confirmed via `YugabytePersistentStore.queryNodeYsql`'s `setBytes`
  binding). YugabyteDB parallelizes this across tablets automatically — likely *faster* at real scale
  than today's single-JVM Hazelcast key scan, not just more complete. Needs a fetch-size-streamed
  JDBC cursor (`stmt.setFetchSize(n)`, `autoCommit = false`), not a single unbounded `ResultSet`.
- **Tag-filtered lookup**: `NodeLike`/`EdgeLike.tags` (`Model.kt:9,19`) are a plain, unencumbered
  `List<String>` — grepped every reference in `abyss-graph`/`abyss-store-api`/`abyss-store-yugabyte`
  main source, nothing else touches it (no traversal filtering, no eviction, no integrity checks).
  Already durable and already GIN-indexed (`idx_nodes_tags ON abyss.nodes USING GIN (tags)`,
  `ysql-schema.sql:31`). Nothing stops the app writing `tags = listOf(userId.toString())` today.
  Query form that uses the GIN index: `WHERE tags @> ARRAY['user-123']` (Postgres array-containment
  operator, indexed by `array_ops`). Zero schema change — this beats an earlier idea of adding a
  dedicated `tag` column + index; reuse what's already there and already indexed.
- **Missing piece, concretely**: `tags` is currently write-only — grepped for any existing
  read/filter-by-tags path and there isn't one. Need one new `AbyssStoreLike` method (safe default,
  same pattern `batchTransaction` used so other implementers/fakes don't break) —
  e.g. `scanNodeIds(tag: String? = null): Flow<NodeId>` — plus one query implementation in
  `YugabytePersistentStore`.

## YCQL (`ephemeral_nodes`/`ephemeral_edges`): structurally harder, not just unimplemented

This schema deliberately runs without `transactions=true` — its own comment
(`ycql-schema.cql:16-18`) explains why: YugabyteDB YCQL requires `transactions=true` together with
secondary indexes, and `transactions=true` blocks per-row `USING TTL` (YugabyteDB issue #10992).
Since `ephemeral_nodes`/`ephemeral_edges` need per-row TTL — that's the entire point of the ephemeral
store — secondary indexes were traded away deliberately. A Cassandra-style collection index
(`CREATE INDEX ... (values(tags))`, enabling `WHERE tags CONTAINS 'x'` — the direct YCQL analog of
Postgres's GIN-on-array) is therefore not just missing, it's excluded by this table's own TTL design.

An **unfiltered full scan** is still plausible via token-range partitioning: split the partition-key
hash ring into ranges (`WHERE token(id) > ? AND token(id) <= ?`, or via the DataStax driver's
`TokenMap`/`getTokenRanges()` API — the repo already uses `com.datastax.oss.driver`,
`build.gradle.kts:5`) and query each range independently, the same technique Spark's Cassandra
connector and DSBulk use for bulk exports. This gives a clean, parallelizable, no-`ALLOW FILTERING`
enumeration of `ephemeral_nodes`. It does **not** give tag-filtering for free — within each token
range there's still no index, so filtering by tag would mean row-by-row inspection
(`ALLOW FILTERING` scoped to that range, or client-side filtering), still O(all ephemeral nodes)
overall, just chunked and parallel instead of one unbounded query.

This is general Cassandra/YCQL-family knowledge, not verified by exercising it against this repo's
actual cluster (nothing here uses token-range queries yet) — needs a feasibility spike against the
real YugabyteDB version in use before it goes into a design, particularly to confirm the DataStax
driver's token-map APIs behave as expected against YugabyteDB's partitioning rather than assuming 1:1
Cassandra compatibility.

## Shape of the fix (not yet a committed design)

- Add DB scan capability to `AbyssStoreLike` once, at the store layer (safe-default method, mirrors
  `batchTransaction`'s pattern) — e.g. `scanNodeIds(tag: String? = null): Flow<NodeId>`.
- `YugabytePersistentStore` implements it via a fetch-size-streamed query — unfiltered `SELECT id`
  or `WHERE tags @> ARRAY[?]` depending on whether a tag was passed.
- `YugabyteEphemeralStore` realistically only covers the unfiltered/token-range case (see YCQL
  section above) — pending the feasibility spike.
- `AbyssSchemaWorker.allNodeIds()`/`allNodeIdsRaw()` redesign: when `persistentStore != null`, the
  store scan is authoritative (every persistent node is written there before cache — confirmed via
  `transaction()`'s persistentStore-then-populateCache ordering in `AbyssSchemaWorker.kt:233-253`),
  so it can replace rather than merely merge with `nodesMap.keys`. Cache-only fallback stays for pure
  in-memory deployments (no stores configured at all).
- A new `allTaggedNodes()`-style entry point becomes a second caller of the same `scanNodeIds`
  capability, not a separate design.

No design plan has been written yet — this RFC is the pre-plan exploration.