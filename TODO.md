# TODO

Scalability issues identified for 1M nodes / few million edges (DAG, mostly forward reads).

## High

- **Edge data invisible after cold Hazelcast restart**
  `loadAllKeys()` returns `null` (no bulk preload). `outEdges` uses predicate on in-memory data only,
  so edges that haven't been individually accessed are invisible to traversal after restart.

## Medium

- **Sequential frontier processing in `TraversalBuilder`**
  Each node in the frontier is queried one by one. Parallelize with `coroutineScope { frontier.map { async { ... } }.awaitAll() }`.

- **No `loadEdges(fromId)` on `AbyssStoreLike`**
  Prevents bulk edge recovery from the store on cache miss. Needed to properly fix cold-restart blindness.

## Low

- **YSQL connection acquired per cache-miss query** (`queryNodeYsql` / `queryEdgeYsql`)
  HikariCP handles it under normal load but will queue under burst cold-cache misses.

- **Dual parallel YSQL + YCQL query on every cache miss**
  Half the queries always return nothing. Wasteful under high miss rate.
