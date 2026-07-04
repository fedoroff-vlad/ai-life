# Plan index — READ THIS FIRST, then ONE domain file

This is the map of the system design. **Read only the file for the domain you are touching.**
Never load more than one domain file per task. Do not re-read a file you already read this session.

| File | Covers | Read when |
|---|---|---|
| `architecture.md` | High-level diagram, layers, monorepo structure, DB schemas, LLM strategy, conventions (SKILL.md / AGENT.md), inter-agent comms | Touching cross-cutting structure, adding a new service/agent/MCP, or unsure where something lives |
| `core.md` | `core` schema (users, household, people, sessions, conversations), profile-service, identity resolution | Working on identity, profiles, people, households |
| `calendar.md` | calendar domain: `mcp-caldav`, `calendar-agent`, Radicale, ICS import, birthday/gift skills, `calendar.*` schema | Any calendar work |
| `finance.md` | finance domain: `mcp-finance`, `finance-agent`, Money Pro import, `finance.*` schema, skills | Any finance work |
| `tasks.md` | tasks/GTD domain: `mcp-tasks`, `tasks-agent`, `tasks.*` schema (projects + items + contexts), inbox/clarify flow, skills | Any tasks/GTD work |
| `stage4.md` | Stage 4 plan: memory (done) + inter-agent — conversation-state, event-bus, cross-agent chains all built; **memory-driven multi-agent orchestration now in flight (#290): `coordinator-agent` + the read-only `brief` cross-agent query** | Any Stage-4 (dialog state / inter-agent / event-bus / coordinator / multi-domain) work |
| `platform.md` | shared services: orchestrator, gateway-telegram, llm-gateway, memory-service, scheduler, notifier; `memory/audit/bus/media` schemas | Working on a platform/shared service |
| `media.md` | shared media-understanding capability-MCP (`mcp-media-processing`): OCR/STT/vision-caption over media-service bytes, `shared/mcp/`, agent binding | Any media-understanding (OCR/STT/caption) work |
| `research.md` | web research specialist (`researcher-agent`) + shared `mcp-web` capability-MCP (`web_search` + `fetch_url` over SearXNG); cheap-first token economy | Any web-search / research / page-fetch work |
| `market-data.md` | shared `mcp-market-data` capability-MCP (stocks/funds/metals/crypto `quote` over Stooq) + finance `investment-advisor` skill (advisory-only) | Any market-quote / investment-advisory work |
| `stylist.md` | stylist domain: `mcp-wardrobe` domain-MCP (`wardrobe` schema) + `stylist-agent` (wardrobe catalogue + "analyse me" style profile + capsule advice → HTML deliverables; reuses mcp-media-processing + mcp-web). GPU try-on / marketplace / PDF deferred | Any wardrobe / style-profile / outfit-advice work |
| `nutrition.md` | food domain: `mcp-nutrition` domain-MCP (`nutrition` schema) + `nutritionist-agent` (food log + diet profile + nutrition-analysis HTML; MVP) + `chef-agent` (recipes/meal-plans; Phase 2); shared `mcp-food-data` (Open Food Facts) + the `libs/doc-render` lift | Any food / meal-log / diet / recipe / nutrition-analysis work |
| `briefing.md` | briefing domain: proactive morning digest `briefing-agent` (multi-domain read coordinator on the `Coordinator`: weather + calendar + finance + news → one synthesis → HTML board → notifier) + the shared `mcp-weather` capability-MCP (`forecast` over Open-Meteo) | Any briefing / morning-digest / weather work |
| `docs.md` | docs domain: personal document archive `docs-agent` (ingest a receipt/contract/warranty photo → OCR → store + index → "find my X" search) + `mcp-docs` domain-MCP (`docs` schema) — reuses `mcp-media-processing` OCR, media-service blobs, memory-service recall | Any docs / document-archive / receipt-file / "find my X" work |
| `creator.md` | creator / content-factory domain: `mcp-creator` domain-MCP (`creator` schema: per-owner track + trend cache + idea/draft history) + `creator-agent` (multi-source trend → ideas → drafts → format recs on the `Coordinator`); source capability-MCPs `mcp-youtube` / `mcp-reddit` / `mcp-feeds` (+ existing `mcp-web`); `mcp-browser` deferred | Any creator / content / trend-monitoring / post-idea / draft work |
| `second-brain.md` | **Epic** ([#257](https://github.com/fedoroff-vlad/ai-life/issues/257)): the owner's "second memory" — an ai-life-owned notes+vector+graph substrate that evolves `memory-service` (authored `memory.note` tier auto-seeding recall + `[[wiki-links]]` → relations) into the foundation every agent reads/writes. Reframes #189 as a slice | Any second-brain / notes / knowledge-substrate / "запомни …" / "что я думал про …" work |
| `ambient-capture.md` | Post-epic follow-on: fill the note tier **intuitively** (decide what/about-whom to save from ordinary conversation, no "запомни" keyword; explicit-fixation → auto-save, important-inferred → approve, trivial → ignore) by evolving memory-from-chat. Plus the north-star it serves: memory-driven orchestration | Any ambient/implicit capture, "intuitive memory", note-worthiness/dedup, or memory-driven-routing work |
| `roadmap.md` | Stage 0–6 plan, future agents (tasks/health/docs/briefing/...), n8n variant, open-source reuse table, risks | Planning next work, picking what to build, evaluating a reusable component |
| `migration-25-boot4.md` | Platform version migration: Java 21→25 LTS, Spring Boot 3.4→4, Spring AI 1→2 — scheduled AFTER the ambient-capture/second-memory stage; scope, rationale, gating, approach | Doing/planning the JVM/Boot/Spring-AI upgrade |
| `STATUS.md` | **Live only** — current in-flight slice + next steps (shipped work lives in `HISTORY.md`). **Mutable** — update as work progresses | Resuming work, checking what's in flight. Update at end of each PR; move finished bullets to `HISTORY.md` |
| `HISTORY.md` | Shipped-work archive: terse timeline (→ domain plans/READMEs) + verbatim prose moved out of STATUS. **NOT in the reading order** | Only when you need "when/what was done"; follow the link to the domain source for detail |
| `PATTERNS.md` | Short scaffolding recipes (new MCP module, new agent, new migration, new contract) pointing at canonical examples | About to scaffold something new — read this BEFORE reading a sibling module as a template |

After this file you usually read **the single relevant domain file + the target module's `README.md`** (e.g. `mcp/mcp-caldav/README.md`). Open `.java` only when the module README points you there. Each module has a README that lists purpose, port, env, endpoints/tools, and a one-line index of key classes; PRs that change that public contract must update the README in the same change.

## Hard rules (do not violate)
- One PR = one small vertical slice. >~5 files changed → stop and propose a split first.
- Do not restate or re-derive the architecture. Assume these files are correct and act.
- New architectural concept / new layer / new pattern → flag it BEFORE writing code; do not invent silently.
- Decisions already locked are in `architecture.md` §Decisions — do not relitigate them.
