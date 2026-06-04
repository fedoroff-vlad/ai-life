-- Minimal subset of infra/liquibase/features/001-core.yml needed by profile-service
-- integration tests. Kept tiny to make drift obvious when fields change.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";
CREATE SCHEMA IF NOT EXISTS core;

CREATE TABLE IF NOT EXISTS core.households (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(128) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS core.users (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id      uuid NOT NULL REFERENCES core.households(id),
    display_name      varchar(128) NOT NULL,
    locale            varchar(16)  NOT NULL DEFAULT 'ru-RU',
    telegram_user_id  bigint UNIQUE,
    role              varchar(32)  NOT NULL DEFAULT 'member',
    created_at        timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS core.people (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id         uuid NOT NULL REFERENCES core.households(id),
    display_name         varchar(128) NOT NULL,
    relationship         varchar(64),
    locale               varchar(16),
    interests            jsonb NOT NULL DEFAULT '[]'::jsonb,
    notes                text,
    lead_days_override   jsonb,
    created_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_people_household ON core.people (household_id);
CREATE INDEX IF NOT EXISTS ix_people_interests_gin
    ON core.people USING GIN (interests jsonb_path_ops);
CREATE INDEX IF NOT EXISTS ix_people_display_name_trgm
    ON core.people USING GIN (display_name gin_trgm_ops);
