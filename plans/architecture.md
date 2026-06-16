# Architecture — stable cross-cutting design

> Family (2 users) AI-agent system for everyday-life automation. Local deployment (target host: Mac Studio 128GB), Java/Spring Boot, Postgres. Entry point: Telegram (multimodal). MVP: calendar + finance.

## High-level

```
Telegram (audio / text / image / video / files)
        │
        ▼
Gateway (telegram-gateway)
  - receive updates, normalize → NormalizedMessage
  - media → mcp-media-processing (STT / OCR / vision-caption / video-frames) BEFORE orchestrator
  - identity: who (vlad / wife) + scope (private / household / group)
        │
        ▼
Orchestrator ("brain")
  - load context: profile + short memory + memory-service recall
  - intent classification → route to agent(s)
  - tool/agent calling; may call several agents
        │
   ┌────┴────┐
   ▼         ▼
Calendar   Finance   ... Chef, Nutri, Creator, Search, Stylist, Tasks
  agents are thin: EN prompt + skills + tools
   │         │
   ▼         ▼
MCP servers (narrow, one external surface each)
  domain-MCP   (owns a schema):     caldav · finance · tasks · ics-import
  capability-MCP (stateless tool):  media-proc · embeddings · web-fetch · weather · lenta
  └─ a capability-MCP has NO schema; any agent binds it (shared toolbox)
        │
        ▼
Shared services: memory-service · profile · scheduler · notifier · llm-gateway · vault · audit · observability
        │
        ▼
Postgres: relational + JSONB + pgvector + AGE
```

## Brain inputs
Everything enters the orchestrator ("brain") through one of a small, extensible set of inputs:
- **Telegram** (via gateway-telegram) — a user *request*, an *event registration* ("запиши ДР Маши 5 авг"), *media* (already normalised to text/`storageUri`), or a *command*. Reactive.
- **Timer event** — scheduler-service fires a due `core.schedules` row → `/v1/agents/wake`. Proactive.
- *(future, extensible)* other sources — additional channels/event producers slot in here later. Add an input, not a new brain.

## Orchestrator routing doctrine
The brain stays intuitive **because the rules are data-driven** (the LLM classifier reads agent manifests; adding an agent extends routing with no code change) and the precedence is fixed:

**Reactive — a message arrived:**
1. **Active `route-lock`** (an open confirmation owns the conversation) → `resume` the locked agent, **bypass classification** (Track A / A2).
2. Else **classify intent** (FAST channel, few-shot from manifests + memory-service recall as context):
   - single clear domain → route to that one agent;
   - complex / multi-domain → **agent-led coordination**: route to a coordinator agent that gathers the rest through the hub (`/v1/agents/invoke`) or the bus (Track D);
   - small-talk / unmatched-but-actionable → catch-all (`tasks` inbox); pure small-talk → echo.
3. An agent may return a `pendingAction` → the orchestrator **locks** the conversation for confirmation.

**Proactive — an event arrived:**
1. **Scheduler wake** (`kind`) → the owning agent's `trigger`.
2. *(later)* a **domain event** on the bus → a consumer agent/service reacts; it may start a coordinator flow.

**Autonomy rule (applies to both):** propose autonomously, but perform any **outbound** external action only after user confirmation (conversation-state).

