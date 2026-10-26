# Performance

Measured results for the performance suites under `abyss-graph/src/test` (gated by `-Pperf`).

## How these numbers were measured

| | |
|---|---|
| Commit | `29d746d` (2026-10-26) |
| CPU | AMD Ryzen 5 2600, 6 cores / 12 threads |
| RAM | 32 GB |
| JVM | OpenJDK 21.0.12 |
| Libraries | Hazelcast 5.6.0, Kotlin 2.3.21, kotlinx-coroutines 1.9.0 |
| Topology | one embedded Hazelcast member in the test JVM, pure in-memory (no persistent store) |
| Runs | every suite run 3 times, each in its own Gradle invocation; cells show **median (min–max)** |

Things to keep in mind when reading them:

- **One JVM, one member.** Every `IMap` call is a local invocation. On a real cluster each call is a
  network round trip, so paths that make more map calls per operation look better here than they will
  in production.
- **Callers share the CPU with the code under test.** The concurrency sweeps run their callers as
  coroutines in the same JVM, so past ~6–12 callers the load generator competes with Abyss for the same
  12 hardware threads. The knee in those tables is a co-located worst case, not a server ceiling.
- **Every ring-suite result is checked.** Each timed call asserts what it returned (`outEdges` 5,
  `inEdges` 5, 3-hop 13 nodes — see `PerfRing.kt`), so a benchmark cannot silently time empty work.
- **Coarse 3-hop timing.** The single-threaded 3-hop figure is total elapsed milliseconds divided by
  200 traversals, so its resolution is about 0.1 ms.
