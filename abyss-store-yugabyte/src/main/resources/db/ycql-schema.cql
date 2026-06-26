-- YugabyteDB YCQL schema for Abyss ephemeral storage.
-- Run once at startup; idempotent.
-- TTL is set per-row at INSERT time: INSERT INTO ... USING TTL <seconds>.

CREATE KEYSPACE IF NOT EXISTS abyss_test_graph
    WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

-- ── ephemeral_nodes ──────────────────────────────────────────────────────────
-- Partition key: id (UUID). Per-row TTL set at write time by YugabyteAbyssStoreLike.
-- `data` carries the full polymorphic JSON (TEXT, not JSONB — YCQL has no JSONB type).

CREATE TABLE IF NOT EXISTS abyss_test_graph.ephemeral_nodes (
    id          UUID      PRIMARY KEY,
    type        TEXT,
    data        TEXT,
    tags        LIST<TEXT>,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP
) WITH transactions = {'enabled': 'true'};

CREATE INDEX IF NOT EXISTS idx_ephemeral_nodes_type ON abyss_test_graph.ephemeral_nodes (type);

-- ── ephemeral_edges ──────────────────────────────────────────────────────────
-- Partition key: from_id — co-locates all outgoing edges of a node on one tablet.
-- Clustering columns: (to_id, type) — match EdgeKey ordering.
-- Secondary index on to_id supports inEdges() scatter-gather across partitions.

CREATE TABLE IF NOT EXISTS abyss_test_graph.ephemeral_edges (
    from_id     UUID,
    to_id       UUID,
    type        TEXT,
    data        TEXT,
    tags        LIST<TEXT>,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP,
    PRIMARY KEY ((from_id), to_id, type)
) WITH transactions = {'enabled': 'true'};

CREATE INDEX IF NOT EXISTS idx_ephemeral_edges_to_id ON abyss_test_graph.ephemeral_edges (to_id);
CREATE INDEX IF NOT EXISTS idx_ephemeral_edges_type  ON abyss_test_graph.ephemeral_edges (type);
