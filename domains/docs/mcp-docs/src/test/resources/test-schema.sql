-- Mirrors infra/liquibase/features/{001-core, 080-docs}.yml — just enough to run
-- mcp-docs integration tests. Kept minimal so drift surfaces as a failing test.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS docs;

CREATE TABLE IF NOT EXISTS core.households (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(128) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS core.users (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    display_name    varchar(128) NOT NULL,
    locale          varchar(16)  NOT NULL DEFAULT 'ru-RU',
    telegram_user_id bigint,
    role            varchar(32)  NOT NULL DEFAULT 'member',
    created_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS docs.document (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id  uuid NOT NULL REFERENCES core.households(id),
    owner_id      uuid REFERENCES core.users(id),
    media_id      text NOT NULL,
    doc_type      varchar(32),
    title         text,
    party         text,
    doc_date      date,
    amount        numeric(18,2),
    currency      varchar(8),
    ocr_text      text,
    tags          jsonb,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_document_household_created
    ON docs.document (household_id, created_at);

CREATE INDEX IF NOT EXISTS ix_document_search
    ON docs.document
    USING GIN ((coalesce(title, '') || ' ' || coalesce(party, '') || ' ' || coalesce(ocr_text, '')) gin_trgm_ops);
