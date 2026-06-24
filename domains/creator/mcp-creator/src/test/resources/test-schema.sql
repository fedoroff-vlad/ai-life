-- Mirrors infra/liquibase/features/{001-core, 060-creator}.yml — just enough to run
-- mcp-creator integration tests. Kept minimal so drift surfaces as a failing test.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS creator;

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

CREATE TABLE IF NOT EXISTS creator.creator_profile (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id  uuid NOT NULL REFERENCES core.households(id),
    owner_id      uuid REFERENCES core.users(id),
    niche         text,
    audience      text,
    tone          varchar(64),
    platforms     jsonb,
    goals         text,
    guardrails    jsonb,
    notes         text,
    updated_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_creator_profile_household_owner
    ON creator.creator_profile (household_id, owner_id);

CREATE TABLE IF NOT EXISTS creator.trend (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id  uuid NOT NULL REFERENCES core.households(id),
    owner_id      uuid REFERENCES core.users(id),
    source        varchar(16),
    platform      varchar(32),
    title         text NOT NULL,
    url           text,
    summary       text,
    metrics       jsonb,
    captured_at   timestamptz  NOT NULL DEFAULT now(),
    created_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_trend_household_captured ON creator.trend (household_id, captured_at DESC);

CREATE TABLE IF NOT EXISTS creator.content_piece (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id  uuid NOT NULL REFERENCES core.households(id),
    owner_id      uuid REFERENCES core.users(id),
    kind          varchar(16) NOT NULL,
    platform      varchar(32),
    title         text,
    body          text,
    cta           text,
    hashtags      jsonb,
    status        varchar(16) NOT NULL DEFAULT 'new',
    trend_id      uuid,
    created_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_content_piece_household_created ON creator.content_piece (household_id, created_at DESC);
