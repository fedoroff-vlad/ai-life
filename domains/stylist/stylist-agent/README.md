# stylist-agent

Personal style & wardrobe advisor. Catalogues garments from photos, builds a personal style
profile ("analyse me" — colour type/цветотип, body shape, suitable fabrics), and assembles outfit
capsules. The canonical role lives in [AGENT.md](AGENT.md), served at
`GET /agents/stylist/manifest`. Plan: [stylist.md](../../../plans/stylist.md).

A cross-domain specialist (its own `domains/stylist/` folder). It owns the `mcp-wardrobe`
domain-MCP (its persistent data) and binds two shared capabilities: `mcp-media-processing`
(vision `caption` — understand garment / self photos) and `mcp-web` (`web_search` — trends).

**Status (ST-d):** the wardrobe-catalogue **and** "analyse me" flows are live. `IntentController`
routes a **photo attachment** by the caption text:
- analyse-me cues / stated body params → `analyse/AnalyseMe` (ST-d) — `caption` analysis (instruction
  = the `style-analyst` SKILL.md, with the user's note folded in so it also picks up typed
  height/weight/measurements) → `set_style_profile` via `mcp-wardrobe` (`/internal/profile`) → render
  the analysis as a **responsive HTML page** through the `render/StylistRenderer` seam → store it in
  media-service → reply with a summary + a link the user opens on any device;
- otherwise → `catalogue/WardrobeCataloguer` (ST-c) — structured garment extract → write the item via
  `mcp-wardrobe` (`/internal/item`), storing the photo's media id (the default, since the owner
  bulk-loads the wardrobe).

A non-photo message with a **capsule cue** ("собери капсулу", "что надеть") → `flow/StylistAdvisor`
(ST-e): gather the wardrobe + style profile + trends (mcp-web) + season on the shared `Coordinator`
→ one LLM synthesis → render a capsule HTML page (embedding the garment photos) → store → link.
Other non-photo messages fall back to chat (`chat/StylistChat`). The capabilities are bound over SSE
for future LLM-driven tool selection; the deterministic flows call them over HTTP `/internal/*`
passthroughs. **Render-format seam** (`StylistRenderer`): HTML now, a PDF renderer drops in behind
the same interface later. **Stylist MVP complete (ST-a..e).**

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
| `MEDIA_SERVICE_URL` | `http://media-service:8088` | Stores the rendered HTML deliverables (analyse-me / capsule). |
| `STYLIST_PUBLIC_MEDIA_BASE_URL` | `http://media-service:8088` | Public base the deliverable link is built from (`<base>/v1/media/{id}`); set to a reachable gateway in deployment. |
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
- `web/IntentController` — `POST /agents/stylist/intent`; routes a photo to analyse-me (caption
  cues / body params) or the catalogue flow, a capsule-cue text to the advisor, else the chat fallback.
- `chat/StylistChat` — the chat fallback (one LLM turn, AGENT.md as system prompt) for non-photo
  messages; replaced branch-by-branch as the real flows land.
- `catalogue/WardrobeCataloguer` — the wardrobe-catalogue flow: garment photo → `caption` extract
  (instruction = the `wardrobe-cataloguer` SKILL.md) → write via `mcp-wardrobe` `/internal/item`,
  write-immediately. Soft-fails to a friendly message at any stage.
- `analyse/AnalyseMe` — the "analyse me" flow: self-photo + typed params → `caption` analysis
  (instruction = the `style-analyst` SKILL.md) → `set_style_profile` via `/internal/profile` →
  render the analysis HTML → store in media-service → reply with a link.
- `flow/StylistAdvisor` — the capsule flow on the shared `Coordinator`: gather wardrobe items +
  style profile + trends (mcp-web) + season (computed) → one LLM synthesis → render capsule HTML
  with a garment-photo gallery → store → reply with a link. Empty wardrobe → invite to catalogue.
- `render/StylistRenderer` (seam) + `render/HtmlStylistRenderer` (**luxury-editorial** responsive
  HTML — ivory/serif/grid/gold-hero, LOCKED 2026-06-21) + `render/StylistDoc` (board model: keyed
  sections, palette swatches, KEEP/QUESTION/REMOVE verdict grid, hero row, image gallery — fluent
  `builder`) / `render/RenderedDoc` — the render-format seam (HTML now, PDF later).
- `http/CaptionClient` (`/internal/caption`) + `http/WardrobeClient` (`/internal/item`) +
  `http/StyleProfileClient` (`/internal/profile`) + `http/WardrobeReadClient` (`/internal/items` +
  `/internal/profile`) + `http/WebSearchClient` (`/internal/search`) + `http/MediaStoreClient`
  (`POST /v1/media`) — the deterministic capability/media calls (MockWebServer-testable; not SSE).

## Skills

- `wardrobe-cataloguer` (vision extract) — turns a garment photo into a structured item
  (category/colour/material/pattern/season/formality) as strict JSON; the cataloguer flow parses it
  and writes the item. Lives at
  [skills/stylist/wardrobe-cataloguer/SKILL.md](../skills/wardrobe-cataloguer/SKILL.md).
- `style-analyst` (vision analysis) — analyses a self-photo + typed body params into a **full
  style-analysis board** as strict JSON: Kibbe type, colour season + undertone/contrast + palette,
  archetype, body analysis (type/proportions/bone/posture), silhouette strategies + harmony, fabric
  & texture logic, what-not-to-wear, styling principles, style-codes, final direction. The analyse-me
  flow persists the schema fields and renders the whole board. Lives at
  [skills/stylist/style-analyst/SKILL.md](../skills/style-analyst/SKILL.md).
- `capsule-advisor` (synthesis) — assembles an outfit capsule from the catalogued wardrobe, grounded
  in the style profile, occasion, season and trends; consumes the `Coordinator` `context`. Lives at
  [skills/stylist/capsule-advisor/SKILL.md](../skills/capsule-advisor/SKILL.md).
