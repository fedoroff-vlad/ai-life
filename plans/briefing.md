# briefing — proactive morning digest agent

Authority file for the **briefing-agent** (issue [#186](https://github.com/fedoroff-vlad/ai-life/issues/186))
and its one new capability, **`mcp-weather`**. Owner-chosen as the first future-agent
(2026-06-29, build order in the `project-future-agents-order` memory: briefing → docs → health →
family-memory).

## What it is
A **proactive morning digest** delivered to Telegram on a scheduler wake: weather + today's
calendar + a finance snapshot + news, synthesized into one short briefing and an HTML board. The
first real **multi-domain _read_ coordinator** — it gathers from several existing domains/capabilities
on the shared `Coordinator` (gather → one LLM synthesis), then renders + delivers.

## Doctrine (no new layer)
Everything here reuses an existing pattern — flag nothing new:
- **Gather → synthesize** on `libs/agent-runtime` `Coordinator` (same shape as creator/researcher),
  fanned out to weather + calendar + finance + news sources. Per-source soft-fail is built in.
- **Reads** come over the existing deterministic `/internal/*` passthroughs (calendar events,
  finance snapshot) and capability-MCPs (`mcp-weather`, `mcp-web` news). No agent invents a planner;
  the briefing-agent owns its own flow (agent-led coordination).
- **Proactive trigger** = `scheduler-service` wake → orchestrator `POST /v1/agents/wake` → the
  briefing-agent trigger (same proactive path the calendar birthday wake uses).
- **Deliverable** = `libs/doc-render` HTML board → media-service blob → link; **delivery** =
  `notifier-service`. Both already used by creator/stylist/nutrition.
- Weather is the only missing external surface → a **capability-MCP** (`mcp-weather`), schema-less,
  bound by the agent over MCP/SSE — exactly the `mcp-market-data` / `mcp-web` shape.

## Personalization — first-class, not deferred (owner steer 2026-06-29)
The briefing is **per-person** and configurable in natural language, on the **same pattern** as the
diet / style / creator tracks — no new layer. A `briefing_profile` keyed `(household, owner)` holds:
- **location** `{label, latitude, longitude, timezone}` — the user states a city in chat; the
  `briefing-profiler` skill extracts it and we **geocode** it to lat/lon via Open-Meteo's free
  Geocoding API (a `geocode` tool added to `mcp-weather`). No device GPS — it's a stated preference.
- **interests** `string[]` — news topics (e.g. `["AI", "finance"]`) → `mcp-web.web_search` per topic.
- **sections** — toggles for `weather | agenda | finance | news` (**all four in the MVP**, owner 2026-06-29).
- **schedule** `{time, timezone, enabled}` — when the proactive wake fires (BR-f).

The config UX is a skill: the user writes "по утрам в 8:00 показывай погоду в Москве, новости про ИИ
и финансы, мою повестку и траты за вчера" → `briefing-profiler` → strict-JSON extract → upsert the
profile via `mcp-briefing`. Mirrors `creator-profiler`. The digest synthesis is a second skill,
`briefing-composer`.

## Golden tests — from the start (real LLM now available)
Real model access is unblocked (local Ollama `qwen2.5:7b` via llm-gateway). Each LLM seam gets an
**opt-in `@GoldenLlmTest`** (`libs/golden-test-support`, gated by `GOLDEN_LLM` — NOT in the default
fast CI, which stays on the mock LLM): `GoldenBriefingProfileTest` (profiler JSON structure),
`GoldenBriefingComposerTest` (digest structure), and a `briefing` routing golden in orchestrator.
Assert **structure, not wording** (roadmap §Risks).

## PR slices
- **BR-a — `mcp-weather` capability-MCP.** ✅ **DONE (PR #235).** `shared/mcp/mcp-weather` (port 8113);
  `forecast(latitude, longitude)` → today's `Weather` over **Open-Meteo** (free, no key) behind a
  swappable `WeatherSource`; `/internal/forecast` passthrough. No DB, no backing container.
- **BR-b — `mcp-briefing` domain-MCP + `briefing` schema.** ✅ **DONE.** `domains/briefing/mcp-briefing`
  (port **8114**). `briefing.briefing_profile` (location/interests/sections/schedule, keyed
  `(household, owner)`) + `setBriefingProfile` / `getBriefingProfile` / `listScheduledProfiles` tools +
  `/internal/briefing-profile` upsert/resolve (+ `/scheduled`). Liquibase `070-briefing.yml`. Mirrors
  `mcp-creator`. The personalization store. (`briefing-agent` → port **8115** in BR-c.)
- **BR-c1 — `geocode` tool in `mcp-weather`.** ✅ **DONE.** `geocode(name, language)` → `GeoLocation`
  {name/country/lat/lon/timezone} over Open-Meteo Geocoding (free, no key) + `/internal/geocode`
  passthrough. The city→coordinates step the profiler needs.
- **BR-c2 — `briefing-agent` scaffold + `briefing-profiler` skill.** New `domains/briefing/briefing-agent`
  (port 8115). Binds `mcp-briefing` + `mcp-weather`. A profile cue → `briefing-profiler` extract (incl.
  **city → geocode**) → upsert via `/internal/briefing-profile`. Registered in orchestrator as
  `briefing`. + `GoldenBriefingProfileTest`.
- **BR-d — `digest` flow.** Resolve the profile → gather the enabled sections (weather via profile
  lat/lon, calendar-today via `mcp-caldav /internal`, finance snapshot via `mcp-finance /internal`,
  news via `mcp-web.web_search` per interest) in parallel on the `Coordinator` → one `briefing-composer`
  synthesis → reply text. + `GoldenBriefingComposerTest`.
- **BR-e — HTML digest board.** Map the synthesis → a `doc-render` `Doc` (weather / agenda / finance /
  news sections + provenance links) → store in media-service → reply with the link.
- **BR-f — scheduler wake + delivery + E2E closer.** The profile `schedule` drives a per-person
  morning wake → orchestrator → briefing trigger → `notifier-service`. `E2EBriefingWakeFlowTest`
  (scheduler → orchestrator → briefing → notifier), asserting the contracts survive each hop.

## Deferred
- **`chart-render` capability** (data → PNG/SVG) — the finance snapshot sparkline / week-ahead bar.
  First shared with finance year-analysis; out of the MVP critical path.
- **Per-person _content_ filtering** (own vs shared agenda) — tracks the same calendar visibility gap
  as feeds; household-level for now.
