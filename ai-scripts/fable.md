# Abyss — code & docs review (Fable, 2026-07-21)

Scope agreed up front: quality first (correctness, concurrency, design), security light,
docs as background. Internal working doc, blunt, `file:line` refs. Reviewed at `dev` @ `cfe5bd8`
(0.32.1). Full `./gradlew build` ran green during this review, including `LoadTest` against the
live Yugabyte container. Claims about YCQL TTL semantics below were verified against that
container, not asserted from memory.

## Verdict

This is a genuinely good codebase. The architecture (untyped `AbyssSchemaWorker` core, typed
facades, `SchemaResolution` as the single injected tier difference, self-describing `NodeId`)
is coherent and the code matches the docs to an unusual degree. Comments explain *why*, the
CHANGELOG is honest about past bugs, and the test suite covers concurrency, cold-cache
self-heal, multi-member clustering, and perf regressions — not just happy paths. The findings
below are mostly edge-window and cold-path issues, plus one real latent bug. Nothing here is
architectural rot.

---

## 1. Bugs

### 1.1 `AdjacencyMutationProcessorSerializer.read` NPEs on a null `nodeTypeTag` — CONFIRMED, fix is one token

`serialization/AdjacencyMutationProcessorSerializer.kt:41`:

```kotlin
KIND_ADD -> AdjacencyMutation.Add(AdjacencyEntry(neighborId, reader.readNullableInt16("nodeTypeTag")!!, edgeTypeTag))
```

`AdjacencyEntry.nodeTypeTag` is *deliberately* nullable (`AdjacencyKey.kt:37` — unresolvable
neighbor at write time: `checkIntegrity=false` dangling edges, or a preload racing the node's
store row), and the write side correctly emits null (`AdjacencyMutationProcessorSerializer.kt:24`,
fed from `AbyssSchemaWorker.resolveNodeTag` returning null at `AbyssSchemaWorker.kt:400`). The
read side then asserts it non-null. The constructor accepts `Short?` — the `!!` is both
unnecessary and wrong.

Trigger: an `AddEdge` with an unresolvable endpoint, in any deployment where the
`EntryProcessor` actually round-trips through serialization (multi-member cluster, client
topology). The processor deserialization throws, the adjacency mutation is lost, and the
symptom is an edge missing from `inEdges`/untyped hops with only a `populateCache` warn-log as
evidence. Single-embedded-member tests never serialize the processor, which is why the suite
(including `MixedTraversalTest`) doesn't catch it; `MultiMemberClusterTest` is gated behind
`-Pcluster` and doesn't exercise dangling edges.

Fix: drop the `!!`. Worth a serializer round-trip test with `nodeTypeTag = null` while at it.

### 1.2 Sub-second TTL silently means "forever" — verified against live YCQL

`YugabyteEphemeralStore.commitYcql` (`YugabyteEphemeralStore.kt:149,162`) computes
`op.ttl.inWholeSeconds` and interpolates it as `USING TTL $ttl`. For any `ttl < 1s` that's
`USING TTL 0`, and **verified on the live container: TTL 0 = no expiry** — the row persists
forever (`ttl(data)` returns 0, row survives). The cache side has the same hole:
`applyToCacheAsync` (`AbyssSchemaWorker.kt:405,412`) passes `ttl.inWholeSeconds = 0` to
`IMap.setAsync`, where 0 also means "no TTL" in Hazelcast. So
`graph.ephemeral(ttl = 500.milliseconds) { ... }` produces an immortal cache entry *and* an
immortal YCQL row. `ttl_expiration` is written correctly, so store-side reads filter it — but a
cache hit never consults the store, so the element is visible forever.

Fix at the entry point, once: `require(ttl >= 1.seconds)` in `AbyssGraphSchema.ephemeral` (or
ceil to 1s). Cheap, closes both sides.

### 1.3 Additive tag writes + per-element TTL: tags expire on their own schedule — verified

Follow-up wrinkle to TODO 1.24's `tags = tags + ?`. YCQL set elements carry the TTL of the
write that created them (verified live: element written `USING TTL 15`, row re-written
`USING TTL 120` → after ~18s the old element is gone, the new one and `data` remain). Two
consequences for `ephemeral { }` re-saves:

