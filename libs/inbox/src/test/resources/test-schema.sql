-- Mirrors infra/liquibase/features/016-inbox.yml — keep in sync (drift = failing test).
CREATE SCHEMA IF NOT EXISTS bus;

CREATE TABLE IF NOT EXISTS bus.inbox (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dedup_key        varchar(128) NOT NULL UNIQUE,
    payload          jsonb NOT NULL DEFAULT '{}'::jsonb,
    status           varchar(16) NOT NULL DEFAULT 'PENDING',
    attempts         integer NOT NULL DEFAULT 0,
    last_error       text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    next_attempt_at  timestamptz NOT NULL DEFAULT now(),
    processed_at     timestamptz
);

CREATE INDEX IF NOT EXISTS ix_inbox_due ON bus.inbox (status, next_attempt_at);
