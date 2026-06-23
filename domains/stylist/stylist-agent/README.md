# stylist-agent

Personal style & wardrobe advisor. Catalogues garments from photos, builds a personal style
profile ("analyse me" — colour type/цветотип, body shape, suitable fabrics), and assembles outfit
capsules. The canonical role lives in [AGENT.md](AGENT.md), served at
`GET /agents/stylist/manifest`. Plan: [stylist.md](../../../plans/stylist.md).

A cross-domain specialist (its own `domains/stylist/` folder). It owns the `mcp-wardrobe`
domain-MCP (its persistent data) and binds three shared capabilities: `mcp-media-processing`
(vision `caption` — understand garment / self photos), `mcp-web` (`web_search` — trends), and
`mcp-image-gen` (`generate_image` — editorial board illustrations; a stub engine today, a
self-hosted GPU model later by config — no agent change).

**Status (ST-n / Phase 2 complete):** all MVP flows + the four luxury-editorial boards are live —
wardrobe catalogue (ST-c), "analyse me" (ST-d), capsule (ST-e), **Wardrobe Audit** (ST-h) and **Gap
Analysis** (ST-j) boards, the editorial-poster template (ST-f..k), the `mcp-image-gen` lookbook
illustration (ST-m, stub engine), and env-var theming (ST-n). `IntentController` routes a **photo
attachment** by the caption text:
- analyse-me cues / stated body params → `analyse/AnalyseMe` (ST-d) — `caption` analysis (instruction
  = the `style-analyst` SKILL.md, with the user's note folded in so it also picks up typed
  height/weight/measurements) → `set_style_profile` via `mcp-wardrobe` (`/internal/profile`) → render
  the analysis as a **responsive HTML page** through the shared `libs/doc-render` `DocRenderer` seam → store it in
  media-service → reply with a summary + a link the user opens on any device;
- otherwise → `catalogue/WardrobeCataloguer` (ST-c) — structured garment extract → write the item via
  `mcp-wardrobe` (`/internal/item`), storing the photo's media id (the default, since the owner
  bulk-loads the wardrobe).

A non-photo message with a **capsule cue** ("собери капсулу", "что надеть") → `flow/StylistAdvisor`
(ST-e): gather the wardrobe + style profile + trends (mcp-web) + season on the shared `Coordinator`
→ one LLM synthesis → render a capsule HTML page (embedding the garment photos) → store → link.
Other non-photo messages fall back to chat (`chat/StylistChat`). The capabilities are bound over SSE
for future LLM-driven tool selection; the deterministic flows call them over HTTP `/internal/*`
passthroughs. **Render-format seam** lives in the shared **`libs/doc-render`** (`DocRenderer`): HTML
now, a PDF renderer drops in behind the same interface later. An audit-cue text ("ревизия гардероба")
→ `flow/WardrobeAuditor`; a gap-cue text ("что докупить") → `flow/GapAnalyst`. **Stylist MVP (ST-a..e)
+ Phase 2 editorial boards (ST-f..k) + image-gen binding (ST-m) + env theming (ST-n) complete.**
Deferred: real GPU image-gen engine + virtual try-on, marketplace buy-links, PDF.

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
| `MCP_IMAGE_GEN_URL` | `http://mcp-image-gen:8103` | Shared image-gen capability: SSE binding + `/internal/generate` (board illustrations). |
| `MEDIA_SERVICE_URL` | `http://media-service:8088` | Stores the rendered HTML deliverables (analyse-me / capsule). |
| `STYLIST_PUBLIC_MEDIA_BASE_URL` | `http://media-service:8088` | Public base the deliverable link is built from (`<base>/v1/media/{id}`); set to a reachable gateway in deployment. |
| `STYLIST_THEME_*` | locked beige/Oranienbaum | Re-skin the HTML deliverables without code: `STYLIST_THEME_{PAPER,INK,SOFT,MUTED,LINE,GOLD,KEEP,QUESTION,REMOVE}` (colours), `STYLIST_THEME_{SERIF_FAMILY,SANS_FAMILY}` (CSS font stacks), `STYLIST_THEME_GOOGLE_FONTS_QUERY` (the `css2?` query). Defaults = the locked aesthetic. |
| `STYLIST_AGENT_MCP_CLIENT_ENABLED` | `true` | Toggle the Spring AI MCP client. Tests default to `false`. |
| `STYLIST_AGENT_MEMORY_RECALL_K` | `5` | Memory recall depth for the runtime clients. |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` / `MEMORY_SERVICE_URL` | service defaults | Back the shared `agent-runtime` clients. |

Orchestrator side: `STYLIST_AGENT_URL` (default `http://stylist-agent:8102`) is registered in
[orchestrator/application.yml](../../../platform/orchestrator/src/main/resources/application.yml).

