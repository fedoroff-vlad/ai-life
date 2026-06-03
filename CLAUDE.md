# ai-life — instructions for Claude

Personal AI agents system. Telegram → orchestrator → domain agents → narrow MCP servers → Postgres.

## Plan — read SELECTIVELY, never the whole thing
Design lives in `plans/` split per domain. **Read `plans/INDEX.md` first** (1 page), then the ONE relevant domain file. Never load more than one domain file per task. Do NOT re-read a file you already read this session. Current in-flight work + next steps: `plans/STATUS.md` (update it at the end of each PR).
Files: `architecture.md` (cross-cutting), `core.md`, `calendar.md`, `finance.md`, `platform.md`, `roadmap.md`, `STATUS.md`.

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
- Prefer slice/unit tests; reserve Testcontainers for repository/migration tests.

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
- All prompts / system messages / tool descriptions / agent roles — **English**. User-facing replies in the user's language.
- Skill: `skills/<domain>/<name>/SKILL.md` (frontmatter + EN body) + optional `SKILL.ru.md`. Agent: `agents/<name>/AGENT.md` (read at startup, registers skills/MCP).
- DB schemas split by domain (`core, memory, audit, bus, media, calendar, finance, tasks`), not by service. One shared changelog.
- Shared libs in `libs/*`. Inter-service: HTTP/SSE sync, Postgres LISTEN/NOTIFY async. LLM only via `libs/llm-client` → `llm-gateway`. MCP only via `libs/mcp-client`.
