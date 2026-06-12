-- Mirrors infra/liquibase/features/{001-core, 030-tasks}.yml — just enough to run
-- mcp-tasks integration tests. Kept minimal so drift surfaces as a failing test.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS tasks;

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

CREATE TABLE IF NOT EXISTS tasks.task_project (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    owner_id        uuid REFERENCES core.users(id),
    name            varchar(128) NOT NULL,
    status          varchar(16)  NOT NULL DEFAULT 'active',
    note            text,
    metadata        jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_task_project_household ON tasks.task_project (household_id);

CREATE TABLE IF NOT EXISTS tasks.task_item (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id        uuid NOT NULL REFERENCES core.households(id),
    owner_id            uuid REFERENCES core.users(id),
    project_id          uuid REFERENCES tasks.task_project(id),
    title               varchar(256) NOT NULL,
    status              varchar(16)  NOT NULL DEFAULT 'inbox',
    context             varchar(64),
    priority            smallint,
    due_at              timestamptz,
    defer_until         timestamptz,
    note                text,
    source              varchar(32)  NOT NULL DEFAULT 'manual',
    external_ref        varchar(128),
    calendar_event_uid  varchar(256),
    schedule_id         uuid,
    metadata            jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    completed_at        timestamptz
);

CREATE INDEX IF NOT EXISTS ix_task_item_household_status ON tasks.task_item (household_id, status);
CREATE INDEX IF NOT EXISTS ix_task_item_project ON tasks.task_item (project_id);
CREATE INDEX IF NOT EXISTS ix_task_item_due_at ON tasks.task_item (due_at);
CREATE INDEX IF NOT EXISTS ix_task_item_household_context ON tasks.task_item (household_id, context);
