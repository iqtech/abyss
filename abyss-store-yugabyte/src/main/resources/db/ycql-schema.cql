-- YugabyteDB YCQL schema for Abyss ephemeral storage.
-- Run once at startup; idempotent.
-- TTL is set per-row at INSERT time: INSERT INTO ... USING TTL <seconds>.

CREATE KEYSPACE IF NOT EXISTS abyss_test_graph
    WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

USE abyss_test_graph;

-- ── ephemeral_nodes ──────────────────────────────────────────────────────────
-- Partition key: id — raw NodeId byte encoding (see KeyAdapter), not a UUID column,
-- since the same schema serves Uuid/Long/String IDs uniformly. YugabyteEphemeralStore
-- always binds it as a ByteBuffer (idBuf). Per-row TTL set at write time.
-- `data` carries the full polymorphic JSON (TEXT, not JSONB — YCQL has no JSONB type).

-- No secondary indexes and no transactions flag — both are required together in YugabyteDB YCQL,
-- and transactions=true blocks per-row USING TTL (issue #10992). Primary-key lookups suffice
-- for loadNode(id) and loadEdge(fromId, toId, type).
CREATE TABLE IF NOT EXISTS ephemeral_nodes (
    id              BLOB      PRIMARY KEY,
    type            TEXT,
    data            TEXT,
    tags            LIST<TEXT>,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP,
    ttl_expiration  TIMESTAMP
);

-- ── ephemeral_edges ──────────────────────────────────────────────────────────
-- Partition key: from_id — co-locates all outgoing edges of a node on one tablet.
-- Clustering columns: (to_id, type) — match EdgeKey ordering.
-- from_id/to_id are raw NodeId bytes, same rationale as ephemeral_nodes.id above.

CREATE TABLE IF NOT EXISTS ephemeral_edges (
    from_id         BLOB,
    to_id           BLOB,
    type            TEXT,
    data            TEXT,
    tags            LIST<TEXT>,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP,
    ttl_expiration  TIMESTAMP,
    PRIMARY KEY ((from_id), to_id, type)
);

-- ── ephemeral_reverse_edges ──────────────────────────────────────────────────
-- Reverse index for inEdges cold-restart warming. Partition key: to_id.
-- Mirrors ephemeral_edges; written/deleted together with same TTL (non-atomic).
-- Secondary indexes can't be used: transactions=true blocks per-row USING TTL.

CREATE TABLE IF NOT EXISTS ephemeral_reverse_edges (
    to_id           BLOB,
    from_id         BLOB,
    type            TEXT,
    data            TEXT,
    tags            LIST<TEXT>,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP,
    ttl_expiration  TIMESTAMP,
    PRIMARY KEY ((to_id), from_id, type)
);
