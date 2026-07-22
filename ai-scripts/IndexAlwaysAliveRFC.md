# RFC — "Index always alive": authoritative topology, evictable values

## Context

TODO 1.26 gave the adjacency index a pluggable seam (`AdjacencyIndex`) and made reads bounded. This RFC
settles the **eviction story** that fell out of that work: what may be evicted from the Hazelcast maps
under memory pressure, and what may not.

The graph lives in three Hazelcast maps:

| Map | Holds | Size per element | Role |
|---|---|---|---|
| `…-adjacency` | `AdjacencyKey → Set<AdjacencyEntry>` (neighborId + 2 tags) | ~24 B/edge | **topology** |
| `…-edges` | `EdgeKey → EdgeLike` (full payload) | 100s of B | value |
| `…-nodes` | `NodeId → NodeLike` (full payload) | 100s of B | value |

The insight: **topology is small and structural; values are large and reloadable.** So keep the cheap
thing resident and let the expensive thing page. Concretely:

- **Adjacency index = the authoritative in-memory topology.** Never evicted. Warmed from the store on a
  cold miss (`preloadOut`/`preloadIn`), and once warmed for a node it is complete and stays resident.
- **`…-edges` / `…-nodes` = an evictable cache over the durable store.** Sized to memory; entries
  reload on demand.

## What it buys

- **`needValue=false` traversal is pure-index.** Reachability, `hasOutgoing`, path existence, `count`,
  and typed filters (the entry carries `nodeTypeTag`) run entirely off the never-evicted adjacency
  index — **zero reads of the value maps, zero store round trips.** This is the "traverse at speed" path.
- **Values size independently of topology.** Memory for payloads is a tuning knob that no longer risks
  the graph's structural correctness.

## The invariant flip (the one required code change)

Today a null during the batched edge-value fetch (`adjacencyHopFlow`, `needValue=true`) means "the edge
was removed" → skip. Under this RFC the **adjacency index is the existence authority**, so:

> a null value with a **present** adjacency entry means **"evicted, not removed" → reload from the
> store**, not skip.

The change is localized: in the `needValue=true` batch, collect the keys that came back null, do **one**
batched `loadEdges`/`loadEdge` from the store, merge, emit. Batched — it does **not** reintroduce the
per-edge fan-out the index exists to avoid. Nodes already do this per-key (`readNode` = `getAsync ?:
loadAndCacheNode`), so `…-nodes` eviction is *already* safe; only the batched edge path needs it.

## Ephemeral edges leave the index (composition with the `includeEphemeral` flag)

Never-evicting the index makes the TODO 1.26(a) shortcut — writing an OUT adjacency entry for ephemeral
(TTL) edges — **permanently** leaky: the untimed entry can never be reclaimed by eviction. So under this
RFC ephemeral edges are **not** in the adjacency index at all. Traversal opts into them with an
`includeEphemeral` flag on `outgoing()`/`outAt` (OUT-only — ephemeral is outgoing-only), sourced from
the ephemeral store, which is the only representation that survives cache TTL. Bonus: with **only
persistent edges** in the index, an index-null is **unambiguously** "evicted → reload" — never
"expired ephemeral," so the self-heal above needs no disambiguation. (This supersedes the
ephemeral-in-adjacency write from TODO 1.26(a); see `OutEdgePagingAndHopStreamingPlan.md`.)

---

## ⛔ FORBIDDEN: eviction on the adjacency map

**`hazelcast.yaml` (or any `MapConfig`) MUST NOT configure eviction — no `max-size`, no
`eviction-policy` (LRU/LFU/RANDOM), no map-level `time-to-live-seconds` — on the `*-adjacency` map.
Doing so silently corrupts every traversal.**

This is not a performance caveat. It is a **data-correctness** invariant, and the failure is silent:

1. Eviction on a Hazelcast IMap evicts **individual entries** (per `AdjacencyKey`, i.e. per
   `(node, direction, shard)`) under memory pressure — not whole nodes.
2. The self-heal warm-check is **per-node, all-or-nothing, and only inspects the first shard window**
   (`ShardedAdjacencyIndex.isEmpty`: a non-empty first window ⇒ "warm", returns `false`). It **cannot
   detect partial eviction.** A node whose window-0 shards survive but whose later shards were evicted
   reads as fully warm.
