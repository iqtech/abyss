# TODO

## High

- **Transaction not atomic across YSQL and YCQL**
  If YSQL commit succeeds and YCQL fails, the graph is silently inconsistent. Currently logs a
  warning and continues.

## Medium

- **`collectNodes` is sequential**
  Node collection after the final hop is a serial loop. Same `coroutineScope + async` fix as
  was applied to `addHop`.

- **Single Hazelcast node**
  `PartitionAware` and partition-predicate routing only matter in a cluster. On one node it
  degrades to a smaller in-memory scan. No cluster topology awareness, near-cache, or partition
  migration hooks.

- **No schema enforcement**
  `@SerialName` type strings are unchecked. Nothing prevents a `Knows` edge connecting two
  non-`Person` nodes. Invalid graphs are silently possible.

## Low

- **YSQL connection acquired per cache-miss query** (`queryNodeYsql` / `queryEdgeYsql`)
  HikariCP pools connections but will queue under burst cold-cache misses.

- **Dual parallel YSQL + YCQL query on every cache miss**
  Half the queries always return nothing. Wasteful under high miss rate.
