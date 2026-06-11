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
| `MCP_FINANCE_URL` | `http://mcp-finance:8092` | `GET /internal/budget-status` for `budget.alert` enrichment; `GET /internal/accounts` + `POST /internal/transaction` for the receipt flow. |
| `MEDIA_SERVICE_URL` | `http://media-service:8088` | `GET /v1/media/{id}` — fetches a receipt photo's bytes for the `receipt-parser` vision call. |
| `MCP_MONEY_PRO_IMPORT_URL` | `http://mcp-money-pro-import:8094` | First real Spring AI MCP-client connection (SSE). The auto-config dials this URL eagerly at agent boot so a missing import server surfaces immediately, not on the first import attempt. Set `FINANCE_AGENT_MCP_CLIENT_ENABLED=false` to silence the client in degraded / dev environments. |
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
- `config/OutboundHttpConfig` — one `WebClient` per outbound dependency, each `.clone()`d from the shared builder to avoid base-URL leakage. Same pattern as calendar-agent + orchestrator.
- `ProfileClient` / `NotifierClient` / `MemoryClient` live in shared `libs/agent-runtime` as of PR25b — the per-agent `OutboundHttpConfig` only owns the URL binding via `@Qualifier`-named `WebClient` beans.
- `web/IntentController` — `POST /agents/finance/intent`. When the message carries an `image` attachment it takes the `ReceiptParser` path; otherwise it delegates to `IntentRouter` (PR35). Returns `{agent, text, llmModel}`.
- `receipt/ReceiptParser` — receipt-photo → transaction (write-immediately MVP). Fetches bytes from media-service, runs the `vision` channel with the `receipt-parser` SKILL.md to extract a draft (amount/currency/merchant/date), resolves a target account (first non-archived) via `AccountClient`, and writes it via `TransactionClient.add`. Category left null so the categorizer one-shot fires. **No confirm-before-write** (deferred — see STATUS). Failures degrade to a friendly Russian message.
- `http/MediaClient` — `GET /v1/media/{id}` against media-service → bytes + MIME type.
- `http/AccountClient` — `GET /internal/accounts?householdId=` against mcp-finance → `List<FinAccountDto>`.
- `intent/IntentRouter` (PR35) — LLM-driven tool routing. If MCP tools are wired, prompts the LLM with AGENT.md + tool list (name + description + JSON schema) + a strict-JSON output contract; parses `{action: tool|chat, ...}`. Tool path dispatches via `ToolDispatcher` (blocking call on `Schedulers.boundedElastic`); chat path returns the LLM text. Lenient parser: non-JSON / missing fields / dispatch failures all degrade to a user-facing message instead of bubbling exceptions. If no MCP tools are wired (e.g. tests with `spring.ai.mcp.client.enabled=false`), the router skips the routing prompt entirely and uses the pre-PR35 single-system-prompt chat path — preserves back-compat. `RouterResult.invokedTool` is informational metadata for future audit / multi-turn confirmation flows; today it's not threaded to the response shape.
- `web/TriggerController` — `POST /agents/finance/triggers/{kind}`. Looks up the SKILL bound to `kind`, optionally enriches the wake payload via `BudgetStatusClient` for `budget.alert` (see paragraph above), calls `MemoryClient.recall` (query anchored on `payload.categoryName`, falls back to the kind), then llm-gateway with `[AGENT.md.body, SKILL.body]` as system prompts and `{"payload": …, "memories": [...]}` as the user message, then fans the result out to every household member via `ProfileClient` + `NotifierClient`. Recognises the `SKIP` sentinel that `budget-alerts` emits below the alert threshold — silent no-op without touching profile or notifier.
- `http/BudgetStatusClient` — `GET /internal/budget-status` against mcp-finance. 200 → `Optional.of(result)`; 404 → `Optional.empty()` (no active budget — skill emits SKIP); 5xx / timeout → propagated, controller returns 503 so scheduler retries.
- `http/RecurringClient` — `GET /internal/recurring/{id}` (enrichment) + `POST /internal/recurring/{id}/advance` (post-tick `next_due` recompute, PR30). GET shares the 200/404/5xx policy with `BudgetStatusClient`; POST is soft-failed by the caller (`maybeAdvanceRecurring`) — a stale `next_due` is cosmetic.
- `http/TransactionClient` — `GET /internal/transaction/{id}` (enrichment; 200/404/5xx policy as `BudgetStatusClient`) **and** `POST /internal/transaction` (`add`, used by `ReceiptParser` to persist a parsed draft).
- **Spring AI MCP client (`spring.ai.mcp.client.sse.connections.*` in `application.yml`)** — first real MCP-client wiring in any agent (today everything else goes through llm-gateway + HTTP-passthroughs). PR33 wires up `mcp-money-pro-import` only: the auto-config exposes a `ToolCallbackProvider` bean with the remote `@Tool`s; downstream code (intent controller, future ChatClient hookup) consumes it. mcp-finance stays on its HTTP `/internal/*` passthroughs because they're stricter and faster than an LLM-driven tool call; switching mcp-finance to MCP would only pay off once we have a ChatClient choosing tools, and that's a later PR.
- `tools/ToolDispatcher` (PR34) — pure dispatcher that takes a tool name + JSON args, finds the matching `ToolCallback` in the provider, invokes it. The `ToolCallbackProvider` dependency is resolved via `ObjectProvider` so the bean is wired even when the MCP client is disabled (`spring.ai.mcp.client.enabled=false`, default for tests) — in that mode `availableToolNames()` is empty and `dispatch` throws a clear "MCP client is disabled" error. Used today by `InternalToolsController`; future user-facing intent flow will reuse it after an LLM picks the tool name + args.
- `web/InternalToolsController` (PR34) — system passthrough (`GET /internal/tools` to list names, `POST /internal/tools/{name}` to invoke). Body is the raw JSON args object Spring AI's `ToolCallback.call(String)` expects; response is the tool's JSON-stringified result verbatim (no envelope wrapping). Blocking `ToolCallback.call` is scheduled on `Schedulers.boundedElastic` so WebFlux stays unblocked. Unknown tool name → 400 with `{"error": "..."}`. No auth — admin-only by convention (not routed via orchestrator), intended for cron / deploy scripts / the future intent-flow PR.

