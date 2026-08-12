# ai-life

Personal AI agents system for a 2-person household.
Telegram entry → orchestrator → domain agents → narrow MCP servers → Postgres.
Local-first deployment (target: Mac Studio 64 GB), Java / Spring Boot.

## Quickstart

From a fresh clone (each step has detail in [`infra/README.md`](infra/README.md)):

1. **Install everything.** macOS: `./scripts/bootstrap-mac.sh` · Windows: `.\scripts\bootstrap-win.ps1`.
   Installs the toolchain + apps, starts Ollama, seeds `infra/.env`, and pulls the models (~26 GB). Idempotent.
2. **Configure `infra/.env`.** Apply the [`.env.mac.example`](infra/.env.mac.example) LLM block, then fill the 4 secrets:
   - `GATEWAY_TELEGRAM_BOT_TOKEN` — create a bot via [@BotFather](https://t.me/BotFather).
   - `GATEWAY_INTERNAL_API_TOKEN` **and** `INTERNAL_API_TOKEN` — one random string, identical in both (`openssl rand -hex 32`).
   - `CALDAV_USER` / `CALDAV_PASSWORD` — pick any credentials; they create the Radicale CalDAV account.
3. **Launch.** macOS: `./scripts/start-mac.sh` · Windows: `.\scripts\start-win.ps1`. First run builds every image (~5–10 min).
4. **Verify.** `docker compose -f infra/docker-compose.yml logs -f gateway-telegram` shows it listening — then message your bot on Telegram and it replies.

LLM inference is local Ollama (native on the host). For IDE-driven development (JVMs from IntelliJ against
backing services only) use `docker-compose.dev.yml` — see [`infra/README.md`](infra/README.md).

> **Status:** foundations (Stages 0–5) + all Stage-6 domain agents shipped; both cross-cutting epics
> (identity, sharing) complete; **no feature slice in flight.** The list below is a set of **pointers, not
> a restatement** — the authoritative "what's done" lives in [`roadmap.md`](plans/roadmap.md) (stages/epics),
> the ADR headers, and [`HISTORY.md`](plans/HISTORY.md) (timeline); in-flight + next + parked live in
> [`plans/STATUS.md`](plans/STATUS.md).

**Shipped** — each line links to its source of truth; presence here = done, detail lives at the link:
- **Stages 0–5** — foundation · calendar · finance · tasks · memory + inter-agent · real-LLM golden tests → [`roadmap.md`](plans/roadmap.md)
- **Stage 6 domain agents** — researcher, stylist, nutrition (nutritionist + chef), creator → [`roadmap.md`](plans/roadmap.md)
- **Future agents** — briefing ([#186](https://github.com/fedoroff-vlad/ai-life/issues/186)) · docs ([#188](https://github.com/fedoroff-vlad/ai-life/issues/188)) → [`briefing.md`](plans/briefing.md) · [`docs.md`](plans/docs.md)
- **Second brain** ([#257](https://github.com/fedoroff-vlad/ai-life/issues/257)) + ambient capture + memory-driven orchestration ([#290](https://github.com/fedoroff-vlad/ai-life/issues/290)) → [`second-brain.md`](plans/second-brain.md) · [`ambient-capture.md`](plans/ambient-capture.md) · [`stage4.md`](plans/stage4.md)
- **Finance reporting** — monthly + year report, `mcp-chart-render` ([#291](https://github.com/fedoroff-vlad/ai-life/issues/291) · [#292](https://github.com/fedoroff-vlad/ai-life/issues/292)) → [`finance.md`](plans/finance.md)
- **Platform** — Java 25 / Boot 4 / Spring AI 2 migration ([#288](https://github.com/fedoroff-vlad/ai-life/issues/288)) + build/CI perf + fast/slow test split ([#423](https://github.com/fedoroff-vlad/ai-life/issues/423)) → [`migration-25-boot4.md`](plans/migration-25-boot4.md)
- **Identity & membership** epic → [ADR-0001](plans/adr/ADR-0001-identity-membership-scope.md)
- **Sharing as a capability** epic → [ADR-0002](plans/adr/ADR-0002-sharing-shared-capability.md)
- **skills-vs-flows** in-agent refactor (shared `SkillClassifier`) → [`skills-vs-flows.md`](plans/skills-vs-flows.md)

**Paused / not done:** coach-agent (CO-1/CO-2 shipped, then PARKED mid-epic — [#289](https://github.com/fedoroff-vlad/ai-life/issues/289), [`coach.md`](plans/coach.md)) · Mac deployment + hot/cold lifecycle (PARKED, hardware-blocked — [`lifecycle.md`](plans/lifecycle.md)) · future agents health/travel/email/smart-home. Next pick → [`plans/STATUS.md`](plans/STATUS.md).

See [`docs/REFERENCE.md`](docs/REFERENCE.md) for the two-lens overview and [`CLAUDE.md`](CLAUDE.md) for conventions + authorization.

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
│   ├── doc-render/           shared HTML deliverable renderer (stylist/nutrition boards)
│   └── sharing/             personal-vs-shared privacy engine (SharingResolver + per-domain policy seam)
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
│   ├── assistant/           coordinator-agent (cross-cutting multi-domain synthesis; #290)
│   └── coach/               coach-agent, mcp-coach, skills/ (#289 — CO-1/CO-2 done: safety gate + Reflect; PARKED, CO-3 intake deferred)
├── shared/                  shared RUNTIME capabilities any agent binds:
│   └── mcp/                 mcp-media-processing, mcp-web, mcp-market-data, mcp-weather, mcp-image-gen,
│                            mcp-chart-render, mcp-food-data, mcp-youtube, mcp-reddit, mcp-feeds,
│                            mcp-travel-search (capability-MCPs, no schema)
├── infra/                   docker-compose, liquibase, postgres init, .env.example
├── scripts/                 bootstrap/start (mac: *.sh, windows: *.ps1) one-command setup/launch, pull-models, golden.sh
├── Brewfile                 macOS toolset for `brew bundle` (see scripts/bootstrap-mac.sh)
└── winget-packages.json     Windows toolset for `winget import` (see scripts/bootstrap-win.ps1)
```
Each agent/MCP is its **own Spring Boot app + Dockerfile + container** — co-location ≠ one process.
New machine? Clone, then `./scripts/bootstrap-mac.sh` (macOS) or `.\scripts\bootstrap-win.ps1` (Windows) →
the matching `start` script (details: [`infra/README.md`](infra/README.md)).

## Build
```sh
mvn -T4 test             # FAST loop: unit/slice tests only — container ITs skipped, NO Docker needed
mvn -T4 verify           # full build + tests, parallel (Testcontainers spins up PG etc.); ~2x vs serial
mvn -T1C -DskipTests install   # fast local compile (respects the module DAG)
```
Tests are split fast/slow: `mvn test` runs unit/slice tests under surefire (no Testcontainers, no Docker);
container integration tests (tagged `it` — everything extending `libs/test-support`'s
`AbstractPostgresIntegrationTest`) run under **failsafe** in the `integration-test`/`verify` phases, so
only `verify` (and CI) pays for them. Run `verify` with Testcontainers **reuse OFF** (the default) — `-T`
needs an isolated container per module; reuse + parallel corrupt each other's DB.
See [`plans/migration-25-boot4.md`](plans/migration-25-boot4.md) §Build/CI performance.

## Plan & docs
New here? Read [`docs/REFERENCE.md`](docs/REFERENCE.md) first — a two-lens overview (developer, then
user) of what's done, how it works, and how to run it. The design lives in [`plans/`](plans/) — start at
[`plans/INDEX.md`](plans/INDEX.md), then the one relevant domain file. Key entry points:
- [`plans/STATUS.md`](plans/STATUS.md) — current in-flight work + what's done (mutable).
- [`plans/roadmap.md`](plans/roadmap.md) — stages, future agents, reuse table, risks.
- [`plans/architecture.md`](plans/architecture.md) — layers, monorepo structure, DB schemas, LLM strategy, locked decisions.
- [`plans/PATTERNS.md`](plans/PATTERNS.md) — scaffolding recipes (new MCP / agent / migration / contract).
- [`CLAUDE.md`](CLAUDE.md) — session reading order, conventions, authorization.

## License

[MIT](LICENSE) — do what you like; keep the copyright notice.
