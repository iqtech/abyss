# TODO 2.35 — Metrics callback: persistence and cache-population observability

Status: **plan agreed 2026-10-26** (event list accepted "for now"). Not implemented.

## 1. Decision

Abyss exposes a plain callback interface, `AbyssMetrics`. No Micrometer (or any metrics library)
dependency. The host system wires its own backend; a Micrometer adapter is ~30 lines on the host side.

A library must not pick the metrics backend for its host. A callback also keeps Abyss's dependency
surface unchanged.

## 2. What is deliberately NOT instrumented

Other layers already see these. Duplicating them would add a callback to the hottest path for no new
information.

- **Raw cache hits/misses/latency.** Hazelcast's own map stats (`LocalMapStats`, Prometheus/JMX export).
  Caveat: with a client `HazelcastInstance` they are member-local, visible only on the cluster side.
- **Raw store latency/errors.** The host can decorate `AbyssStoreLike` (a plain interface) with zero
  Abyss code; driver/pool metrics cover the rest. The decorator must forward every method, including
  the defaulted ones (`loadNodes`, `batchTransaction`, scans), or it silently replaces an override with
  the slow default.

Abyss reports only what is invisible from outside: **why** the store was hit (self-heal verdicts) and
what the commit pipeline did to the cache afterwards.

## 3. Interface

```kotlin
// abyss-graph. Callbacks fire only on slow/semantic paths, never per cache hit.
// Contract: non-blocking, cheap; a throwing listener is caught and logged, never fails a commit.
interface AbyssMetrics {
    // Read path: cache miss → store self-heal
    fun storeLoad(kind: LoadKind, requested: Int, found: Int, failed: Boolean, nanos: Long) {}
    fun adjacencyPreload(direction: AdjacencyDirection, edges: Int, failed: Boolean, nanos: Long) {}
    fun outScanVerdict(complete: Boolean) {}       // cachedOutScan: false = heal path (eviction / cold)
    fun evictedEdgesHealed(count: Int) {}           // resolveEdges misses

    // Write path
    fun commit(kind: CommitKind, ops: Int, failed: Boolean, storeNanos: Long) {}
    fun cachePopulation(ops: Int, failed: Boolean, nanos: Long) {}   // async mode: fired on completion
    fun deleteFanoutFailed(kind: CommitKind) {}     // "stale ephemeral/persistent data possible"
    fun batchPartiallyCommitted(committed: Int, total: Int) {}

    companion object { val NONE: AbyssMetrics = object : AbyssMetrics {} }
}
enum class LoadKind { NODE, NODES, EDGE }
enum class CommitKind { TRANSACTION, BATCH, EPHEMERAL }
```

Design points:

- **Default no-op methods, not a sealed event plus `(AbyssEvent) -> Unit`.** No allocation per event;
  `NONE` is the only implementation in the common case, so the JIT inlines it to nothing.
  Kotlin 2.3 emits real JVM default methods (`-jvm-default=enable` is the default since 2.2), so a
  Java host overrides only what it needs.
- **Primitives only; time is `nanos: Long`.** The unit is in the parameter name (`System.nanoTime`
  deltas), so seconds and milliseconds cannot get mixed up at the boundary.
- **No schema or map name.** The listener is passed per container, so the host already knows the
  tenant and can bind tags at construction.
- **No node/edge type strings.** A cardinality trap for Prometheus-style backends. Add them on request.
- **`AdjacencyDirection` is already public** (`AdjacencyKey.kt:8`), so it can appear in the signature.

## 4. Call sites (`AbyssSchemaWorker`)