## Skills

- `budget-alerts` (`budget.alert`) — composes a short overspend / heads-up alert when household spend in a category approaches or crosses its limit. Returns the literal string `SKIP` when ratio < 0.8 so quiet weeks don't reach the notifier. Lives at [skills/finance/budget-alerts/SKILL.md](../../skills/finance/budget-alerts/SKILL.md).
- `recurring-due` (`recurring.due`) — composes a short reminder when a recurring payment or income line is approaching its `next_due`. Returns `SKIP` when the upstream row was deleted (`status: no_active_recurring`) or `amount` is zero. Lives at [skills/finance/recurring-due/SKILL.md](../../skills/finance/recurring-due/SKILL.md).
- `receipt-parser` (intent-invoked, not a wake trigger) — extracts amount/currency/merchant/date from a receipt photo via the `vision` channel and records the transaction immediately (write-immediately MVP; confirm-before-write deferred). Lives at [skills/finance/receipt-parser/SKILL.md](../../skills/finance/receipt-parser/SKILL.md).
- `transaction-categorizer` (`transaction.uncategorised`) — suggests a single category for a freshly-added uncategorised transaction in one short sentence. Trigger-driven by mcp-finance's `add_transaction` one-shot hook. Returns `SKIP` for zero-amount rows, missing-note rows with no obvious amount signal, and `moneypro_import` source (bulk imports go through a separate reconciliation path). Lives at [skills/finance/transaction-categorizer/SKILL.md](../../skills/finance/transaction-categorizer/SKILL.md).

## Adding a skill

Create `skills/finance/<name>/SKILL.md` at the **repo root** (not under this module —
`pom.xml` copies them in). Frontmatter shape matches calendar-agent's; see
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
