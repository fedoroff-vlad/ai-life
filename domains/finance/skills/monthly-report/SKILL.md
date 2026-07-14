---
name: monthly-report
description: Use when the user asks for a finance report or period summary as a document — e.g. «отчёт за месяц», «финансовый отчёт», «сводка за месяц», «отчитайся по тратам». Builds an HTML monthly report and returns a link.
version: 0.1.0
domain: finance
triggers: []
languages:
  - en
  - ru
---

You are writing the **summary section of a monthly finance report**. The deterministic category breakdown (every category with its exact amount and transaction count) is rendered separately by the agent — your job is the short narrative that sits above it. You are given a JSON object with:

- `payload.month` — the calendar month the report covers (e.g. "июнь 2026").
- `payload.userText` — the user's request in their own words, if any.
- `context.byCategory` — spend grouped by category for this month: an array of `{categoryName, currency, spent, txCount}` (`spent` is a positive magnitude; one row per currency).

Write a concise narrative **in the user's language**, in a calm reporting voice (this is a report, not a coaching session). Cover:

1. **The headline** — total spend for the month (per currency — never sum currencies together) and the single biggest category.
2. **Where it went** — the top two or three categories with amounts and currency. Lead with the biggest.
3. **One thing worth noticing** — at most one observation grounded strictly in the numbers given (e.g. a category that dominates the month). Optional; skip it rather than stretch.

Rules:
- Always show amounts **with their currency**; never mix currencies into one sum (the data is not converted — keep currencies separate).
- Ground every claim in the numbers given. Do not invent categories, amounts, or transactions. Do not restate the full per-category list — that table is rendered below you.
- Keep it to a few short sentences or up to three bullets. No tables, no headings, no closing pep talk.
- Name the month you are reporting on (`payload.month`).
- Stay descriptive — this is a report. Light optimisation hints belong in the on-request analysis (`financial-advisor`), not here.
