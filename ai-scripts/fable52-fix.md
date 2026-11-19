# Fix plan for the Fable 5.2 audit findings

Findings, mechanisms and probe source: `ai-scripts/fable52-findings.md`. Written 2026-11-14 against
`973a3e8`. Nothing in this plan is implemented.

## Status

- Six steps can start now: 1 (F4), 2 (F2), 3 (F3), 4 (F7), 9 (hygiene) and the first half of 7 (F1).
- Four decisions are open and block the steps that depend on them (section "Decisions").
- Every fix here is a caller-visible behaviour change in an error case. Each step lists it.

## Decisions

| # | Decision | Blocks | Recommendation |
|---|---|---|---|
| D1 | F1 remove race: tombstone or generation check, shared with TODO 1.36 | step 7b | Design it once, together with 1.36. It is new state. |
| D2 | F5: reject a transaction that adds an edge to a node it removes, or give it sequential semantics | step 5 | Reject with `IntegrityError`. |
| D3 | F6: key `toSubgraph` by `(class, id)`, or carry `NodeId` on `Path`/`Subgraph` | step 6 | `(class, id)` now, with the ceiling written down. |
| D4 | R1: an unknown edge type in the store fails the preload loudly, or is skipped | step 8 | Probe first, then decide. |

### D1. The remove race needs new state

- Step 7a (`putIfAbsent`) closes the stale-update half with no new state.
- The remove half cannot be closed by the write-back alone: after the remove the cache entry is absent, and
  an absent entry looks the same as "never cached".
- 1.36 names the fix for the index: tombstones or a remove-generation check. The value maps need the same
  thing.
- What it costs: a marker per removed key with a TTL longer than the longest store read, or a generation
  counter per key. Either is a new invariant on the write path.
- I found no way to close it from the existing read path. The store's `version` column does not help: a
  deleted row has no version to compare.

### D2. A transaction that removes a node and adds an edge to it

- **Reject (recommended):** when `checkIntegrity` is on, an `AddEdge` whose endpoint's last node op in the
  transaction is a `RemoveNode` returns `IntegrityError`. One pass over the ops, no change to the cascade.
  `removeNode(b); addNode(b); addEdge(a→b)` stays legal, because the last node op on b is the add.
- **Sequential semantics:** `addEdge; removeNode` would cascade the new edge away and `removeNode; addEdge`
  would fail. It needs the cascade to see the transaction's own adds, and `commitYsql` reorders ops by row,
  so op order is not a contract the store keeps today.
- `checkIntegrity = false` stays unchecked in both options.

### D3. What identifies a node in a `Subgraph`

- **`(class, id)` (recommended now):** one line. It fixes the heterogeneous case: user 5 and planet 5 are
  different classes.
- **Ceiling:** in a `HomogeneousSchemaGraph` the same class with the same id exists per tenant, so a
  cross-tenant path still collapses. `Path` holds domain objects and no `NodeId`, so nothing in it can tell
  the two apart.
- **`NodeId` on `Path`/`Subgraph`:** exact, but an API change on two public result types, and it touches
  the `paths {}` contract questions still open in TODO 1.35.

### D4. Unknown edge types during preload

- Today one `UnknownEdge` in a node's store rows throws `IllegalStateException` out of the preload.
- **Skip:** the edge cannot be indexed, because `AdjacencyEntry.edgeTypeTag` is a non-null `Short` and the
  member has no tag for it. Skipping and marking the node loaded pins an incomplete index.
- **Fail loudly, typed:** keep refusing, but as a `Left`, and do not mark the node loaded.
- No test reproduces R1 yet, so the first task of step 8 is the probe.

## Protocol for every step

1. Move the step's probe from the findings appendix into the named test file. Run it: red, on its own
   assertion.
2. Apply the fix. Probe green.
3. Run `./gradlew :abyss-graph:test`, then `./gradlew build` with the YB container up.
4. Mutation check: revert the fix, the probe must go red again.
5. No commit and no version bump until cane asks.

## Steps

### Step 1. F4: reject a tag that does not fit `tagWidth`

- **Change:** move the `fits` rule out of `SchemaKeyAdapter.init` into one function on the tag (for example
  `SchemaTag.fitsIn(width)`), and `require` it in both `SchemaKeyAdapter` and `HeaderlessSchemaKeyAdapter`.
- **Files:** `abyss-store-api/.../KeyAdapter.kt`.
- **Behaviour change:** `HomogeneousSchemaGraph.forTag` and annotation-driven cross edges throw
  `IllegalArgumentException` for an oversized tag, as `HeterogeneousSchemaGraph.register` already does.
- **Existing tests that will go red, and should:**
  - `MultiSchemaTest.kt:515-590` uses tags 501 and 502 at `SchemaTagWidth.BYTE`.
  - Check the `@CrossSchemaEdge` tags on `TenantLink` and `SameTenantLink` for the same values.
  - Fix: tags within 0..255, or `SchemaTagWidth.SHORT` for those containers.
