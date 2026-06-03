# Finance domain

Source of truth: **Postgres `finance.*`** (our own schema, not Firefly III). Money Pro = one-time CSV history import. Dashboards: Metabase/Grafana over `finance.*` + matviews.

## Schema `finance` (020-finance.yml)
- `fin_account` — id, owner_id, household_id (private/shared), name, type (card|cash|deposit|credit), currency, opening_balance, archived, metadata jsonb, created_at.
- `fin_category` — id, household_id, parent_id (self-ref), name, kind (income|expense|transfer), icon, metadata jsonb.
- `fin_transaction` — id, account_id, category_id, amount numeric (sign: expense<0 / income>0), currency, ts, note, owner_id, household_id, source (manual|telegram|moneypro_import), external_ref (Money Pro id for idempotent import), metadata jsonb, created_at.
- `fin_budget` — id, household_id, category_id, period (month|week|year), limit_amount, currency, valid_from, valid_to, metadata jsonb.
- `fin_recurring` — id, owner_id, household_id, account_id, category_id, amount, rrule/cron, next_due, note, auto_remind bool, metadata jsonb.
- Matviews: `fin_mv_monthly_by_category`, `fin_mv_account_balance` (opening + sum tx). Refreshed via scheduler or trigger.

## mcp-finance (mcp/)
CRUD + aggregates over Postgres. Tools: `add_transaction`, `update_transaction`, `delete_transaction`, `list_transactions`, `get_balance`, `spending_by_category`, `upsert_category`, `list_categories`, `set_budget`, `get_budget_status`, `upsert_recurring`, `list_recurring`, `export_csv`.

## mcp-money-pro-import (mcp/)
`import_moneypro_csv(file_ref, account_map)` — autodetect delimiter/encoding, dry-run preview, idempotent by `external_ref` (no double-insert on re-import). Reports created/skipped.

## finance-agent (agents/)
Tools: mcp-finance, mcp-money-pro-import. Cross-cutting: chart-render (charts to TG), scheduler (payment reminders), memory, telegram, media (OCR receipt → tx). May answer calendar's gift-budget query via orchestrator.
Principles: money is sensitive — respect owner/scope, don't show private accounts in household scope; amounts always with currency; expense negative; suggest category from text/history, ask if ambiguous; receipt photo → media OCR → show parsed → confirm → add; spending/trend questions → data + chart image; budget overflow warns, doesn't block; confirm before delete/bulk.

## Skills (skills/finance/)
- `transaction-categorizer` — rules + LLM fallback.
- `receipt-parser` — photo → amount/date/merchant/items → draft tx → confirm (LLM-vision at start, later mcp-ocr).
- `budget-alerts` — proactive via scheduler.
- (later) `spending-report`, `budget-check`, `chart-spec`.

## Reminders → scheduler-service
`fin_recurring.auto_remind=true` → agent registers `mcp-scheduler.schedule_recurring(target=finance, payload=pay X)`. Scheduler wakes finance-agent via orchestrator on due date.
