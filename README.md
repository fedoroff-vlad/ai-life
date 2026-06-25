# ai-life

Personal AI agents system for a 2-person household.
Telegram entry → orchestrator → domain agents → narrow MCP servers → Postgres.
Local-first deployment (target: Mac Studio 128 GB), Java / Spring Boot.

> **Status:** Stage 6 — domains being built out. Foundation (Stage 0–4) closed; Stage 5 (real LLM) blocked on model access. Live domains: **calendar, finance, tasks, researcher, stylist, nutrition (nutritionist + chef), creator**. The **creator** (content-factory) MVP is complete (trend → ideas → drafts, trend/draft cache, and the inter-agent birthday-greeting chain `calendar → creator → notifier`). See [`plans/STATUS.md`](plans/STATUS.md) for the in-flight detail and [`CLAUDE.md`](CLAUDE.md) for working conventions + authorization rules.

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
│                            media-service, conversation-service
├── domains/                 one self-contained folder per specialist (agent + its MCP(s) + skills):
│   ├── calendar/            calendar-agent, mcp-caldav, mcp-ics-import, skills/
│   ├── finance/             finance-agent, mcp-finance, mcp-money-pro-import, skills/
│   ├── tasks/               tasks-agent, mcp-tasks, skills/
│   ├── researcher/          researcher-agent
│   ├── stylist/             stylist-agent, mcp-wardrobe, skills/
│   └── nutrition/           nutritionist-agent, chef-agent, mcp-nutrition, skills/
├── shared/                  shared RUNTIME capabilities any agent binds:
│   └── mcp/                 mcp-media-processing, mcp-web, mcp-market-data, mcp-image-gen (capability-MCPs, no schema)
└── infra/                   docker-compose, liquibase, postgres init, .env.example
```
Each agent/MCP is its **own Spring Boot app + Dockerfile + container** — co-location ≠ one process.

## Build
```sh
mvn -B verify            # full build + tests (Testcontainers spins up PG etc.)
mvn -T1C -DskipTests install   # fast local compile (respects the module DAG)
```

## Plan & docs
The design lives in [`plans/`](plans/) — start at [`plans/INDEX.md`](plans/INDEX.md), then the one
relevant domain file. Key entry points:
- [`plans/STATUS.md`](plans/STATUS.md) — current in-flight work + what's done (mutable).
- [`plans/roadmap.md`](plans/roadmap.md) — stages, future agents, reuse table, risks.
- [`plans/architecture.md`](plans/architecture.md) — layers, monorepo structure, DB schemas, LLM strategy, locked decisions.
- [`plans/PATTERNS.md`](plans/PATTERNS.md) — scaffolding recipes (new MCP / agent / migration / contract).
- [`CLAUDE.md`](CLAUDE.md) — session reading order, conventions, authorization.
