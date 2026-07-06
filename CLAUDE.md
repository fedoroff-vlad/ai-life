# ai-life — instructions for Claude

Personal AI agents system. Telegram → orchestrator → domain agents → narrow MCP servers → Postgres.

## Session start — fixed reading order (do NOT deviate)
1. This file (`CLAUDE.md`).
2. `plans/STATUS.md` — current in-flight slice + next steps (live only; kept lean). Shipped-work detail is NOT here — it lives in `plans/HISTORY.md` (archive, out of this reading order) and the domain plan/module README. Don't load HISTORY.md unless you need "when/what was done".
3. `plans/INDEX.md` — map of `plans/`; pick ONE domain file relevant to the task.
4. The single relevant `plans/<domain>.md` (e.g. `calendar.md`). Never load more than one domain file per task.
5. **Module README first, source code second.** Before reading any `.java` in a module, read that module's `README.md` (`<module>/README.md`). It lists purpose, port, env, endpoints/tools, and a one-line index of key classes. Open a class only when the README points you there or you need its actual behaviour, not its existence.
6. For a known cross-cutting recipe (new MCP module / new agent / new migration / new contract), read `plans/PATTERNS.md` — it points at the canonical example so you don't have to re-derive the layout by reading a sibling module wholesale.

Do NOT re-read a file you already read this session. Do NOT broad-Glob/Grep the repo to "see what's there" — start with READMEs and follow their pointers.

## README upkeep — non-negotiable
Every module has a `README.md` at its root. Every PR that changes a module's **public contract** (env vars, endpoints, MCP tools, key class names, behaviour summary, port, manifest) **must update that module's README in the same PR.** READMEs that drift past one PR are worse than no READMEs at all — they actively mislead future sessions. Folder-level READMEs only exist where the grouping isn't obvious from filenames (e.g. `config/`, `sync/`). Don't add folder READMEs that just enumerate filenames.