- Re-save with a **longer** TTL does *not* extend previously-written tags — they vanish while
  the node/edge lives on.
- Re-save with a **shorter** TTL leaves old tags outliving `data`. The read path defends
  (`queryNodeYcql` returns null when `data` is null, `YugabyteEphemeralStore.kt:116`), so no
  ghost nodes surface, just orphaned tag bytes until their TTL clears them.

Not a crash, but it contradicts the natural reading of "tags on the element". Worth one
sentence in TODO 1.24 / CHANGELOG so it's a documented semantic, not a surprise. YSQL is
unaffected (no TTL there).

---

## 2. Design-level risks (know these are there; most are defensible)

### 2.1 `modifyNode`/`modifyEdge` are lost-update prone — no locking, no version check

`BufferedTransaction.modifyNode` (`AbyssGraphSchema.kt:298`) reads the current value at
*buffer time* and commits later; nothing detects a concurrent writer in between. Two
concurrent `modifyNode(id) { it.copy(count = it.count + 1) }` calls → one increment lost,
silently. The YSQL upsert is unconditional (`ON CONFLICT ... DO UPDATE`,
`YugabytePersistentStore.kt:148`), so the store arbitrates nothing. `ensureSubgraph` at least
marks its TOCTOU window with a `ponytail:` comment (`Extensions.kt:172`); `modifyNode`/
`modifyEdge` have the same window, undocumented, on the API whose whole point is
read-modify-write. At a million events/min this is not theoretical.

Cheapest honest options: (a) document it as last-write-wins, or (b) an optional CAS — the YSQL
upsert already has `updated_at` to predicate on (`DO UPDATE ... WHERE n.updated_at = ?`), which
per the flag-new-state rule needs no new persisted state. (b) only pays if you actually have
concurrent writers on the same key.

### 2.2 Preload can resurrect a just-deleted edge (read-through vs. write-invalidate race)

`preloadOut` (`AbyssSchemaWorker.kt:219`) reads the store, then `putIfAbsent`s into the cache.
Interleaving with a concurrent `RemoveEdge` (store delete commits, `removeAsync` clears the
cache, *then* the preload's `putIfAbsent` lands with the pre-delete row) leaves a deleted edge
alive in cache + adjacency until the next eviction. Same shape for `loadAndCacheNode`'s
`set()` (`AbyssSchemaWorker.kt:445`), which doesn't even have putIfAbsent semantics. Window is
small and self-heals on eviction; at your throughput it will occur, so decide whether
"eventually evicted" is acceptable and write that down. A real fix needs delete tombstones or
versioned cache values — likely not worth it; documenting it is.

### 2.3 Store commit → cache populate is non-atomic (known, and the failure mode is only a warn-log)

`transaction()` (`AbyssSchemaWorker.kt:250-270`): store commits, then cache updates
best-effort with a `log.warn` on failure. Fine as a design choice (store is truth, cache
self-heals on miss) — but a *partial* cache failure (edge `setAsync` succeeds, adjacency
`submitToKey` fails) leaves the index and `edgesMap` disagreeing until eviction, and warm-path
reads never re-check the store (`adjacencyRead` only preloads when the shard read is *empty*,
`AbyssSchemaWorker.kt:167`). A non-empty-but-stale adjacency set has no heal path short of
eviction. Accepted-risk territory; worth stating in the README's durability discussion.

### 2.4 In-transaction add-then-remove ordering blind spots (minor)

`integrityError` (`AbyssSchemaWorker.kt:335`) builds `addedInTx` from every `AddNode` in the
op list, ignoring a later `RemoveNode` of the same id in the same transaction — an edge
referencing it passes the check and lands as a durable dangling row (no FK in the schema to
stop it). Sibling of the already-documented "edges added in the same tx as `removeNode` aren't
cascaded" caveat (README ~line 641). Both are "don't do that" cases; both are silent when you
do. A cheap guard: reject transactions that add an edge to a node removed later in the same op
list.

### 2.5 `detectCycle` recurses per node — StackOverflow on long chains

`TraversalBuilder.dfsCycle` (`TraversalBuilder.kt:210`) recurses one frame per node with no
depth cap; a 50k-node chain will blow the stack (suspend frames land on the heap but default
`-Xss`-style limits still apply to the trampoline... in practice Kotlin suspend recursion
grows heap, so the real failure is OOM-ish degradation rather than SOE — either way unbounded).
`checkReaches`/`exhaustReachable` are iterative; `paths()` is bounded by `maxDepth`. Only
`dfsCycle` is unbounded. Iterative rewrite with an explicit stack is mechanical.

---

## 3. Performance notes (million-events/min lens)

### 3.1 Cold-start warming of a hub node is O(E) round trips, including a store read per neighbor

`preloadOut` (`AbyssSchemaWorker.kt:219-236`): per edge — `putIfAbsent` (1 RT), `readNode(toId)`
(1 RT cache, **plus a store point-read on miss** — and after a cold restart every neighbor *is*
a miss), and a synchronous `executeOnKey` (1 RT). A 10k-edge hub after restart ≈ 30k+
sequential round trips, several of them YSQL queries, all inside the first `outEdges` call's
latency. The `nodeTypeTag` being fetched is an *optional hint* by its own contract
(`AdjacencyKey.kt:33-36`). Cheapest fix: pass `null` for the tag during preload (drop the
`readNode` entirely) and batch the map writes (`putAll` + group mutations per shard key —
the processor already merges sets, so one `Add` per entry could become one processor carrying
N entries per shard). Same pattern in `preloadIn` (`AbyssSchemaWorker.kt:239`).

### 3.2 `allNodeIds()` materializes the whole key set, blocking, off-dispatcher

`AbyssSchemaWorker.kt:113,198`: `nodesMap.keys` pulls every key in the cluster into one set on
the *caller's* coroutine context (everything else wraps Hazelcast calls in
`withContext(Dispatchers.IO)`; this doesn't). At README-sizing scale (36k users × 500 nodes =
18M keys) that's a multi-GB materialization to start a `connectedComponents` or export. TODO
1.23 (store scan capability) is the real fix; until then at least route it through IO and note
the memory profile in the export/`connectedComponents` docs.

