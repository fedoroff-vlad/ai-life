# Finance domain

Source of truth: **Postgres `finance.*`** (our own schema, not Firefly III). Money Pro = one-time CSV history import. Dashboards: Grafana over `finance.*` + matviews — ✅ **shipped** (#196 PR1, zero-code: provisioned datasource + `Finance overview` dashboard in `infra/grafana/`; monthly trend + spending-by-category + balances + budget burn-down over the matviews). The Telegram HTML report skill (text-first monthly summary) is ✅ **shipped** (#196 PR2: `monthly-report` skill → `MonthlyReporter` on the Coordinator + `DeliverablePublisher`; current-month narrative + deterministic category breakdown → HTML → media-service → TG link; inline charts deferred to `chart-render`).

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
- `transaction-categorizer` — rules + LLM fallback. ✅
- `receipt-parser` — photo → amount/date/merchant → draft tx → confirm. ✅ (now via the shared `mcp-media-processing` `caption` capability, MP-c).
- `budget-alerts` — proactive via scheduler. ✅
- `recurring-due` — proactive reminder before a recurring line's `next_due`. ✅
- `financial-advisor` — reactive spending **analysis** on request (summary + category trends + why a category grew + optimisation hints), text-first. **MVP now** — Coordinator gather (`spending_by_category` + balances) → LLM synthesis. (Chart image is a later add — see Vision below.)
- `investment-advisor` — reactive **advisory** on request over the named symbols (stocks/funds/metals/crypto), **advisory-only — never trades**. ✅ Coordinator gather (`quote` per symbol via the sibling `mcp-market-data` capability) → LLM synthesis of considerations; routed via the `invest` classifier action. See [market-data.md](market-data.md).
- `monthly-report` — reactive **monthly finance report** on request, the domain's first Telegram **deliverable** (#196). ✅ Coordinator gather (current month's `spending-by-category`) → LLM narrative → HTML report board (spending bar chart via `mcp-chart-render` + narrative + deterministic per-category breakdown) via the shared `DeliverablePublisher` (`libs/doc-render` → media-service → link); routed via the `report` classifier action (`period:month`). Inline chart landed with #291.
- `year-report` — reactive **annual finance report** on request (#291). ✅ Coordinator gather (the year's `spending-by-category` + a per-month spend trend) → LLM narrative → HTML board with a category **bar** chart + a per-month **line** trend (both via `mcp-chart-render`) + deterministic breakdown; routed via the `report` classifier action with `period:year`.

## MVP boundary & recorded vision (owner, 2026-06-20)
The owner's full finance vision is recorded here so it is not lost. **Build now = MVP only:**
1. **Receipt → capture** income/expense (OCR/vision draft → confirm → write). ✅ shipped (`receipt-parser`).
2. **Согласование трат** — confirm-before-write on the draft. ✅ shipped (conversation-state route-lock / resume).
3. **Анализ трат** — on-request spending analysis. ← the one missing MVP piece (`financial-advisor`, in progress).

Everything below is **deliberately deferred** (recorded, not now). Each maps to an existing
architectural home — none needs a new layer:

| Vision item | Architectural home | Why deferred |
|---|---|---|
| **Year analysis with chart + %s** | `year-report` skill (`YearReporter`) + the shared **`chart-render` capability-MCP** (data → PNG for Telegram; reused by briefing) | ✅ **DONE** (#291, 2026-07-07). `chart-render` capability shipped (#292); finance-agent binds it (`ChartRenderClient`). `monthly-report` now leads with a spending bar chart; the new `year-report` (`report` action + `period:year`) gathers the year + a per-month trend and renders a category **bar** + monthly **line** chart into the HTML board. Shared breakdown/format helpers lifted to `ReportFormatting`. |
| **Report template** (periods, breakdowns, benchmarks, anomaly rules) | the `monthly-report` skill (`MonthlyReporter`) | ✅ **text-first monthly summary shipped** (#196): current-month narrative + deterministic category breakdown → HTML → Telegram link. Remaining (deferred): arbitrary periods, benchmarks/anomaly rules, inline charts (`chart-render`). |
| **Create / group spending categories from chat** | `upsert_category` (+ `parent_id` for groups) already exists; needs a thin agent skill | tool is ready; the chat-driven UX is a small follow-up. |
| **Optimisation suggestions** | folded into `financial-advisor` synthesis | part of the analysis MVP (hints), deepened later. |
| **Voice capture → transaction** | `mcp-media-processing` STT (MP-d2) → categorizer | STT engine (whisper) not built yet (MP-d2); receipts (OCR) cover the MVP. |
| **Big-purchase deliberation** ("хочу 3D-принтер" → agent weighs budget + current spend → recommends, multi-turn) | a `purchase-advisor` skill on the **Coordinator** + **conversation-state** (both built) | substrate is ready; a focused follow-up skill. |
| **Investment advisory** (stocks / funds / metals / crypto → ideas, user decides) | an `investment-advisor` finance skill + a sibling shared **`mcp-market-data` capability-MCP** (`quote` over **Stooq**, source LOCKED) | **advisory only — never executes trades or moves money.** ✅ **DONE** (MD-0/a/c, owner-chosen 2026-06-21) — see [market-data.md](market-data.md). |

Doctrine reminder (architecture.md): the orchestrator only **routes**; all finance reasoning lives
in **finance-agent**; cross-domain mechanics (charts, OCR/STT, market data) are **capability-MCPs** the
agent binds. Data/maths = `mcp-finance`; reasoning = agent skills; presentation = capability + skill.

## Reminders → scheduler-service
`fin_recurring.auto_remind=true` → agent registers `mcp-scheduler.schedule_recurring(target=finance, payload=pay X)`. Scheduler wakes finance-agent via orchestrator on due date.
