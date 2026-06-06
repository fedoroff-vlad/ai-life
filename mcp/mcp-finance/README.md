# mcp-finance

MCP server: source-of-truth CRUD over the `finance.*` schema (accounts, categories,
transactions). Stage 2 opener — budgets, recurring payments and matview-backed
aggregates land in later PRs. Money Pro CSV import lives in its own MCP module
(`mcp-money-pro-import`, separate PR).

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
- `list_transactions(householdId, accountId?, categoryId?, from?, to?, limit?)` —
  newest first, `from` inclusive / `to` exclusive, hard cap 200, default 50.
- `get_balance(accountId)` — `opening_balance + Σ amount` (sign-aware). Returns the
  account's currency alongside the balance.

Scope rule: every tool takes a `householdId` and reads/writes only within that
household. Per-user privacy (private accounts filtered by `owner_id`) is the agent
layer's job — this MCP is intentionally low-level.

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_FINANCE_PORT` | `8092` | HTTP port |
| `MCP_FINANCE_DB_URL` | `jdbc:postgresql://localhost:5432/ailife` | Postgres |
| `MCP_FINANCE_DB_USER` / `MCP_FINANCE_DB_PASSWORD` | `ailife` | DB credentials |

## Key classes

- `McpFinanceApplication`.
- `domain/FinAccount` + `FinAccountRepository` — JPA over `finance.fin_account`.
- `domain/FinCategory` + `FinCategoryRepository` — JPA over `finance.fin_category`.
- `domain/FinTransaction` + `FinTransactionRepository` — JPA over
  `finance.fin_transaction`; `filter()` is the parameterised list query (all filters
  optional, newest first), `sumAmountByAccountId()` powers `get_balance`.
- `tools/FinanceMcpTools` — the seven `@Tool` methods. Cross-household account check
  on `add_transaction` is the only invariant guarded here; everything else relies on
  DB constraints.
- `tools/ToolsConfig` — `MethodToolCallbackProvider`.

## Schema

[020-finance.yml](../../infra/liquibase/features/020-finance.yml) — creates
`finance.fin_account`, `finance.fin_category`, `finance.fin_transaction` with the
indices `list_transactions`/`get_balance` need. Budget + recurring tables (and
matviews) ship in later PRs when their tools land.

## Sign / scope notes

- We do NOT enforce sign at the DB layer — the agent owns sign discipline. The MCP
  stores what it's given so manual corrections (e.g. a refund posted as `+`) are
  possible without a workaround tool.
- Transfers between accounts are handled at the agent layer as a pair of rows; this
  MCP does not enforce pairing.
- "Private" vs "shared" accounts: the column exists (`fin_account.owner_id`), the
  enforcement is in the agent. A future PR may add an opt-in scope filter on `list_*`
  tools if the agent layer keeps repeating the same filter.
