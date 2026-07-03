# RFC — Untyped `AbyssSchemaWorker` over a self-describing NodeId

Status: **implemented** (TODO 1.16). Supersedes the registry/`resolveSchema` parts of
`UnifiedGraphEngineRFC.md` and `SchemaConceptRFC.md`.

## Problem

The multi-schema container performed schema operations **via a registry**: `AbyssGraph` held
`schemas: Map<Long, AbyssGraphSchema<*>>` and `resolveSchema(nid)` read the tag prefix and looked the
typed schema instance up. That map only existed as a pre-1.15 crutch — before self-describing NodeIds
you needed a table to recover a schema's width/shape/adapter from its tag.

Since 1.15 every `NodeId` self-describes: header high nibble → `SchemaTagWidth`, low nibble →
`NodeKeyKind` → canonical `KeyAdapter` via `NodeKeyKind.adapter()`, plus the tag bytes. So everything
a schema operation needs is **derivable from the key**, not looked up. `SchemaDescriptor` already named
that triple but was dead code.

## Design

**Derive, don't map.** `SchemaDescriptor.of(nid)` is a pure function returning
`(edgeAdapter, tagWidth, tag)`: the native canonical adapter for NONE-width keys, the stateless
`MultiSchemaAdapter(width)` for tagged keys. Both agree with the per-schema `SchemaKeyAdapter` on
`partitionKey`/`encodeKey`, so keys built from a derived descriptor match keys a typed schema builds.

**One untyped engine.** `AbyssSchemaWorker` owns the shared Hazelcast maps and the single shared store
and performs every operation on `NodeId`/`NodeLike<*>`/`SchemaEdgeLike<*>`: reads (cache read-through +
store self-heal), traversal (`NodeIdEngine`), the transaction/ephemeral commit pipeline, cascade,
cache population, and schema enforcement. It derives the descriptor per key; it holds no `<ID>` and no
registry.

**Thin typed facade.** `AbyssGraphSchema<ID>` keeps the `AbyssEngineLike<ID>` surface but is now just an
`ID ⇄ NodeId` adapter over a worker: it converts at the boundary, buffers typed transaction ops, maps
each to a NodeId-level `NodeOp`, and delegates. Standalone (single-schema) use builds its own worker;
inside a container the facades share one worker.

**Registry-free container.** `AbyssGraph` owns one worker; its `NodeIdEngine` methods delegate straight
to it (the worker self-resolves per key). `register`/`singleSchema` return a typed facade over the
shared worker and insert nothing into a map — the container keeps only a `Set<Long>` of registered tags
for duplicate-registration and cross-edge integrity guards. Cross edges route through the worker's
shared maps; integrity checks node presence in the shared map instead of "tag ∈ registry".

**Single shared NodeId-keyed store.** `AbyssStoreLike`/`AbyssEphemeralStoreLike` drop `<ID>` and key on
`NodeId` (the PK is already the NodeId's BYTEA bytes; the Yugabyte impls just stop deriving it through
an adapter). Values stay polymorphic `NodeLike<*>`/`SchemaEdgeLike<*>`. Because an edge value stores
*domain* endpoint ids that can't be turned back into tagged NodeIds untyped, edge **scans**
(`loadEdges`/`loadInEdges`) return `StoredEdge(fromId, toId, edge, remaining)` with both endpoint
NodeIds read from the PK columns — so cache preload rebuilds keys with no adapter.

## Consequences / API changes

- **Removed:** `AbyssGraph.schema<ID>(tag)` and `AbyssGraph.resolveSchema(nid)`. Callers hold the typed
  facade that `register`/`singleSchema` return (as `UniverseFixture` already did).
- `register`/`singleSchema` no longer take per-schema stores/`asyncCachePopulation`; those move to the
  `AbyssGraph` constructor (one shared store, one flag).
- Store implementers now implement the untyped, NodeId-keyed interfaces and return `StoredEdge` from
  scans.

## Alternatives rejected

- *Keep a tag→adapter (or tag→facade) map for typed lookup.* That is the mapping the design set out to
  remove; the self-describing key makes it unnecessary for routing, and typed handles are already
  returned by `register`.
- *Worker cache-only, stores stay typed on the facade.* Rejected: the worker owns commit, so it owns the
  store; unifying on a single NodeId-keyed store is the endpoint of "one engine, one store".

## Verification

Existing suites are the spec (`SingleSchemaTest` byte-compat, `MultiSchemaTest` tagged routing + cross
edges, `UniverseTraversalTest` heterogeneous String/Long/Uuid over one shared store, `GraphTest`
including store-warming/commit fakes, `ensureSubgraph`). Added `schemaDescriptorDerivesFromKeyWithoutRegistry`
for the derive-don't-map invariant. All green.
