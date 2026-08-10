-- Mirrors infra/liquibase/features/{001-core, 110-travel}.yml — just enough to run
-- mcp-travel integration tests. Kept minimal so drift surfaces as a failing test.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS travel;

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

CREATE TABLE IF NOT EXISTS travel.travel_profile (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id          uuid NOT NULL REFERENCES core.households(id),
    owner_id              uuid REFERENCES core.users(id),
    home_base_label       text,
    home_base_latitude    double precision,
    home_base_longitude   double precision,
    rest_types            jsonb,
    companions            varchar(16),
    child_ages            jsonb,
    budget_amount         numeric(14,2),
    budget_currency       varchar(8),
    notes                 text,
    updated_at            timestamptz  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_travel_profile_household_owner
    ON travel.travel_profile (household_id, owner_id);
