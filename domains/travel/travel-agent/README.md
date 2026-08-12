# travel-agent

On-demand **vacation planner** (port **8124**). Designs a trip from a stated wish and keeps the
per-person travel preferences. A **cold** agent (started on demand, not always-on). Owns the
`mcp-travel` domain-MCP; binds the shared `mcp-weather` (geocoding + climate), `mcp-web` (destination
research) and `mcp-chart-render` (the board's climate chart) capabilities. Routes via the orchestrator as
`travel`. **Never books or pays** — proposes options and provider links only
([ADR-0003](../../../plans/adr/ADR-0003-travel-data-source.md)). Plan:
[plans/travel.md](../../../plans/travel.md).

## Status (TR-e — travel MVP closed)

Two flows behind the intent cue-split:
- **travel-profiler** (TR-c) write path: a preferences cue → one llm-gateway extract via the
  `travel-profiler` SKILL → geocode the stated home-base city via `mcp-weather /internal/geocode`
  (soft-fail) → upsert via `mcp-travel /internal/travel-profile`. Vocabulary enforcement lives here (the
  write path): `restTypes` is filtered to `beach|active|family|couple|city|ski|wellness` and `companions`
  to `solo|couple|family` before the upsert, so an out-of-vocabulary value never reaches the store.
- **trip-planner** (TR-d) `gather → synthesize` flow: a plan-a-trip cue → resolve the profile (self →
  household-default → empty) → one **FAST** scope extract (named destination + month) → geocode the
  destination → **gather in parallel** on the shared `Coordinator`: the household **budget** and **free
  dates** as read-only cross-agent `brief` answers from the `finance`/`calendar` agents (through the
  orchestrator hub), the destination's **season** from `mcp-weather /internal/climate`, and qualitative
  **research** from `mcp-web /internal/search` → one `trip-composer` **DEFAULT** synthesis → a concise
  plan (route + season verdict + budget check) grounded in the web links. Per-source soft-fail; a missing
  finance brief falls back to the profile's `budgetHint` (unverified); no named destination skips climate.
  **Never books or pays** ([ADR-0003](../../../plans/adr/ADR-0003-travel-data-source.md)) — options +
  links only.
  - **TR-e HTML travel board (closer):** the synthesis is rendered to an HTML board — the plan text as a
    section, the gathered web sources as grounded provenance links, and the destination's **climate-by-month
    curve** as a line chart (rendered by the shared `mcp-chart-render` capability) — via the shared
    `DeliverablePublisher` (render → store in media-service → link); the open-link is appended to the reply.
    Chart and board are soft-failed independently: a render/store hiccup ships the text-only plan. The
    full 12-month climate curve is fetched for the board and also grounds the season verdict. Same board
    seam as briefing/finance.

Non-plan, non-config messages fall through to the conversational chat fallback. Mirrors `briefing-agent`
(BR-c2 profiler + BR-d `BriefingComposer`) and the finance report board (`MonthlyReporter`).

## HTTP surface

| method | path | body | purpose |
|--------|------|------|---------|
| POST | `/agents/travel/intent` | `NormalizedMessage` | reactive entrypoint: preferences cue → `travel-profiler`; plan-a-trip cue → `trip-composer`; else → chat fallback. |
| GET | `/agents/travel/manifest` | — | the `AgentManifest` (AGENT.md frontmatter) the orchestrator scrapes for routing. |

## Env

| Var | Default | Purpose |
|---|---|---|
| `TRAVEL_AGENT_PORT` | `8124` | HTTP port. |
| `MCP_TRAVEL_URL` | `http://mcp-travel:8123` | travel domain-MCP (`/internal/travel-profile`). |
| `MCP_WEATHER_URL` | `http://mcp-weather:8113` | shared weather capability (`/internal/geocode`; TR-d `/internal/climate`). |
| `MCP_WEB_URL` | `http://mcp-web:8098` | shared web capability (`/internal/search` for the TR-d research gather). |
| `MEDIA_SERVICE_URL` | `http://media-service:8088` | blob store for the TR-e HTML board (`POST /v1/media`). |
| `TRAVEL_PUBLIC_MEDIA_BASE_URL` | `http://media-service:8088` | externally-reachable base for the board open-link. |
| `MCP_CHART_RENDER_URL` | `http://mcp-chart-render:8120` | shared chart-render capability (`/internal/render`) for the TR-e climate chart. |
| `ORCHESTRATOR_URL` | `http://orchestrator:8083` | the hub the TR-d planner reaches to invoke the `finance`/`calendar` `brief` action. |
| `TRAVEL_AGENT_MCP_CLIENT_ENABLED` | `true` | toggle the Spring AI MCP-SSE client (off in dev/degraded envs). |
| `TRAVEL_AGENT_MEMORY_RECALL_K` | `5` | memory-recall depth for the shared agent-runtime clients. |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` / `MEMORY_SERVICE_URL` | internal | the shared agent-runtime platform clients. |

## Key classes

- `TravelAgentApplication` — `@SpringBootApplication` + `@Import(AgentRuntimeConfig)`.
- `config/TravelAgentProperties` (`travel-agent.*`, implements `SharedClientProperties`) +
  `config/OutboundHttpConfig` (`mcpTravel`/`mcpWeather`/`mcpWeb`/`orchestrator`/`mediaService`/
  `mcpChartRender` WebClient beans + the `OrchestratorInvokeClient`, `MediaStoreClient` and the TR-e
  `DeliverablePublisher`).
- `profile/TravelProfiler` — the LLM extract → geocode → upsert flow; **vocabulary filtering** for
  `restTypes`/`companions`.
- `flow/TripComposer` — the TR-d/TR-e `gather → synthesize` planner: profile resolve → FAST scope extract
  → parallel gather (finance/calendar `brief`, 12-month climate, web search) → one `trip-composer`
  synthesis → the TR-e HTML board (climate-by-month chart + plan + provenance links) via
  `DeliverablePublisher`, open-link appended to the reply (soft-failed to text-only).
- `http/TravelProfileClient` (upsert/resolve `mcp-travel`) + `http/GeocodeClient` (`mcp-weather` geocode)
  + `http/ClimateClient` (`mcp-weather` climate) + `http/WebSearchClient` (`mcp-web` search) +
  `http/ChartRenderClient` (`mcp-chart-render` `/internal/render` for the board's climate chart).
- `chat/TravelChat` — conversational fallback for non-config, non-plan messages.
- `web/IntentController` (`/agents/travel/intent`, cue split) + `web/ManifestController`
  (`/agents/travel/manifest`).
- `AGENT.md` (manifest) + `../skills/travel-profiler/SKILL.md` (the extract prompt) +
  `../skills/trip-composer/SKILL.md` (the synthesis prompt).
