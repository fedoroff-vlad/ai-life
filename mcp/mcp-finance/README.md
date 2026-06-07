# mcp-finance

MCP server: source-of-truth CRUD + budget tracking over the `finance.*` schema
(accounts, categories, transactions, budgets). Recurring payments and
matview-backed aggregates still land in later PRs. Money Pro CSV import lives
in its own MCP module (`mcp-money-pro-import`, separate PR).

## Tools (MCP)

- `upsert_account(id?, householdId, ownerId?, name, type, currency, openingBalance?, archived?)`
  — create or update a finance account (`type` ∈ `card|cash|deposit|credit`). `ownerId`
  null = household-shared; non-null = private to one member.
- `list_accounts(householdId)` — newest first, includes archived rows.
- `upsert_category(id?, householdId, parentId?, name, kind, icon?)` — `kind` ∈
  `income|expense|transfer`. Unique on `(household, name, kind)`.
- `list_categories(householdId)` — ordered by name.
- `add_transaction(householdId, accountId, categoryId?, ownerId?, amount, currency?, ts?, note?, source?, externalRef?)`
  — sign convention `expense<0, income>0`. `currency` defaults to the account's
  currency. `source` defaults to `manual`; `(household, source, externalRef)` is unique
  for idempotent re-import. Rejects accounts from a different household than
  `householdId` to keep cross-household leaks impossible at the API boundary.
  When `categoryId` is omitted AND `source` is not `moneypro_import`, the tool
  registers a one-shot `transaction.uncategorised` schedule (runAt=now, payload
  `{transactionId}`) so finance-agent's `transaction-categorizer` skill can
  suggest a category for the user. Bulk imports skip this hook to avoid
  flooding the user with N suggestions. Soft-fail: scheduler outage logs WARN
  and is swallowed — the row is still saved.
- `list_transactions(householdId, accountId?, categoryId?, from?, to?, limit?)` —
  newest first, `from` inclusive / `to` exclusive, hard cap 200, default 50.
- `get_balance(accountId)` — `opening_balance + Σ amount` (sign-aware). Returns the
  account's currency alongside the balance.
- `set_budget(householdId, categoryId, period, limitAmount, currency)` — upsert
  the active budget for a (household, category, period); `period` ∈
  `month|week|year`. Time-versioned: the prior active row gets `valid_to=now()`
  and a fresh row is inserted, so history stays queryable. Rejects
  cross-household categories.
- `get_budget_status(householdId, categoryId, period)` — snapshot of the active
  budget for the current UTC period window. Returns limit, `spent` (absolute
  value of net spending — refunds reduce it), `[periodFrom, periodTo)`, and
  `ratio = spent / limit` (null when limit is 0). Throws if no active budget
  exists. Used by the scheduler-driven `budget.alert` trigger.
- `spending_by_category(householdId, from, to, kind?)` — aggregate spend
  grouped by `(category, currency)`. `kind` defaults to `expense`; empty
  string includes every kind. Ordered by absolute spend descending.
  Multi-currency categories produce one row per currency — mcp-finance does
  not convert.
- `upsert_recurring(id?, householdId, ownerId?, accountId, categoryId?, name, amount, currency?, cron, note?, autoRemind?)`
  — define a recurring template ("rent", "spotify", "salary"). `cron` is a
  Spring `CronExpression` (UTC); `next_due` is recomputed on every upsert so
  a changed cadence takes effect immediately. Cross-household account
  rejected. When `autoRemind=true`, the tool auto-registers a
  `recurring.due` schedule in scheduler-service whose cron equals the row's
  cron (PR29b). Lifecycle: enabling autoRemind on a row that had none →
  register; flipping autoRemind off → delete; cron change while autoRemind
  is on → register new + delete old (register-first ordering matches PR27b).
- `list_recurring(householdId, accountId?, categoryId?)` — ordered by
  `next_due` ascending (soonest first; nulls last).

Scope rule: every tool takes a `householdId` and reads/writes only within that
household. Per-user privacy (private accounts filtered by `owner_id`) is the agent
layer's job — this MCP is intentionally low-level.

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_FINANCE_PORT` | `8092` | HTTP port |
| `MCP_FINANCE_DB_URL` | `jdbc:postgresql://localhost:5432/ailife` | Postgres |
| `MCP_FINANCE_DB_USER` / `MCP_FINANCE_DB_PASSWORD` | `ailife` | DB credentials |
| `SCHEDULER_URL` | `http://scheduler-service:8085` | Used by `set_budget` to register/delete the recurring `budget.alert` cron. |
| `MCP_FINANCE_BUDGET_CRON` | `0 0 9 * * *` | Cron for the auto-registered `budget.alert` schedule. Spring `CronExpression`, UTC. Default fires daily at 09:00 UTC regardless of budget period — finance-agent recomputes the period window at trigger time. |
| `MCP_FINANCE_BUDGET_OWNER_AGENT` | `finance` | `ownerAgent` written on the schedule row. |
| `MCP_FINANCE_BUDGET_TRIGGER_KIND` | `budget.alert` | `kind` the agent dispatches on. |
| `MCP_FINANCE_RECURRING_OWNER_AGENT` | `finance` | `ownerAgent` for the auto-registered `recurring.due` schedule. |
| `MCP_FINANCE_RECURRING_TRIGGER_KIND` | `recurring.due` | `kind` for the recurring schedule (no cron prop — cron comes from `fin_recurring.cron`). |
| `MCP_FINANCE_TRANSACTION_OWNER_AGENT` | `finance` | `ownerAgent` for the one-shot `transaction.uncategorised` schedule. |
| `MCP_FINANCE_TRANSACTION_TRIGGER_KIND` | `transaction.uncategorised` | `kind` for the one-shot categorisation schedule fired by `add_transaction` when a row lands without a category. No cron — `runAt=now`. |