### 3.3 Per-worker `hopDispatcher` bounds multiply

`AbyssSchemaWorker.kt:81`: each worker gets its own `limitedParallelism(256)` view. Documented
as a per-graph tuning feature (README ~1049), fine — just remember N graphs in one JVM can
occupy N×256 IO slots; `Dispatchers.IO` itself caps at `max(64, cores)` by default, so the
per-worker "256" is already quietly clamped by the parent pool unless
`kotlinx.coroutines.io.parallelism` is raised. The README's tuning advice doesn't mention that
interaction; one sentence would save someone a confused afternoon.

### 3.4 Smaller items

- `inEdges` pays adjacency read + `edgesMap.getAll` — known, documented (README ~910). Fine.
- `EphemeralOp.SaveNode` uses `Long` seconds, `SaveEdge` uses `.toInt()`
  (`YugabyteEphemeralStore.kt:149,162`) — harmless inconsistency, unify when touching the file.
- YCQL `SaveNode`/`SaveEdge` build `SimpleStatement` text per call while deletes are prepared —
  already tracked as TODO 2.25, agreed "do not touch yet".
- `HeterogeneousSchemaGraph.registeredTags` is an unsynchronized `mutableSetOf`
  (`HeterogeneousSchemaGraph.kt:61`) — register-at-startup discipline is assumed but not
  enforced; a `ConcurrentHashMap.newKeySet()` is free if you ever register lazily.

---

## 4. Security / hardening (light, per scope)

Trust model is "embedded library, trusted callers" and the code is consistent with it. All
user-data-carrying SQL/CQL binds through parameters; JSON decoding is lenient with a
polymorphic-unknown fallback (`UnknownNode`/`UnknownEdge`) rather than throwing — good
rolling-deploy behavior.

