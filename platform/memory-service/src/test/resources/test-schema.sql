-- Mirrors infra/liquibase/features/{001-core, 040-memory}.yml just enough to run
-- memory-service integration tests. Kept minimal so drift surfaces as a failing test.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS memory;

CREATE TABLE IF NOT EXISTS core.households (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(128) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS memory.memories (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id uuid NOT NULL REFERENCES core.households(id),
    user_id      uuid,
    person_id    uuid,
    source       varchar(64) NOT NULL,
    text         text        NOT NULL,
    metadata     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    embedding    vector(384) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_memories_household ON memory.memories (household_id);
CREATE INDEX IF NOT EXISTS ix_memories_person    ON memory.memories (person_id);
CREATE INDEX IF NOT EXISTS ix_memories_embedding_hnsw
    ON memory.memories USING hnsw (embedding vector_cosine_ops);
