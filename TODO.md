# TODO

## High

- **Transaction not atomic across YSQL and YCQL**
  If YSQL commit succeeds and YCQL fails, the graph is silently inconsistent. Currently logs a
  warning and continues.

## Medium

- **Single Hazelcast node**
  `PartitionAware` and partition-predicate routing only matter in a cluster. On one node it
  degrades to a smaller in-memory scan. No cluster topology awareness, near-cache, or partition
  migration hooks.

- **No schema enforcement**
  `@SerialName` type strings are unchecked. Nothing prevents a `Knows` edge connecting two
  non-`Person` nodes. Invalid graphs are silently possible.

- **Graph export / import (property graph JSON)**
  Export the full graph (or a subgraph) to the nodes + relationships flat JSON format compatible
  with Neo4j, Gephi, and similar tools. Import in the same format via `transaction { }`.
  Node labels and edge types map to `@SerialName` values.

## Low

- **YSQL connection acquired per cache-miss query** (`queryNodeYsql` / `queryEdgeYsql`)
  HikariCP pools connections but will queue under burst cold-cache misses.

- **Dual parallel YSQL + YCQL query on every cache miss**
  Half the queries always return nothing. Wasteful under high miss rate.