- **Identifier interpolation**: `$ysqlSchema` (`YugabytePersistentStore.kt:107` etc.) and
  `$ycqlKeyspace` (`YugabyteEphemeralStore.kt:62` etc.) are spliced into statement text. They're
  config, not user input, but they're the only unparameterized strings in the store layer — a
  one-line `require(schema.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))` in the constructors turns
  "trusted config" into "checked config". `Neo4jStorePlan.md` already plans exactly this for
  Cypher, so the awareness exists; apply it retroactively here.
- **Sample DDL ships credentials**: `ysql-schema.sql:1` creates user `abyss`/`abyss`;
  `ycql-schema.cql` uses `SimpleStrategy`/RF1. Obviously dev defaults, but the README's
  "Apply the schema scripts then:" (~line 490) doesn't say "change these in prod". One sentence.
- **TTL interpolation** `USING TTL $ttl` (`YugabyteEphemeralStore.kt:153,166`) is a number, not
  injectable — fine; the 1.2 validation incidentally also pins its range.
- Hazelcast cluster security (auth, TLS, who can join) is correctly left to the deployer; the
  README's "distinct clusterName to avoid auto-joining" (~line 264) is the only place touching
  it — a pointer that clusterName is *not* a security boundary wouldn't hurt.

## 5. Docs

Overall the best-documented small library I've reviewed in a while — README is honest about
costs (multi-schema overhead, benchmark corrections kept visible in TODO 3.5/3.6/4.6), and
`ai-scripts/` RFCs match shipped code closely. Two drift items stumbled on (background-only
scope, so not a systematic audit):

- **README sizing section is stale**: lines ~1071-1079 still budget a
  "reverse edge map (`IMap<ReverseEdgeKey, Unit>`)" and line ~1105 says "was ~5,000 before the
  reverse edge map was added" — `ReverseEdgeKey` was deleted by TODO 2.21; the adjacency map
  that replaced it has a different (larger-per-entry, sharded) memory shape. The 2.0 GB/1000
  users figure needs re-deriving or a "pre-2.21 numbers" disclaimer.
- `YugabyteEphemeralStore`'s default keyspace is `abyss_test_graph`
  (`YugabyteEphemeralStore.kt:43`) — a *test*-flavored name as the production default, while the
  YSQL default schema is plain `abyss`. Cosmetic, but it leaks into real deployments that don't
  override it.

## 6. Housekeeping

- Stale worktree `.claude/worktrees/abstract-sauteeing-backus` (branch
  `worktree-abstract-sauteeing-backus` @ e5c7e09) is still registered and doubles every
  source file in searches. If it's abandoned: `git worktree remove` + branch delete.
- `abyss-dsl` isn't Hazelcast-free: `EdgeKey.kt` implements `PartitionAware`, so the "DSL =
  interfaces" module story has one leak. Not worth restructuring; just don't advertise the
  module as engine-agnostic.

## 7. What's notably good (so it doesn't get "fixed")

- `SchemaResolution` as the *only* tier-varying seam — three container tiers without a base-class
  hierarchy. Textbook.
- Self-describing `NodeId` header killing the tag→schema registry; `SchemaDescriptor.of` as a
  pure function of the key.
- The empty-`AdjacencyValue`-as-warm-marker accident (`AdjacencyMutationProcessor` keeps the
  entry on last-remove rather than deleting it) — it makes a fully-unlinked node's adjacency
  read warm instead of re-hitting the store. Worth a comment so nobody "cleans it up" into a
  map-entry delete and reintroduces store hammering.
- Vendored murmur3 for shard assignment with the rationale spelled out (`AdjacencyHash.kt:5-8`).
- Perf claims in docs backed by committed, re-runnable `-Pperf` tests, with the benchmark bug
  that produced earlier wrong numbers documented rather than erased.

## Priority order

1. **1.1** serializer `!!` — one-token fix, latent data-integrity bug in clustered mode.
2. **1.2** sub-second TTL → immortal data — one `require`, verified failure mode.
3. **3.1** preload O(E) round trips — real cold-start cost at hub-node scale; fix is a deletion
   (drop the per-neighbor `readNode`) plus batching.
4. **2.1** document or CAS-guard `modifyNode`/`modifyEdge` — decide, then write it down.
5. **5** README sizing-section drift + keyspace default; **1.3** tag-TTL sentence.
6. Everything else as it gets touched.