3. Result: the read returns the **surviving shards only** — a **silently incomplete neighbor set**. No
   exception, no log, no integrity signal. `reaches` returns false negatives; `outEdges` drops edges;
   `subgraph` is missing nodes. The graph lies about its own structure.

Unlike a value miss (which self-heals from the store), a partially-evicted *index* is never re-warmed,
because step 2 believes it is already warm. The store still physically has the edges — but the running
system will never go ask for them. From the application's perspective this **is** data loss: correct
data, permanently unreachable through queries, undetectably.

### Enforce it, don't just document it

A comment in `hazelcast.yaml` is too weak for a footgun this quiet. **On startup, read the effective
`MapConfig` for the adjacency map and fail fast** — refuse to boot — if any eviction/max-size/TTL is
set on it:

```kotlin
val cfg = hazelcast.config.findMapConfig(adjacencyMapName)   // resolves wildcard/default configs too
require(
    cfg.evictionConfig.evictionPolicy == EvictionPolicy.NONE &&
    cfg.timeToLiveSeconds == 0 &&
    cfg.maxIdleSeconds == 0
) {
    "Eviction/TTL is configured on the adjacency map '$adjacencyMapName' " +
    "(evictionPolicy=${cfg.evictionConfig.evictionPolicy}, ttl=${cfg.timeToLiveSeconds}s, " +
    "maxIdle=${cfg.maxIdleSeconds}s). This silently corrupts traversal — partial eviction is " +
    "invisible to the per-node warm-check, so reads return an incomplete neighbor set with no error. " +
    "Remove all eviction from this map; evict '$edgesMapName'/'$nodesMapName' instead (those " +
    "self-heal from the store)."
}
```
Note `findMapConfig` resolves wildcard patterns (`abyss-*`) and the `default` map config, so a
cluster-wide `default` eviction policy that happens to catch the adjacency map is also caught here.

Better a loud refusal at boot than a graph that quietly returns wrong answers in production.

## The memory ceiling (when this mode is valid)

"Never evict adjacency" means **topology must fit in RAM** — ~50–85 GB at 1e9 edges (see the memory
math in `AdjacencyIndexInterfaceRFC.md`). Topology ≪ values, so this holds for most workloads. When the
**topology itself** exceeds cluster memory, this mode is invalid: you cannot keep it all resident, and
you must fall back to **cache-mode** adjacency (evictable, cold-miss `preloadOut` on every access) — in
which case the value maps gain nothing from separate eviction and the whole thing is one coherent cache.
The two modes are pluggable behind the existing `AdjacencyIndex` seam:

- `AuthoritativeAdjacencyIndex` — never-evict + self-healing value fetch (this RFC; topology-fits-memory).
- `ShardedAdjacencyIndex` (today) — cache-mode, cold-miss re-warm (topology-exceeds-memory).

Choosing the wrong one is the failure the startup guard above prevents.

## Changes

- `AbyssSchemaWorker.adjacencyHopFlow` (`needValue=true`): batched **self-heal-on-null** from the store,
  replacing skip-null. This is the read-side of "adjacency is authoritative."
- Startup **eviction guard** on the adjacency map (fail-fast) — wherever the maps are obtained /
  `registerAbyssSerializers` is applied.
- Ephemeral: drop the OUT adjacency write (TODO 1.26 a); add `includeEphemeral` to `outgoing()`/`outAt`,
  store-sourced. (Own change; can land before or with this.)
- Optionally, a second `AdjacencyIndex` impl selecting authoritative vs cache mode by policy.

## Verification

- **Correctness of self-heal:** a `needValue=true` read after evicting specific `…-edges` values (real
  Hazelcast + a counting store) returns **every** edge, with exactly one batched store `loadEdges` for
  the evicted keys — model on `AdjacencyIndexTest`'s counting-proxy harness.
- **Pure-index proof:** a `needValue=false` traversal after clearing/evicting the entire `…-edges` map
  still returns the correct frontier, with **zero** store reads and zero `…-edges` touches.
- **The guard:** a config with eviction on the adjacency map fails startup with the message above;
  eviction on `…-edges`/`…-nodes` boots fine.
- **Live-Yugabyte:** value eviction under load, assert no dropped edges across a supernode traversal.
