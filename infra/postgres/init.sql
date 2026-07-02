-- Runs once on first Postgres start (mounted into /docker-entrypoint-initdb.d/).
-- Idempotent statements so re-applying via psql is safe.

-- Extensions
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
-- NOTE: Apache AGE (graph) will be added in Stage 4 via a custom Postgres image.

-- Schemas — one per bounded context, NOT per service.
CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS memory;
CREATE SCHEMA IF NOT EXISTS audit;
CREATE SCHEMA IF NOT EXISTS bus;
CREATE SCHEMA IF NOT EXISTS media;
CREATE SCHEMA IF NOT EXISTS calendar;
CREATE SCHEMA IF NOT EXISTS finance;
CREATE SCHEMA IF NOT EXISTS tasks;
CREATE SCHEMA IF NOT EXISTS wardrobe;
CREATE SCHEMA IF NOT EXISTS nutrition;
CREATE SCHEMA IF NOT EXISTS creator;
CREATE SCHEMA IF NOT EXISTS briefing;
CREATE SCHEMA IF NOT EXISTS docs;

-- Schemas are populated by Liquibase changelogs (infra/liquibase/features/*).
