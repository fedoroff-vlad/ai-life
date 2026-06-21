# stylist-agent

Personal style & wardrobe advisor. Catalogues garments from photos, builds a personal style
profile ("analyse me" — colour type/цветотип, body shape, suitable fabrics), and assembles outfit
capsules. The canonical role lives in [AGENT.md](AGENT.md), served at
`GET /agents/stylist/manifest`. Plan: [stylist.md](../../../plans/stylist.md).

A cross-domain specialist (its own `domains/stylist/` folder). It owns the `mcp-wardrobe`
domain-MCP (its persistent data) and binds two shared capabilities: `mcp-media-processing`
(vision `caption` — understand garment / self photos) and `mcp-web` (`web_search` — trends).

**Status (ST-b):** scaffold only. `IntentController` ships a **chat fallback**
(`chat/StylistChat` — one LLM turn with AGENT.md as the system prompt). The three capabilities are
bound over SSE (so a future LLM-driven flow can pick their tools); the deterministic catalogue /
analyse / capsule flows land in ST-c..e and call the capabilities over their HTTP `/internal/*`
passthroughs.

## Port: `8102` (`STYLIST_AGENT_PORT`)

## Endpoints

| method | path | purpose |
|--------|------|---------|
| GET | `/agents/stylist/manifest` | parsed AGENT.md (orchestrator scrapes it on startup) |
| POST | `/agents/stylist/intent` | hit by the orchestrator on a stylist intent (chat fallback for now) |
| GET | `/actuator/health` | liveness |

## Env

| Var | Default | Purpose |
|---|---|---|
| `STYLIST_AGENT_PORT` | `8102` | HTTP port. |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | Via `libs/llm-client` (the chat fallback + future synthesis). |
| `MCP_WARDROBE_URL` | `http://mcp-wardrobe:8101` | Its data: SSE binding + the future HTTP base URL for the catalogue flow. |
| `MCP_MEDIA_PROCESSING_URL` | `http://mcp-media-processing:8097` | Shared vision capability: SSE binding + `/internal/caption`. |
| `MCP_WEB_URL` | `http://mcp-web:8098` | Shared web capability: SSE binding + `/internal/search` (trends). |
| `STYLIST_AGENT_MCP_CLIENT_ENABLED` | `true` | Toggle the Spring AI MCP client. Tests default to `false`. |
| `STYLIST_AGENT_MEMORY_RECALL_K` | `5` | Memory recall depth for the runtime clients. |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` / `MEMORY_SERVICE_URL` | service defaults | Back the shared `agent-runtime` clients. |

Orchestrator side: `STYLIST_AGENT_URL` (default `http://stylist-agent:8102`) is registered in
[orchestrator/application.yml](../../../platform/orchestrator/src/main/resources/application.yml).

## Key classes

- `StylistAgentApplication` — `@SpringBootApplication` + `@Import(AgentRuntimeConfig)`.
- `config/StylistAgentProperties` — `stylist-agent.{mcp-wardrobe-url, mcp-media-processing-url,
  mcp-web-url, profile/notifier/memory urls}`.
- `config/OutboundHttpConfig` — `mcpWardrobe/mcpMediaProcessing/mcpWeb` WebClients (for the
  ST-c..e flows) + the `profile/notifier/memory` qualified beans the shared runtime clients pick up.
- `web/ManifestController` — `GET /agents/stylist/manifest`.
- `web/IntentController` — `POST /agents/stylist/intent`; delegates to the chat fallback.
- `chat/StylistChat` — the scaffold chat fallback (one LLM turn, AGENT.md as system prompt);
  replaced branch-by-branch as the real flows land.

## Skills

None yet — the first skill (`wardrobe-cataloguer`) ships in ST-c at
`domains/stylist/skills/<name>/SKILL.md`.
