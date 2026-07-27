# Hide raw traversal primitives behind a `TraversalScope` facade

**Status: implemented.** See "Implementation notes" at the end for what changed from this plan
during execution (scope was narrowed after a concrete test failure surfaced a case the original
grep missed).

## Context

`TraversalBuilderLike<ID>` (abyss-dsl) is the DSL receiver type for every traversal block
(`from(id) { }`, `checkReaches(id) { }`, `pathTo(id) { }`, `exhaustReachable { }`,
`detectCycle { }`, `hasTraversal { }`). It mixes two kinds of members:

- **Raw primitives** that take/return untyped data: `addHop`, `addNodeHop`,
  `filterFrontierByNode`, `filterFrontierByOutEdgeTo(Type)`, `filterFrontierByInEdgeFrom(Type)`,
  `filterFrontierByTraversal`, `flushFrontierNodes`, `flushHopEdges`, `countEdges(direction,
  edgeType: String)`, `collectSubgraph(nodeType: String?, nodeTag: Short?)`.
- **Typed/structural members** that are fine as-is: `count()`, `checkReaches`, `pathTo`,
  `exhaustReachable`, `detectCycle`, `paths(...)`.

Every raw primitive already has a reified, type-safe wrapper in `Extensions.kt` (`outgoing<E>()`,
`incoming<E>()`, `nodes<N>()`, `hasOutgoing<E>()`, `hasIncoming<E>()`, `hasTraversal { }`,
`collectNodes<N>()`, `collectEdges<E>()`, `countEdges<E>()`, `subgraphOf<N>()`), but the raw
methods themselves are public members of `TraversalBuilderLike<ID>` and autocomplete/compile
identically to the sugar inside any DSL block today. `@DslMarker` (added earlier in this
investigation) does not hide them — it only fixes implicit-receiver leakage across nested
same-marked blocks. Verified via a real compile that `@DslMarker` does not block a direct
`addHop()` call.

Established during investigation (two Explore agents, full-repo grep):
- `TraversalBuilderLike<ID>` has exactly one implementation, `TraversalBuilder<ID>`
  (`abyss-graph/.../traversal/TraversalBuilder.kt`), in a **different Gradle module** than the
  interface (`abyss-graph` depends on `abyss-dsl`, never the reverse).
- Zero call sites anywhere in the repo (including all test source sets) call the raw primitives
  directly — every caller already goes through the `Extensions.kt` sugar.
- All 5 nested-block methods (`filterFrontierByTraversal`, `checkReaches`, `pathTo`,
  `exhaustReachable`, `detectCycle`) construct a **fresh child `TraversalBuilder` locally** (`sub =
  TraversalBuilder(engine, nodeSet, homeAdapter)`) and call `sub.block()` — none of them pass
  `this`. `sub.frontier` / `sub.traversedHops` (concrete-only members) are read from the same local
  `sub` variable *after* the block call, so wrapping only the `block()` invocation doesn't disturb
  that.
- 4 test files construct `TraversalBuilder` directly and invoke DSL sugar on it outside of any
  `from()`/`pathTo()` block context (e.g. `builder.run { outgoing<E>() }`):
  `TypedNodeFilterFetchTest.kt`, `PathToPerformanceTest.kt`, `DetectCycleDepthTest.kt`,
  `TypedNodeFilterPerformanceTest.kt`. These need a one-line wrap at each call site (see below).

Goal: make the raw primitives unreachable from ordinary DSL-block code (no autocomplete, no
direct call, compile error if attempted) while leaving `TraversalBuilder` as the sole,
unchanged-in-substance implementation.

## Design

Add a facade type in abyss-dsl:

```kotlin
// abyss-dsl/src/main/kotlin/pl/iqtech/abyss/dsl/TraversalScope.kt
class TraversalScope<ID>(@PublishedApi internal val raw: TraversalBuilderLike<ID>) {
    suspend fun count(): Int = raw.count()
    suspend fun checkReaches(targetId: ID, block: suspend TraversalScope<ID>.() -> Unit): Boolean = raw.checkReaches(targetId, block)
    suspend fun pathTo(targetId: ID, block: suspend TraversalScope<ID>.() -> Unit): Path? = raw.pathTo(targetId, block)
    suspend fun exhaustReachable(block: suspend TraversalScope<ID>.() -> Unit): Subgraph = raw.exhaustReachable(block)
    suspend fun detectCycle(block: suspend TraversalScope<ID>.() -> Unit): Boolean = raw.detectCycle(block)
    fun paths(
        strategy: TraversalStrategy = TraversalStrategy.DFS,
        direction: EdgeTraversalDirection = EdgeTraversalDirection.BOTH,
        maxDepth: Int = Int.MAX_VALUE,
        edgeVisitor: (path: Path, edge: EdgeLike<*, *>) -> Boolean,
        nodeEvaluator: (path: Path, node: NodeLike<*>) -> Evaluation
    ): Flow<Path> = raw.paths(strategy, direction, maxDepth, edgeVisitor, nodeEvaluator)
}
```

