-- Mirrors infra/liquibase/features/{001-core, 020-finance, 024-finance-matviews, 025-fin-gift-budget-rule}.yml —
-- just enough to run mcp-finance integration tests. Kept minimal so drift surfaces
-- as a failing test.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS finance;

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

CREATE TABLE IF NOT EXISTS finance.fin_account (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    owner_id        uuid REFERENCES core.users(id),
    name            varchar(128) NOT NULL,
    type            varchar(16)  NOT NULL,
    currency        varchar(8)   NOT NULL,
    opening_balance numeric(18,2) NOT NULL DEFAULT 0,
    archived        boolean      NOT NULL DEFAULT false,
    metadata        jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_fin_account_household ON finance.fin_account (household_id);

CREATE TABLE IF NOT EXISTS finance.fin_category (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    parent_id       uuid REFERENCES finance.fin_category(id),
    name            varchar(128) NOT NULL,
    kind            varchar(16)  NOT NULL,
    icon            varchar(64),
    metadata        jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_fin_category_household_name_kind UNIQUE (household_id, name, kind)
);

CREATE INDEX IF NOT EXISTS ix_fin_category_household ON finance.fin_category (household_id);

CREATE TABLE IF NOT EXISTS finance.fin_transaction (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    account_id      uuid NOT NULL REFERENCES finance.fin_account(id),
    category_id     uuid REFERENCES finance.fin_category(id),
    owner_id        uuid REFERENCES core.users(id),
    amount          numeric(18,2) NOT NULL,
    currency        varchar(8)    NOT NULL,
    ts              timestamptz   NOT NULL,
    note            text,
    source          varchar(32)   NOT NULL DEFAULT 'manual',
    external_ref    varchar(128),
    metadata        jsonb         NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_fin_transaction_external_ref UNIQUE (household_id, source, external_ref)
);

CREATE INDEX IF NOT EXISTS ix_fin_transaction_account_ts
    ON finance.fin_transaction (account_id, ts DESC);
CREATE INDEX IF NOT EXISTS ix_fin_transaction_household_ts
    ON finance.fin_transaction (household_id, ts DESC);
CREATE INDEX IF NOT EXISTS ix_fin_transaction_category
    ON finance.fin_transaction (category_id);

CREATE TABLE IF NOT EXISTS finance.fin_budget (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    category_id     uuid NOT NULL REFERENCES finance.fin_category(id),
    period          varchar(16)   NOT NULL,
    limit_amount    numeric(18,2) NOT NULL,
    currency        varchar(8)    NOT NULL,
    valid_from      timestamptz   NOT NULL DEFAULT now(),
    valid_to        timestamptz,
    schedule_id     uuid,
    metadata        jsonb         NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_fin_budget_household ON finance.fin_budget (household_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_fin_budget_active
    ON finance.fin_budget (household_id, category_id, period)
    WHERE valid_to IS NULL;

CREATE TABLE IF NOT EXISTS finance.fin_gift_budget_rule (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    relationship    varchar(64)   NOT NULL,
    amount          numeric(18,2) NOT NULL,
    currency        varchar(8)    NOT NULL,
    metadata        jsonb         NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_fin_gift_budget_rule_household
    ON finance.fin_gift_budget_rule (household_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_fin_gift_budget_rule
    ON finance.fin_gift_budget_rule (household_id, lower(relationship));

CREATE TABLE IF NOT EXISTS finance.fin_recurring (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    uuid NOT NULL REFERENCES core.households(id),
    owner_id        uuid REFERENCES core.users(id),
    account_id      uuid NOT NULL REFERENCES finance.fin_account(id),
    category_id     uuid REFERENCES finance.fin_category(id),
    name            varchar(128)  NOT NULL,
    amount          numeric(18,2) NOT NULL,
    currency        varchar(8)    NOT NULL,
    cron            varchar(64)   NOT NULL,
    next_due        timestamptz,
    note            text,
    auto_remind     boolean       NOT NULL DEFAULT false,
    schedule_id     uuid,
    metadata        jsonb         NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_fin_recurring_household ON finance.fin_recurring (household_id);
CREATE INDEX IF NOT EXISTS ix_fin_recurring_next_due ON finance.fin_recurring (next_due);

-- Reporting matviews (024-finance-matviews.yml). Dashboards read these directly;
-- mcp-finance refreshes them via the refresh_matviews tool.
CREATE MATERIALIZED VIEW IF NOT EXISTS finance.fin_mv_monthly_by_category AS
SELECT
    t.household_id                                       AS household_id,
    (date_trunc('month', t.ts AT TIME ZONE 'UTC'))::date AS month,
    t.category_id                                        AS category_id,
    c.name                                               AS category_name,
    c.kind                                               AS category_kind,
    t.currency                                           AS currency,
    sum(t.amount)                                        AS net_amount,
    sum(CASE WHEN t.amount < 0 THEN -t.amount ELSE 0 END) AS spent,
    count(*)                                             AS tx_count
FROM finance.fin_transaction t
LEFT JOIN finance.fin_category c ON c.id = t.category_id
GROUP BY t.household_id,
         (date_trunc('month', t.ts AT TIME ZONE 'UTC'))::date,
         t.category_id, c.name, c.kind, t.currency
WITH DATA;

CREATE INDEX IF NOT EXISTS ix_fin_mv_monthly_by_category_household
    ON finance.fin_mv_monthly_by_category (household_id, month);

CREATE MATERIALIZED VIEW IF NOT EXISTS finance.fin_mv_account_balance AS
SELECT
    a.id                                           AS account_id,
    a.household_id                                 AS household_id,
    a.name                                         AS account_name,
    a.currency                                     AS currency,
    a.archived                                     AS archived,
    a.opening_balance + COALESCE(sum(t.amount), 0) AS balance,
    count(t.id)                                    AS tx_count
FROM finance.fin_account a
LEFT JOIN finance.fin_transaction t ON t.account_id = a.id
GROUP BY a.id, a.household_id, a.name, a.currency, a.archived, a.opening_balance
WITH DATA;

CREATE INDEX IF NOT EXISTS ix_fin_mv_account_balance_household
    ON finance.fin_mv_account_balance (household_id);
