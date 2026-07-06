# ai-life

Personal AI agents system for a 2-person household.
Telegram entry → orchestrator → domain agents → narrow MCP servers → Postgres.
Local-first deployment (target: Mac Studio 128 GB), Java / Spring Boot.

> **Status:** Foundation (Stage 0–4) closed; **Stage 6 domain agents complete** — calendar, finance, tasks, researcher, stylist, nutrition (nutritionist + chef), creator, all MVP-done. **Stage 5 (real LLM) UNBLOCKED ([#199](https://github.com/fedoroff-vlad/ai-life/issues/199), closed)** — validated on local Ollama (`qwen2.5:7b`): opt-in, CI-skipped **golden tests** (structure-not-text) cover the whole behavioural surface — agent + tool routing, JSON-skill output, free-text synthesis — across all 8 agents + the orchestrator, sharing `libs/golden-test-support`. Latest reporting follow-up: finance ships a Telegram monthly report ([#196](https://github.com/fedoroff-vlad/ai-life/issues/196): Grafana dashboards + `monthly-report` skill). **Done:** the first future agent, **briefing-agent** ([#186](https://github.com/fedoroff-vlad/ai-life/issues/186), morning digest) — personalization store + NL config flow + the digest (multi-domain gather→synthesize on the `Coordinator`) + its HTML board + the proactive wake receiver + the per-profile scheduler registration & E2E closer (BR-f2), all shipped (mcp-weather + mcp-briefing + briefing-agent, golden-verified on local Ollama). **Done: docs-agent ([#188](https://github.com/fedoroff-vlad/ai-life/issues/188), personal document archive)** — `mcp-docs` (8116, `docs` schema, store + pg_trgm search) + `/internal/ocr` passthrough + `docs-agent` (8117) with `doc-archiver` ingest (photo → OCR → metadata → archive) and `doc-finder` search, golden-tested + E2E-closed. **Done: the second-brain epic ([#257](https://github.com/fedoroff-vlad/ai-life/issues/257), the owner's "вторая память", SB-1..7 all merged)** — an ai-life-owned notes+vector+graph substrate that evolves `memory-service` into the foundation every agent reads/writes: authored `memory.note` tier auto-seeding recall (SB-2) + `[[wiki-links]]`→relations/backlinks (SB-3), a thin `notes-agent` front (SB-4), a universal note-write seam adopted by docs-agent (SB-5), curated person-notes feeding `GiftRecommender` (SB-6, **closed #189**), and a round-trippable markdown-vault export (SB-7). **Post-epic follow-ons:** proactive resurfacing ✅ (a scheduler wake surfaces a stale note, R-a/R-b/R-c); and **ambient / intuitive capture ✅ complete (AC-1..5, 2026-07-04)** ([ambient-capture.md](plans/ambient-capture.md)): the second brain fills itself from ordinary conversation — deciding *what* to keep and *about whom* without the "запомни" keyword (explicit fixation → auto-save, important inferred → approve via a proactive push + notes-agent `/resume`, trivial → ignore), deduping and reconciling near-duplicates (enrich/supersede) on write — the quality-intake half of the north-star toward memory-driven, one-shot orchestration (flag-gated `memory.ambient-capture.enabled`). **Done — memory-driven orchestration ([#290](https://github.com/fedoroff-vlad/ai-life/issues/290)), the *output* half of the north-star:** a thin **`coordinator-agent`** (8119) reads the second brain and synthesizes one grounded answer for multi-domain requests, routed to it purely by manifest (Slice A, dual-triggered + golden-verified on `qwen2.5:7b`); a reusable read-only **`brief`** cross-agent query (`BriefResponder` in `agent-runtime`, exposers finance + calendar) so specialists feed it *live* answers, which the coordinator plans-for and gathers per request (Slices B1/B2/B2-followup); and a bounded **confidence loop** — a FAST `SufficiencyAssessor` gates one focus-sharpened re-gather within `coordinator-agent.max-rounds` (Slice E-later). **Done — the Java 25 + Spring Boot 4.0.7 + Spring AI 2.0.0 + Jackson 3 platform migration** ([#288](https://github.com/fedoroff-vlad/ai-life/issues/288), one coordinated bump; 51/51 modules + full Testcontainers verify green — [migration-25-boot4.md](plans/migration-25-boot4.md)). **Done — the build/CI performance pass** on that baseline: measured the 51-module build, enabled `-T` module parallelism (local `-T4` / CI `-T2`, Testcontainers reuse off) for a ~2× `verify`, and deduped build-config cruft. **Next: the finance year-analysis report + chart-render** ([#291](https://github.com/fedoroff-vlad/ai-life/issues/291)/[#292](https://github.com/fedoroff-vlad/ai-life/issues/292)). Owner's future-agent order: briefing → docs (#188) → **health ([#187](https://github.com/fedoroff-vlad/ai-life/issues/187))** → family-memory (#189, folded into #257). See [`docs/REFERENCE.md`](docs/REFERENCE.md) for the overview, [`plans/STATUS.md`](plans/STATUS.md) for in-flight detail, and [`CLAUDE.md`](CLAUDE.md) for conventions + authorization.

## Stack
- Java 25 LTS, Maven 3.9+, Spring Boot 4.0.x (Framework 7, Jackson 3), Spring AI 2 (MCP).
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
│   ├── briefing/            briefing-agent, mcp-briefing, skills/ (✅ done, #186)
│   ├── docs/                docs-agent, mcp-docs, skills/ (✅ done, #188)
│   ├── knowledge/           notes-agent (second-brain front; binds memory-service, no own MCP)
│   └── assistant/           coordinator-agent (cross-cutting multi-domain synthesis; #290)
├── shared/                  shared RUNTIME capabilities any agent binds:
│   └── mcp/                 mcp-media-processing, mcp-web, mcp-market-data, mcp-weather, mcp-image-gen,
│                            mcp-food-data, mcp-youtube, mcp-reddit, mcp-feeds (capability-MCPs, no schema)
└── infra/                   docker-compose, liquibase, postgres init, .env.example
```
Each agent/MCP is its **own Spring Boot app + Dockerfile + container** — co-location ≠ one process.

## Build
```sh
mvn -T4 verify           # full build + tests, parallel (Testcontainers spins up PG etc.); ~2x vs serial
mvn -T1C -DskipTests install   # fast local compile (respects the module DAG)
```
Run with Testcontainers **reuse OFF** (the default) — `-T` needs an isolated container per module;
reuse + parallel corrupt each other's DB. See [`plans/migration-25-boot4.md`](plans/migration-25-boot4.md) §Build/CI performance.

## Plan & docs
New here? Read [`docs/REFERENCE.md`](docs/REFERENCE.md) first — a two-lens overview (developer, then
user) of what's done, how it works, and how to run it. The design lives in [`plans/`](plans/) — start at
[`plans/INDEX.md`](plans/INDEX.md), then the one relevant domain file. Key entry points:
- [`plans/STATUS.md`](plans/STATUS.md) — current in-flight work + what's done (mutable).
- [`plans/roadmap.md`](plans/roadmap.md) — stages, future agents, reuse table, risks.
- [`plans/architecture.md`](plans/architecture.md) — layers, monorepo structure, DB schemas, LLM strategy, locked decisions.
- [`plans/PATTERNS.md`](plans/PATTERNS.md) — scaffolding recipes (new MCP / agent / migration / contract).
- [`CLAUDE.md`](CLAUDE.md) — session reading order, conventions, authorization.