**Visibility mechanics (deliberate, verify during implementation):**
- The **constructor is public** (no modifier) — it must be, because `TraversalBuilder.kt` lives in
  a different module (`abyss-graph`) and needs to call `TraversalScope(sub)` in ordinary
  (non-inline) override bodies; `internal`/`@PublishedApi internal` constructors are only
  reachable from public-inline bytecode in *other* modules, not from ordinary cross-module source.
- The **`raw` property is `@PublishedApi internal`** — same pattern already used in this codebase
  (`AnnotationCache.kt:14-18`, `annotationCache`/`NoAnnotation`) for state that must be reachable
  from public inline+reified functions but not from ordinary caller source. `Extensions.kt`'s sugar
  is `inline` (required for `reified E`), so its compiled body can reach `raw` via the
  `@PublishedApi` bytecode carve-out; a consumer writing `scope.raw.addHop(...)` in their own
  source gets a compile error (`raw` is internal to `abyss-dsl`) regardless of module.
- A public constructor over a public-interface-typed parameter doesn't leak more than today: the
  interface `TraversalBuilderLike<ID>` already has to stay fully public (cross-module override
  requirement, unchanged), so this is no weaker than the status quo — it just removes the raw
  methods from the *ordinary* DSL-block surface.

## Changes

1. **New file** `abyss-dsl/src/main/kotlin/pl/iqtech/abyss/dsl/TraversalScope.kt` — as above.

2. **`TraversalBuilderLike.kt`** — retype the nested-block parameter on `filterFrontierByTraversal`,
   `checkReaches`, `pathTo`, `exhaustReachable`, `detectCycle` from
   `suspend TraversalBuilderLike<ID>.() -> T` to `suspend TraversalScope<ID>.() -> T`. Everything
   else on the interface (the raw primitives themselves) is unchanged — they must stay public
   members of this interface; only their *entry points* (the block receiver type) change.

3. **`TraversalBuilder.kt`** (abyss-graph) — mechanical, in each of the 5 override bodies plus the
   private `dfsCycle`/`neighborsOf` helper:
   - Change the `block` parameter type to `suspend TraversalScope<ID>.() -> T` (matching the
     interface).
   - Change `sub.block()` to `TraversalScope(sub).block()`.
   - Leave everything else (the `sub.frontier` / `sub.traversedHops` reads after the call, BFS/DFS
     loop structure, concurrency) untouched — `sub` is still a local `TraversalBuilder<ID>`.

4. **`AbyssEngineLike.kt`** — both `from()` overloads: change `block: suspend
   TraversalBuilderLike<ID>.() -> T` to `block: suspend TraversalScope<ID>.() -> T`.

5. **`AbyssGraphSchema.kt`** — both `from()` overrides: wrap the constructed builder before
   invoking the block, e.g.
   `TraversalScope(TraversalBuilder(traversalEngine, setOf(adapter.toNodeId(nodeId)), adapter)).block()`
   (and the `Set<ID>` overload analogously).

6. **`Extensions.kt`** — retype every traversal-sugar extension's receiver from
   `TraversalBuilderLike<*>`/`TraversalBuilderLike<ID>` to `TraversalScope<*>`/`TraversalScope<ID>`:
   - Raw-calling sugar (`outgoing`, `incoming`, `outgoingAny`, `incomingAny`, both predicate
     overloads, both typed-node overloads, `countEdges<E>()`, `nodes<N>()` both overloads,
     `hasOutgoing`/`hasIncoming` both overloads, `collectNodes<N>()`, `collectEdges<E>()`,
     `subgraph()`, `subgraphOf<N>()`) — body changes from calling the member directly to
     `raw.addHop(...)` / `raw.filterFrontierByNode(...)` / `raw.flushFrontierNodes()` /
     `raw.collectSubgraph(...)` etc.
   - `hasTraversal`, `reaches`, `allReachable`, `hasCycle` — become plain pass-throughs to
     `TraversalScope`'s own new member methods (`filterFrontierByTraversal` stays raw-only,
     reachable via `raw.filterFrontierByTraversal(block)`; `checkReaches`/`exhaustReachable`/
     `detectCycle` are now direct `TraversalScope` members, no `.raw` needed).

7. **Remove `@DslMarker`/`TraversalDsl`** from `TraversalBuilderLike.kt` (added earlier in this
   investigation, before this facade design existed). After step 2, `TraversalBuilderLike` is never
   again used as a lambda-receiver type in the DSL surface — every nested block, including the ones
   still declared on the raw interface, takes a `TraversalScope<ID>`-typed block. `@DslMarker` only
   guards implicit-receiver fallthrough for lambdas of the marked type; with no such lambda left,
   it's dead weight. Do **not** move it to `TraversalScope` either — `TraversalScope`'s own nested
   methods (`checkReaches`, `pathTo`, etc.) nest the same type inside itself, which is exactly the
   homogeneous-nesting case verified by compile test to need no marker (nearest-receiver-wins
   already resolves it correctly without one). Delete the `TraversalDsl` annotation class and its
   usage entirely; only reintroduce a marker if a second, narrower nested-scope type is ever added
   to this family.

