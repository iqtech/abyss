# Multi-member Hazelcast cluster test (TODO 2.1)

## Why

TODO.md 2.1 ("Single Hazelcast node") flagged that every test in `abyss-graph` ran against exactly
one embedded Hazelcast member. That made several load-bearing pieces of the engine trivially
"correct" only because every partition happened to be local:

- `EdgeKey`/`AdjacencyKey` `PartitionAware` co-location (all `EdgeKey`s sharing a `fromId`, all
  `AdjacencyKey`s sharing a `nodeId`, are meant to land on one partition).
- `AbyssSchemaWorker.outAt`'s fast path (`Predicates.partitionPredicate(...)`), `outEdgeFlow`'s
  partitioned scan, `adjacencyRead`'s batched cross-shard `getAll`, and `cascadeEdgeRemovals`'s
  OUT/IN scans — all designed around partition-scoped reads that only get genuinely exercised once
  ownership can actually be remote.

This was scoped as a **test-only** change validating existing, already-shipped behavior — no
production code was expected to change, and none did.

## Two corrections found while designing the test (would have made a naive version wrong)

1. **`EdgeKey`'s constructor default `pk = fromId.toString()` is never used in production.**
   `AbyssSchemaWorker.edgeKey()`/`outKeyFor()`/`inKeyFor()` always pass an explicit
   `edgeAdapterOf(nid).partitionKey(nid)`. For `HeaderlessKeyAdapter(LongKeyAdapter)` (the adapter
   this test uses), that resolves to `inner.nativePartitionValue(fromNodeId(nodeId))`, and
   `LongKeyAdapter` doesn't override `nativePartitionValue`, so the real partition key is a raw
   `Long` — not the string a bare `EdgeKey(...)` constructor call would produce. The test never
   hand-constructs `EdgeKey`/`AdjacencyKey`; it pulls the real key objects back out of the live
   `IMap`s after writing through the public `transaction { }` API and queries the partition service
   on those actual objects (`EdgeKey`/`AdjacencyKey` `equals`/`hashCode` ignore `pk`, so filtering
   the returned key set by `.fromId`/`.nodeId` is safe). This also proves, for free, that the
   fast-path partition key and the write-path partition key can never disagree — both call the same
   private `edgeAdapterOf(nid).partitionKey(nid)`.
2. **This repo's `test` task is JUnit4** (`kotlin("test")` → `kotlin-test-junit` → `junit:junit`, no
   `useJUnitPlatform()` anywhere). Teardown needed JUnit4's `@AfterClass` on a `@JvmStatic`
   companion function, not JUnit5's `@AfterAll` — unlike every other Hazelcast fixture in this repo
   (lazy `val`, shared for the whole JVM run, never torn down), this test's 3 real members bind real
   sockets and must be explicitly shut down.

## What shipped

- **`abyss-graph/build.gradle.kts`**: one line bridging `-Pcluster` to a `cluster` system property,
  mirroring the existing `-Pperf` gate.
- **`abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/MultiMemberClusterTest.kt`** (new): starts a
  real 3-member in-process Hazelcast cluster (companion-object lazy singleton, `@AfterClass`
  teardown), gated behind `-Pcluster` exactly like the perf suite (`if (System.getProperty("cluster") == null) return`
  at the top of each `@Test`).
  - **Test A** (`multi-hop traversal, incoming edges, and cascade delete are correct across a real
    3-member cluster`): hub/mid/leaf/other graph, driven through `outEdgeFlow`, `outAt`'s fast path
    (via a predicate-form `outgoing<E> { }` hop), `adjacencyRead` (`inEdges`), and
    `cascadeEdgeRemovals` (`removeNode`) — all now genuinely crossing member boundaries instead of
    trivially resolving locally.
  - **Test B** (`EdgeKey and AdjacencyKey partitions co-locate per node and are genuinely spread
    across cluster members`): seeds 60 nodes / 240 edges (4 per node, ring topology), then for each
    node asserts every real `EdgeKey`/`AdjacencyKey` sharing that node's id maps to exactly one
    partition ID, and that the sampled owning members span **≥2 distinct members** — the assertion
    that rules out a degenerate "looked like it worked but never actually spread" false pass.

## Interop snags hit along the way

`IMap`'s `keySet()`/`entrySet()` are declared on both `ConcurrentMap` (inherited, zero-arg) and
`BaseMap` (zero-arg **and** a `Predicate`-taking overload) — this confuses Kotlin's overload
resolution (`No value passed for parameter 'p0'`). Kotlin's `.map { }` extension is separately
ambiguous on `IMap` since it's simultaneously a `Map` and an `Iterable<Map.Entry<K,V>>`. Worked
around by iterating the `Iterable` side directly with a plain `for` loop
(`buildSet { for (e in imap) add(e.key) }`), which only has one unambiguous candidate.

## Result

- `./gradlew :abyss-graph:test -Pcluster --tests MultiMemberClusterTest` — both tests pass (~6s;
  real transactions logged: 300 ops for the seed graph, 7+3 ops for the cascade-delete graph).
- `./gradlew :abyss-graph:test --tests MultiMemberClusterTest` (no `-Pcluster`) — both tests
  short-circuit in ~0.01s, no Hazelcast members started, confirming the gate.
- Full 26-class `abyss-graph` suite green both with and without `-Pcluster`.

No production code changed — this closes TODO.md 2.1 as a validated (not just assumed) property of
the existing partitioning design.