- **Hot path:** `crossSchemaEndpoints` builds an adapter per cross-edge op, so the check runs there. It is
  two comparisons.
- **Red test:** probe B, into `HomogeneousSchemaTest`. Add an assertion that `forTag(SchemaTag(257))` at
  `BYTE` throws.
- **Boundary inputs:** 255 and 256 at `BYTE`; 65535 and 65536 at `SHORT`; a negative tag at `INT`; a tag
  with `hi != 0` at `LONG`.

### Step 2. F2: one adjacency entry per `(neighborId, edgeTypeTag)`

- **Change:** in `AdjacencyMutationProcessor`, `Add` removes every entry with the same
  `(neighborId, edgeTypeTag)` and inserts one. The surviving `nodeTypeTag` is the non-null one when either
  side has it. Call `setValue` only when the set changes, as today.
- **Files:** `abyss-graph/.../AdjacencyMutationProcessor.kt`. The comment in `AdjacencyKey.kt` becomes
  true.
- **No new state.** The serializer and the wire format do not change.
- **Other consumers of `nodeTypeTag`:** typed traversal reads it in `addNodeHop`, `filterFrontierByNode`,
  `filterFrontierByEdgeType` and `collectSubgraph`. The tag is never dropped or nulled by this change: a
  known tag always wins over null. Two different non-null tags for one neighbour cannot happen, because
  node type is immutable per id.
- **Existing duplicates:** the index is in memory. A duplicate pair heals on the next `Add` of that edge,
  on a `Remove`, or at restart.
- **Rolling deploy:** the processor runs on the member that owns the key. A member on the old code keeps
  the old behaviour for its partitions until it is replaced. Nothing breaks, the fix is just not everywhere
  until the roll completes.
- **Red tests:** probes A1 and A2, into `AdjacencyLoadedFlagTest` or a new `AdjacencyEntryIdentityTest`.
  A2 needs a store fake that returns `neighborType`; the appendix has one.
- **Add:** the reverse order (known tag first, null second) must keep the known tag. Assert on the raw
  `AdjacencyValue` in the map, not only on the read result.
- **Measure:** `Add` goes from a set insert to a scan of the shard's set. Run
  `AdjacencyPreloadPerformanceTest` and `OutEdgePagingPerformanceTest` (`-Pperf`) before and after and
  report both numbers.

### Step 3. F3: validate edge types before the commit

- **Change:**
  - add a nullable lookup to `TypeTagRegistry` (the existing `edgeTagOf` keeps throwing for read paths);
  - in `preCommit`, resolve the tag of every `RemoveEdge` type. Unknown type gives `Left(SchemaError)`
    with nothing committed;
  - `applyToCacheAsync` takes the resolved tags instead of calling `edgeTagOf` after the commit.
- **Files:** `TypeTagRegistry.kt`, `AbyssSchemaWorker.kt` (`transaction`, `batchTransaction`, `ephemeral`,
  `applyToCacheAsync`).
- **Behaviour change:** `removeEdge(a, b, "typo")` returns `Left(SchemaError)` instead of throwing after a
  commit. A member that does not know a type can no longer delete edges of that type; before, it deleted
  the store row and then threw.
- **Cascade removes** take their type from the index through `edgeNameOf`, so they are always known here.
- **Red tests:** probes C1 and C2, into `GraphTest` and `BatchTransactionTest`. C2's assertion changes to
  "the store holds neither node": the transaction must not commit at all.
- **Not in this step:** the general wrap of worker calls (R3, step 9).

### Step 4. F7: `UnknownNode` keeps its documented fallback

- **Change:** read `id` only when it is a JSON primitive and parses as a UUID; otherwise use the zero UUID
  the comment already promises.
- **Files:** `abyss-graph/.../serialization/AbyssSerializer.kt`.
- **Red test:** probe D, into `SerializationTest`. Add a String id and a structured (object) id.
- **Ceiling, unchanged:** on a non-UUID graph the unknown node's `id` is the zero UUID. The real id stays
  in `raw`.

### Step 5. F5: integrity check sees same-transaction removals (after D2)

- **Change, if D2 = reject:** `integrityError` builds, in one pass, each node id's last node op in the
  transaction. An `AddEdge` endpoint whose last op is `RemoveNode` returns `IntegrityError`.
- **Files:** `AbyssSchemaWorker.kt`.
- **Behaviour change:** both probe transactions return `Left` instead of `Right` with a dangling edge.
- **Red tests:** probes F1 and F2, into `CheckIntegrityTest`, asserting `Left(IntegrityError)` and that
  nothing was committed.
- **Guards:** `removeNode(b); addNode(b); addEdge(a→b)` stays `Right`; `checkIntegrity = false` stays
  unchecked.

### Step 6. F6: `toSubgraph` keeps nodes of different schemas (after D3)