8. **Test call sites** — `TypedNodeFilterFetchTest.kt` (lines 63,72,85,103),
   `PathToPerformanceTest.kt` (90,108), `DetectCycleDepthTest.kt` (65),
   `TypedNodeFilterPerformanceTest.kt` (73): wherever they call DSL sugar directly on a
   directly-constructed `TraversalBuilder` (not inside a `pathTo`/`checkReaches`/etc. block, where
   the type flows through automatically), wrap with `TraversalScope(builder).run { ... }` or
   equivalent. Exact fix per call site to be determined by reading each during implementation —
   this is a 1-line change per site, not a structural one.

## Verification

1. `./gradlew :abyss-dsl:compileKotlin :abyss-graph:compileKotlin` — must succeed.
2. `./gradlew :abyss-graph:test` — full suite, confirms the 4 flagged test files still pass after
   their call-site wraps, and nothing else broke (repo-wide grep already confirmed no other raw
   call sites exist).
3. Add one throwaway scratch file (same technique used earlier this session, deleted after) that
   attempts `from(id) { addHop(HopDirection.OUTGOING, null) }` inside a `TraversalScope`-typed
   block and confirms it now **fails to compile** (`Unresolved reference: addHop`) — this is the
   actual acceptance criterion for the whole change. Delete the scratch file after confirming.

## Implementation notes (deviations from the plan above)

- **Scope narrowed to match what was actually asked.** The original "raw primitives" list (Context
  section, and step 6) included `flushFrontierNodes`, `flushHopEdges`, `countEdges(direction,
  edgeType: String)`, `collectSubgraph(nodeType, nodeTag)` alongside `addHop`/`addNodeHop`/
  `filterFrontierBy*`. That was this agent's own scope expansion, not something the user asked for
  — the actual instruction named "filter*, addHop etc." As implemented, only `addHop`, `addNodeHop`,
  `filterFrontierByNode`, `filterFrontierByOutEdgeTo(Type)`, `filterFrontierByInEdgeFrom(Type)`, and
  `filterFrontierByTraversal` (kept for the nested-block loophole — see below) are hidden behind
  `.raw`. `flushFrontierNodes`, `flushHopEdges`, `countEdges`, `collectSubgraph` are plain
  pass-through **members** on `TraversalScope` (delegating to `raw.xxx(...)` once, inside the class
  body) — exposed, not hidden.
- **Why:** `TraversalTest.kt` (not found by the pre-implementation grep, which only searched for
  `addHop`/`addNodeHop`/`filterFrontierBy*` call sites, not `flushFrontierNodes` et al.) has two
  tests that deliberately call `flushFrontierNodes()` as the last expression of a `from(id) { }`
  block, asserting the raw `Flow<NodeLike<*>>` this returns stays collectible by the caller after
  `from` returns — legitimate black-box coverage of that method's own (untyped, cold-flow)
  contract, not a workaround. Hiding `flushFrontierNodes` broke both tests for no requested benefit.
  Once that was found, `countEdges`/`collectSubgraph` were pulled back to pass-through too, for the
  same reason: no evidence they were the "filter*, addHop" the user meant, and hiding them buys
  nothing beyond what was actually asked.
- **Final `TraversalBuilderLike` → `TraversalScope` map:**
  - Hidden (interface-only, reachable via `.raw` inside `Extensions.kt`/`TraversalBuilder.kt`):
    `addHop`, `addNodeHop`, `filterFrontierByNode`, `filterFrontierByOutEdgeTo`,
    `filterFrontierByOutEdgeToType`, `filterFrontierByInEdgeFrom`, `filterFrontierByInEdgeFromType`,
    `filterFrontierByTraversal`.
  - Exposed as `TraversalScope` members (plain pass-throughs): `count`, `countEdges`,
    `collectSubgraph`, `flushFrontierNodes`, `flushHopEdges`, `checkReaches`, `pathTo`,
    `exhaustReachable`, `detectCycle`, `paths`.
- `@DslMarker`/`TraversalDsl` removed entirely from `TraversalBuilderLike.kt`, per the separate
  discussion that concluded it was dead weight once `TraversalBuilderLike` stopped being used as a
  lambda-receiver type anywhere.
- Dedicated test coverage added: `abyss-graph/src/test/kotlin/pl/iqtech/abyss/graph/
  TraversalScopeTest.kt` exercises `from`, `hasTraversal`, `reaches`, `pathTo`, `allReachable`,
  `hasCycle` end-to-end through the real `AbyssGraphSchema` + Hazelcast fixture (`graphTest`),
  confirming the receiver-type swap didn't change traversal behavior. The negative/compile-time
  guarantee (raw methods unreachable) was verified manually via the scratch-file technique above,
  not as a committed test — there's no compile-fail-testing tooling in this repo and adding one for
  a single assertion isn't warranted.