## Key classes

- `McpFinanceApplication`.
- `domain/FinAccount` + `FinAccountRepository` — JPA over `finance.fin_account`.
- `domain/FinCategory` + `FinCategoryRepository` — JPA over `finance.fin_category`.
- `domain/FinTransaction` + `FinTransactionRepository` — JPA over
  `finance.fin_transaction`; `filter()` is the parameterised list query (all filters
  optional, newest first), `sumAmountByAccountId()` powers `get_balance`,
  `sumAmountByCategoryInWindow()` powers `get_budget_status`, and
  `spendingByCategory()` is the native-SQL group-by behind the tool of the
  same name.
- `domain/FinBudget` + `FinBudgetRepository` — JPA over `finance.fin_budget`;
  `findActive()` is the partial-unique-index-guarded "currently-active budget"
  lookup.
- `domain/FinRecurring` + `FinRecurringRepository` — JPA over
  `finance.fin_recurring`; `filter()` is the parameterised list ordered by
  `next_due ASC NULLS LAST`.
- `tools/FinanceMcpTools` — ten `@Tool` methods. Cross-household guards on
  `add_transaction` (account) and `set_budget` (category) are the only
  invariants enforced here; everything else relies on DB constraints. Period
  windows in `get_budget_status` are UTC-anchored.
- `tools/ToolsConfig` — `MethodToolCallbackProvider`.
- `config/McpFinanceProperties` — `mcp-finance.scheduler-url` + `mcp-finance.budget.{cron,owner-agent,trigger-kind}`.
- `config/HttpConfig` — `schedulerWebClient` bean (`.clone()` per outbound — same builder-leakage caveat as elsewhere).
- `scheduler/SchedulerClient` — synchronous client to scheduler-service. `register(household, categoryId, period)` for the budget loop (cron from props), `registerRecurring(household, recurringId, cron)` for the recurring loop (cron per-row), `registerTransactionUncategorisedOneshot(household, transactionId, runAt)` for the one-shot categorisation wake fired by `add_transaction`, `delete(scheduleId)`. Soft-fail on outage: a failed register returns null and the row is still saved; delete swallows errors. Mirrors mcp-ics-import's `SchedulerClient` (PR13).

## Internal REST passthroughs

Non-MCP, no LLM tax — for system callers driven by scheduler-service.

- `GET /internal/budget-status?householdId=<uuid>&categoryId=<uuid>&period=<month|week|year>`
  → `BudgetStatusResult` (200) | 404 when no active budget exists. Used by
  finance-agent to enrich a scheduler-driven `budget.alert` wake payload before
  the LLM call. Mirrors mcp-ics-import's `/internal/pull/{subscriptionId}`
  pattern.
- `GET /internal/recurring/{id}` → `FinRecurringDto` (200) | 404. Used by
  finance-agent to enrich a scheduler-driven `recurring.due` wake payload
  (PR29a).
- `POST /internal/recurring/{id}/advance` → `FinRecurringDto` (200, updated
  `next_due`) | 404. Called by finance-agent after a successful
  `recurring.due` trigger so `fin_recurring.next_due` stops being a stale
  snapshot once the cron starts firing (PR30). scheduler-service still
  advances its own `next_run_ts` independently — this endpoint only updates
  the agent-visible column.
- `GET /internal/transaction/{id}` → `FinTransactionDto` (200) | 404. Used by
  finance-agent to enrich a scheduler-driven `transaction.uncategorised` wake
  payload (just `{transactionId}` from `add_transaction`'s soft hook) into the
  full transaction so the `transaction-categorizer` skill can suggest a
  category.

## Schema

- [020-finance.yml](../../infra/liquibase/features/020-finance.yml) —
  `finance.fin_account`, `finance.fin_category`, `finance.fin_transaction`
  with the indices `list_transactions` / `get_balance` need.
- [021-fin-budget.yml](../../infra/liquibase/features/021-fin-budget.yml) —
  `finance.fin_budget` with a partial unique index
  `uq_fin_budget_active(household_id, category_id, period) WHERE valid_to IS
  NULL` so at most one active row per slot.
- [022-fin-budget-schedule-id.yml](../../infra/liquibase/features/022-fin-budget-schedule-id.yml) —
  nullable `fin_budget.schedule_id uuid` for the auto-registered scheduler
  row. No FK to `core.schedules` so a deleted schedule row never
  cascade-corrupts a budget row.
- [023-finance-recurring.yml](../../infra/liquibase/features/023-finance-recurring.yml) —
  `finance.fin_recurring` (id, household, owner?, account, category?, name,
  amount, currency, cron, next_due, note, auto_remind, schedule_id, metadata,
  created_at) with indices on `household_id` and `next_due`. Matviews still
  land in a later PR when their tools do.

## Sign / scope notes

- We do NOT enforce sign at the DB layer — the agent owns sign discipline. The MCP
  stores what it's given so manual corrections (e.g. a refund posted as `+`) are
  possible without a workaround tool.
- Transfers between accounts are handled at the agent layer as a pair of rows; this
  MCP does not enforce pairing.
- "Private" vs "shared" accounts: the column exists (`fin_account.owner_id`), the
  enforcement is in the agent. A future PR may add an opt-in scope filter on `list_*`
  tools if the agent layer keeps repeating the same filter.