## Key classes

- `StylistAgentApplication` — `@SpringBootApplication` + `@Import(AgentRuntimeConfig)`.
- `config/StylistAgentProperties` — `stylist-agent.{mcp-wardrobe-url, mcp-media-processing-url,
  mcp-web-url, mcp-image-gen-url, profile/notifier/memory urls}`.
- `config/OutboundHttpConfig` — `mcpWardrobe/mcpMediaProcessing/mcpWeb/mcpImageGen` WebClients (for
  the flows) + the `profile/notifier/memory` qualified beans the shared runtime clients pick up.
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
  style profile + trends (mcp-web) + season (computed) → one LLM synthesis → generate a lookbook
  illustration (mcp-image-gen, soft-fail) as the board's featured image → render capsule HTML with a
  garment-photo gallery → store → reply with a link. Empty wardrobe → invite to catalogue.
- `flow/WardrobeAuditor` — the audit flow on the `Coordinator`: gather wardrobe + profile → one LLM
  synthesis → a verdict JSON → render the **audit board** (KEEP/QUESTION/REMOVE grid with garment
  photos matched by name, gold hero row, palette, "Системная ошибка" diagnosis) → store → link.
- `flow/GapAnalyst` — the gap-analysis flow on the `Coordinator`: gather wardrobe + profile → one LLM
  synthesis → a gap JSON → render the **gap board** (what-to-buy with priority/price tier, "Не
  покупать", coverage before/after, palette) → store → link. Marketplace buy-links deferred.
- `config/StylistThemeProperties` — `stylist-agent.theme.*` (palette + font stacks + Google-Fonts
  query); env-overridable (`STYLIST_THEME_*`) so a redeploy re-skins the deliverables without code.
  Defaults = the locked beige/Oranienbaum aesthetic. `toDocTheme()` maps it into the shared lib's
  `DocTheme`.
- `config/RenderConfig` — exposes the shared `DocRenderer` bean (`new HtmlDocRenderer(theme.toDocTheme())`).
- **Rendering lives in the shared `libs/doc-render`** (lifted from here in DR-a on the second consumer
  — nutrition/chef): `DocRenderer` (seam) + `HtmlDocRenderer` (**luxury-editorial** responsive HTML —
  ivory/serif/grid/gold-hero, LOCKED 2026-06-21; palette/fonts from a `DocTheme`, layout constant) +
  `Doc` (board model: keyed sections, palette swatches, verdict grid, hero row, image gallery — fluent
  `builder`) / `RenderedDoc`. HTML now, PDF later behind the same seam. The flows build a `Doc` and
  render it through the injected `DocRenderer`.
- `http/CaptionClient` (`/internal/caption`) + `http/WardrobeClient` (`/internal/item`) +
  `http/StyleProfileClient` (`/internal/profile`) + `http/WardrobeReadClient` (`/internal/items` +
  `/internal/profile`) + `http/WebSearchClient` (`/internal/search`) + `http/ImageGenClient`
  (`/internal/generate`) + `http/MediaStoreClient` (`POST /v1/media`) — the deterministic
  capability/media calls (MockWebServer-testable; not SSE).

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
- `wardrobe-auditor` (synthesis) — a KEEP/QUESTION/REMOVE verdict per catalogued garment + hero
  pieces + a one-sentence systemic-pattern diagnosis + power palette, judged against the profile.
  Lives at [skills/stylist/wardrobe-auditor/SKILL.md](../skills/wardrobe-auditor/SKILL.md).
- `capsule-advisor` (synthesis) — assembles an outfit capsule from the catalogued wardrobe, grounded
  in the style profile, occasion, season and trends; consumes the `Coordinator` `context`. Lives at
  [skills/stylist/capsule-advisor/SKILL.md](../skills/capsule-advisor/SKILL.md).
- `gap-analyst` (synthesis) — finds wardrobe gaps vs the profile & lifestyle: what to buy + the gap it
  fills + priority + price tier, a "do not buy" list, and a coverage before/after. Lives at
  [skills/stylist/gap-analyst/SKILL.md](../skills/gap-analyst/SKILL.md).
