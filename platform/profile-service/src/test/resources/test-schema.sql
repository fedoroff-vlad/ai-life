-- Minimal subset of infra/liquibase/features/001-core.yml needed by profile-service
-- integration tests. Kept tiny to make drift obvious when fields change.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";
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
