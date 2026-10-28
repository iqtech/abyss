# AbyssMetrics callback for persistence and cache population

Created: 2026-10-27

From TODO 2.35.

A plain callback interface with default no-op methods and `AbyssMetrics.NONE`; no Micrometer
dependency, the host wires its own backend. Fires only on slow/semantic paths: store self-heal loads,
adjacency preloads, the `cachedOutScan` verdict (the eviction-health signal), evicted-edge heals,
commits, cache population (async mode reported once on completion), delete-fanout failures, partial
batch commits. Raw cache hits and raw store latency are left to Hazelcast map stats and a host-side
`AbyssStoreLike` decorator. Event list agreed 2026-10-26. Read-side `failed` hooks 5 of the 6 worker read
functions that throw `StoreUnavailableException` since 1.38 (`29d746d`), once per function; the
store-only `ephemeralStoreHops` is left to the host's store decorator. Plan, call sites, red probes, and the
before/after benchmark: `ai-scripts/MetricsCallbackPlan.md`.
