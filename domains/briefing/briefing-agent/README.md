# briefing-agent

Briefing domain agent (port **8115**). A proactive morning-digest assistant: keeps a person's
briefing preferences (location, interests, sections, schedule) and — on a scheduled wake or request —
gathers weather + today's calendar + a finance snapshot + news into one short digest. The first
multi-domain **read** coordinator (gather → synthesize on the shared `Coordinator`). Owns the
`mcp-briefing` domain-MCP; binds the shared `mcp-weather` (weather + geocoding) and `mcp-web` (news).
Routes via the orchestrator (registered as `briefing`). See [plans/briefing.md](../../../plans/briefing.md).

## Status (through BR-d)

Manifest endpoint + the `chat/BriefingChat` fallback (one LLM turn, AGENT.md as system prompt) + the
**briefing-preferences flow** (BR-c) + the **digest flow** (BR-d):
- **BR-c — briefing profile. DONE.** A typed config message with a preferences cue ("настрой брифинг",
  "показывай мне утром…", "set up my briefing") → one LLM extract via the `briefing-profiler` SKILL →
  if a city was stated, one `mcp-weather` geocode resolves it to coordinates + timezone → upsert the
  per-person prefs via `mcp-briefing`'s `/internal/briefing-profile` (self → the sender, household →
  the default). Geocoding soft-fails (the profile saves without coordinates). `profile/BriefingProfiler`.
- **BR-d — the digest flow. DONE.** A produce-now cue ("собери мне брифинг на сегодня", "brief me") →
  resolve the profile (self → household-default → all-sections default) → **gather** the enabled
  sections in parallel over the `/internal/*` read passthroughs (weather for the geocoded coordinates,
  today's agenda from `mcp-caldav`, yesterday's spend snapshot from `mcp-finance`, news from `mcp-web`
  — one search per interest) → **one** `briefing-composer` LLM synthesis on the shared `Coordinator`.
  Per-source soft-fail; weather needs coordinates and news needs ≥1 interest, else those steps skip.
  Household-scoped agenda/finance for now. `flow/BriefingComposer`. The digest is text-only here — an
  HTML board (BR-e) + scheduled wake/delivery (BR-f) come next.

## Endpoints

- `POST /agents/briefing/intent` (body `NormalizedMessage`) → `IntentResponse` — the orchestrator's
  entry point. Digest cue → briefing-composer; preferences cue → briefing-profiler; else chat.
- `GET /agents/briefing/manifest` → `AgentManifest` — scraped by the orchestrator on startup.

## Env

| Var | Default | Purpose |
|---|---|---|
| `BRIEFING_AGENT_PORT` | `8115` | HTTP port |
| `MCP_BRIEFING_URL` | `http://mcp-briefing:8114` | briefing domain-MCP (its data) |
| `MCP_WEATHER_URL` | `http://mcp-weather:8113` | shared weather + geocoding capability |
| `MCP_WEB_URL` | `http://mcp-web:8098` | shared web/news capability (BR-d news gather) |
| `MCP_CALDAV_URL` | `http://mcp-caldav:8090` | calendar domain-MCP — today's agenda (`/internal/events`, BR-d) |
| `MCP_FINANCE_URL` | `http://mcp-finance:8092` | finance domain-MCP — spend snapshot (`/internal/spending-by-category`, BR-d) |
| `BRIEFING_AGENT_MCP_CLIENT_ENABLED` | `true` | toggle the eager MCP-SSE binding off in dev |
| `BRIEFING_AGENT_MEMORY_RECALL_K` | `5` | memory recall fan-out |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | llm-gateway (chat) |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` / `MEMORY_SERVICE_URL` | — | shared agent-runtime clients |

## Key classes

- `BriefingAgentApplication` — `@Import(AgentRuntimeConfig)` + `@EnableConfigurationProperties`.
- `config/BriefingAgentProperties` — the outbound base URLs (`briefing-agent.*`).
- `config/OutboundHttpConfig` — one `clone()`d `WebClient` per dependency (`mcpBriefing`, `mcpWeather`,
  `mcpWeb`, `mcpCaldav`, `mcpFinance`); the shared `profile/notifier/memory` clients come from `agent-runtime`.
- `web/IntentController` — `POST /agents/briefing/intent`; digest cue → composer, preferences cue →
  profiler, else chat.
- `web/ManifestController` — `GET /agents/briefing/manifest`.
- `chat/BriefingChat` — the chat fallback (one LLM turn, AGENT.md as system prompt).
- `profile/BriefingProfiler` — the preferences flow: cue → LLM extract via `briefing-profiler` SKILL →
  geocode the city → upsert via `/internal/briefing-profile`.
- `flow/BriefingComposer` — the digest flow (BR-d): resolve the profile → gather the enabled sections in
  parallel on the `Coordinator` → one `briefing-composer` synthesis.
- `http/BriefingProfileClient` — `POST/GET /internal/briefing-profile` on `mcp-briefing`.
- `http/GeocodeClient` — `POST /internal/geocode` on `mcp-weather` (city → coords + timezone; soft-fail).
- `http/ForecastClient` — `POST /internal/forecast` on `mcp-weather` (today's weather for the profile coords).
- `http/CalendarEventsClient` — `GET /internal/events` on `mcp-caldav` (today's agenda for the window).
- `http/FinanceSnapshotClient` — `GET /internal/spending-by-category` on `mcp-finance` (yesterday's spend).
- `http/NewsSearchClient` — `POST /internal/search` on `mcp-web` (headlines per interest).

## Skills

- `briefing-profiler` (`domains/briefing/skills/briefing-profiler/SKILL.md`) — NL config → strict-JSON
  preferences (location/interests/sections/schedule + self|household scope).
- `briefing-composer` (`domains/briefing/skills/briefing-composer/SKILL.md`) — pre-gathered corpus
  (weather/agenda/finance/news) → one short, grounded morning briefing (only reports what was gathered;
  never fabricates a headline or link).