**Three blind spots that drift anyway — check them explicitly (they're not in the session reading order, so nothing forces a look):**
1. **The root [`README.md`](README.md)** counts as a module README. Bump its Status line + layout when a stage closes or the repo restructures.
2. **A capability/lib README when you *bind/consume* it, not just when you change it.** Binding `mcp-web` from a new agent, or adding a `libs/doc-render` consumer, is a change to *that* module's "who uses me" surface — update its README in your PR even though your code lives elsewhere. The "same PR" rule is about the *contract you touched*, and a consumer list is part of it.
3. **The README's Status/summary header, not just the body.** It's easy to extend the key-classes list and forget the `Status (X)` line above it — bump both.

At every **stage/domain closer**, do a quick freshness pass: root README + `plans/{STATUS,roadmap,architecture,INDEX}.md` + the closed domain's module READMEs (status headers especially).

## STATUS / HISTORY discipline — non-negotiable
`plans/STATUS.md` is **live-only** and stays lean — it's read in full at every session start (reading-order step 2), so bloat there taxes every session (it once grew to 300KB / ~78k tokens of shipped-work log; that must not recur).
- STATUS holds **only what is in flight or the next slice/stage**: the current `## Now` bullet(s), a terse `## Next` queue, open `## Deferred` items, the workflow reminder. Nothing already shipped.
- **At the end of every PR, move the finished work OUT of STATUS into `plans/HISTORY.md`** — add a terse timeline row (`date · milestone · → domain plan + PR`) and, if the prose is worth keeping, append it to HISTORY's verbatim archive. **Never let `## Now` accumulate `✅ DONE` bullets** — that append-only drift is exactly what bloated STATUS.
- **Detail lives at the source, not in STATUS/HISTORY.** The authoritative "what was done" is the **domain plan file** (`plans/<domain>.md`) + the **module README**. STATUS and HISTORY *link* to them ("go to the source"); they do not duplicate the detail.
- `plans/HISTORY.md` is the archive and is **NOT in the session reading order** — do not load it unless you specifically need "when/what was done", and then follow its links to the domain source rather than reading it wholesale.

## Work style — minimise overthinking
- One PR = one small vertical slice. If a task needs >~5 files changed, STOP and propose splitting before writing code.
- Don't restate the plan or re-derive the architecture. Assume the plan is correct and act.
- Skip preamble. No "here's my understanding / what I'll do" essays — make the change, then a ≤3-line summary of what changed and why.
- Auto-merge flow is approved and routine — don't reason each time about whether you're allowed; just follow the rules below.
- Genuinely ambiguous? Ask ONE question. Don't explore options.
- New layer / pattern / architectural concept → flag it BEFORE coding; don't invent silently.

## Test strategy — don't burn cycles
- While iterating, run ONLY the relevant test class (`mvn -Dtest=ClassName test`), never the full suite.
- Full suite + Testcontainers runs ONCE before opening the PR — **the main-branch CI is the authority for the full run** (PR CI is now incremental, see below), so run the full `mvn verify` locally before opening a PR as the safety net.
- **Build parallelism — `-T` with container isolation (measured 2026-07-06, #288 perf pass).** The scan overturned the old "serial on purpose" doctrine: the bottleneck is **per-module JVM+Spring-context startup across 51 modules, run serially**, not Testcontainers churn (container start is ~4% of the total; reuse only ever amortised that). `-T` cuts full `verify` ≈**2×** (11:13 → 5:24, all tests green) — **but only with Testcontainers reuse OFF**: reuse + parallel share one PG and corrupt each other's schema. So the two levers are mutually exclusive; parallelism needs an **isolated container per module** (the default when `testcontainers.reuse.enable` is unset). Isolated PG is light (~150 MB), so `-T2` on the 2-vCPU/7 GB runner (≈300 MB of containers) is well within budget. **Use:** local loop `mvn -T4 verify`; CI's **main-branch full run** uses `-T2` (reuse step removed) — the **PR/GIB incremental path stays serial**, because `-T` + a GIB-pruned reactor races on upstream SNAPSHOT resolution (a pruned PR can't find an upstream lib jar mid-parallel); PR builds are already small so serial costs little. Compile-only `mvn -T1C -DskipTests install` is always safe (`-T` respects the module DAG). Full detail + numbers: [`plans/migration-25-boot4.md`](plans/migration-25-boot4.md) §Build/CI performance.
- **CI incremental builds (PRs only).** `.mvn/extensions.xml` loads gitflow-incremental-builder (GIB), **disabled by default** via `.mvn/maven.config` (`-Dgib.disable=true`) so local builds and the **main-branch** CI stay full. The CI workflow enables GIB **only on pull requests** (`-Dgib.disable=false -Dgib.referenceBranch=origin/<base> -Dgib.buildUpstream=always`): a PR builds only the modules changed vs the base **plus their upstream libs** (resolved intra-reactor, no `install`). A leaf-agent change → ~7 modules instead of 41; a change to `.mvn/`, the root pom, or another root/non-module file → GIB safely builds **all** (so build-config changes are still fully validated). Docs-only PRs (`*.md` / `docs/` / `plans/`) **skip Maven entirely** (a detect step), still reporting the `build & test` check green. GIB prunes the module *set* on PRs and runs **serially** there; `-T2` is applied only on the **main-branch full build** (GIB off → full DAG → parallel-safe). GIB pruning + `-T` do **not** compose (parallel threads race the pruned reactor's upstream SNAPSHOTs).
- Don't paste full container/test logs. On failure, extract only the failing assertion + ~3 relevant lines.
- Prefer slice/unit tests for iterating; reserve Testcontainers for repository/migration tests.
- **End-to-end test is mandatory for each stage closer and for any slice that adds/changes a cross-service wire contract.** Fast slice/unit tests stay the default while iterating, but the slice is not done until the chain is proven across real HTTP boundaries. Shape (the monorepo's fat-jar packaging blocks a true all-real-services module, so don't attempt one): ONE real Spring context for the pivotal service + MockWebServers that **forward** between hops, asserting the `libs/contracts` DTOs survive serialisation each way. Canonical examples — **proactive wake path** (real scheduler context): [`E2EStage1ClosingFlowTest`](platform/scheduler-service/src/test/java/dev/fedorov/ailife/scheduler/E2EStage1ClosingFlowTest.java) / `E2EStage2FinanceWakeFlowTest` / `E2EStage3TasksWakeFlowTest` (scheduler → orchestrator → agent trigger); **inbound path** (real agent context): [`E2EInboundClarifyFlowTest`](domains/tasks/tasks-agent/src/test/java/dev/fedorov/ailife/agents/tasks/E2EInboundClarifyFlowTest.java) (user message → agent → intent skill → mcp passthrough → reply). Name them `E2E<Stage/Feature>…Test`. **Note:** MCP SSE can't be MockWebServer'd, so an inbound closer that needs a tool call routes through an `/internal/*` HTTP passthrough (what the intent-skill flows already use), not the MCP transport.
- When you DO need to capture full output, write to `logs/<descriptive>.log` (folder is gitignored; see `logs/README.md`). Never drop ad-hoc `*.log` files in the repo root.

## Authorization — do without asking
- Push to `stage-*` / `feat-*` / `fix-*`; create PRs (`gh pr create`); **auto-merge own PRs into `main` once CI green** (squash + delete branch); create/update Issues, labels, milestones; write `.github/workflows/*`.

## Confirm before doing
- Direct push to `main` — **never** (always via PR). Force-push / `git reset --hard` on shared branches / deleting others' branches. `docker compose up` / `docker run` locally. Adding paid services / API keys. Changing licence, repo visibility, branch protection.

## Branching
- `stage-<n>-pr<m>-<slug>` for staged plan work; else `feat/<slug>` / `fix/<slug>`. PR description references the plan section. `main` always green — if CI breaks on main, drop everything and fix.

## Stack (details: `plans/architecture.md`)
- Java 25 LTS, Maven 3.9+, Spring Boot 4.0.x (Framework 7, Jackson 3), Spring AI 2 (MCP client).
- Postgres 16 + pgvector + Apache AGE + pg_trgm.
- Liquibase: master XML `infra/liquibase/db.changelog-master.xml`; per-feature YAML `features/NNN-<domain>.yml`; complex DDL raw SQL in `features/NNN-<domain>/*.sql`.
- Docker Compose (dev infra). GitHub Actions (CI). Tests: JUnit 5 + Testcontainers (PG auto-starts).

## Conventions (details: `plans/architecture.md`)
- Talk to the repo owner in **Russian** (chat replies, PR descriptions targeted at them, summaries). Code, identifiers, commit messages, prompts, system messages, tool descriptions, agent roles, plan/STATUS files, and anything that ends up in the repo stay in **English**. User-facing app replies follow the end user's language.
- Skill: `skills/<domain>/<name>/SKILL.md` (frontmatter + EN body) + optional `SKILL.ru.md`. Agent: `agents/<name>/AGENT.md` (read at startup, registers skills/MCP).
- DB schemas split by domain (`core, memory, audit, bus, media, calendar, finance, tasks`), not by service. One shared changelog.
- Shared libs in `libs/*`. Inter-service: HTTP/SSE sync, Postgres LISTEN/NOTIFY async. LLM only via `libs/llm-client` → `llm-gateway`. MCP only via `libs/mcp-client`.
- Two kinds of MCP: **domain-MCP** (owns a schema) vs **capability-MCP** (schema-less shared tool: weather, web-fetch — any agent binds it). Raw external capability → capability-MCP; reasoning over it → a specialist agent. Coordination is **agent-led via the hub** (orchestrator stays a thin router); outbound external actions need user confirm. Full model + routing doctrine + brain inputs: `plans/architecture.md`.
