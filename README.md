# ai-life

Personal AI agents system for a 2-person household.
Telegram entry → orchestrator → domain agents → narrow MCP servers → Postgres.
Local-first deployment (target: Mac Studio 128 GB), Java / Spring Boot.

> **Status:** Foundation (Stage 0–4) closed; **Stage 6 domain agents complete** — calendar, finance, tasks, researcher, stylist, nutrition (nutritionist + chef), creator, all MVP-done. **Stage 5 (real LLM) UNBLOCKED ([#199](https://github.com/fedoroff-vlad/ai-life/issues/199), closed)** — validated on local Ollama (`qwen2.5:7b`): opt-in, CI-skipped **golden tests** (structure-not-text) cover the whole behavioural surface — agent + tool routing, JSON-skill output, free-text synthesis — across all 8 agents + the orchestrator, sharing `libs/golden-test-support`. Latest reporting follow-up: finance ships a Telegram monthly report ([#196](https://github.com/fedoroff-vlad/ai-life/issues/196): Grafana dashboards + `monthly-report` skill). **Done:** the first future agent, **briefing-agent** ([#186](https://github.com/fedoroff-vlad/ai-life/issues/186), morning digest) — personalization store + NL config flow + the digest (multi-domain gather→synthesize on the `Coordinator`) + its HTML board + the proactive wake receiver + the per-profile scheduler registration & E2E closer (BR-f2), all shipped (mcp-weather + mcp-briefing + briefing-agent, golden-verified on local Ollama). **Next up: docs-agent (#188).** Owner's future-agent order: briefing → docs (#188) → health (#187) → family-memory (#189). See [`docs/REFERENCE.md`](docs/REFERENCE.md) for the overview, [`plans/STATUS.md`](plans/STATUS.md) for in-flight detail, and [`CLAUDE.md`](CLAUDE.md) for conventions + authorization.

## Stack
- Java 21 LTS, Maven 3.9+, Spring Boot 3.4.x, Spring AI (MCP).
- Postgres 16 + pgvector + Apache AGE + pg_trgm.
- Liquibase (XML master, YAML features, raw SQL for complex DDL).
- Docker Compose for local infra. GitHub Actions for CI.

## Layout (group-by-domain)
```
ai-life/
├── pom.xml                  parent POM (versions, plugin mgmt, BOMs)
├── libs/                    shared compile-time Java (jars consumed by services)
│   ├── contracts/           DTOs, NormalizedMessage, agent/event contracts
│   ├── llm-client/          channel-based client for llm-gateway
│   ├── mcp-client/          wrapper around Spring AI MCP
│   ├── event-bus/           Postgres LISTEN/NOTIFY + outbox adapter
│   ├── platform-common/     logging, metrics, error envelopes
│   ├── agent-runtime/       AGENT.md/SKILL.md loaders + shared HTTP clients (agents @Import this)
│   └── doc-render/           shared HTML deliverable renderer (stylist/nutrition boards)
├── platform/                cross-cutting SERVICES (the brain + infra):
│                            orchestrator, gateway-telegram, llm-gateway, memory-service,
│                            profile-service, scheduler-service, notifier-service,
│                            media-service, conversation-service, calendar-web (read-only ICS feeds)
├── domains/                 one self-contained folder per specialist (agent + its MCP(s) + skills):
│   ├── calendar/            calendar-agent, mcp-caldav, mcp-ics-import, skills/
│   ├── finance/             finance-agent, mcp-finance, mcp-money-pro-import, skills/
│   ├── tasks/               tasks-agent, mcp-tasks, skills/
│   ├── researcher/          researcher-agent
│   ├── stylist/             stylist-agent, mcp-wardrobe, skills/
│   ├── nutrition/           nutritionist-agent, chef-agent, mcp-nutrition, skills/
│   ├── creator/             creator-agent, mcp-creator, skills/
│   └── briefing/            briefing-agent, mcp-briefing, skills/ (✅ done, #186)
├── shared/                  shared RUNTIME capabilities any agent binds:
│   └── mcp/                 mcp-media-processing, mcp-web, mcp-market-data, mcp-weather, mcp-image-gen,
│                            mcp-food-data, mcp-youtube, mcp-reddit, mcp-feeds (capability-MCPs, no schema)
└── infra/                   docker-compose, liquibase, postgres init, .env.example
```
Each agent/MCP is its **own Spring Boot app + Dockerfile + container** — co-location ≠ one process.

## Build
```sh
mvn -B verify            # full build + tests (Testcontainers spins up PG etc.)
mvn -T1C -DskipTests install   # fast local compile (respects the module DAG)
```

## Plan & docs
New here? Read [`docs/REFERENCE.md`](docs/REFERENCE.md) first — a two-lens overview (developer, then
user) of what's done, how it works, and how to run it. The design lives in [`plans/`](plans/) — start at
[`plans/INDEX.md`](plans/INDEX.md), then the one relevant domain file. Key entry points:
- [`plans/STATUS.md`](plans/STATUS.md) — current in-flight work + what's done (mutable).
- [`plans/roadmap.md`](plans/roadmap.md) — stages, future agents, reuse table, risks.
- [`plans/architecture.md`](plans/architecture.md) — layers, monorepo structure, DB schemas, LLM strategy, locked decisions.
- [`plans/PATTERNS.md`](plans/PATTERNS.md) — scaffolding recipes (new MCP / agent / migration / contract).
- [`CLAUDE.md`](CLAUDE.md) — session reading order, conventions, authorization.
