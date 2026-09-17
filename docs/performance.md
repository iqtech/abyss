# Performance

Measured results for the performance suites under `abyss-graph/src/test` (gated by `-Pperf`).

## How these numbers were measured

| | |
|---|---|
| Commit | `c0a80b9` (2026-09-17) |
| CPU | AMD Ryzen 5 2600, 6 cores / 12 threads |
| RAM | 32 GB |
| JVM | OpenJDK 21.0.11 |
| Libraries | Hazelcast 5.6.0, Kotlin 2.3.21, kotlinx-coroutines 1.9.0 |
| Topology | one embedded Hazelcast member in the test JVM, pure in-memory (no persistent store) |
| Runs | every suite run 3 times; cells show **median (min–max)** |

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
| `UuidKeyAdapter` | 2,083 (2,008–2,129) | 2,265 (2,087–2,439) | 1.7 (1.7–1.9) |
| `LongKeyAdapter` | 2,207 (2,159–2,358) | 2,320 (2,197–2,427) | 1.8 (1.8–1.9) |
| `StringKeyAdapter` | 1,962 (1,945–2,098) | 2,333 (2,222–2,336) | 2.0 (1.9–2.7) |

## Single-threaded, schema inside a multi-schema container

The same ring, `LongKeyAdapter`, registered inside a `HeterogeneousSchemaGraph`.

| | `outEdges` (ops/sec) | `inEdges` (ops/sec) | 3-hop traversal (ms avg) |
|---|---|---|---|
| Heterogeneous container, `LongKeyAdapter` | 1,524 (1,436–1,600) | 2,844 (2,797–3,053) | 1.6 (1.5–1.7) |
| Standalone `LongKeyAdapter` (from the table above) | 2,207 (2,159–2,358) | 2,320 (2,197–2,427) | 1.8 (1.8–1.9) |

`outEdges` inside the container runs at about **0.69×** standalone: its predicate compares the schema tag
plus the native value over the container's wider key layout. `inEdges` and 3-hop go through the adjacency
index, where that predicate isn't evaluated, and are not slower than standalone.

## Value serialization roundtrip

Isolated from `IMap` and partitioning: one `toData` + `toObject` of a single node or edge value, 200,000
roundtrips each (ns/op).

| Value | `Uuid` ids | `Long` ids | `String` ids | Uuid / Long |
|---|---|---|---|---|
| edge | 4,644 (4,590–4,651) | 2,437 (2,376–2,497) | 2,244 (2,174–2,369) | 1.91× |
| node | 2,468 (2,430–2,512) | 2,179 (2,121–2,249) | 2,241 (2,156–2,243) | 1.13× |

## Concurrency scaling

N concurrent callers, 200 operations each, ops/sec.

### Heterogeneous container (Astronomy fixture)

| N callers | `outEdges` | `inEdges` | 3-hop traversal |
|---|---|---|---|
| 1 | 680 (666–701) | 2,020 (1,904–2,105) | 925 (896–947) |
| 2 | 2,312 (2,285–2,500) | 5,063 (4,878–5,194) | 1,532 (1,365–1,724) |
| 4 | 5,369 (5,298–5,594) | 8,421 (6,611–8,888) | 3,652 (3,404–3,703) |
| 8 | 7,174 (7,017–7,339) | 12,500 (11,428–12,800) | 5,228 (4,776–5,423) |
| 16 | 8,533 (7,459–8,602) | 17,777 (17,021–18,181) | 7,223 (6,808–7,710) |
| 32 | 11,786 (10,596–12,623) | 22,222 (22,068–23,443) | 8,247 (6,794–10,000) |

`outEdges` at N=1 is the first sweep in the suite and carries JIT warm-up; read its N=1 cell as a lower bound.

### Standalone schema (`LongKeyAdapter`, ring)

| N callers | `outEdges` | `inEdges` | 3-hop traversal |
|---|---|---|---|
| 1 | 1,333 (970–1,449) | 2,857 (2,777–2,985) | 800 (790–813) |
| 2 | 3,333 (2,684–3,361) | 5,405 (5,194–5,479) | 1,257 (1,063–1,257) |
| 4 | 5,673 (4,597–5,882) | 9,195 (7,843–9,756) | 1,374 (1,292–1,423) |
| 8 | 7,843 (6,837–7,920) | 13,913 (12,030–16,000) | 1,376 (1,325–1,474) |
| 16 | 10,666 (8,913–10,702) | 18,497 (17,021–21,192) | 1,581 (1,565–1,641) |
| 32 | 13,034 (11,428–13,061) | 20,447 (18,390–22,145) | 1,912 (1,850–2,065) |

## Known gaps

- **`outEdges` under concurrency.** `outEdges` checks its cached partition scan against the adjacency
  index's entry count, so a value evicted from the cache is never silently dropped. That check is a second
  partition operation per call; with many concurrent callers it is the bottleneck, and `outEdges` scales
  worse than `inEdges` in both sweeps above. Being investigated (TODO 4.14).
- **3-hop on the ring stops scaling after N≈4.** It stays around 1,400–1,900 ops/sec from N=4 to N=32,
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
`abyss-graph/build/test-results/test/TEST-pl.iqtech.abyss.graph.<Suite>.xml`.
