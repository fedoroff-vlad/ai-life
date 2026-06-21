# stylist-agent

Personal style & wardrobe advisor. Catalogues garments from photos, builds a personal style
profile ("analyse me" — colour type/цветотип, body shape, suitable fabrics), and assembles outfit
capsules. The canonical role lives in [AGENT.md](AGENT.md), served at
`GET /agents/stylist/manifest`. Plan: [stylist.md](../../../plans/stylist.md).

A cross-domain specialist (its own `domains/stylist/` folder). It owns the `mcp-wardrobe`
domain-MCP (its persistent data) and binds two shared capabilities: `mcp-media-processing`
(vision `caption` — understand garment / self photos) and `mcp-web` (`web_search` — trends).

**Status (ST-c):** the wardrobe-catalogue flow is live. `IntentController` routes a **photo
attachment** → `catalogue/WardrobeCataloguer`: ask `mcp-media-processing` `caption`
(`/internal/caption`) for a structured garment extract (instruction = the `wardrobe-cataloguer`
SKILL.md) → write the item via `mcp-wardrobe` (`/internal/item`), storing the photo's media id on
the item. **Write-immediately** (the owner bulk-loads the wardrobe; edits go through `update_item`
later). Non-photo messages fall back to chat (`chat/StylistChat`). The three capabilities are bound
over SSE for future LLM-driven tool selection; the deterministic flows call them over their HTTP
`/internal/*` passthroughs. The analyse-me / capsule flows land in ST-d..e.

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
- `web/IntentController` — `POST /agents/stylist/intent`; routes a photo attachment to the
  catalogue flow, else the chat fallback.
- `chat/StylistChat` — the chat fallback (one LLM turn, AGENT.md as system prompt) for non-photo
  messages; replaced branch-by-branch as the real flows land.
- `catalogue/WardrobeCataloguer` — the wardrobe-catalogue flow: garment photo → `caption` extract
  (instruction = the `wardrobe-cataloguer` SKILL.md) → write via `mcp-wardrobe` `/internal/item`,
  write-immediately. Soft-fails to a friendly message at any stage.
- `http/CaptionClient` (`POST /internal/caption`) + `http/WardrobeClient` (`POST /internal/item`) —
  the deterministic capability calls (MockWebServer-testable; not the SSE transport).

## Skills

- `wardrobe-cataloguer` (vision extract) — turns a garment photo into a structured item
  (category/colour/material/pattern/season/formality) as strict JSON; the cataloguer flow parses it
  and writes the item. Lives at
  [skills/stylist/wardrobe-cataloguer/SKILL.md](../skills/wardrobe-cataloguer/SKILL.md).
