-- YugabyteDB YCQL schema for Abyss ephemeral storage.
-- Run once at startup; idempotent.
-- TTL is set per-row at INSERT time: INSERT INTO ... USING TTL <seconds>.

CREATE KEYSPACE IF NOT EXISTS abyss_test_graph
    WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

-- ── ephemeral_nodes ──────────────────────────────────────────────────────────
-- Partition key: id (UUID). Per-row TTL set at write time by YugabyteAbyssStoreLike.
-- `data` carries the full polymorphic JSON (TEXT, not JSONB — YCQL has no JSONB type).

-- No secondary indexes and no transactions flag — both are required together in YugabyteDB YCQL,
-- and transactions=true blocks per-row USING TTL (issue #10992). Primary-key lookups suffice
-- for loadNode(id) and loadEdge(fromId, toId, type).
CREATE TABLE IF NOT EXISTS abyss_test_graph.ephemeral_nodes (
    id          UUID      PRIMARY KEY,
    type        TEXT,
    data        TEXT,
    tags        LIST<TEXT>,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP
);

-- ── ephemeral_edges ──────────────────────────────────────────────────────────
-- Partition key: from_id — co-locates all outgoing edges of a node on one tablet.
-- Clustering columns: (to_id, type) — match EdgeKey ordering.

CREATE TABLE IF NOT EXISTS abyss_test_graph.ephemeral_edges (
    from_id     UUID,
    to_id       UUID,
    type        TEXT,
    data        TEXT,
    tags        LIST<TEXT>,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP,
    PRIMARY KEY ((from_id), to_id, type)
);
