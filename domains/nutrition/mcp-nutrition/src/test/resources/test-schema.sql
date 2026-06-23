-- Mirrors infra/liquibase/features/{001-core, 050-nutrition}.yml — just enough to run
-- mcp-nutrition integration tests. Kept minimal so drift surfaces as a failing test.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS nutrition;

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

CREATE TABLE IF NOT EXISTS nutrition.meal_log (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    owner_id        uuid REFERENCES core.users(id),
    eaten_at        timestamptz  NOT NULL DEFAULT now(),
    source          varchar(16),
    description     text NOT NULL,
    items           jsonb,
    kcal            integer,
    protein_g       numeric(7,2),
    fat_g           numeric(7,2),
    carbs_g         numeric(7,2),
    image_media_id  uuid,
    metadata        jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_meal_log_household_eaten ON nutrition.meal_log (household_id, eaten_at DESC);

CREATE TABLE IF NOT EXISTS nutrition.diet_profile (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    owner_id        uuid REFERENCES core.users(id),
    goal_kcal       integer,
    goal_protein_g  numeric(7,2),
    goal_fat_g      numeric(7,2),
    goal_carbs_g    numeric(7,2),
    restrictions    jsonb,
    tastes          jsonb,
    notes           text,
    updated_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_diet_profile_household_owner
    ON nutrition.diet_profile (household_id, owner_id);

CREATE TABLE IF NOT EXISTS nutrition.basket (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id      uuid NOT NULL REFERENCES core.households(id),
    owner_id          uuid REFERENCES core.users(id),
    captured_at       timestamptz  NOT NULL DEFAULT now(),
    merchant          varchar(128),
    source            varchar(16),
    receipt_media_id  uuid,
    items             jsonb,
    kcal              integer,
    protein_g         numeric(8,2),
    fat_g             numeric(8,2),
    carbs_g           numeric(8,2),
    analysis          jsonb,
    created_at        timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_basket_household_captured ON nutrition.basket (household_id, captured_at DESC);
