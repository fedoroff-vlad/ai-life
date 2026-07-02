-- Mirrors infra/liquibase/features/{001-core, 070-briefing}.yml — just enough to run
-- mcp-briefing integration tests. Kept minimal so drift surfaces as a failing test.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS briefing;

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

CREATE TABLE IF NOT EXISTS briefing.briefing_profile (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id      uuid NOT NULL REFERENCES core.households(id),
    owner_id          uuid REFERENCES core.users(id),
    location_label    text,
    latitude          double precision,
    longitude         double precision,
    timezone          varchar(64),
    interests         jsonb,
    sections          jsonb,
    schedule_time     varchar(8),
    schedule_enabled  boolean,
    schedule_id       uuid,
    notes             text,
    updated_at        timestamptz  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_briefing_profile_household_owner
    ON briefing.briefing_profile (household_id, owner_id);
