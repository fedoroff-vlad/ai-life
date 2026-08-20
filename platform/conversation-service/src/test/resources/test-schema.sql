-- Mirrors infra/liquibase/features/{001-core, 009-conversation-state, 010-conversation-last-route}.yml minimally.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE SCHEMA IF NOT EXISTS core;

CREATE TABLE IF NOT EXISTS core.households (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(128) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS core.conversation_state (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    user_id         uuid NOT NULL,
    channel         varchar(32) NOT NULL,
    route_lock      varchar(64),
    pending_action  jsonb,
    last_route_agent varchar(64),
    last_route_text  text,
    last_route_trace text,
    expires_at      timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_conversation_state_key UNIQUE (household_id, user_id, channel)
);