- **Background load.** A YugabyteDB dev container was running during these runs. The previous edition's
  commit (`c0a80b9`), re-run on the same day under the same conditions, reproduced its published numbers
  within a few percent (see [Changes since the previous edition](#changes-since-the-previous-edition)), so
  the two editions are comparable.

## Fixtures

- **Ring (Uuid / Long / String / MultiSchema / LongSchemaConcurrency):** 10,000 nodes, each with 5
  outgoing edges to the next 5 nodes around the ring, seeded through `transaction { addNode / addEdge }`.
  A 3-hop walk from any node reaches exactly 13 distinct nodes.
- **Astronomy (AstronomyConcurrency):** the 2×-enlarged astronomy schema of the Universe fixture, inside a
  `HeterogeneousSchemaGraph` container. `outEdges`/`inEdges` hit random astronomy nodes; the 3-hop walk is
  moon → planet → star → singularity.

## Single-threaded, standalone schema

One caller; `outEdges` and `inEdges` over 2,000 random nodes, 3-hop over 200 random start nodes.

| Adapter | `outEdges` (ops/sec) | `inEdges` (ops/sec) | 3-hop traversal (ms avg) |
|---|---|---|---|
| `UuidKeyAdapter` | 2,919 (2,915–3,105) | 2,234 (1,809–2,280) | 1.9 (1.8–2.6) |
| `LongKeyAdapter` | 2,631 (2,347–3,144) | 2,049 (1,638–2,123) | 2.4 (2.0–2.7) |
| `StringKeyAdapter` | 2,475 (2,378–2,677) | 2,249 (1,666–2,257) | 1.8 (1.8–2.9) |

## Single-threaded, schema inside a multi-schema container

The same ring, `LongKeyAdapter`, registered inside a `HeterogeneousSchemaGraph`.

| | `outEdges` (ops/sec) | `inEdges` (ops/sec) | 3-hop traversal (ms avg) |
|---|---|---|---|
| Heterogeneous container, `LongKeyAdapter` | 1,990 (1,787–2,105) | 2,207 (1,947–2,472) | 2.5 (2.4–3.3) |
| Standalone `LongKeyAdapter` (from the table above) | 2,631 (2,347–3,144) | 2,049 (1,638–2,123) | 2.4 (2.0–2.7) |

`outEdges` inside the container runs at about **0.76×** standalone: its predicate compares the schema tag
plus the native value over the container's wider key layout. `inEdges` and 3-hop go through the adjacency
index, where that predicate isn't evaluated, and are not slower than standalone.

## Value serialization roundtrip

Isolated from `IMap` and partitioning: one `toData` + `toObject` of a single node or edge value, 200,000
roundtrips each (ns/op).

| Value | `Uuid` ids | `Long` ids | `String` ids | Uuid / Long |
|---|---|---|---|---|
| edge | 5,181 (4,755–5,805) | 2,596 (2,387–2,760) | 2,337 (2,188–2,397) | 2.0× |
| node | 2,556 (2,465–2,784) | 2,367 (2,183–2,501) | 2,256 (2,156–2,509) | 1.1× |

## Concurrency scaling

N concurrent callers, 200 operations each, ops/sec.

### Heterogeneous container (Astronomy fixture)

| N callers | `outEdges` | `inEdges` | 3-hop traversal |
|---|---|---|---|
| 1 | 970 (925–1,015) | 1,036 (480–1,092) | 722 (615–796) |
| 2 | 4,255 (3,225–4,347) | 3,030 (2,040–3,539) | 1,538 (1,346–1,587) |
| 4 | 9,090 (8,602–10,256) | 5,714 (4,848–6,722) | 3,030 (2,424–3,389) |
| 8 | 15,533 (15,533–17,777) | 9,815 (6,896–10,738) | 4,123 (3,106–4,597) |
| 16 | 18,181 (16,494–19,047) | 15,165 (11,721–17,679) | 5,673 (4,289–6,165) |
| 32 | 19,814 (15,802–22,299) | 18,233 (16,326–22,068) | 7,009 (4,996–7,600) |

`outEdges` at N=1 is the first sweep in the suite and carries JIT warm-up; read its N=1 cell as a lower bound.

### Standalone schema (`LongKeyAdapter`, ring)

| N callers | `outEdges` | `inEdges` | 3-hop traversal |
|---|---|---|---|
| 1 | 930 (865–1,010) | 1,550 (1,526–1,600) | 561 (546–641) |
| 2 | 3,252 (3,030–4,210) | 3,603 (3,539–3,809) | 833 (763–886) |
| 4 | 6,611 (5,839–7,272) | 6,504 (6,201–6,779) | 1,156 (1,106–1,331) |
| 8 | 10,191 (9,638–11,347) | 8,695 (7,960–9,696) | 1,492 (1,428–1,531) |
| 16 | 16,666 (16,243–16,753) | 12,167 (11,594–15,311) | 1,567 (1,422–1,802) |
| 32 | 18,338 (17,679–18,934) | 14,746 (14,285–16,452) | 1,650 (1,571–2,034) |

## Changes since the previous edition

The previous edition was measured at `c0a80b9` (2026-09-17). To separate code changes from machine drift,
`c0a80b9` and the current `HEAD` were re-run on the same day, interleaved per round, 3 rounds each
(medians). `c0a80b9` reproduced its own published numbers within a few percent (e.g. Astronomy `inEdges`
N=1: 1,960 now vs 2,020 published), so the differences below are code, not environment.

| Metric | `c0a80b9` | `HEAD` | `HEAD` / `c0a80b9` |
|---|---|---|---|
| `outEdges`, Uuid ring | 2,270 | 2,941 | 1.30× |
| `outEdges`, Astronomy N=8 | 7,305 | 16,842 | 2.31× |
| `outEdges`, LongSchema N=8 | 7,920 | 15,384 | 1.94× |
| `inEdges`, Uuid ring | 2,314 | 2,038 | 0.88× |
| `inEdges`, Astronomy N=1 | 1,960 | 1,219 | 0.62× |
| `inEdges`, LongSchema N=8 | 13,223 | 6,986 | 0.53× |
| 3-hop, Astronomy N=4 | 3,703 | 2,395 | 0.65× |

- **`outEdges` is 1.3–2.3× faster** — TODO 4.14 (the checked partition scan, and one scan with no index
  count in cache-only mode).
- **`inEdges` and Astronomy 3-hop regressed** — see Known gaps.

The `HEAD` cells in this table come from the same-day comparison run, not from the tables above; the two
runs differ within the ranges shown there.

## Known gaps

- **`inEdges` regression since `c0a80b9`.** `inEdges` under concurrency runs at 0.53–0.97× of `c0a80b9`,
  single-threaded at 0.88–0.94×; Astronomy 3-hop at 0.65–0.99×. Located between `c0a80b9` and `888b870^`:
  `888b870` (adjacency loaded flag) was measured on its own and made `inEdges` faster (1.08–1.30× on the
  Astronomy sweep), so it is not the cause. Not yet bisected. The prime suspect, unverified, is `03a012c`
  (TODO 1.34: `resolveEdges` heals evicted values), since every value-carrying `inEdges` hop and 3-hop step
  goes through `resolveEdges`.
- **3-hop on the ring stops scaling after N≈4.** It stays around 1,150–1,650 ops/sec from N=4 to N=32,
  while `inEdges` on the same ring keeps climbing. Not yet investigated.
- **Edge count per hop.** Earlier documentation published a per-hop fan-out table (K = 10 to 10,000 edges).
  No test in the repository reproduces it (`PerformanceTest.kt` is an empty stub), so it is not included here.

## Corrections to previously published numbers

Before commit `c0a80b9`, the ring suites seeded the Hazelcast maps directly instead of through transactions,
and never checked their results. As a result:

- every standalone and multi-schema **3-hop** figure timed a walk that found nothing after the first hop;
- the multi-schema **`inEdges`** figure timed empty results (the seed used the wrong edge type name);
- **`outEdges`** timed empty results once it began reading the adjacency index (TODO 1.26); before that it
  measured real work.

The concurrency-sweep 3-hop numbers for the standalone schema were affected the same way. The Astronomy
fixture always seeded through transactions and was not affected. All numbers on this page come from the
corrected suites.

## Reproduce

```
./gradlew :abyss-graph:test --tests "pl.iqtech.abyss.graph.UuidPerformanceTest" -Pperf
./gradlew :abyss-graph:test --tests "pl.iqtech.abyss.graph.LongPerformanceTest" -Pperf
./gradlew :abyss-graph:test --tests "pl.iqtech.abyss.graph.StringPerformanceTest" -Pperf
./gradlew :abyss-graph:test --tests "pl.iqtech.abyss.graph.MultiSchemaPerformanceTest" -Pperf
./gradlew :abyss-graph:test --tests "pl.iqtech.abyss.graph.SerdeRoundtripPerformanceTest" -Pperf
./gradlew :abyss-graph:test --tests "pl.iqtech.abyss.graph.AstronomyConcurrencyPerformanceTest" -Pperf
./gradlew :abyss-graph:test --tests "pl.iqtech.abyss.graph.LongSchemaConcurrencyPerformanceTest" -Pperf
```

Output is printed to the test's standard out; with Gradle's default logging it lands in
`abyss-graph/build/test-results/test/TEST-pl.iqtech.abyss.graph.<Suite>.xml`. Re-running the same suite
needs `:abyss-graph:cleanTest` first, or Gradle skips the test task as up to date.
