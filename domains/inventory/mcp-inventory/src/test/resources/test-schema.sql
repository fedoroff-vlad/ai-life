-- Mirrors infra/liquibase/features/{001-core, 120-inventory}.yml — just enough to run
-- mcp-inventory integration tests. Kept minimal so drift surfaces as a failing test.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS inventory;

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

CREATE TABLE IF NOT EXISTS inventory.storage_zone (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id  uuid NOT NULL REFERENCES core.households(id),
    owner_id      uuid REFERENCES core.users(id),
    name          text NOT NULL,
    kind          varchar(32),
    label_colour  varchar(32),
    note          text,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_storage_zone_household_name
    ON inventory.storage_zone (household_id, lower(name));

CREATE TABLE IF NOT EXISTS inventory.container (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id  uuid NOT NULL REFERENCES core.households(id),
    owner_id      uuid REFERENCES core.users(id),
    zone_id       uuid REFERENCES inventory.storage_zone(id),
    code          varchar(32) NOT NULL,
    label         text,
    kind          varchar(32) DEFAULT 'box',
    qr_token      varchar(64) NOT NULL UNIQUE,
    status        varchar(32) NOT NULL DEFAULT 'open',
    destination   text,
    note          text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    closed_at     timestamptz,
    CONSTRAINT ux_container_household_code UNIQUE (household_id, code)
);

CREATE INDEX IF NOT EXISTS ix_container_household_zone
    ON inventory.container (household_id, zone_id);

CREATE TABLE IF NOT EXISTS inventory.item (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    container_id  uuid NOT NULL REFERENCES inventory.container(id) ON DELETE CASCADE,
    media_id      text NOT NULL,
    title         text,
    description   text,
    tags          jsonb,
    qty           integer DEFAULT 1,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_item_container_created
    ON inventory.item (container_id, created_at);

CREATE INDEX IF NOT EXISTS ix_item_search
    ON inventory.item
    USING GIN ((coalesce(title, '') || ' ' || coalesce(description, '')) gin_trgm_ops);