| Callback | Site | Notes |
|---|---|---|
| `storeLoad(NODE)` | `loadAndCacheNode` | `requested = 1`; `failed` = either side returned `Left` |
| `storeLoad(NODES)` | `loadAndCacheNodes` | `requested = nids.size`, `found` = result size |
| `storeLoad(EDGE)` | `loadAndCacheEdge` | per miss, including `resolveEdges` heals |
| `adjacencyPreload` | `preloadOut` / `preloadIn` | `edges` = loaded list size; `failed` = store `Left` |
| `outScanVerdict` | `cachedOutScan` | only on the store-backed branch; cache-only mode has no verdict |
| `evictedEdgesHealed` | `resolveEdges` | `missing.size`, fired only when > 0 |
| `commit` | `transaction` / `batchTransaction` / `ephemeral` | `storeNanos` = the store call only |
| `cachePopulation` | `populateCache` | see §5 |
| `deleteFanoutFailed` | the two `onLeft` fanout branches | replaces nothing; the log line stays |
| `batchPartiallyCommitted` | `batchTransaction` Left branch | `committed` from `BatchPartiallyCommitted` |

`failed` on the read side is unblocked by 1.38 (`29d746d`). The 7 worker read sites (incl.
`ephemeralStoreHops`) no longer collapse `Left` to null; they throw `StoreUnavailableException`.
Phase 1 reports `failed` at those sites just before the throw. The default store `loadNodes` returns
the first `Left` instead of swallowing per-id failures, so `storeLoad(NODES)` sees it too.

`outScanVerdict(false)` rate is the eviction-health signal. The TODO 4.14 count check already computes
it; today the result is discarded.

## 5. Async cache population

`populateCache` with `asyncCachePopulation = true` attaches `exceptionally` per stage and only logs.
Change: one `CompletableFuture.allOf(stages)`, then a single `whenComplete` reporting
`cachePopulation(ops, failed, nanos)` and logging once. One allocation per commit; the per-stage
`exceptionally` chain goes away. Sync mode reports after the existing `awaitAll`.

Silent cache staleness after a successful store commit is the second alert worth having (the first is
store read failures, surfaced as `StoreUnavailable` since 1.38).

## 6. Wiring

`metrics: AbyssMetrics = AbyssMetrics.NONE` on `AbyssSchemaWorker` and the four public constructors:
`AbyssGraphSchema`, `SingleSchemaGraph`, `HomogeneousSchemaGraph`, `HeterogeneousSchemaGraph`. The
default keeps everything source-compatible.

Listener invocation goes through one private helper that catches and logs (`log.warn`) any throwable.
A `try` costs nothing on the happy path. A listener exception must never skip cache population after a
store commit.

## 7. Phases

1. **Red probes** (in a new `MetricsCallbackTest`, using a counting listener and a fake store patterned
   on `OutEdgesEvictionTest`/`IndexAlwaysAliveTest`). Inputs break symmetry: odd op counts, a batch
   that fails at a non-zero, non-midpoint chunk.
   - Store returns `Left` on a cold node read → `storeLoad(NODE, failed = true)` fired once.
   - Evicted subset of out-edges (3 of 7) → `outScanVerdict(false)`, `evictedEdgesHealed(3)`.
   - Async population against a failing stage → `cachePopulation(failed = true)` fired exactly once.
   - Batch failing after 2 of 5 chunks → `batchPartiallyCommitted(committed, total)` with real numbers.
   - Throwing listener → the commit still returns `Right` and the cache is populated.
   All fail on the current code (no interface exists; the first one at compile time, so commit the
   interface with `NONE` first, then the probes fail on assertion).
2. **Implement** the interface, the helper, the call sites, and the wiring. Green, then the full suite.
   Remove each call site in turn: its probe must go red.
3. **Measure** (perf rule): warm-read and commit benchmarks with `NONE` vs. a counting listener, before
   and after. Expected: no measurable change on warm reads (no callback on the hit path); a `nanoTime`
   pair plus one virtual call per slow-path event. A claim without the numbers gets deleted.

## 8. Parked

- Per-hit counters (Abyss-level hit ratio). Hazelcast stats cover this until shown otherwise.
- Per-type tags on events.
- Store-layer callbacks inside `YugabytePersistentStore` (retries, pool waits). The host decorator and
  driver metrics cover these.
- Traversal-level metrics (hops, fan-out, frontier sizes). A separate question from persistence.
