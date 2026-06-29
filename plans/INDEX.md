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
| `stage4.md` | Stage 4 plan: memory (done) + the open inter-agent half — conversation-state, event-bus, cross-agent chains, multi-agent orchestration | Any Stage-4 (dialog state / inter-agent / event-bus) work |
| `platform.md` | shared services: orchestrator, gateway-telegram, llm-gateway, memory-service, scheduler, notifier; `memory/audit/bus/media` schemas | Working on a platform/shared service |
| `media.md` | shared media-understanding capability-MCP (`mcp-media-processing`): OCR/STT/vision-caption over media-service bytes, `shared/mcp/`, agent binding | Any media-understanding (OCR/STT/caption) work |
| `research.md` | web research specialist (`researcher-agent`) + shared `mcp-web` capability-MCP (`web_search` + `fetch_url` over SearXNG); cheap-first token economy | Any web-search / research / page-fetch work |
| `market-data.md` | shared `mcp-market-data` capability-MCP (stocks/funds/metals/crypto `quote` over Stooq) + finance `investment-advisor` skill (advisory-only) | Any market-quote / investment-advisory work |
| `stylist.md` | stylist domain: `mcp-wardrobe` domain-MCP (`wardrobe` schema) + `stylist-agent` (wardrobe catalogue + "analyse me" style profile + capsule advice → HTML deliverables; reuses mcp-media-processing + mcp-web). GPU try-on / marketplace / PDF deferred | Any wardrobe / style-profile / outfit-advice work |
| `nutrition.md` | food domain: `mcp-nutrition` domain-MCP (`nutrition` schema) + `nutritionist-agent` (food log + diet profile + nutrition-analysis HTML; MVP) + `chef-agent` (recipes/meal-plans; Phase 2); shared `mcp-food-data` (Open Food Facts) + the `libs/doc-render` lift | Any food / meal-log / diet / recipe / nutrition-analysis work |
| `briefing.md` | briefing domain: proactive morning digest `briefing-agent` (multi-domain read coordinator on the `Coordinator`: weather + calendar + finance + news → one synthesis → HTML board → notifier) + the shared `mcp-weather` capability-MCP (`forecast` over Open-Meteo) | Any briefing / morning-digest / weather work |
| `creator.md` | creator / content-factory domain: `mcp-creator` domain-MCP (`creator` schema: per-owner track + trend cache + idea/draft history) + `creator-agent` (multi-source trend → ideas → drafts → format recs on the `Coordinator`); source capability-MCPs `mcp-youtube` / `mcp-reddit` / `mcp-feeds` (+ existing `mcp-web`); `mcp-browser` deferred | Any creator / content / trend-monitoring / post-idea / draft work |
| `roadmap.md` | Stage 0–6 plan, future agents (tasks/health/docs/briefing/...), n8n variant, open-source reuse table, risks | Planning next work, picking what to build, evaluating a reusable component |
| `STATUS.md` | Current in-flight branch, what's done, next concrete steps. **Mutable** — update as work progresses | Resuming work, checking what's in flight. Update at end of each PR |
| `PATTERNS.md` | Short scaffolding recipes (new MCP module, new agent, new migration, new contract) pointing at canonical examples | About to scaffold something new — read this BEFORE reading a sibling module as a template |

After this file you usually read **the single relevant domain file + the target module's `README.md`** (e.g. `mcp/mcp-caldav/README.md`). Open `.java` only when the module README points you there. Each module has a README that lists purpose, port, env, endpoints/tools, and a one-line index of key classes; PRs that change that public contract must update the README in the same change.

## Hard rules (do not violate)
- One PR = one small vertical slice. >~5 files changed → stop and propose a split first.
- Do not restate or re-derive the architecture. Assume these files are correct and act.
- New architectural concept / new layer / new pattern → flag it BEFORE writing code; do not invent silently.
- Decisions already locked are in `architecture.md` §Decisions — do not relitigate them.
