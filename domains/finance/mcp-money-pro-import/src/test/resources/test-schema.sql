-- Mirrors infra/liquibase/features/{001-core, 020-finance}.yml — just enough to run
-- mcp-money-pro-import integration tests. Kept minimal so drift surfaces as a
-- failing test. Schema is owned by mcp-finance — this file mirrors a subset.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS finance;

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

CREATE TABLE IF NOT EXISTS finance.fin_account (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    owner_id        uuid REFERENCES core.users(id),
    name            varchar(128) NOT NULL,
    type            varchar(16)  NOT NULL,
    currency        varchar(8)   NOT NULL,
    opening_balance numeric(18,2) NOT NULL DEFAULT 0,
    archived        boolean      NOT NULL DEFAULT false,
    metadata        jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS finance.fin_category (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    parent_id       uuid REFERENCES finance.fin_category(id),
    name            varchar(128) NOT NULL,
    kind            varchar(16)  NOT NULL,
    icon            varchar(64),
    metadata        jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_fin_category_household_name_kind UNIQUE (household_id, name, kind)
);

CREATE TABLE IF NOT EXISTS finance.fin_transaction (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    account_id      uuid NOT NULL REFERENCES finance.fin_account(id),
    category_id     uuid REFERENCES finance.fin_category(id),
    owner_id        uuid REFERENCES core.users(id),
    amount          numeric(18,2) NOT NULL,
    currency        varchar(8)    NOT NULL,
    ts              timestamptz   NOT NULL,
    note            text,
    source          varchar(32)   NOT NULL DEFAULT 'manual',
    external_ref    varchar(128),
    metadata        jsonb         NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_fin_transaction_external_ref UNIQUE (household_id, source, external_ref)
);

CREATE INDEX IF NOT EXISTS ix_fin_transaction_account_ts
    ON finance.fin_transaction (account_id, ts DESC);
CREATE INDEX IF NOT EXISTS ix_fin_transaction_household_ts
    ON finance.fin_transaction (household_id, ts DESC);
