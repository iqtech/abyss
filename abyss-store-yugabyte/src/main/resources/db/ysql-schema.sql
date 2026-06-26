CREATE USER abyss WITH PASSWORD 'abyss';
CREATE DATABASE abyss_test_graph WITH OWNER abyss;

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

SET ROLE abyss;

-- connect to new database
-- \c abyss_test_graph

-- YugabyteDB YSQL schema for Abyss durable storage.
-- Run once at startup; idempotent.

CREATE SCHEMA IF NOT EXISTS abyss;

-- ── nodes ────────────────────────────────────────────────────────────────────
-- `data` carries the full polymorphic JSON produced by NodeLikeHzSerializer.
-- `type` mirrors the @SerialName discriminator for indexed filtering.

CREATE TABLE IF NOT EXISTS abyss.nodes (
    id          UUID        PRIMARY KEY,
    type        TEXT        NOT NULL,
    data        JSONB       NOT NULL,
    tags        TEXT[]      NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_nodes_type ON abyss.nodes (type);
CREATE INDEX IF NOT EXISTS idx_nodes_tags  ON abyss.nodes USING GIN (tags);

-- ── edges ────────────────────────────────────────────────────────────────────
-- Primary key matches EdgeKey: (from_id, to_id, type).
-- Secondary indexes on to_id and type support inEdges() and type-filtered scans.

CREATE TABLE IF NOT EXISTS abyss.edges (
    from_id     UUID        NOT NULL,
    to_id       UUID        NOT NULL,
    type        TEXT        NOT NULL,
    data        JSONB       NOT NULL,
    tags        TEXT[]      NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (from_id, to_id, type)
);

CREATE INDEX IF NOT EXISTS idx_edges_to_id ON abyss.edges (to_id);
CREATE INDEX IF NOT EXISTS idx_edges_type  ON abyss.edges (type);
CREATE INDEX IF NOT EXISTS idx_edges_tags  ON abyss.edges USING GIN (tags);

GRANT USAGE ON SCHEMA abyss TO abyss;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA abyss TO abyss;
