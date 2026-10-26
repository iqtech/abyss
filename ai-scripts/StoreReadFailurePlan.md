# TODO 1.38 — Store read failures must surface as `StoreUnavailable`, never as "absent"

Status: **implemented 2026-10-26.** Deviations from the plan below: the container probe uses
`HeterogeneousSchemaGraph` (its Hazelcast instance is public in tests; `crossEdgeExists` is identical in
both containers). The guard-removal run found 4 guards without a probe (`preloadIn`, the ephemeral side of
`loadAndCacheNodes`/`loadAndCacheEdge`, the ephemeral store-api default), so 4 more probes were added: 18
in total, and every one of the 14 guards turns at least one red. Full suite 444/444.

## Context

Every self-heal read in `AbyssSchemaWorker` collapses a store `Left` to null via `getOrNull()`. On a cache
miss (eviction or cold start) with the store failing, reads return `Right` with wrong data:
`nodeExists` → `Right(false)`, traversal → `Right([])`, `removeNode` commits and leaves edges dangling,
and `modifyNode` runs `transform(null)` and **overwrites the stored node** (data loss).

Agreed:
- New `AbyssError.StoreUnavailable(error: AbyssError)` (already added to `abyss-store-api`).
- **Any** `Left` fails the read: persistent or ephemeral side, even if the other side answered.
- Failure leaves `Flow`/engine paths as a public `StoreUnavailableException`. Two fixes agreed: it lives in
  `abyss-graph` (stores return `Left`, never throw it) and carries the underlying `Throwable` as `cause`.

Public signatures: unchanged. Behaviour: reads that lied now return `Left(StoreUnavailable)`; `Flow`
reads throw on collect.

State now: `AbyssError.kt` has the variant **and** the exception (exception to be moved);
`StoreReadFailureTest.kt` has 10 probes, all red on their assertions (verified by execution).

## Step 0 — apply the two agreed fixes

- Move `StoreUnavailableException` out of `abyss-store-api/.../AbyssError.kt` into a new
  `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/StoreUnavailable.kt`, with the one helper every catch
  site will use:
  ```kotlin
  class StoreUnavailableException(val error: AbyssError.StoreUnavailable) :
      RuntimeException("Store unavailable: ${error.error}", (error.error as? AbyssError.Unexpected)?.cause)

  internal fun Throwable.toAbyssError(): AbyssError = (this as? StoreUnavailableException)?.error ?: AbyssError.Unexpected(this)
  internal fun AbyssError.unavailable(): Nothing = throw StoreUnavailableException(AbyssError.StoreUnavailable(this))
  ```
- Update the test import (`pl.iqtech.abyss.graph.StoreUnavailableException`, same package; drop it).

## Step 1 — extra red probes (in `StoreReadFailureTest`)

Each must fail on the current code by assertion:
- `modifyNode` on an evicted node with the store down → `Left(StoreUnavailable)`, and the stored node is
  unchanged. Today: `Right`, node overwritten by `transform(null)`.
- `outEdges(includeEphemeral = true)` with the ephemeral store's `loadEdges` failing → throws
  `StoreUnavailableException` (site 7, `ephemeralStoreHops`). The fake ephemeral store needs a
  `loadEdges` override. Today: completes with persistent edges only.
- **Post-commit ordering guard:** `transaction(checkIntegrity = false) { addEdge(a → e) }` with `e`
  evicted and store reads down → `Left(StoreUnavailable)` **and** the store holds no `a-e` edge. Today:
  `Right`, edge committed. A naive fix (throwing from `resolveNodeTag` after the commit) would return
  `Left` with the edge committed — this probe catches that too.
- One container site: `HomogeneousSchemaGraph.crossEdgeExists` with the store down → `Left(StoreUnavailable)`.
  Today: `Right(false)`. Construct like the existing container tests (`MultiSchemaTest`).

## Step 2 — implementation

