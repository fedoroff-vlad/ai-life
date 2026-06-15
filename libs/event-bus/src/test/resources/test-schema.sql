-- Mirrors infra/liquibase/features/007-bus.yml — keep in sync (drift = failing test).
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
