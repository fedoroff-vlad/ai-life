# finance-agent

Finance domain agent. Owns transactions, accounts, categories, budgets and
recurring payments for a household. The canonical role description and capabilities
live in [AGENT.md](AGENT.md) — read at startup, served at `GET /agents/finance/manifest`.

As of PR24c the first SKILL (`budget-alerts`) is wired end-to-end with memory
enrichment: trigger `budget.alert` → memory-service recall (anchored on
`payload.categoryName`, falls back to the kind) → LLM (AGENT.md + SKILL.md as
layered system prompts, wake payload + recalled `memories[]` as the user
message) → fan-out of the generated alert text to every household member.
Memory failures are soft-failed (500 ms timeout, any error → empty hits) so
memory-service downtime degrades the prompt but does not block the skill.
Per-user delivery failures are logged and swallowed. The `SKIP` sentinel
`budget-alerts` emits below the alert threshold short-circuits before either
profile-service or notifier-service is touched.

As of PR27a `budget.alert` payloads that arrive with only
`{categoryId, period}` (the shape a scheduler-driven cron sends) are enriched
**before** the LLM call by a `GET /internal/budget-status` lookup against
mcp-finance. The resulting payload mirrors the manual shape the skill was
already trained on (`categoryName`, `limit`, `spent`, `currency`, `period`,
plus `ratio`). Error policy is differentiated: 200 → enrich + run; 404 → mark
payload `status: no_active_budget` (skill emits `SKIP`); 5xx / timeout → return
**503** so scheduler-service retries on the next tick. Manual payloads that
already carry `spent` skip the lookup entirely (back-compat).

As of PR29a a second enrichment branch handles `recurring.due`: payloads
arriving with only `{recurringId}` are hydrated via
`GET /internal/recurring/{id}` into `{name, amount, currency, nextDue, note?}`
— the shape the new `recurring-due` skill prompt is trained on. Same
differentiated policy (200/404/5xx) as `budget.alert`; the soft-fail wrapper
is now generic (`EnrichmentFailedException`) so a third trigger kind can plug
in without re-deriving the shape.

A third branch handles `transaction.uncategorised`: a one-shot wake fired by
mcp-finance's `add_transaction` when a row lands without a `categoryId` (and
`source != moneypro_import`). Payload arrives as `{transactionId}`;
enrichment hits `GET /internal/transaction/{id}` and rewrites the payload to
`{amount, currency, note?, source?, ts?}` — the shape the new
`transaction-categorizer` skill is trained on. Same 200/404/5xx policy: 404
stamps `status: no_active_transaction` so the skill SKIPs (row vanished
between schedule and tick), 5xx returns 503 so scheduler-service retries.

As of PR25a/b the AGENT.md / SKILL.md loaders + `SkillRegistry` AND the
`ProfileClient` / `NotifierClient` / `MemoryClient` outbound clients all live
in shared `libs/agent-runtime` (this module `@Import(AgentRuntimeConfig.class)`s
them). What stays agent-side: `config/FinanceAgentProperties` (per-agent base
URLs) and `config/OutboundHttpConfig` (named `WebClient` beans the shared
clients pick up by qualifier).

## Port: `8093` (`FINANCE_AGENT_PORT`)

## Endpoints