**Store API defaults** (`abyss-store-api/.../AbyssStoreLike.kt:33,75`): `loadNodes` in both interfaces
returns the first `Left` instead of dropping the id (arrow `either { … bind() }` or an explicit loop).

**Worker read sites** (`AbyssSchemaWorker.kt`), each `getOrNull()` becomes "Right value, or
`.unavailable()`":
| Site | Line (current) | Note |
|---|---|---|
| `ephemeralStoreHops` | 262 | ephemeral `loadEdges` |
| `preloadOut` / `preloadIn` | 407 / 423 | throw before `markLoaded` (it already isn't marked) |
| `loadAndCacheNode` | 641-642 | both `async`s; either `Left` throws, siblings cancel |
| `loadAndCacheNodes` | 661-662 | same |
| `loadAndCacheEdge` | 682-683 | same |

Missing-store case (`persistentStore == null`) keeps returning null: no store is not a failure.

**Worker commits** (`transaction` / `batchTransaction` / `ephemeral`, ~445/474/499):
- Catch `StoreUnavailableException` around the pre-commit phase (cascade expansion, integrity check, tag
  resolution) and return `Left(e.error)`. Only this type: other throwables keep today's behaviour (out of
  scope).
- **Resolve AddEdge endpoint tags before the store commit.** Today `populateCache` →
  `applyToCacheAsync` → `resolveNodeTag` → `readNode` runs after the commit. Compute
  `Map<NodeId, Short?>` for all AddEdge endpoints (using `addedInTx` first, as now) in the pre-commit
  phase and pass it into `populateCache`/`applyToCacheAsync`; `resolveNodeTag` reads the map. Same reads,
  earlier, so a failure means nothing was committed. Applies to the partial-batch `populateCache` call too.
- `AbyssError.cause()` (line 439): add `is AbyssError.StoreUnavailable -> error.cause()` so the error logs
  keep the stack trace.

**Facade catch sites**: replace `AbyssError.Unexpected(it)` / `Unexpected(e)` with `it.toAbyssError()` /
`e.toAbyssError()` at every `Either.catch` and `buffer.block()` catch in `AbyssGraphSchema.kt`
(133-175, 179, 208, 224), `HomogeneousSchemaGraph.kt` (106, 138, 141-144), `HeterogeneousSchemaGraph.kt`
(100, 138, 141-144). Not `HazelcastEphemeralStore` — it's a store and keeps returning `Unexpected`.
Re-grep for `Unexpected(` in `abyss-graph/src/main` after editing; only the store's sites remain.

**Docs**: KDoc on `outEdges`/`inEdges` (facade interface) and on `NodeIdEngine`: "throws
`StoreUnavailableException` on collect when a store read fails". Update TODO 1.38 with site 7, the
`modifyNode` data loss, and the tag-resolution ordering.

## Step 3 — verification

1. `./gradlew :abyss-graph:test --tests '*StoreReadFailureTest'`: all 14 green.
2. Full suite `./gradlew test` (YB tests need the dev container from sniper's compose). Report counts.
3. Guard removal, one at a time, revert after each: restore `getOrNull()` at each worker site; restore
   the store-api default; move tag resolution back after the commit; drop the worker commit catch; revert
   one facade helper call. Each must turn at least one probe red. Record which.
4. Run `PathsSilentDropAuditTest` part 2 and compare the S2–S6 lines: store-down scenarios should now print
   `Left(StoreUnavailable(...))`.
5. Perf: tag pre-resolution only moves existing reads; no benchmark needed unless the full suite's
   existing perf tests move. If they do, report before/after.

## Critical files

- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AbyssSchemaWorker.kt`
- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/{AbyssGraphSchema,HomogeneousSchemaGraph,HeterogeneousSchemaGraph}.kt`
- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/StoreUnavailable.kt` (new)
- `abyss-store-api/src/main/kotlin/pl/iqtech/abyss/store/api/{AbyssError,AbyssStoreLike}.kt`
- `abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/StoreReadFailureTest.kt`
- `TODO.md`, `ai-scripts/StoreReadFailurePlan.md`
