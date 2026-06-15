-- Mirrors infra/liquibase/features/{001-core, 002-scheduling, 007-bus}.yml minimally.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE SCHEMA IF NOT EXISTS core;

CREATE SCHEMA IF NOT EXISTS bus;

CREATE TABLE IF NOT EXISTS bus.outbox (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    topic         varchar(128) NOT NULL,
    household_id  uuid,
    payload       jsonb NOT NULL DEFAULT '{}'::jsonb,
    status        varchar(16) NOT NULL DEFAULT 'PENDING',
    created_at    timestamptz NOT NULL DEFAULT now(),
    published_at  timestamptz
);

CREATE INDEX IF NOT EXISTS ix_outbox_pending ON bus.outbox (status, created_at);

CREATE TABLE IF NOT EXISTS core.households (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(128) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS core.shedlock (
    name        varchar(64) PRIMARY KEY,
    lock_until  timestamptz NOT NULL,
    locked_at   timestamptz NOT NULL,
    locked_by   varchar(128) NOT NULL
);

CREATE TABLE IF NOT EXISTS core.schedules (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id  uuid NOT NULL REFERENCES core.households(id),
    owner_agent   varchar(64) NOT NULL,
    kind          varchar(64) NOT NULL,
    cron          varchar(128),
    rrule         varchar(512),
    payload       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    enabled       boolean     NOT NULL DEFAULT true,
    next_run_ts   timestamptz NOT NULL,
    last_run_ts   timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_schedules_due
    ON core.schedules (enabled, next_run_ts);