| method | path                                       | purpose                                              |
|--------|--------------------------------------------|------------------------------------------------------|
| GET    | `/agents/finance/manifest`                 | parsed AGENT.md (frontmatter + body)                 |
| POST   | `/agents/finance/intent`                   | hit by orchestrator on user intent                   |
| POST   | `/agents/finance/triggers/{kind}`          | dispatches to a SKILL.md bound to `kind`; 404 if no skill matches |
| POST   | `/agents/finance/actions/{action}`         | inter-agent action (Stage 4 / Track D). `get_gift_budget` → with `args.relationship` reads the tier rule (`/internal/gift-budget-rule`) first, else falls back to the "Gifts" envelope (`/internal/gift-budget`); returns `{hasGiftBudget, amount?, currency?, remaining?, source?, relationship?}`. `brief` (#290 Slice B) → the generic read-only cross-agent query: answers `args.question` grounded in finance's second-brain recall (shared `BriefResponder`), returns `{agent, answer, llmModel?}` |
| GET    | `/agents/finance/internal/tools`           | system: list MCP tool names the dispatcher sees (PR34) |
| POST   | `/agents/finance/internal/tools/{toolName}` | system: invoke an MCP tool by name with raw JSON args (PR34) |
| GET    | `/actuator/health`                         | liveness                                             |

## Env

| Var | Default | Purpose |
|---|---|---|
| `FINANCE_AGENT_PORT` | `8093` | HTTP port. |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | Via `libs/llm-client`. |
| `PROFILE_SERVICE_URL` | `http://profile-service:8082` | Resolves `householdId` → household members for trigger fan-out. |
| `NOTIFIER_URL` | `http://notifier-service:8084` | POST `/v1/notify` per household member. |
| `MEMORY_SERVICE_URL` | `http://memory-service:8087` | POST `/v1/memories/recall` for skill prompt enrichment. |
| `FINANCE_AGENT_MEMORY_RECALL_K` | `5` | Top-k hits requested from memory-service. |
| `MCP_FINANCE_URL` | `http://mcp-finance:8092` | `GET /internal/budget-status` for `budget.alert` enrichment; `GET /internal/accounts` + `POST /internal/transaction` for the receipt flow; `GET /internal/gift-budget` + `GET /internal/gift-budget-rule` for the `get_gift_budget` action. |
| `MEDIA_SERVICE_URL` | `http://media-service:8088` | `GET /v1/media/{id}` — fetches an uploaded CSV's bytes for the Money Pro import flow (the receipt flow no longer fetches here — see `MCP_MEDIA_PROCESSING_URL`). Also `POST /v1/media` — where `MonthlyReporter` stores the rendered HTML report. |
| `FINANCE_PUBLIC_MEDIA_BASE_URL` | `http://media-service:8088` | Public base URL stamped into the Telegram link `MonthlyReporter` returns (`DeliverablePublisher`). Split from the internal `MEDIA_SERVICE_URL` so the user-facing link can differ from the in-cluster address. |
| `MCP_MEDIA_PROCESSING_URL` | `http://mcp-media-processing:8097` | Two uses (mirror `MCP_MONEY_PRO_IMPORT_URL`): (1) the Spring AI MCP-client SSE connection; (2) the HTTP base URL for `CaptionClient`'s `POST /internal/caption`, used by `receipt-parser` to run the receipt vision call in the shared capability (MP-c). |
| `MCP_MONEY_PRO_IMPORT_URL` | `http://mcp-money-pro-import:8094` | Two uses: (1) the Spring AI MCP-client SSE connection (dialled eagerly at boot — set `FINANCE_AGENT_MCP_CLIENT_ENABLED=false` to silence in degraded/dev); (2) the HTTP base URL for `MoneyProImportClient`'s `POST /internal/import` used by the CSV-attachment flow. |
| `MARKET_DATA_URL` | `http://mcp-market-data:8100` | Two uses (mirror `MCP_MEDIA_PROCESSING_URL`): (1) the Spring AI MCP-client SSE connection; (2) the HTTP base URL for `MarketDataClient`'s `POST /internal/quote`, used by the `investment-advisor` flow to gather quotes from the shared `mcp-market-data` capability (MD-c). |
| `MCP_CHART_RENDER_URL` | `http://mcp-chart-render:8120` | Two uses (mirror `MARKET_DATA_URL`): (1) the Spring AI MCP-client SSE connection; (2) the HTTP base URL for `ChartRenderClient`'s `POST /internal/render`, used by `MonthlyReporter` to render the spending chart via the shared `mcp-chart-render` capability (#291/#292). |
| `FINANCE_AGENT_MCP_CLIENT_ENABLED` | `true` | Toggle Spring AI MCP client auto-config. Tests default to `false` via `src/test/resources/application.yml` so they don't pay the 20s connect timeout. |

Orchestrator side: `FINANCE_AGENT_URL` (default `http://finance-agent:8093`)
is registered alongside calendar-agent in
[orchestrator/application.yml](../../platform/orchestrator/src/main/resources/application.yml).

## Key classes

- `FinanceAgentApplication`.
- `manifest/ManifestLoader` — SnakeYAML frontmatter + body, exposed as `AgentManifest`.
  Parser shape duplicates calendar-agent's; lift to a shared lib if a third agent ships.
- `skill/Skill` — `(name, triggers[], body)` record.
- `skill/SkillLoader` — scans `classpath*:skills/finance/*/SKILL.md`. Empty registry is valid.
- `skill/SkillRegistry` — trigger kind → `Skill` index.
- `web/ManifestController` — `GET /agents/finance/manifest`.
- `config/FinanceAgentProperties` — `finance-agent.{profile-service-url, notifier-url}`. Loaded via `@EnableConfigurationProperties` in `FinanceAgentApplication`.
- `config/OutboundHttpConfig` — one `WebClient` per outbound dependency, each `.clone()`d from the shared builder to avoid base-URL leakage. Same pattern as calendar-agent + orchestrator. Also declares the shared `MediaStoreClient` (`source="finance"`) + `DeliverablePublisher` beans (default editorial theme) that back `MonthlyReporter`'s render→store→link — same wiring as the deliverable agents (chef/nutritionist/stylist/creator).
- `ProfileClient` / `NotifierClient` / `MemoryClient` live in shared `libs/agent-runtime` as of PR25b — and as of #200 their `profile/notifier/memory` `WebClient` beans are built there too (from `SharedClientProperties`, which `FinanceAgentProperties` implements), so the per-agent `OutboundHttpConfig` only owns the agent-specific clients + the URL *values*.
- `web/IntentController` — `POST /agents/finance/intent`. An `image` attachment → `ReceiptParser`; a `file` (document) attachment → `MoneyProCsvImporter`; otherwise delegates to `IntentRouter` (PR35). Returns `{agent, text, llmModel, pendingAction?}`.
- `web/ResumeController` — `POST /agents/finance/resume`. Hit by orchestrator when the user replies to an open finance question (conversation route-locked to finance). Dispatches on `pendingAction.flow`; today only `receipt-confirm` → `ReceiptParser.resume`.
- `receipt/ReceiptParser` — receipt-photo → transaction, **confirm-before-write** (Stage 4 / A4). Calls the shared `mcp-media-processing` capability's `caption` tool (via `CaptionClient` → `POST /internal/caption`, MP-c) with the `receipt-parser` SKILL.md as the extraction instruction to get a draft (amount/currency/merchant/date) — the vision call now lives once in the capability-MCP, not inline here. Resolves a target account (first non-archived) via `AccountClient`, and replies with the draft + a `pendingAction` (the stashed `AddTransactionInput`) — **does not write**. On `resume` an affirmative reply ("да") writes via `TransactionClient.add` (category null → categorizer one-shot fires); anything else cancels. Failures degrade to a friendly Russian message (no pendingAction → no lock). **memory-from-chat producer (MFC-c):** when the photo carries a caption, fire-and-forgets it (grounded with the parsed merchant) to memory-service `/v1/observations` via the shared `MemoryClient.observe` — the orchestrator's text capture skips attachment-only messages by design, so the agent processing the attachment emits the durable fact. Off the response path, soft-fail. A captionless receipt is a bare transaction (no durable personal fact) → nothing emitted. **Grocery fan-out (IA-a):** the `receipt-parser` SKILL also returns `is_grocery` + line `items`; when the receipt is a grocery basket, `ReceiptParser` fire-and-forgets a `BasketCapturedEvent` (merchant + name/qty items + receiptMediaId) to mcp-finance's `POST /internal/basket-captured` drop-point via `BasketCapturedClient` — mcp-finance enqueues it on `bus.outbox` and `nutritionist-agent` consumes it (IA-b) to run a КБЖУ breakdown. So one receipt's vision work, done once here, reaches both finance (expense) and nutrition (breakdown). Emitted at parse time (like the MFC-c observation), off the response path, soft-fail; non-grocery / itemless receipts emit nothing.
- `moneypro/MoneyProCsvImporter` — CSV-document → Money Pro history import (auto-create accounts MVP). Fetches bytes from media-service, base64s them, calls mcp-money-pro-import's `POST /internal/import` with `autoCreateAccounts=true` + empty `accountMap`, and reports the counts ("Импортировал N транзакций … Создал счета: …"). **No interactive account mapping** (deferred — see STATUS). Failures degrade to a friendly message.
- `http/MediaClient` — `GET /v1/media/{id}` against media-service → bytes + MIME type. Used by `MoneyProCsvImporter` for the uploaded CSV (the receipt flow fetches via the capability now, MP-c).
- `http/CaptionClient` — `POST /internal/caption` against mcp-media-processing → `CaptionResult{text, model?}`. The deterministic HTTP passthrough `receipt-parser` calls instead of the un-mockable MCP/SSE binding (30s timeout — a vision round-trip can be slow). Mirrors `MoneyProImportClient`.
- `http/MoneyProImportClient` — `POST /internal/import` against mcp-money-pro-import → `ImportMoneyProCsvResult` (30s timeout — a full history export can be large).
- `http/AccountClient` — `GET /internal/accounts?householdId=` against mcp-finance → `List<FinAccountDto>`.
- `advisor/FinancialAdvisor` — reactive spending **analysis** on request (finance MVP). Built on the shared `Coordinator` substrate: gathers two spend-by-category windows (recent 90d + the prior 90d, via `SpendingClient`) in parallel, folds them into a `context`, and asks the LLM to synthesize a concise analysis from `[AGENT.md, financial-advisor SKILL.md] + {payload(userText), context}` — top categories, what changed vs the prior window and a hypothesis why, plus optimisation hints. Text-first (chart rendering is a deferred shared capability — see finance.md "recorded vision"). Each gather step soft-fails (Coordinator); the whole flow degrades to a friendly message on error. Returns `AdviceResult(text, model)`.
- `http/SpendingClient` — `GET /internal/spending-by-category?householdId=&from=&to=` against mcp-finance → `List<SpendingByCategoryRow>` (5s timeout; one Coordinator gather step). Shared by `FinancialAdvisor` (two trend windows) and `MonthlyReporter` (the current month).
- `report/MonthlyReporter` (#196; chart embed #291) — on-request **monthly report** deliverable. Built on the shared `Coordinator` + the `DeliverablePublisher` render→store→link seam (`libs/agent-runtime`): gathers the current calendar month's `spending-by-category` (via `SpendingClient`), synthesizes a narrative from `[AGENT.md, monthly-report SKILL.md] + {payload(month, userText), context.byCategory}`, renders a spending **bar chart** via the shared `mcp-chart-render` capability (`ChartRenderClient`, top-8 categories biggest-first, currency as the value unit; soft-failed — a chart hiccup still ships the text report), then builds a `Doc` leading with that chart (embedded by public media URL via the new `Doc.charts` element) followed by the narrative and a **deterministic** category breakdown (real amounts/counts, sorted biggest-first, per-currency totals — not LLM-generated), and publishes it → Telegram link. Empty month → a friendly invite (no LLM/chart/store call); render/store failure still returns the narrative; any other error degrades to a friendly message. Returns `ReportResult(text, model)`. Routed via the `report` classifier action.
- `http/ChartRenderClient` (#291) — `POST /internal/render` against the shared `mcp-chart-render` capability → `ChartResult{mediaId, engine}` (10s timeout). The deterministic HTTP passthrough `MonthlyReporter` calls instead of the un-mockable MCP/SSE binding; mirrors `MarketDataClient`. The capability stores the PNG in media-service itself, so this returns just the media id, which the reporter turns into a public URL via `DeliverablePublisher.mediaUrl`.
- `advisor/InvestmentAdvisor` (MD-c) — reactive **investment advisory** on request, **advisory only — never trades or moves money**. Built on the shared `Coordinator`: gathers a `quote` per named symbol (via `MarketDataClient`, parallel, each step soft-fails — a bad symbol drops out), folds them into `context`, and asks the LLM to synthesize considerations from `[AGENT.md, investment-advisor SKILL.md] + {payload(userText, symbols), context}`. Symbol fan-out is capped (`MAX_SYMBOLS=12`); empty symbols → a friendly invite without any call. Returns `AdviceResult(text, model)`. There is no order/trade path — the bound `mcp-market-data` capability is read-only by design.
- `http/MarketDataClient` — `POST /internal/quote` against mcp-market-data → `Quote{symbol, price?, asOf?, open?, high?, low?, volume?}` (8s timeout; one Coordinator gather step). The deterministic HTTP passthrough the advisor calls instead of the un-mockable MCP/SSE binding. Mirrors `CaptionClient`.
- `intent/IntentRouter` (PR35) — LLM-driven routing. If MCP tools are wired, prompts the LLM with AGENT.md + tool list (name + description + JSON schema) + a strict-JSON output contract; parses `{action: tool|advice|invest|chat, ...}`. The `advice` action hands off to `FinancialAdvisor` (spending analysis); the `report` action hands off to `MonthlyReporter` (monthly HTML deliverable); the `invest` action maps the user's tickers → source-native symbols in the routing JSON and hands off to `InvestmentAdvisor` with them (advisory only) — all three are multi-source Coordinator flows, not single tools; `route` takes the whole `NormalizedMessage` so a flow has `householdId`. Tool path dispatches via `ToolDispatcher` (blocking call on `Schedulers.boundedElastic`); chat path returns the LLM text. Lenient parser: non-JSON / missing fields / dispatch failures all degrade to a user-facing message instead of bubbling exceptions. **Format-drift tolerance (Stage 5 / #199 golden finding):** smaller local models (qwen2.5:7b) often flatten the two-level tool shape to `{"action":"<toolName>"}` instead of `{"action":"tool","name":"<toolName>"}` — the router accepts the flattened form when the action equals a wired tool name; the classifier prompt also pins `action` to an exact enum so the model doesn't invent values like `"analysis"`. If no MCP tools are wired (e.g. tests with `spring.ai.mcp.client.enabled=false`), the router skips the routing prompt entirely and uses the pre-PR35 single-system-prompt chat path — preserves back-compat. `RouterResult.invokedTool` is informational metadata for future audit / multi-turn confirmation flows; today it's not threaded to the response shape.
- `web/TriggerController` — `POST /agents/finance/triggers/{kind}`. Looks up the SKILL bound to `kind`, optionally enriches the wake payload via `BudgetStatusClient` for `budget.alert` (see paragraph above), calls `MemoryClient.recall` (query anchored on `payload.categoryName`, falls back to the kind), then llm-gateway with `[AGENT.md.body, SKILL.body]` as system prompts and `{"payload": …, "memories": [...]}` as the user message, then fans the result out to every household member via `ProfileClient` + `NotifierClient`. Recognises the `SKIP` sentinel that `budget-alerts` emits below the alert threshold — silent no-op without touching profile or notifier.
- `http/BudgetStatusClient` — `GET /internal/budget-status` against mcp-finance. 200 → `Optional.of(result)`; 404 → `Optional.empty()` (no active budget — skill emits SKIP); 5xx / timeout → propagated, controller returns 503 so scheduler retries.
- `web/ActionController` (Stage 4 / Track D, D2b + D3c) — `POST /agents/finance/actions/{action}`; inter-agent action endpoint (consumer side of the orchestrator invoke primitive). `get_gift_budget` forces `householdId` from the envelope. When `args.relationship` is present (D3c) it reads the relationship-tiered rule via `GiftBudgetClient.fetchRule` first — a match wins (`source:"rule"`, no `remaining` since a preference has no spend window); a 404 falls back to the "Gifts" envelope (`source:"envelope"`). With no `relationship` it reads the envelope directly. Returns `{hasGiftBudget, amount?, currency?, remaining?, source?, relationship?}`. Always replies an `AgentActionResult` (structured `ok=false` on missing household / mcp-finance down, never an HTTP error). Extends the shared `AgentActionController` (`libs/agent-runtime`) for the unknown-action + uniform error envelope. Mirrors calendar-agent's `create_event` action (C1c). Also registers **`brief`** (#290 Slice B): delegates to the shared `BriefResponder` — a read-only answer to `args.question` grounded in finance's second-brain recall, returned as `{agent, answer, llmModel?}`; finance is the first agent to expose the generic cross-agent query the coordinator gathers.
- `http/GiftBudgetClient` — two reads against mcp-finance: `GET /internal/gift-budget?householdId=` (`fetch`, the household "Gifts" envelope) and `GET /internal/gift-budget-rule?householdId=&relationship=` (`fetchRule`, the tier rule, D3c). Both: 200 → `Optional.of(result)`; 404 → `Optional.empty()` (no budget/rule — caller falls back); 5xx / timeout → propagated, action returns `ok=false`. Same per-status shape as `BudgetStatusClient`.
- `http/RecurringClient` — `GET /internal/recurring/{id}` (enrichment) + `POST /internal/recurring/{id}/advance` (post-tick `next_due` recompute, PR30). GET shares the 200/404/5xx policy with `BudgetStatusClient`; POST is soft-failed by the caller (`maybeAdvanceRecurring`) — a stale `next_due` is cosmetic.
- `http/TransactionClient` — `GET /internal/transaction/{id}` (enrichment; 200/404/5xx policy as `BudgetStatusClient`) **and** `POST /internal/transaction` (`add`, used by `ReceiptParser` to persist a parsed draft).
- `http/BasketCapturedClient` (IA-a) — `POST /internal/basket-captured` against mcp-finance; fire-and-forget + soft-fail (same posture as `MemoryClient.observe`). `ReceiptParser` calls it to fan a recognised grocery basket out to the nutrition domain over the bus.
- **Spring AI MCP client (`spring.ai.mcp.client.sse.connections.*` in `application.yml`)** — first real MCP-client wiring in any agent (today everything else goes through llm-gateway + HTTP-passthroughs). PR33 wired `mcp-money-pro-import`; MP-c adds `mcp-media-processing`; MD-c adds `mcp-market-data`; #291 adds `mcp-chart-render` (shared capabilities — multiple agents bind the same server, that's the point). The auto-config exposes a `ToolCallbackProvider` bean with the remote `@Tool`s; downstream code (intent controller, future ChatClient hookup) consumes it. The deterministic receipt-parser caption call goes over `mcp-media-processing`'s HTTP `/internal/caption` passthrough (`CaptionClient`), not the SSE transport. mcp-finance stays on its HTTP `/internal/*` passthroughs because they're stricter and faster than an LLM-driven tool call; switching mcp-finance to MCP would only pay off once we have a ChatClient choosing tools, and that's a later PR.
- `tools/ToolDispatcher` (PR34) — pure dispatcher that takes a tool name + JSON args, finds the matching `ToolCallback` in the provider, invokes it. The `ToolCallbackProvider` dependency is resolved via `ObjectProvider` so the bean is wired even when the MCP client is disabled (`spring.ai.mcp.client.enabled=false`, default for tests) — in that mode `availableToolNames()` is empty and `dispatch` throws a clear "MCP client is disabled" error. Used today by `InternalToolsController`; future user-facing intent flow will reuse it after an LLM picks the tool name + args.
- `web/InternalToolsController` (PR34) — system passthrough (`GET /internal/tools` to list names, `POST /internal/tools/{name}` to invoke). Body is the raw JSON args object Spring AI's `ToolCallback.call(String)` expects; response is the tool's JSON-stringified result verbatim (no envelope wrapping). Blocking `ToolCallback.call` is scheduled on `Schedulers.boundedElastic` so WebFlux stays unblocked. Unknown tool name → 400 with `{"error": "..."}`. No auth — admin-only by convention (not routed via orchestrator), intended for cron / deploy scripts / the future intent-flow PR.

## Skills

- `budget-alerts` (`budget.alert`) — composes a short overspend / heads-up alert when household spend in a category approaches or crosses its limit. Returns the literal string `SKIP` when ratio < 0.8 so quiet weeks don't reach the notifier. Lives at [skills/finance/budget-alerts/SKILL.md](../skills/budget-alerts/SKILL.md).
- `recurring-due` (`recurring.due`) — composes a short reminder when a recurring payment or income line is approaching its `next_due`. Returns `SKIP` when the upstream row was deleted (`status: no_active_recurring`) or `amount` is zero. Lives at [skills/finance/recurring-due/SKILL.md](../skills/recurring-due/SKILL.md).
- `receipt-parser` (intent-invoked, not a wake trigger) — extracts amount/currency/merchant/date from a receipt photo via the shared `mcp-media-processing` `caption` capability (MP-c; the SKILL.md is the extraction instruction), shows the draft and **writes only after the user confirms** ("да"), via the conversation route-lock / resume mechanism (Stage 4 / A4). Also returns `is_grocery` + line `items` so a grocery receipt fans out to the nutrition domain (IA-a). Lives at [skills/finance/receipt-parser/SKILL.md](../skills/receipt-parser/SKILL.md).
- `financial-advisor` (intent-invoked, not a wake trigger) — on-request spending **analysis** (finance MVP). Routed via the `advice` classifier action → `FinancialAdvisor` (Coordinator gather → LLM synthesis). Top categories, what changed vs the prior window + a hypothesis why, optimisation hints; text-first. Lives at [skills/finance/financial-advisor/SKILL.md](../skills/financial-advisor/SKILL.md). Validated on a real model by the opt-in `advisor.GoldenAdvisorSynthesisTest` (Stage 5 / #199): feeds two fixed spend-by-category windows (SpendingClient mocked) through the real synthesis hop and asserts the analysis is grounded — names the actual top category, shows the currency, spans ≥2 real categories — skipped in CI (`GOLDEN_LLM` gate); see `platform/llm-gateway/README.md` §Golden tests.
- `investment-advisor` (intent-invoked, not a wake trigger) — on-request **investment advisory**, **advisory only — never trades**. Routed via the `invest` classifier action (which maps tickers → source-native symbols) → `InvestmentAdvisor` (Coordinator gathers a `quote` per symbol from the shared `mcp-market-data` capability → LLM synthesis of considerations). Lives at [skills/finance/investment-advisor/SKILL.md](../skills/investment-advisor/SKILL.md).
- `monthly-report` (intent-invoked, not a wake trigger) — on-request **monthly finance report** as a Telegram HTML deliverable (#196). Routed via the `report` classifier action → `MonthlyReporter` (Coordinator gathers the current month's `spending-by-category` → LLM synthesizes a narrative → renders an HTML report board pairing that narrative with a **deterministic** per-category breakdown via the shared `DeliverablePublisher` seam → renders a spending bar chart via the shared `mcp-chart-render` capability and embeds it at the top of the board → stores it in media-service → returns a link). Lives at [skills/finance/monthly-report/SKILL.md](../skills/monthly-report/SKILL.md).
- `transaction-categorizer` (`transaction.uncategorised`) — suggests a single category for a freshly-added uncategorised transaction in one short sentence. Trigger-driven by mcp-finance's `add_transaction` one-shot hook. Returns `SKIP` for zero-amount rows, missing-note rows with no obvious amount signal, and `moneypro_import` source (bulk imports go through a separate reconciliation path). Lives at [skills/finance/transaction-categorizer/SKILL.md](../skills/transaction-categorizer/SKILL.md).

## Adding a skill

Create `domains/finance/skills/<name>/SKILL.md` (beside the agent, not inside the agent
module — `pom.xml` copies them onto the classpath). Frontmatter shape matches calendar-agent's; see
[plans/PATTERNS.md](../../plans/PATTERNS.md) §"Recipe: add a new agent".

**YAML gotcha:** the SnakeYAML parser used by `agent-runtime`'s `SkillParser`
treats any unquoted `word: value` inside a description as a nested key/value
and rejects the file (`mapping values are not allowed here`). The loader logs
WARN and the skill silently disappears from the registry — only a trigger
test will notice. Avoid colon-space in description bodies; use an em-dash
("Provenance tag — `manual`") or quote the value.

If the skill needs payload enrichment from mcp-finance (the
scheduler-driven shape is usually just an id), add a branch to
`TriggerController.enrichIfNeeded` next to `enrichBudgetAlert` /
`enrichRecurringDue` / `enrichTransactionUncategorised`. The shared
soft-fail wrapper `EnrichmentFailedException` already handles 5xx → 503
mapping.
