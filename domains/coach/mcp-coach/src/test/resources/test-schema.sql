-- Mirrors infra/liquibase/features/{001-core, 100-coach}.yml — just enough to run
-- mcp-coach integration tests. Kept minimal so drift surfaces as a failing test.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS coach;

CREATE TABLE IF NOT EXISTS core.households (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(128) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

-- coach.* — all (household_id, subject) scoped; subject is a soft person ref (no FK).

CREATE TABLE IF NOT EXISTS coach.coach_profile (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    subject         uuid NOT NULL,
    method_weights  jsonb,
    tone            text,
    focus_areas     jsonb,
    boundaries      jsonb,
    active          boolean NOT NULL DEFAULT true,
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_coach_profile_household_subject
    ON coach.coach_profile (household_id, subject);

CREATE TABLE IF NOT EXISTS coach.coach_value (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    subject         uuid NOT NULL,
    label           text NOT NULL,
    note            text,
    source          varchar(16) NOT NULL DEFAULT 'stated',
    weight          smallint,
    active          boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_coach_value_household_subject
    ON coach.coach_value (household_id, subject);

CREATE TABLE IF NOT EXISTS coach.coach_session (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    subject         uuid NOT NULL,
    mode            varchar(16) NOT NULL,
    summary         text,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_coach_session_household_subject_created
    ON coach.coach_session (household_id, subject, created_at DESC);

CREATE TABLE IF NOT EXISTS coach.coach_observation (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    subject         uuid NOT NULL,
    session_id      uuid REFERENCES coach.coach_session(id),
    text            text NOT NULL,
    method          varchar(8) NOT NULL,
    evidence_refs   jsonb,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_coach_observation_household_subject
    ON coach.coach_observation (household_id, subject);
CREATE INDEX IF NOT EXISTS ix_coach_observation_session
    ON coach.coach_observation (session_id);

CREATE TABLE IF NOT EXISTS coach.coach_hypothesis (
    id                              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id                    uuid NOT NULL REFERENCES core.households(id),
    subject                         uuid NOT NULL,
    text                            text NOT NULL,
    status                          varchar(16) NOT NULL DEFAULT 'open',
    confidence                      smallint,
    supporting_observation_ids      jsonb,
    contradicting_observation_ids   jsonb,
    created_at                      timestamptz NOT NULL DEFAULT now(),
    updated_at                      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_coach_hypothesis_household_subject_status
    ON coach.coach_hypothesis (household_id, subject, status);

CREATE TABLE IF NOT EXISTS coach.coach_action (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    subject         uuid NOT NULL,
    text            text NOT NULL,
    value_id        uuid REFERENCES coach.coach_value(id),
    hypothesis_id   uuid REFERENCES coach.coach_hypothesis(id),
    status          varchar(16) NOT NULL DEFAULT 'proposed',
    due_at          timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_coach_action_household_subject_status
    ON coach.coach_action (household_id, subject, status);

CREATE TABLE IF NOT EXISTS coach.coach_intake (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    subject         uuid NOT NULL,
    topic           varchar(64),
    question        text NOT NULL,
    answer          text,
    asked_by        varchar(16) NOT NULL DEFAULT 'session',
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_coach_intake_household_subject
    ON coach.coach_intake (household_id, subject);
