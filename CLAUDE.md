# ai-life — instructions for Claude

Personal AI agents system. Telegram entry → orchestrator → domain agents → narrow MCP servers → Postgres.
Full system design lives in `C:\Users\vlad\.claude\plans\fluffy-sparking-sunset.md` (the approved plan). Read it before any large change.

## Authorization — what to do without asking
- Push to feature branches `stage-*` / `feat-*` / `fix-*` — OK.
- Create PRs with `gh pr create` — OK.
- **Auto-merge own PRs into `main` once CI is green** — OK (user explicitly approved).
- Create/update GitHub Issues, labels, milestones — OK.
- Write and modify GitHub Actions workflows (`.github/workflows/*`) — OK.

## What to confirm before doing
- Direct push to `main` — **never** (always via PR).
- Force-push, `git reset --hard` on shared branches, deleting branches that aren't yours.
- Running `docker compose up` / `docker run` locally — ask first (resource cost, port conflicts).
- Adding paid services / API keys to configs.
- Changing licence, repo visibility, branch protection rules.

## Branching
- Branch per PR: `stage-<n>-pr<m>-<slug>` for staged work from the plan; `feat/<slug>` or `fix/<slug>` otherwise.
- Open PR with a description that references the plan section.
- After CI green: auto-merge with squash, delete branch.
- `main` is always green. If CI breaks on `main`, drop everything and fix.

## Stack
- Java 21 LTS, Maven 3.9+, Spring Boot 3.4.x, Spring AI (MCP client).
- Postgres 16 + pgvector + Apache AGE + pg_trgm.
- Liquibase: master XML in `infra/liquibase/`, feature files in YAML, complex DDL in raw SQL.
- Docker Compose for dev infra. GitHub Actions for CI.
- Tests: JUnit 5 + Testcontainers (PG auto-starts in tests).

## Code conventions
- All prompts, system messages, tool descriptions, agent role definitions — **English** (token economy + better model behaviour). User-facing responses are in the user's language (auto-detected).
- Each skill: separate folder under `skills/<domain>/<skill-name>/` with `SKILL.md` (Anthropic Skills frontmatter + EN system prompt body) and optional `SKILL.ru.md` (translation for humans, not read by the LLM).
- Each agent: `agents/<name>/AGENT.md` with the same convention. Spring Boot reads `AGENT.md` at startup and registers skills/MCP tools from its frontmatter.
- DB schemas split by domain (`core`, `memory`, `audit`, `bus`, `media`, `calendar`, `finance`, `tasks`), not by service. One shared Liquibase changelog applies them all.
- Services share libraries via `libs/*` (contracts, llm-client, mcp-client, event-bus, platform-common).
- Inter-service: HTTP/SSE for sync, Postgres LISTEN/NOTIFY for async (via `libs/event-bus`).
- LLM access: only through `libs/llm-client` → `llm-gateway` (env-var-switchable provider).
- MCP access: only through `libs/mcp-client` (Spring AI MCP under the hood).

## Liquibase format
- Master file: `infra/liquibase/db.changelog-master.xml` (XML).
- Per-feature: `infra/liquibase/features/NNN-<domain>.yml` (YAML).
- Complex DDL or data fixes: `infra/liquibase/features/NNN-<domain>/*.sql` referenced from the YAML.

## When in doubt
Re-read the plan (`C:\Users\vlad\.claude\plans\fluffy-sparking-sunset.md`) before introducing new architectural concepts. Don't invent new layers or patterns without flagging it first.
