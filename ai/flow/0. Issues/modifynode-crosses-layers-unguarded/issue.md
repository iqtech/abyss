# modifyNode crosses layers unguarded (gap 2)

Created: 2026-11-02

Settled 2026-09-11 (VersionGuardPlan gap 2 discussion): a persistent `transaction{}` must not touch
ephemeral nodes (no implicit promotion through `modifyNode`), and an `ephemeral{}` modify can never touch
persistent nodes. Nothing enforces either rule. Both `modifyNode` buffers call `readNode`, which reads
across both layers, and write the result to their own layer. Moved here from nodes-history on 2026-11-02.
All of the below is by reading the code; no test reproduces it yet.

## Cases

- **`transaction { modifyNode(e) }` on an ephemeral id** (`AbyssGraphSchema.kt:349`): promotes it into
  `nodes` and, for a `@WithHistory` type, into `node_history`. Ephemeral content reaches disk, twice for
  history, and the history copy stays until the node is deleted. Ephemeral exists for data that must
  never get there (refresh tokens, session keys).
- **`ephemeral { modifyNode(x) }` on a persistent id** (`AbyssGraphSchema.kt:372`): YCQL and the cache get
  the new value with a TTL. Readers see it, then persistent wins again
  (`AbyssSchemaWorker.loadAndCacheNode`) and the old value returns. No history row, `version` unmoved.

`modifyEdge` is worse, because it emits `RemoveEdge` + `AddEdge` and the removal fans out to the other
layer (traced 2026-11-02, by reading):

- **`transaction { modifyEdge }` on an ephemeral edge:** `loadAndCacheEdge` falls through to the ephemeral
  store, the add writes it to the persistent store, and the delete fanout (`AbyssSchemaWorker.kt:478`)
  removes the ephemeral copy. The edge moves to disk outright, which is the case refresh tokens must never
  hit.
- **`ephemeral { modifyEdge }` on a persistent edge:** the delete fanout (`:536`) deletes the persistent
  edge and the add writes an ephemeral one. When the TTL expires the edge is gone for good.

## Why it isn't a one-line guard

`readNode` returns a value with no layer, and a cache hit can't tell which layer it came from. A guard
needs either a store read on every modify or a way to know the layer from the id.

## From the gap 2 discussion

- Candidates: id-encoded layer through a per-schema `layerOf(id)`; persistent-wins precedence.
- Rejected or unsettled: class layering (rejected: the same class lives in both layers for traversal), a
  runtime id registry (stale: ephemeral TTL expiry fires no event), post-commit ephemeral-delete fanout on
  persistent add.
- Deletes stay unguarded, also settled.
