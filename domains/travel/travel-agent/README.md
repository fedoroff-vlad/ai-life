# travel-agent

On-demand **vacation planner** (port **8124**). Designs a trip from a stated wish and keeps the
per-person travel preferences. A **cold** agent (started on demand, not always-on). Owns the
`mcp-travel` domain-MCP; binds the shared `mcp-weather` (geocoding + climate) and `mcp-web` (destination
research) capabilities. Routes via the orchestrator as `travel`. **Never books or pays** — proposes
options and provider links only ([ADR-0003](../../../plans/adr/ADR-0003-travel-data-source.md)). Plan:
[plans/travel.md](../../../plans/travel.md).

## Status (TR-c)

The scaffold + the **travel-profiler** write path: a preferences cue → one llm-gateway extract via the
`travel-profiler` SKILL → geocode the stated home-base city via `mcp-weather /internal/geocode`
(soft-fail) → upsert via `mcp-travel /internal/travel-profile`. Vocabulary enforcement lives here (the
write path): `restTypes` is filtered to `beach|active|family|couple|city|ski|wellness` and `companions`
to `solo|couple|family` before the upsert, so an out-of-vocabulary value never reaches the store.
Non-config messages (incl. plan-a-trip requests) fall through to the conversational chat fallback until
the **TR-d** trip-planner flow is wired. Mirrors `briefing-agent` (BR-c2).

## HTTP surface

| method | path | body | purpose |
|--------|------|------|---------|
| POST | `/agents/travel/intent` | `NormalizedMessage` | reactive entrypoint: preferences cue → `travel-profiler`; else → chat fallback. |
| GET | `/agents/travel/manifest` | — | the `AgentManifest` (AGENT.md frontmatter) the orchestrator scrapes for routing. |

## Env

| Var | Default | Purpose |
|---|---|---|
| `TRAVEL_AGENT_PORT` | `8124` | HTTP port. |
| `MCP_TRAVEL_URL` | `http://mcp-travel:8123` | travel domain-MCP (`/internal/travel-profile`). |
| `MCP_WEATHER_URL` | `http://mcp-weather:8113` | shared weather capability (`/internal/geocode`; TR-d `/internal/climate`). |
| `MCP_WEB_URL` | `http://mcp-web:8098` | shared web capability (bound for the TR-d research gather). |
| `TRAVEL_AGENT_MCP_CLIENT_ENABLED` | `true` | toggle the Spring AI MCP-SSE client (off in dev/degraded envs). |
| `TRAVEL_AGENT_MEMORY_RECALL_K` | `5` | memory-recall depth for the shared agent-runtime clients. |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` / `MEMORY_SERVICE_URL` | internal | the shared agent-runtime platform clients. |

## Key classes

- `TravelAgentApplication` — `@SpringBootApplication` + `@Import(AgentRuntimeConfig)`.
- `config/TravelAgentProperties` (`travel-agent.*`, implements `SharedClientProperties`) +
  `config/OutboundHttpConfig` (`mcpTravel`/`mcpWeather`/`mcpWeb` WebClient beans).
- `profile/TravelProfiler` — the LLM extract → geocode → upsert flow; **vocabulary filtering** for
  `restTypes`/`companions`.
- `http/TravelProfileClient` (upsert/resolve `mcp-travel`) + `http/GeocodeClient` (`mcp-weather`).
- `chat/TravelChat` — conversational fallback for non-config messages.
- `web/IntentController` (`/agents/travel/intent`, cue split) + `web/ManifestController`
  (`/agents/travel/manifest`).
- `AGENT.md` (manifest) + `../skills/travel-profiler/SKILL.md` (the extract prompt).