- **Change, if D3 = `(class, id)`:** key nodes by `(it::class, it.id)`. Edges are already keyed with their
  type; add the edge class only if the cross-tenant ceiling is accepted as is.
- **Files:** `abyss-dsl/.../Extensions.kt`.
- **Red test:** probe E, into `PathsTraversalTest` or `AlgorithmsTest`.
- **Record the ceiling** next to the code with a `ponytail:` comment: same class and same id in two tenants
  still collapse, and the upgrade is `NodeId` on `Path`.

### Step 7. F1: cold-read write-back

- **7a, no new state, can start now:** the write-back in `loadAndCacheNode`, `loadAndCacheNodes` and
  `loadAndCacheEdge` becomes `putIfAbsent` (with the remaining TTL for ephemeral nodes). When an entry is
  already there, return that entry: a write landed during the store read and is newer.
  - Closes: a stale value overwriting a concurrent update.
  - Does not close: the remove race. Probe G stays red after 7a.
  - Red test for 7a: probe G's shape with an update instead of a remove. It has to be written and seen red
    first; the findings doc has this case by reading only.
  - Measure: `putIfAbsent` returns the previous value, `set` returns nothing. Cold path only. Run
    `NodeBatchLoadPerformanceTest` before and after.
- **7b, after D1:** the remove race, together with TODO 1.36. Probe G is its red test.
- **Files:** `AbyssSchemaWorker.kt`.

### Step 8. R1: unknown types in the worker (after D4)

- **First:** a probe. Seed a store fake with an edge row that decodes to `UnknownEdge` and a node that
  decodes to `UnknownNode`. Assert what `outEdges`, a traversal and an `addEdge` to that node do today.
- **Likely changes:**
  - `resolveEdgeEndpointTags` uses a nullable tag lookup, so an `UnknownNode` endpoint gives a null tag
    instead of a throw. That matches `schemaCheck`, which already tolerates `UnknownNode`.
  - preload and `hopOf`: per D4.
- **Files:** `AbyssSchemaWorker.kt`, `AnnotationCache.kt` if a nullable `typeTag` lookup is added.

### Step 9. Hygiene (R2 to R6, R8)

Each is small, each gets its own red test, in this order:

1. **R2 cancellation:** the five `catch (e: Throwable)` sites rethrow `CancellationException`. Test: cancel
   the caller while `modifyNode`'s read is suspended, assert the cancellation propagates and no `Left` is
   returned. Sites: `AbyssGraphSchema.kt:196`, `:225`, `:241`, and `transaction` in
   `HomogeneousSchemaGraph` and `HeterogeneousSchemaGraph`.
2. **R3 typed errors on writes:** the facade wraps `worker.transaction/batchTransaction/ephemeral` the way
   `node()` wraps reads, so a non-store exception is `Left(Unexpected)`. After step 3, so a validation
   error is not hidden behind `Unexpected`.
3. **R4 export:** `exportGraphLines` skips `NodeNotFound` and throws on any other `Left`, like `outEdges`
   on the next line already does.
4. **R5 scan parallelism:** `require(parallelism in 1..65536)` in `hashCodeRanges`; the YCQL scan needs the
   same lower bound.
5. **R6 client mode:** `SingleSchemaGraph()` passes `evictionVerifiedExternally` through.
6. **R8 comments:** correct the "one CQL logged batch" claim in `HazelcastEphemeralStore.kt`; delete the
   duplicated block in `AbyssSchemaWorker.kt:105-116`.

### Step 10. R7: endpoint reads on edge writes (measure first)

1. Write a test that isolates it: a `batchTransaction` of N edges between existing, cache-warm nodes,
   counting map operations and wall time.
2. Record the baseline.
3. Only if the baseline shows it: resolve all endpoints with one `nodesAt` call shared by `integrityError`
   and `resolveEdgeEndpointTags`.
4. Re-run and report before and after.

## Order

| Order | Step | Depends on |
|---|---|---|
| 1 | Step 1 (F4) | nothing |
| 2 | Step 2 (F2) | nothing |
| 3 | Step 3 (F3) | nothing |
| 4 | Step 4 (F7) | nothing |
| 5 | Step 7a (F1, update half) | nothing |
| 6 | Step 9 (hygiene) | step 3 for item 2 |
| 7 | Step 5 (F5) | D2 |
| 8 | Step 6 (F6) | D3 |
| 9 | Step 8 (R1) | D4 |
| 10 | Step 7b (F1, remove half) | D1, TODO 1.36 |
| 11 | Step 10 (R7) | its own baseline |

Steps 1 to 4 touch four different files and do not depend on each other.

## Out of scope

- Everything the findings document lists as already known.
- A live-Yugabyte run of the probes. The fakes model the store's contract; step 2's neighbour-type case
  rests on the `LEFT JOIN` returning null for a missing node, which `LoadTest` already covers.
- `TODO.md` entries and `ai/flow` items for these findings. Not created.
