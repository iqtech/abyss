# Edge history

Created: 2026-11-02

Keep every committed state of a `@WithHistory` edge, the way nodes-history does for nodes
(`ai/flow/6. Test/nodes-history`). Same annotation, eager resolution, wiring check and persistent-layer
rule; same multi-row statement with ISO instants, retry and duplicate-key cut. This is its own item
because edges differ in identity, in how they're modified and in hub shape.

## Blocked by

1. **`ai/flow/0. Issues/modifyedge-same-key-delete-insert`: a hard blocker.** `modifyEdge` deletes and
   re-inserts, so with a delete cascade every modify would erase the key's history and reset `version`.
   History would hold one row forever.
2. **`ai/flow/0. Issues/batch-partition-node-and-edge-saves`: should land first.** It widens
   `statementOrder` to every save and groups by `statementOf`, so a history edge statement is covered with
   nothing more. Without it, mixed history and plain edges in a batch hit the trap nodes-history fixed.

## Measured

`EdgeHistoryProbeTest`, raw JDBC, `-Pperf`, dev YB, median of 5 interleaved rounds after a warm-up, counts
asserted every run, on tables with production's indexes:

| workload | result |
|---|---|
| plain + `version`, single 1001 | +2.0% |
| plain + `version`, batched 10007 | +6.1% spread, +8.4% hub |
| history, per-row CTE, single 1001 | +3.7% (PK by key), +5.5% (PK by from) |
| history, multi-row, batched 10007 | −22.0% / −20.4% spread, −18.1% / −19.8% hub (PK by key / by from) |
| delete of a history type, batched 3003 | +99.9% against bare |

## Design position (by reading, not agreed)

- **Identity is `(from_id, to_id, type)`:** there's no surrogate id. A retarget is a new key, so the old
  key's history goes with the delete cascade.
- **Delete is simpler than on nodes:** `DeleteEdge` carries its type, so the store picks the statement up
  front. Plain types keep the bare `DELETE`, 0%, against +5.7% on nodes. Unknown types still cascade.
- **`version` in the history statement only:** plain edge upserts stay untouched while the guard is
  parked. That defers +6–8%, it doesn't avoid it: a guard needs every write to bump, and adding that later
  is a code change, not a migration. During that rollout old pods won't bump.
- **History PK `((from_id, to_id, type) HASH, version ASC)`:** a hub's unbounded history doesn't pile onto
  one tablet, and every v1 access has the full key. The price: "all history from node x" is a full scan.
  One dev container can't show tablet hot spots, and hub against spread showed no real difference, so
  this rests on reasoning. Measure on a real topology.
- **Node delete:** the cascade expands through the adjacency index into one `RemoveEdge` per edge, so each
  history edge pays the cascade, and a hub delete doubles. Ephemeral edges aren't in the index and aren't
  in history.
- **Rollout:** `edge_history` must exist before a build that cascades unknown types. The `ALTER` for
  `edges.version` is needed before the first annotated type.
- **`UnknownEdge` isn't a gap:** the store never decodes one, and saving one would fail at serialization.

## Open, cane's calls

- **Does delete erase edge history, as on nodes?** "Was a connected to b last year" may be what edge history
  is for, and a cascade can't answer it. Keeping it needs the nodes upgrade path (`idea.md` item 3): a read
  of the newest key on the insert branch. Proposed for v1: mirror nodes.
- **Tag union on `modifyEdge`:** decided in the blocking issue.

## Later

The edge guard follows the node guard (`ai-scripts/VersionGuardPlan.md`, Q3), over the same machinery.
Probed live, semantics only: `ON CONFLICT … WHERE version = ?` matches §2.1 on the composite key, and a
multi-row guarded `UPDATE … FROM unnest` writes only the fresh rows. Two proposed rules, undecided: a
retarget's delete is guarded (an exception to "deletes stay unguarded") and its target is insert-only.
