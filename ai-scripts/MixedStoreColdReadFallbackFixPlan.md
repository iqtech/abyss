# Plan: fix mixed-store cold-read fallback bug (TODO 1.29, item 1)

## Context

`AbyssSchemaWorker.loadAndCacheNode`/`loadAndCacheEdge` (`abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AbyssSchemaWorker.kt:519-548`) are the self-heal path that runs on a cold-cache read (after eviction, restart, or partition migration) when a `persistentStore` and an `ephemeralStore` are both configured. They select between the two stores with:

```kotlin
val fromPersistent = async(Dispatchers.IO) { persistentStore?.loadNode(nid)?.getOrNull() }
val fromEphemeral  = async(Dispatchers.IO) { ephemeralStore.loadNode(nid).getOrNull() }
fromPersistent.await() ?: fromEphemeral.await()
```

`loadNode`/`loadEdge` return `Either<AbyssError, Pair<T?, Duration?>>`. A genuine miss is `Right(Pair(null, null))` — a **non-null** `Pair`. `.getOrNull()` only strips the outer `Either`, so `fromPersistent.await()` is never Kotlin-`null` once a `persistentStore` is configured, and the `?:` never falls through to `fromEphemeral` — its result is computed (wasted work) and discarded. Net effect: once a persistent store is configured, any node/edge that only lives in the ephemeral store reads as not-found on any cold-cache lookup. Invisible while the cache stays warm (reads hit the cache, this path never runs), which is why it went unnoticed — found and reproduced live during this session's audit sweep of TODO.md's ✅ ledger (full writeup: `ai-scripts/ConsistencyAuditFindings.md`, item 1).

Checked every other `.getOrNull()` call site in `abyss-graph`/`abyss-store-api`/`abyss-store-yugabyte` (this session) — no other instance of this bug. The other sites either operate on Kotlin's `Result.getOrNull()` (unrelated type), or on `Either<Error, List<T>>` where an empty list is already a correct "nothing here" signal. So the fix is contained to these two functions — no store-contract change needed.

## Implementation

File: `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AbyssSchemaWorker.kt`

Replace the ambiguous elvis-on-Pair with an explicit check on the Pair's first element, in both functions:

**`loadAndCacheNode`** (line 521-523):
```kotlin
val fromPersistent = async(Dispatchers.IO) { persistentStore?.loadNode(nid)?.getOrNull() }
val fromEphemeral  = async(Dispatchers.IO) { ephemeralStore.loadNode(nid).getOrNull() }
val p = fromPersistent.await()
if (p?.first != null) p else fromEphemeral.await()
```

**`loadAndCacheEdge`** (line 536-538): same shape —
```kotlin
val fromPersistent = async(Dispatchers.IO) { persistentStore?.loadEdge(fromNid, toNid, type)?.getOrNull() }
val fromEphemeral  = async(Dispatchers.IO) { ephemeralStore.loadEdge(fromNid, toNid, type).getOrNull() }
val p = fromPersistent.await()
if (p?.first != null) p else fromEphemeral.await()
```

`p?.first != null` covers both cases in one check: `p == null` (no persistent store configured, or the persistent lookup errored — `.getOrNull()` already returns `null` on `Left`) falls through to ephemeral, same as today; `p.first == null` (genuine persistent miss) now *also* falls through to ephemeral, which is the fix. Everything downstream (the `?: return null` / `node ?: return null` unpacking) is unchanged — it already correctly handles a genuine dual-miss.

## Test

Add one regression test to `abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/MultiSchemaTest.kt`, reusing what's already there:

- **Persistent side**: `RecordingStore` (already defined in this file, line ~755) with `seededNodes` left empty — its `loadNode` already returns `Either.Right(seededNodes[id] to null)`, so an empty map gives the exact genuine-miss shape (`Right(Pair(null,null))`) the bug needs.
- **Ephemeral side**: `RecordingEphemeralStore` (same file, line ~779) is hardcoded to always return a miss — not reusable as-is. Add a small local fake ephemeral store in the same test (or a minimal new private class) whose `loadNode`/`loadEdge` return a seeded hit.
- **Construction**: mirror the existing dual-store pattern at line ~559-560 — `HeterogeneousSchemaGraph(hz, ..., persistentStore = recordingStore, ephemeralStore = seededEphemeralFake, ...)`.
- **Assertion**: on a cold graph (no prior write/read through this instance), call `graph.node(id)` (or the equivalent read) for a node that only the ephemeral fake has, and assert it comes back found — this fails today (returns not-found) and should pass after the fix. Cover both the node and edge path if it's cheap to do in one test; otherwise one test per function is fine.

## Verification

1. Run the new test before the fix to confirm it fails (proves it actually reproduces the bug).
2. Apply the fix.
3. `./gradlew :abyss-graph:test --tests "*MultiSchemaTest*"` — new test passes, nothing else regresses.
4. `./gradlew :abyss-graph:test` — full module run, since this touches a widely-used self-heal path (`readNode`/`readEdge`'s cache-miss fallback).
5. Update `ai-scripts/ConsistencyAuditFindings.md` item 1 and TODO 1.29 to reflect this sub-item is fixed (don't mark the whole 1.29 done yet — items 2-5 are still open).

Not in scope: items 2-5 from the audit (batch-cache staleness, index-always-alive self-heal gaps, integrity-check TOCTOU, stale TODO 1.5 doc) — separate, to be planned individually per the agreed one-at-a-time order. No version bump, no commit/push unless asked.
