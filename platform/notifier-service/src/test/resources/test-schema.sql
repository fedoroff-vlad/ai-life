-- Mirrors infra/liquibase/features/007-bus.yml minimally (consumer needs bus.outbox).

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

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

-- Mirrors infra/liquibase/features/013-notification-preference.yml (#487 PX-1): the proactive-UX gate store.
CREATE SCHEMA IF NOT EXISTS core;

CREATE TABLE IF NOT EXISTS core.notification_preference (
    user_id     uuid PRIMARY KEY,
    quiet_start time,
    quiet_end   time,
    tz          varchar(64) NOT NULL DEFAULT 'UTC',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS core.notification_held (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL,
    body          text NOT NULL,
    source        varchar(128),
    held_at       timestamptz NOT NULL DEFAULT now(),
    deliver_after timestamptz NOT NULL
);
