-- Mirrors infra/liquibase/features/{001-core, 040-wardrobe}.yml — just enough to run
-- mcp-wardrobe integration tests. Kept minimal so drift surfaces as a failing test.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS wardrobe;

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

CREATE TABLE IF NOT EXISTS wardrobe.wardrobe_item (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    owner_id        uuid REFERENCES core.users(id),
    name            varchar(128) NOT NULL,
    category        varchar(32),
    colour          varchar(64),
    material        varchar(64),
    pattern         varchar(64),
    season          varchar(32),
    formality       varchar(32),
    image_media_id  uuid,
    metadata        jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_wardrobe_item_household ON wardrobe.wardrobe_item (household_id);
CREATE INDEX IF NOT EXISTS ix_wardrobe_item_household_category ON wardrobe.wardrobe_item (household_id, category);

CREATE TABLE IF NOT EXISTS wardrobe.style_profile (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id      uuid NOT NULL REFERENCES core.households(id),
    owner_id          uuid REFERENCES core.users(id),
    person_type       varchar(64),
    body_shape        varchar(64),
    colour_type       varchar(64),
    suitable_fabrics  jsonb,
    height_cm         integer,
    weight_kg         numeric(5,2),
    measurements      jsonb,
    notes             text,
    image_media_id    uuid,
    updated_at        timestamptz  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_style_profile_household_owner
    ON wardrobe.style_profile (household_id, owner_id);
