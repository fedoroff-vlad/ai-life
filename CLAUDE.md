# ai-life — instructions for Claude

Personal AI agents system. Telegram → orchestrator → domain agents → narrow MCP servers → Postgres.

## Session start — fixed reading order (do NOT deviate)
1. This file (`CLAUDE.md`).
2. `plans/STATUS.md` — current in-flight branch + next concrete steps.
3. `plans/INDEX.md` — map of `plans/`; pick ONE domain file relevant to the task.
4. The single relevant `plans/<domain>.md` (e.g. `calendar.md`). Never load more than one domain file per task.
5. **Module README first, source code second.** Before reading any `.java` in a module, read that module's `README.md` (`<module>/README.md`). It lists purpose, port, env, endpoints/tools, and a one-line index of key classes. Open a class only when the README points you there or you need its actual behaviour, not its existence.
6. For a known cross-cutting recipe (new MCP module / new agent / new migration / new contract), read `plans/PATTERNS.md` — it points at the canonical example so you don't have to re-derive the layout by reading a sibling module wholesale.

Do NOT re-read a file you already read this session. Do NOT broad-Glob/Grep the repo to "see what's there" — start with READMEs and follow their pointers.

## README upkeep — non-negotiable
Every module has a `README.md` at its root. Every PR that changes a module's **public contract** (env vars, endpoints, MCP tools, key class names, behaviour summary, port, manifest) **must update that module's README in the same PR.** READMEs that drift past one PR are worse than no READMEs at all — they actively mislead future sessions. Folder-level READMEs only exist where the grouping isn't obvious from filenames (e.g. `config/`, `sync/`). Don't add folder READMEs that just enumerate filenames.

## Work style — minimise overthinking
- One PR = one small vertical slice. If a task needs >~5 files changed, STOP and propose splitting before writing code.
- Don't restate the plan or re-derive the architecture. Assume the plan is correct and act.
- Skip preamble. No "here's my understanding / what I'll do" essays — make the change, then a ≤3-line summary of what changed and why.
- Auto-merge flow is approved and routine — don't reason each time about whether you're allowed; just follow the rules below.
- Genuinely ambiguous? Ask ONE question. Don't explore options.
- New layer / pattern / architectural concept → flag it BEFORE coding; don't invent silently.

## Test strategy — don't burn cycles
- While iterating, run ONLY the relevant test class (`mvn -Dtest=ClassName test`), never the full suite.
- Full suite + Testcontainers runs ONCE before opening the PR — CI is the authority for the full run.
- Don't paste full container/test logs. On failure, extract only the failing assertion + ~3 relevant lines.
- Prefer slice/unit tests for iterating; reserve Testcontainers for repository/migration tests.
- **End-to-end test is mandatory for each stage closer and for any slice that adds/changes a cross-service wire contract.** Fast slice/unit tests stay the default while iterating, but the slice is not done until the chain is proven across real HTTP boundaries. Shape (the monorepo's fat-jar packaging blocks a true all-real-services module, so don't attempt one): ONE real Spring context for the pivotal service + MockWebServers that **forward** between hops, asserting the `libs/contracts` DTOs survive serialisation each way. Canonical examples — **proactive wake path** (real scheduler context): [`E2EStage1ClosingFlowTest`](platform/scheduler-service/src/test/java/dev/fedorov/ailife/scheduler/E2EStage1ClosingFlowTest.java) / `E2EStage2FinanceWakeFlowTest` / `E2EStage3TasksWakeFlowTest` (scheduler → orchestrator → agent trigger); **inbound path** (real agent context): [`E2EInboundClarifyFlowTest`](agents/tasks-agent/src/test/java/dev/fedorov/ailife/agents/tasks/E2EInboundClarifyFlowTest.java) (user message → agent → intent skill → mcp passthrough → reply). Name them `E2E<Stage/Feature>…Test`. **Note:** MCP SSE can't be MockWebServer'd, so an inbound closer that needs a tool call routes through an `/internal/*` HTTP passthrough (what the intent-skill flows already use), not the MCP transport.
- When you DO need to capture full output, write to `logs/<descriptive>.log` (folder is gitignored; see `logs/README.md`). Never drop ad-hoc `*.log` files in the repo root.

## Authorization — do without asking
- Push to `stage-*` / `feat-*` / `fix-*`; create PRs (`gh pr create`); **auto-merge own PRs into `main` once CI green** (squash + delete branch); create/update Issues, labels, milestones; write `.github/workflows/*`.

## Confirm before doing
- Direct push to `main` — **never** (always via PR). Force-push / `git reset --hard` on shared branches / deleting others' branches. `docker compose up` / `docker run` locally. Adding paid services / API keys. Changing licence, repo visibility, branch protection.

## Branching
- `stage-<n>-pr<m>-<slug>` for staged plan work; else `feat/<slug>` / `fix/<slug>`. PR description references the plan section. `main` always green — if CI breaks on main, drop everything and fix.

## Stack (details: `plans/architecture.md`)
- Java 21 LTS, Maven 3.9+, Spring Boot 3.4.x, Spring AI (MCP client).
- Postgres 16 + pgvector + Apache AGE + pg_trgm.
- Liquibase: master XML `infra/liquibase/db.changelog-master.xml`; per-feature YAML `features/NNN-<domain>.yml`; complex DDL raw SQL in `features/NNN-<domain>/*.sql`.
- Docker Compose (dev infra). GitHub Actions (CI). Tests: JUnit 5 + Testcontainers (PG auto-starts).

## Conventions (details: `plans/architecture.md`)
- Talk to the repo owner in **Russian** (chat replies, PR descriptions targeted at them, summaries). Code, identifiers, commit messages, prompts, system messages, tool descriptions, agent roles, plan/STATUS files, and anything that ends up in the repo stay in **English**. User-facing app replies follow the end user's language.
- Skill: `skills/<domain>/<name>/SKILL.md` (frontmatter + EN body) + optional `SKILL.ru.md`. Agent: `agents/<name>/AGENT.md` (read at startup, registers skills/MCP).
- DB schemas split by domain (`core, memory, audit, bus, media, calendar, finance, tasks`), not by service. One shared changelog.
- Shared libs in `libs/*`. Inter-service: HTTP/SSE sync, Postgres LISTEN/NOTIFY async. LLM only via `libs/llm-client` → `llm-gateway`. MCP only via `libs/mcp-client`.
- Two kinds of MCP: **domain-MCP** (owns a schema) vs **capability-MCP** (schema-less shared tool: weather, web-fetch — any agent binds it). Raw external capability → capability-MCP; reasoning over it → a specialist agent. Coordination is **agent-led via the hub** (orchestrator stays a thin router); outbound external actions need user confirm. Full model + routing doctrine + brain inputs: `plans/architecture.md`.
