# RFC — `HazelcastEphemeralStore`: memory-only ephemeral via the store seam

## The funny fact (and why it's the right shape)

TODO 1.27 made ephemeral edges **store-only** — they live in an `AbyssEphemeralStoreLike`, never in
`edgesMap` — which reliably reaches them (survives cache eviction) and keeps the persistent fast path
clean. That seemed to make "no store" an unsupported mode. It isn't: `AbyssEphemeralStoreLike` is a
**pluggable seam** (YCQL is just one impl), so "keep this data in memory, never on disk" becomes *pick a
memory-backed store*, not a special case in the engine.

So: an **ephemeral store whose backend is Hazelcast.** It looks circular — the store's durable backend
is the same engine as the cache — but the roles are different:

- `edgesMap` (the worker's cache) is a **read-through cache of a disk store**; entries may be persisted
  (MapStore / Hot-Restart) and are self-healed from the store on miss.
- A `HazelcastEphemeralStore` map is the **authoritative in-memory home** of ephemeral data: dedicated,
  TTL'd, **no MapStore, no Hot-Restart** — memory-only by construction, gone at TTL.

Same technology, opposite semantics. The engine never learns the word "cache-only."

## Motivation — secrets that must never touch disk

An auth module keeps refresh tokens / session keys / one-time codes as ephemeral edges. The security
requirement is that these **never** reach durable storage — not YSQL, not a MapStore, not Hot-Restart.
Hazelcast is memory-only by default, so a dedicated persistence-free ephemeral store is the natural,
structural way to express "in memory, TTL'd, never on disk." Per-schema (Abyss is multi-tenant): the
auth schema wires `ephemeralStore = HazelcastEphemeralStore(...)`; other schemas use YCQL for durable
ephemeral. Same engine, different plug.

## Design — one new store impl, zero engine changes

New module `abyss-ephemeral-hazelcast` (deps: `abyss-store-api` + Hazelcast), one class implementing
`AbyssEphemeralStoreLike`:

```kotlin
class HazelcastEphemeralStore(
    hz: HazelcastInstance,
    ephEdgesMapName: String,        // dedicated, no MapStore / Hot-Restart
    ephNodesMapName: String,        // dedicated by default (may point at the schema's nodesMap — see below)
) : AbyssEphemeralStoreLike {

    transaction {
        saveEdge(fromId, toId, edge, ttl, tags) -> ephEdges.set(key(fromId,toId,type), edge, ttl.seconds, SECONDS)
        saveNode(id, node, ttl, tags)           -> ephNodes.set(id, node, ttl.seconds, SECONDS)
        deleteEdge / deleteNode                 -> remove
    }
    loadEdge(f,t,type) = ephEdges.get(key) + remaining     // remaining from getEntryView(key).expirationTime - now
    loadEdges(fromId)  = partition scan by fromId -> List<StoredEdge> (remaining per entry; neighborType = null)
    loadInEdges(toId)  = emptyList   // ephemeral is outgoing-only (TODO 1.13) — same as YugabyteEphemeralStore
    loadNode(id)       = ephNodes.get(id) + remaining
}
```

- **Key**: a `PartitionAware` edge key (partitioned by `fromId`, same trick as `AdjacencyKey`/`EdgeKey`)
  so `loadEdges(fromId)` is a single-partition scan, not a cluster query.
- **Remaining TTL**: Hazelcast has no "time left" accessor, but `getEntryView(key).expirationTime` is the
  absolute expiry — `remaining = (expirationTime - now)`; `<= 0` ⇒ treat as gone.

It threads through the **unchanged** 1.27 paths:
- Write: `ephemeral { addEdge }` → worker → `ephemeralStore.transaction` → this store's map. Never `edgesMap`.
- `includeEphemeral` read: `ephemeralStoreHops` → `ephemeralStore.loadEdges` → this store's map.
- Point-read: `readEdge` → cache miss → `loadAndCacheEdge` → `ephemeralStore.loadEdge`; returns without
  re-caching (`remaining != null`), so it never lands in `edgesMap`.

**No `AbyssSchemaWorker` change at all** — this is purely additive.

## Map sharing — configurable, but with one hard rule

The constructor takes map names, so it *can* point at existing maps. But:

- **Node map — may be shared** with the schema's `nodesMap` (ephemeral nodes already coexist there, no
  fast-path scans it). Default dedicated, for isolation of secret nodes; shareable when a schema wants
  ephemeral nodes visible in the general node map.
- **Edge map — must be dedicated.** Two reasons: (1) the `outAt` typed fast path scans `edgesMap` by
  `fromId + type`, so a shared map makes non-leakage depend on a fragile edge-type-disjointness invariant
  — unacceptable under a security feature; (2) decisively, **`edgesMap` may carry persistence** (MapStore
  / Hot-Restart), and a secret written there would ride it **to disk** — the exact thing this forbids. A
  dedicated, persistence-free edge map makes never-to-disk *structural*, not incidental.

Non-collision of keys (the observation that sparked this) is real but not the property we need: the
property is **physical separation from anything that persists**.

## Security properties (by construction)

- **Never on disk**: dedicated maps with no MapStore and no Hot-Restart. Deployment must not enable
  persistence on these map names — documented and, optionally, assert-on-start (a guard like the
  adjacency eviction guard: refuse to boot if the ephemeral maps have a MapStore configured).
- **Isolated**: secrets are never in `edgesMap`/`nodesMap` (dedicated maps) nor the adjacency index.
- **Expiring**: TTL is the map's native expiry; no leak (unlike the reverted 1.26a untimed index entry).
- **Invisible by default**: only `includeEphemeral` reads them; default persistent traversal never does.

## Interaction with index-always-alive (TODO 1.27 Phase 2)

None adverse. The eviction guard forbids eviction/TTL on the **adjacency** map (never-evict topology) —
the ephemeral store's maps are a *different* set and are *supposed* to TTL. No conflict.

## Verification

- Round-trip: `HazelcastEphemeralStore` `saveEdge`/`loadEdge`/`loadEdges` with TTL; remaining decreases;
  entry gone after expiry; `loadInEdges` empty.
- Integration: a schema wired with it — `ephemeral { addEdge }` then `outEdges(includeEphemeral = true)`
  returns it and `readEdge` finds it; **default read excludes it**; assert the edge is **absent from
  `edgesMap`, the adjacency map, and any persistent store** (the never-leak/never-disk guarantee).
- Multi-tenant: two schemas on one instance, one YCQL-ephemeral + one Hazelcast-ephemeral, don't cross.

## Not doing
- Reusing `edgesMap` for ephemeral edges (persistence-to-disk risk; see above).
- A persistent Hazelcast store (this is ephemeral-only; persistent stays YSQL).
