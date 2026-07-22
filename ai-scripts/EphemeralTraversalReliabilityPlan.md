# Ephemeral traversal reliability — ephemeral is store-only, `includeEphemeral` opt-in

## Why

TODO 1.26(a) shipped a shortcut: ephemeral (TTL) OUT edges got an OUT **adjacency entry** so
`outEdges` found them. That entry is untimed → it leaks (never reclaimed after the edge expires), and
under the "index always alive" model (`IndexAlwaysAliveRFC.md`) it leaks *permanently*. Ephemeral edges
are a first-class, reliable asset — they must survive cache eviction and must not pollute the fast
persistent path. So this plan makes ephemeral edges **store-backed and explicitly opt-in for traversal.**

## Decisions to confirm (please check these on review)

1. **Ephemeral edges become store-only.** Written to `ephemeralStore` (durable YCQL), and **not** to the
   adjacency index (revert 1.26a) **nor** to `edgesMap`. This is what keeps the persistent read path —
   adjacency index + `edgesMap` — clean and fast: the default OUT read **keeps its 1-round-trip fast
   path** with zero ephemeral cost. (This is the "ephemeral out of edgesMap" option; the fast path is
   **kept**, not dropped.)
2. **Traversal is ephemeral-aware via an explicit flag.** `includeEphemeral: Boolean = false`, OUT-only
   (ephemeral is outgoing-only), on `outgoing()`/`outgoingAny()`/`outAt`/`outEdges`. Default = persistent
   only. `true` = also read ephemeral **from the store** (reliable, survives eviction).
3. **Consequences (accepted):** ephemeral **point-reads hit YCQL** (no cache); **cache-only ephemeral —
   no `ephemeralStore` configured — is unsupported** (an ephemeral write would have nowhere durable to
   go). Any current cache-only ephemeral usage/tests move to a store-backed fake.

## Changes

### Write path — ephemeral stops touching the cache
- `applyToCacheAsync`, `NodeOp.AddEdge` with `op.ttl != null`: write **nothing to `edgesMap`** (and no
  adjacency entry). The `ephemeral()` commit already persists to `ephemeralStore`. Reverts the 1.26a
  adjacency writes.
- `preloadOut`: **remove the ephemeral branch** entirely — nothing to warm (ephemeral isn't cached, and
  the adjacency write is gone). Persistent branch unchanged.
- `loadAndCacheEdge` (`:465`): it already falls back to `ephemeralStore`, but it then **re-caches** the
  edge into `edgesMap` (`:475`). For an ephemeral edge (`remaining != null`) it must **return without
  caching**, or a point-read would re-pollute `edgesMap` and re-break the fast path. So: ephemeral edge →
  return, don't `edgesMap.set`. (Nodes are unaffected — `loadAndCacheNode` already handles both stores,
  and ephemeral *nodes* stay in `nodesMap`; this plan is edges-only.)

### Read path — traversal & outEdges
- Keep the `outAt` **fast path** (`type != null && needValue` → `edgesMap.entrySet`) — now clean, since
  `edgesMap` is persistent-only.
- `outAt(nid, type, needValue, includeEphemeral = false)`: `persistent` = fast path or
  `adjacencyHopFlow(OUT)`; return `persistent` when `!includeEphemeral`, else
  `flow { emitAll(persistent); emitAll(ephemeralStoreHops(nid, type)) }`.
- `outEdges(nid, type, pageSize, includeEphemeral = false)`: `adjacencyEdgeFlow(OUT)` then, if
  `includeEphemeral`, `emitAll(ephemeralStoreHops(nid, type).mapNotNull { it.edge })`.
- New `ephemeralStoreHops(nid, type): Flow<Hop>` — from `ephemeralStore?.loadEdges(nid)`, filter by
  `type` and drop expired (`remaining <= 0`), emit `Hop(fromId, toId, edgeName, edge, nodeTypeTag=null)`.
  (YCQL `loadEdges` carries no neighbor-type JOIN, so `nodeTypeTag` is null → typed filters fetch-fall-back.)

### Interface / DSL surface (all additive, default `false` — backward compatible)
- `NodeIdEngine.outAt(..., includeEphemeral = false)`; `HomogeneousSchemaGraph`/`HeterogeneousSchemaGraph`
  delegate.
- `AbyssEngineLike.outEdges(nodeId, pageSize, includeEphemeral = false)` (both overloads); `AbyssGraphSchema`
  + the reified `outEdges` DSL extension thread it.
- `TraversalBuilderLike.addHop(..., includeEphemeral = false)`; `TraversalBuilder.addHop`/`hops` thread it
  into `outAt` (INCOMING ignores it — ephemeral is outgoing-only).
- DSL `outgoing()` / `outgoing(predicate)` / `outgoingAny()` gain `includeEphemeral = false`. `incoming*`
  unchanged. (Scope: not adding it to `hasOutgoing`/`countEdges`/typed-node `outgoing<E,N>` in this pass —
  easy follow-ups.)

## Tests
- **Reliability (the point):** an ephemeral OUT edge, with an `ephemeralStore` fake, is **not** in
  `edgesMap`/adjacency; `outgoing(includeEphemeral = true)` / `outEdges(includeEphemeral = true)` returns
  it (sourced from the store); the default excludes it. Then **clear/evict the cache** and confirm it's
  still returned with the flag (survives eviction) — the reliability guarantee.
- **Default excludes ephemeral:** default `outEdges`/`outgoing()` over a node with both persistent and
  ephemeral out-edges returns only the persistent ones.
- **Migrate existing ephemeral tests** (e.g. GraphTest "ephemeral addEdge is outgoing-only", 499) to a
  store-backed schema + `includeEphemeral = true`; assert outgoing-only still holds (empty `inEdges`).
- Full `:abyss-graph:test` green incl. `-Pperf`; the persistent perf tests (OutEdgePaging/HopStreaming)
  unchanged since the persistent path is untouched.

## Not in scope
- `hasOutgoing`/`countEdges`/typed-node OUT hops gaining the flag (follow-up).
- The "index always alive" eviction guard + authoritative-value self-heal (`IndexAlwaysAliveRFC.md`) —
  separate change; this plan is compatible with it (ephemeral already out of the index).
