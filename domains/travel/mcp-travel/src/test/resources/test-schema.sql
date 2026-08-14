-- Mirrors infra/liquibase/features/{001-core, 003-people, 110-travel, 111-travel-trip, 112-travel-route}.yml
-- — just enough to run mcp-travel integration tests. Kept minimal so drift surfaces as a failing test.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS travel;

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

CREATE TABLE IF NOT EXISTS travel.travel_profile (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id          uuid NOT NULL REFERENCES core.households(id),
    owner_id              uuid REFERENCES core.users(id),
    home_base_label       text,
    home_base_latitude    double precision,
    home_base_longitude   double precision,
    rest_types            jsonb,
    companions            varchar(16),
    child_ages            jsonb,
    budget_amount         numeric(14,2),
    budget_currency       varchar(8),
    notes                 text,
    updated_at            timestamptz  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_travel_profile_household_owner
    ON travel.travel_profile (household_id, owner_id);

-- People owned by a household (mirrors 003-people.yml; only the columns the trip roster FK needs).
CREATE TABLE IF NOT EXISTS core.people (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id uuid NOT NULL REFERENCES core.households(id),
    display_name varchar(128) NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now()
);

-- Trip wallet tables (mirrors 111-travel-trip.yml).
CREATE TABLE IF NOT EXISTS travel.trip (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id   uuid NOT NULL REFERENCES core.households(id),
    owner_id       uuid REFERENCES core.users(id),
    title          text NOT NULL,
    destination    text,
    start_date     date,
    end_date       date,
    home_currency  varchar(8)  NOT NULL DEFAULT 'RUB',
    status         varchar(16) NOT NULL DEFAULT 'planning',
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_trip_household ON travel.trip (household_id);

CREATE TABLE IF NOT EXISTS travel.trip_member (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id    uuid NOT NULL REFERENCES travel.trip(id) ON DELETE CASCADE,
    user_id    uuid REFERENCES core.users(id),
    person_id  uuid REFERENCES core.people(id),
    label      text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_trip_member_one_identity CHECK (num_nonnulls(user_id, person_id) <= 1)
);
CREATE INDEX IF NOT EXISTS ix_trip_member_trip ON travel.trip_member (trip_id);

CREATE TABLE IF NOT EXISTS travel.trip_funding (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id      uuid NOT NULL REFERENCES travel.trip(id) ON DELETE CASCADE,
    currency     varchar(8)    NOT NULL,
    amount       numeric(19,4) NOT NULL,
    rate_to_home numeric(19,4),
    acquired_at  timestamptz   NOT NULL DEFAULT now(),
    note         text,
    CONSTRAINT ck_trip_funding_amount CHECK (amount >= 0),
    CONSTRAINT ck_trip_funding_rate CHECK (rate_to_home IS NULL OR rate_to_home >= 0)
);
CREATE INDEX IF NOT EXISTS ix_trip_funding_trip ON travel.trip_funding (trip_id);

CREATE TABLE IF NOT EXISTS travel.trip_exchange (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id       uuid NOT NULL REFERENCES travel.trip(id) ON DELETE CASCADE,
    from_currency varchar(8)    NOT NULL,
    from_amount   numeric(19,4) NOT NULL,
    to_currency   varchar(8)    NOT NULL,
    to_amount     numeric(19,4) NOT NULL,
    exchanged_at  timestamptz   NOT NULL DEFAULT now(),
    note          text,
    CONSTRAINT ck_trip_exchange_amounts CHECK (from_amount >= 0 AND to_amount >= 0),
    CONSTRAINT ck_trip_exchange_diff CHECK (from_currency <> to_currency)
);
CREATE INDEX IF NOT EXISTS ix_trip_exchange_trip ON travel.trip_exchange (trip_id);

CREATE TABLE IF NOT EXISTS travel.trip_expense (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id     uuid NOT NULL REFERENCES travel.trip(id) ON DELETE CASCADE,
    currency    varchar(8)    NOT NULL,
    amount      numeric(19,4) NOT NULL,
    category    text,
    description text,
    spent_at    timestamptz   NOT NULL DEFAULT now(),
    created_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_trip_expense_amount CHECK (amount >= 0)
);
CREATE INDEX IF NOT EXISTS ix_trip_expense_trip ON travel.trip_expense (trip_id);

-- Route import table (mirrors 112-travel-route.yml).
CREATE TABLE IF NOT EXISTS travel.route (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id  uuid NOT NULL REFERENCES core.households(id),
    trip_id       uuid REFERENCES travel.trip(id) ON DELETE CASCADE,
    name          text        NOT NULL,
    source_format varchar(16) NOT NULL,
    point_count   int         NOT NULL DEFAULT 0,
    distance_m    numeric(19,4),
    geometry      jsonb       NOT NULL,
    imported_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_route_point_count CHECK (point_count >= 0),
    CONSTRAINT ck_route_distance CHECK (distance_m IS NULL OR distance_m >= 0)
);
CREATE INDEX IF NOT EXISTS ix_route_household ON travel.route (household_id);
CREATE INDEX IF NOT EXISTS ix_route_trip ON travel.route (trip_id);