## Principles
- **Monorepo ≠ monolith.** Each service = own Spring Boot app, own Dockerfile, own application.yml, own liquibase changelog. Depends only on `libs/*`. Deploys independently.
- **All prompts, skill descriptions, system messages, tool descriptions — English** (token economy + better model behaviour). User-facing replies in the user's language (auto-detected). Internal reasoning in English.
- **LLM provider switches via env vars** only, no code changes. Every LLM-using service goes through `llm-gateway`.
- **Memory/Librarian** is a cross-cutting service with one API, not tied to any agent.
- **Media-processing happens at the gateway** — voice/image/video become text before the orchestrator, so the orchestrator always sees a unified `NormalizedMessage`.
- Agents never call each other directly — only via orchestrator (sync) or the event bus (async background chains).
- **Two kinds of MCP server.** A *domain-MCP* owns a bounded-context schema (`mcp-finance` → `finance.*`); one per domain. A *capability-MCP* is a stateless wrapper over an external surface with **no schema** (weather, web search/fetch, embeddings) — the shared toolbox that **any** agent binds. Rule of thumb: a raw external *capability* → capability-MCP; *reasoning/multi-step* over the external world → a specialist agent (e.g. `search`/`researcher`) that binds those capability-MCPs. Don't make a "thinking" agent for what is just a tool (keep simple things tools).
- **The orchestrator is a thin router, not the thinking.** Routing is data-driven (the LLM classifier reads agent manifests), so adding an agent extends routing with no orchestrator edit. The *reasoning* happens in the LLM, invoked by both the orchestrator and the agents. Complex/multi-domain requests are **agent-led**: a domain agent coordinates the flow and reaches other specialists through the hub (`/v1/agents/invoke`) or the bus — the orchestrator does not become a monolithic planner.
- **Propose freely, act outward only on confirm.** The assistant may *propose* autonomously (gift ideas, an outfit, a menu). Any **outbound** action with external effect (messaging other people, a purchase, booking) requires explicit user confirmation, reusing the conversation-state confirm mechanism (Track A).

## Monorepo structure
```
ai-life/
├── pom.xml                  # parent: versions, plugins
├── libs/
│   ├── contracts/           # DTO, JSON Schemas, NormalizedMessage, AgentRequest/Response, EventBusMessage<T>
│   ├── mcp-client/          # wrapper over Spring AI MCP + retry/trace
│   ├── llm-client/          # client to llm-gateway (channel-based)
│   ├── event-bus/           # Postgres LISTEN/NOTIFY adapter
│   ├── platform-common/     # security, logging, metrics, errors
│   └── agent-runtime/       # AGENT.md/SKILL.md loaders + SkillRegistry + shared ProfileClient/NotifierClient/MemoryClient (agents @Import this)
├── infra/
│   ├── docker-compose.yml          # all services
│   ├── docker-compose.dev.yml      # infra only (PG/Radicale/MinIO/Langfuse)
│   ├── .env.example
│   ├── postgres/init.sql           # extensions + schemas
│   └── liquibase/                  # see "DB & migrations" below
├── platform/   gateway-telegram, orchestrator, llm-gateway, memory-service, profile-service, scheduler-service, notifier-service
├── agents/     calendar-agent, finance-agent, tasks-agent  (each independent Spring Boot app)
├── mcp/        mcp-media-processing, mcp-caldav, mcp-finance, mcp-tasks, mcp-money-pro-import, mcp-ics-import
└── skills/     per-skill folder (Anthropic Skills convention); _registry.yaml autogenerated
```
Adding an agent/MCP = new folder from template + register in docker-compose. No edits to existing services.

## DB & migrations
One Postgres, schemas split by **bounded context, not by service**:
`core, memory (pgvector+AGE), audit, bus, media, calendar, finance, tasks`.

One shared Liquibase changelog, features split by domain. Numbering convention
is owned by [PATTERNS.md](PATTERNS.md) §"Recipe: add a Liquibase migration" —
read it before creating a new file. Cheat-sheet:

| Range | Domain |
|---|---|
| `001-009` | core / cross-cutting (`core`, `memory`, `audit`, `bus`, `media`, `scheduling`, `people`) |
| `010-019` | calendar |
| `020-029` | finance |
| `030-039` | tasks |
| `040-049` | _reserved for next domain_ |

```
infra/liquibase/
├── db.changelog-master.xml          # XML master, <include> order matters (deps first)
└── features/
    ├── 001-core.yml  002-scheduling.yml  003-people.yml  004-memory.yml  005-memory-relations.yml
    ├── 010-calendar.yml   011-ics-subscriptions.yml  012-ics-subscriptions-schedule-id.yml
    ├── 020-finance.yml    021-fin-budget.yml         022-fin-budget-schedule-id.yml  023-finance-recurring.yml
    └── NNN-<domain>/*.sql            # complex DDL referenced from YAML
```
Format: master = XML; per-feature = YAML; complex DDL / data fixes = raw SQL referenced from YAML.
Applied by one job at infra start — no migration races between services.

