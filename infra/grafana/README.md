# grafana

Zero-code finance dashboards — "adopt, zero code" (architecture.md). Grafana reads the
`finance.*` matviews directly; no app code, no new schema. Everything here is **provisioned
from files** so the dashboards are reproducible and version-controlled (no click-ops).

## Layout
- `provisioning/datasources/postgres.yml` — read-only Postgres datasource (`uid: ailife-finance-pg`).
  `${POSTGRES_*}` are expanded from the Grafana container env (set in the compose files).
- `provisioning/dashboards/provider.yml` — file provider that loads every dashboard JSON below.
- `dashboards/finance-overview.json` — the one dashboard (uid `ailife-finance-overview`):
  monthly spending trend, spending by category (this month), account balances, and a budget
  burn-down (this month). A `currency` template variable scopes panels so amounts in different
  currencies are never summed together.

## Data source
The two matviews from `024-finance-matviews.yml`:
- `finance.fin_mv_monthly_by_category` — net + spent per (household, month, category, currency).
- `finance.fin_mv_account_balance` — opening + sign-aware sum per account.
Plus `finance.fin_budget` + `finance.fin_category` for the burn-down panel. Matviews are
refreshed by the scheduler/trigger path (see finance.md); the dashboards show last-refresh data.

## Run
Grafana comes up with the infra layer in **both** compose files (`docker-compose.dev.yml` and
`docker-compose.yml`). Open `http://localhost:${GRAFANA_PORT:-3000}` and log in with
`GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` (defaults `admin` / `ailife`). The dashboard is
under the **ai-life** folder.

## Extending
Add another dashboard by dropping a new JSON in `dashboards/` — the provider picks it up within
~30s. Reference the datasource by `uid: ailife-finance-pg`. Keep dashboards household-agnostic
or add a `household` template variable (query `SELECT DISTINCT household_id …`) if multi-household
scoping is needed.
