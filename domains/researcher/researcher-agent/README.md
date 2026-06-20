# researcher-agent

Web research specialist. Finds information online, reads the best sources, and returns a concise
summary with links — **cheap-first** (HTTP search + page-fetch, then a single LLM synthesis, to
save tokens). The canonical role lives in [AGENT.md](AGENT.md), served at
`GET /agents/researcher/manifest`. Plan: [research.md](../../../plans/research.md).

A cross-domain specialist (its own `domains/researcher/` folder), not tied to one domain — it
binds the shared `mcp-web` capability (`web_search` + `fetch_url`). The same capability is reused
by chef / briefing / finance-investment later.

**Status (R-d):** the cheap-first research flow is live. `IntentController` runs `flow/Researcher`
on the shared `Coordinator`: **search** the web (`mcp-web` `/internal/search`) → **read** the top
hits in full (`/internal/fetch`, parallel, soft-fail per page) → **one** LLM synthesis over the
gathered corpus → a summary with grouped article/video links. Search + fetch are plain HTTP (no
model cost); only the synthesis hits the LLM — token economy is structural.

## Port: `8099` (`RESEARCHER_AGENT_PORT`)

## Endpoints

| method | path | purpose |
|--------|------|---------|
| GET | `/agents/researcher/manifest` | parsed AGENT.md (orchestrator scrapes it on startup) |
| POST | `/agents/researcher/intent` | hit by the orchestrator on a research intent |
| GET | `/actuator/health` | liveness |

## Env

| Var | Default | Purpose |
|---|---|---|
| `RESEARCHER_AGENT_PORT` | `8099` | HTTP port. |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | Via `libs/llm-client` (the synthesis). |
| `MCP_WEB_URL` | `http://mcp-web:8098` | The shared web capability: SSE binding + the HTTP `/internal/search` + `/internal/fetch` base URL the flow calls. |
| `RESEARCHER_AGENT_SEARCH_LIMIT` | `6` | Hits requested from `web_search`. |
| `RESEARCHER_AGENT_FETCH_TOP_N` | `3` | Top hits fetched in full before synthesis (cheap-first depth). |
| `RESEARCHER_AGENT_MCP_CLIENT_ENABLED` | `true` | Toggle the Spring AI MCP client. Tests default to `false`. |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` / `MEMORY_SERVICE_URL` | service defaults | Back the shared `agent-runtime` clients (unused by the MVP flow, but the runtime beans need them). |

Orchestrator side: `RESEARCHER_AGENT_URL` (default `http://researcher-agent:8099`) is registered
in [orchestrator/application.yml](../../../platform/orchestrator/src/main/resources/application.yml).

## Key classes

- `ResearcherAgentApplication` — `@SpringBootApplication` + `@Import(AgentRuntimeConfig)`.
- `config/ResearcherAgentProperties` — `researcher-agent.{mcp-web-url, profile/notifier/memory urls}`.
- `config/OutboundHttpConfig` — `mcpWebWebClient` + the `profile/notifier/memory` qualified beans
  the shared runtime clients pick up.
- `web/ManifestController` — `GET /agents/researcher/manifest`.
- `web/IntentController` — `POST /agents/researcher/intent`; runs the `Researcher` flow (a specialist
  treats every routed message as a research request).
- `flow/Researcher` — the cheap-first flow on the shared `Coordinator`: search → fetch top N
  (parallel, soft-fail) → fold the corpus into `context` → one LLM synthesis → summary + links.
- `http/WebSearchClient` (`POST /internal/search`) + `http/PageFetchClient` (`POST /internal/fetch`)
  — the deterministic mcp-web calls (MockWebServer-testable; not the SSE transport).

## Skills

- `research` (intent-invoked) — synthesizes a concise answer with cited links from the pre-gathered
  corpus (search hits + fetched page text); groups video links separately; never invents
  facts/links. Lives at [skills/researcher/research/SKILL.md](../skills/research/SKILL.md).
