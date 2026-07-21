# Typed-traversal fetch avoidance via AdjacencyEntry.nodeTypeTag (Option 2)

## Context

`nodes<N>()` and every node-type filter currently fetch each node's **full value** just to read
its type discriminator. `filterFrontierByNode` (`TraversalBuilder.kt:94`) does
`engine.nodeAt(nid)` per frontier node only to compare `node.typeName()` (@SerialName) — a full
cache/store read discarded for non-matching nodes. On a hub fan-out that's one fetch per frontier
node, per type-filter.

`AdjacencyEntry.nodeTypeTag` (the neighbor node's `@TypeTag`, see [[project_node_type_tag_intent]])
exists precisely to avoid this: the adjacency index already knows each neighbor's type. But the tag
is dropped when `adjacencyRead` builds a `Hop` (Hop has no tag field) and again when `addHop`
collapses hops to a bare `Set<NodeId>` frontier. **Goal:** carry the tag from `adjacencyRead` →
`Hop` → frontier/visited, so typed filters compare a `Short` in memory instead of fetching. Chosen
approach: **Option 2** — the frontier (and accumulated visited set) carry the tag, so the benefit
survives multiple hops and extends to `collectSubgraph(nodeType)`, not just the immediate hop.

Best-effort accelerator, never a correctness change: the tag is nullable (dangling edges,
cold-preloaded entries, fast-path hops), and any null → fall back to the existing `nodeAt` fetch.

## Core mechanism

**1. `Hop` carries the target's tag** — `NodeIdEngine.kt:12`:
`data class Hop(fromId, toId, type, edge, nodeTypeTag: Short?)`. Invariant (document it): `nodeTypeTag`
is the `@TypeTag` of the node at `hop.target(direction)` (= `entry.neighborId`), or null when
unresolved. Populate in `AbyssSchemaWorker`:
- `adjacencyRead` build (`:180`): `nodeTypeTag = entry.nodeTypeTag`.
- `adjacencyRead` needValue rebuild (`:186`): preserve `hop.nodeTypeTag`.
- `outAt` fast path (`:140`, edgesMap entrySet — no adjacency entry): `null`. **Limitation to document:**
  a hop that needs edge values (`outgoing<E>{ edgePredicate }`) uses this path, so a following
  `nodes<N>()` degrades to fetch. The common value-free `outgoing<E>()` routes through `adjacencyRead`
  and keeps the tag. `resolveEdges` (`:190`) needs no change — it reuses existing `Hop`s as keys.

**2. Frontier & visited carry tags, exposed as id-views** — `TraversalBuilder.kt`. `.frontier` has no
external readers, so keep the consumed shape a `Set<NodeId>` and add a parallel tag map as the source
of truth:
- `private var frontierTags: Map<NodeId, Short?>` (seed `startFrontier.associateWith { null }` — origin
  ids have no producing hop, so null; constructor signature and all sub-`TraversalBuilder(engine, setOf(nid)/current, ...)`
  call sites stay unchanged).
- `val frontier: Set<NodeId> get() = frontierTags.keys` (replaces the stored `var` + `private set`).
  Every existing `frontier` read — `- visited`, iteration, `.isNotEmpty()`, `.toSet()`, `in frontier`,
  `.size`, `sub.frontier` in checkReaches/pathTo/dfsCycle/exhaustReachable/filterFrontierByTraversal —
  is unchanged.
- `private val allVisitedTags: MutableMap<NodeId, Short?>` replaces `allVisitedIds`. The `+=`/`-=`
  sites become map ops: on a hop `frontierTags.forEach { (k,v) -> allVisitedTags.merge(k, v) { o, n -> o ?: n } }`
  (keep the first non-null tag when a node is revisited via a null-tag path); on a narrowing filter
  `(oldFrontier - matching).forEach { allVisitedTags.remove(it) }`.
- Hop-producing sites set tags: `addHop`/`addNodeHop` → `frontierTags = hopEdges.associate { it.target(dir) to it.nodeTypeTag }`.
  Narrowing filters (`filterFrontierByNode`/`ByEdge`/`ByEdgeType`/`ByTraversal`) →
  `frontierTags = frontierTags.filterKeys { it in matching }`.

**3. Tag-aware filters** (nullable-tag param added; reified DSL passes `N::class.typeTag()`):
- `filterFrontierByNode(nodeType: String, nodeTag: Short?, predicate)` — per nid: `tag = frontierTags[nid]`;
  if `tag != null && nodeTag != null` compare `tag == nodeTag` (no fetch); else fall back to
  `nodeAt(nid)?.typeName() == nodeType`. With a predicate, tag still pre-filters (skip fetching
  wrong-tag nodes), then fetch survivors to run it. Keep `nodeType` string for the null-tag fallback.
- `collectSubgraph(nodeType)` (`:175`) — when `nodeType != null`, use `allVisitedTags` to skip fetching
  nodes whose non-null tag rules them out; fetch only matching-tag and null-tag nodes, then filter by
  `typeName` as today. `nodeType == null` unchanged.

## Part B — hop-target filters (confirmed in scope)

Same `Hop.nodeTypeTag` enabler, no frontier change (these inspect hop targets directly). Convert to
tag-first with `nodeAt` fallback, reified DSL passes the tag:
- `addNodeHop(..., nodeTag: Short?, ...)` (`:76`) — `outgoing<E,N>()`/`incoming<E,N>()`.
- `filterFrontierByEdgeType` (`:119`) via `filterFrontierByOutEdgeToType`/`InEdgeFromType(..., nodeTag)`
  — `hasOutgoing<E,N>()`/`hasIncoming<E,N>()`.

## Signature changes (contained — only the reified inlines call these)

`TraversalBuilderLike` (`abyss-dsl`): add nullable `nodeTag: Short?` to `filterFrontierByNode`,
`addNodeHop`, `filterFrontierByOutEdgeToType`, `filterFrontierByInEdgeFromType`. `Extensions.kt`:
`nodes<N>()`/`nodes<N>(filter)` (`:108,112`), `outgoing/incoming<E,N>` (`:98,103`),
`hasOutgoing/hasIncoming<E,N>` (`:119,128`) pass `N::class.typeTag()` alongside the existing
`N::class.serialName()`.

## Correctness invariants
- Tag present + wanted tag present → compare in memory; any null → `nodeAt` fetch + `typeName` compare
  (existing correct path). Never wrong, only sometimes still fetches.
- Fan-in dedup unaffected: a shared target has one deterministic tag (its own node's).
- Containers (`Homogeneous`/`HeterogeneousSchemaGraph`) delegate `outAt`/`inAt`/`nodeAt` to the worker,
  so tags flow through unchanged; cross-schema neighbor tags are null when unresolved → fetch.

## Files
- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/NodeIdEngine.kt` — `Hop.nodeTypeTag` + invariant doc.
- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/AbyssSchemaWorker.kt` — `adjacencyRead` (2 sites), `outAt` fast path.
- `abyss-graph/src/main/kotlin/pl/iqtech/abyss/graph/traversal/TraversalBuilder.kt` — frontier/visited tag backing; tag-aware `filterFrontierByNode`, `collectSubgraph`; Part B `addNodeHop`/`filterFrontierByEdgeType`.
- `abyss-dsl/src/main/kotlin/pl/iqtech/abyss/dsl/TraversalBuilderLike.kt` — `nodeTag` params.
- `abyss-dsl/src/main/kotlin/pl/iqtech/abyss/dsl/Extensions.kt` — reified DSLs pass `N::class.typeTag()`.

## Tests & verification (perf-bug workflow: isolate → baseline → after)
New `TypedNodeFilterFetchTest` (abyss-graph): a `CountingNodeIdEngine` test double implementing
`NodeIdEngine` (public), returning canned `Hop`s with `nodeTypeTag` set and counting `nodeAt` calls.
Drive `TraversalBuilder(countingEngine, startFrontier, huid)` directly (public ctor):
- `outgoing<E>(); nodes<N>()` no predicate → **baseline** `nodeAt == frontier.size`; **after** `== 0`.
- Mixed-type frontier → `nodes<N>()` keeps only N; 0 fetches; correct result.
- Null-tag entries (dangling/cold) → fall back to `nodeAt` (count == null-tag count), still correct.
- `nodes<N>(predicate)` → only same-tag nodes fetched (wrong-type skipped), predicate applied.
- `collectSubgraph<_>(nodeType)` → wrong-tag visited nodes not fetched.
- Part B: `outgoing<E,N>()` / `hasOutgoing<E,N>()` → 0 target fetches for the type gate.

Regression: `TraversalTest`, `MixedTraversalTest`, `UniverseTraversalTest`, `MultiSchemaTest` green
unmodified. Full `./gradlew build` (incl. `LoadTest`) green.
