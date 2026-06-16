-- Mirrors infra/liquibase/features/{001-core, 010-calendar}.yml just enough to run
-- mcp-caldav integration tests. Kept minimal so drift surfaces as a failing test.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS calendar;

CREATE TABLE IF NOT EXISTS core.households (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(128) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS calendar.events_cache (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    source_calendar varchar(64)  NOT NULL DEFAULT 'ours',
    calendar_uid    varchar(255) NOT NULL,
    etag            varchar(255),
    summary         varchar(512) NOT NULL,
    description     text,
    location        varchar(255),
    dtstart         timestamptz  NOT NULL,
    dtend           timestamptz,
    rrule           varchar(512),
    categories      text[]       NOT NULL DEFAULT ARRAY[]::text[],
    person_id       uuid,
    raw_ics         text,
    last_synced_at  timestamptz  NOT NULL DEFAULT now(),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_events_cache_uid UNIQUE (household_id, source_calendar, calendar_uid)
);

CREATE INDEX IF NOT EXISTS ix_events_cache_household_dtstart
    ON calendar.events_cache (household_id, dtstart);
CREATE INDEX IF NOT EXISTS ix_events_cache_person
    ON calendar.events_cache (person_id);
CREATE INDEX IF NOT EXISTS ix_events_cache_categories
    ON calendar.events_cache USING GIN (categories);
CREATE INDEX IF NOT EXISTS ix_events_cache_summary_trgm
    ON calendar.events_cache USING GIN (summary gin_trgm_ops);

-- Lower the trigram similarity threshold so short fuzzy queries like "маша" match
-- "ДР Маши" in tests. Default 0.3 is too strict for cross-token matching.
ALTER DATABASE ailife SET pg_trgm.similarity_threshold = 0.1;
