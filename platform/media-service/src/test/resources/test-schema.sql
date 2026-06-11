-- Mirrors infra/liquibase/features/{001-core, 008-media}.yml just enough to run
-- media-service integration tests. Kept minimal so drift surfaces as a failing test.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS media;

CREATE TABLE IF NOT EXISTS core.households (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(128) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS media.media_object (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id uuid NOT NULL REFERENCES core.households(id),
    owner_id     uuid,
    kind         varchar(32)  NOT NULL,
    mime_type    varchar(128) NOT NULL,
    size_bytes   bigint       NOT NULL,
    sha256       varchar(64)  NOT NULL,
    bucket       varchar(63)  NOT NULL,
    storage_key  varchar(256) NOT NULL,
    source       varchar(64),
    metadata     jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at   timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_media_object_household ON media.media_object (household_id);
CREATE INDEX IF NOT EXISTS ix_media_object_sha256    ON media.media_object (sha256);