Extensions: `vector` (pgvector), `age` (Apache AGE graph), `pg_trgm` (fuzzy search).

## LLM strategy
All LLM access through `llm-gateway` → one place for provider switch, tracing (Langfuse), rate-limit, retry, prompt cache.
Four **channels** (agents address a channel, not a model name): `default`, `fast`, `vision`, `embedding`.
Provider chosen entirely by env vars (profiles: mock / anthropic / openai-compatible / local Ollama). Switch = edit `.env` + restart llm-gateway; agents unaware.
Mac Studio candidates: default Qwen2.5-72B / Llama-3.3-70B; fast Qwen2.5-7B; vision Qwen2.5-VL-32B; embeddings bge-m3; STT whisper-large-v3 (in mcp-media-processing, outside the gateway).

## Conventions
- **SKILL.md** (Anthropic Skills compatible): YAML frontmatter (name, description, version, channel, inputs, outputs, mcp_tools, tags) + EN system-prompt body. Optional `SKILL.ru.md` = human translation, NOT read by the LLM. Per-skill folder under `skills/<domain>/<name>/` (prompts/, tools.yaml, tests/golden.yaml, tests/live.yaml). `_registry.yaml` autogenerated from frontmatters in CI.
- **AGENT.md** in each `agents/<name>/`: same convention (role, skills, mcp_tools, EN system prompt). Spring Boot reads it at startup and registers skills/MCP tools.
- Prompt files suffixed `.en.md`. System prompt instructs: respond in user's language, reason in English.

## Inter-service comms
- Sync: HTTP/SSE via `libs/contracts` DTO.
- Async: `libs/event-bus` (Postgres LISTEN/NOTIFY); outbox table in `bus`. Swap to Redis Streams later behind the same adapter if needed.
- LLM: only via `libs/llm-client`. MCP: only via `libs/mcp-client`.

## Locked decisions (do NOT relitigate)
- Calendar: Radicale = source of truth. Apple/Google = read-only ICS subscription into an `external` calendar in Radicale, refreshed by scheduler. Writes only through our system.
- Money Pro: import from CSV export (autodetect delimiter/encoding, preview before import, idempotent by row hash). No live API.
- Telegram: one bot for both users; identify by telegram_user_id → user_id; group chat supported for household commands.
- MCP client: Spring AI MCP module; thin HTTP/SSE fallback in libs/mcp-client behind an interface if unstable.
- Finance backend: our own `finance.*` schema in our PG. Not Firefly III (full control, single DB, all Java).
- Memory: hybrid; every record has `scope` = user:<id> | household:<id> | agent:<name>.
- Inter-agent on start: Postgres LISTEN/NOTIFY (no new infra).
- Dev LLM: mock provider first; build/test everything without a live model.
- Transport: MCP over HTTP/SSE (each MCP a container); Java↔Java over REST; orchestration via docker-compose + healthcheck + restart + Resilience4j retry. No k8s.
- **Shared agent-less tools live in capability-MCP servers** (no schema, stateless external-surface wrapper), bound by whichever agents need them — NOT shared skills (a skill is a prompt, it can't fetch). Reasoning-heavy external work = a specialist agent (`search`/`researcher`) that binds capability-MCPs. See PATTERNS.md "Recipe: add a capability-MCP".
- **Multi-agent coordination is agent-led.** A domain agent coordinates its own flow and reaches other specialists only through the orchestrator (`/v1/agents/invoke`) or the bus. The orchestrator stays a thin, data-driven router (the LLM does the thinking) — it does not become a monolithic planner.
- **Outbound external actions require confirmation.** The assistant proposes autonomously but acts outward (messaging others, purchase, booking) only on explicit user confirm (conversation-state, Track A).
