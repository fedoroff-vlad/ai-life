# stylist domain — wardrobe + personal style advisor

Authority file for the **`stylist-agent`** and the **`mcp-wardrobe`** domain-MCP it owns. Owner-chosen
after the market-data line (2026-06-21). The owner's full vision is recorded below so nothing is lost;
**build now = the MVP**, everything heavier (GPU visualisation, marketplaces) is deferred to recorded slices.

## Owner's full vision (verbatim intent, 2026-06-21 — recorded, not all MVP)
1. Upload the **whole wardrobe** as photos; photograph **myself**.
2. Stylist **analyses me** and assembles a **beautiful capsule**; returns it on a **template** (owner will
   send example templates later — designs TBD).
3. Recognise my **body shape**, **colour type (цветотип)** and the **fabric types/textures** that suit me;
   produce an analysis of me, taking my **body measurements**.
4. Know **trends + basic outfit rules** (look at Pinterest / Instagram).
5. **Model me on a trend** — collect height / weight / measurements and **visualise me** ("show how I'd look").
6. (Scaling) Services that search the internet + **marketplaces** for clothing: I see a man in a coat on the
   street, snap a couple of photos, upload → stylist analyses it against my data, decides if it suits me,
   returns **an image of me in that coat**, gives **links to similar coats** (Ozon / Yandex Market / Lamoda /
   store sites), explains *why it suits / doesn't*, what to wear it with, which season, and on request
   assembles a capsule around it from what I already own.

## MVP — what the owner wants to see now (ordered, owner 2026-06-21)
The cheapest vertical: vision **analysis** (cheap `caption` calls) + structured storage + **HTML deliverables**.
No GPU. Reuses two existing capabilities (`mcp-media-processing`, `mcp-web`); the only new code is the
`mcp-wardrobe` domain-MCP + `stylist-agent`.

1. **Inputs the owner uploads** — photos of every garment (the wardrobe), photo(s) of himself, and body
   **measurements** (height / weight / params, typed).
2. **"Analyse me"** — from the self-photo(s) + params, determine: **person/style type**, **colour type
   (цветотип)**, the **fabric types & textures** that suit, and **body shape**. This is vision *analysis* (a
   structured `caption` instruction, like `receipt-parser` — NOT generation). Stored as a `style_profile`.
3. **Beautiful capsule** — assemble a capsule from the catalogued wardrobe (`Coordinator` gather over
   `{wardrobe items, style_profile, occasion, trends via mcp-web, season}` → LLM synthesis) and render it as a
   **beautifully-formatted HTML page** (embeds the garment photos) on a template.
4. **Fabric / colour / texture analysis** — a second **HTML** deliverable: a template the agent fills with the
   owner's colour/fabric/texture analysis **plus concrete examples**, grounded in the `style_profile`.

Both deliverables (3, 4) are returned as an **HTML page** (link/file). Templates start as a sensible default
and are refined once the owner sends example layouts.

## Decision — output format: **HTML page now, PDF-conversion seam for later** (LOCKED, owner 2026-06-21)
The capsule + analysis are rendered as a **templated HTML page** (CSS for "beautiful", garment photos embedded
from media-service), delivered to Telegram as a link/file. Chosen because it's the cheapest path to a
polished, photo-rich layout with **no new infra** — and it converts cleanly to PDF later. The code keeps an
explicit **render-format seam** (a `render(model) → bytes/url` step behind an interface) so a **PDF conversion
integration (HTML→PDF)** drops in without touching the flows — recorded as a future add. Notion (external
API/token) and a rendered-image card are deferred. **Where rendering lives:** inline in `stylist-agent` for the
MVP (a templated HTML builder); lift to a shared **`doc-render` capability-MCP** (HTML/PDF from a model, reused
by briefing) once a second consumer needs it — the repo's "lift on the second copy" rule.

## Deferred (recorded vision — each maps to an architectural home, no MVP work)
| Vision item | Architectural home | Why deferred |
|---|---|---|
| **PDF output** | the `render` seam above → an HTML→PDF library (or the shared `doc-render` capability) | MVP ships HTML; PDF is a drop-in once the template looks right. |
| **Visualise me / virtual try-on** ("show how I'd look", "me in that coat") | a shared **GPU image-gen / try-on capability-MCP** (CatVTON / Fooocus — roadmap 🟢, engine to be LOCKED with the owner) | heavy infra: GPU/CUDA (Apple-Silicon MPS, non-turnkey), non-commercial licence. Cheap text+HTML advice ships first. |
| **Marketplace search + buy links** (Ozon / Yandex Market / Lamoda / store sites) | a sibling **marketplace-search capability-MCP** (or an `mcp-web` extension) — source/engine to be LOCKED | each marketplace is its own integration; needs a source decision like Stooq/SearXNG were. |
| **"Saw it on the street" composite** (photo of an external garment → suits-me? → me-in-it + similar-item links + capsule) | the composite flow stitching `caption` (analyse the external garment) + `style_profile` + GPU try-on + marketplace links + a wardrobe capsule | lands once the GPU + marketplace capabilities exist; pure orchestration of the pieces. |
| **Final template designs** (capsule / analysis layouts) | the stylist render templates once the owner sends examples | owner will provide example layouts; MVP ships sensible defaults. |

Doctrine reminder (architecture.md): the orchestrator only **routes**; all stylist reasoning lives in
**stylist-agent**; cross-domain mechanics (vision understanding, web trends, later image-gen, marketplace,
doc rendering) are **capability-MCPs** the agent binds. Persistent wardrobe data = the **`mcp-wardrobe`
domain-MCP** (owns the `wardrobe` schema); understanding photos = the shared **`mcp-media-processing`**
capability (reused, not rebuilt — a photo can belong to any domain).

## Shape
- `domains/stylist/mcp-wardrobe` — **domain-MCP**, **port 8101**, owns the new **`wardrobe`** schema
  (Liquibase feature `040-wardrobe.yml`; next free number after tasks' `030`). CRUD over garments + the
  owner's style profile. Template: `domains/tasks/mcp-tasks`.
- `domains/stylist/stylist-agent` — **domain agent**, **port 8102**. Binds `mcp-wardrobe` (its data) and the
  shared capabilities `mcp-media-processing` (`caption`, vision analysis) + `mcp-web` (`web_search`, trends)
  over SSE + the deterministic `/internal/*` HTTP passthroughs. Renders HTML deliverables inline (render
  seam). Registered in the orchestrator (manifest-driven, no orchestrator code). Template:
  `domains/finance/finance-agent`.
- Contracts in `libs/contracts/.../wardrobe/`: `WardrobeItem` (+ `AddItemInput`), `StyleProfile`
  (+ `SetStyleProfileInput`), and the caption-extract shapes the catalogue / "analyse me" flows parse.

## Schema `wardrobe` (040-wardrobe.yml)
- `wardrobe_item` — id, household_id, owner_id, name, category (top|bottom|outerwear|shoes|accessory|…),
  colour, material, pattern, season, formality, image_media_id (the media-service object), metadata jsonb,
  created_at. One row per garment.
- `style_profile` — id, household_id, owner_id (one per person), person_type, body_shape, colour_type
  (цветотип), suitable_fabrics jsonb, height_cm, weight_kg, measurements jsonb, notes, image_media_id (the
  analysed self-photo), updated_at.

## PR-sized slices (MVP)
- **ST-0 — docs opener (this).** `stylist.md` + INDEX row + roadmap (mark in-progress, MVP boundary, HTML
  locked) + STATUS. No code.
- **ST-a — `mcp-wardrobe` domain-MCP + schema. DONE (PR132).** `040-wardrobe.yml` (the two tables) + CRUD tools
  (`add_item`, `list_items`, `update_item`, `delete_item`, `set_style_profile`, `get_style_profile`) + the
  `wardrobe/*` contracts. Testcontainers repo test (7 cases). Scaffold per PATTERNS.md "new MCP module" + "new
  migration"; template `mcp-tasks`.
- **ST-b — `stylist-agent` scaffold + orchestrator registration. DONE (PR133).** Manifest + `@Import(AgentRuntimeConfig)`;
  binds `mcp-wardrobe` + `mcp-media-processing` + `mcp-web` over SSE (the deterministic `/internal/*` HTTP clients
  land with each flow); registered `{name: stylist}` in orchestrator `application.yml` + `STYLIST_AGENT_URL`.
  Chat-fallback intent (`chat/StylistChat`) until the flows land. Scaffold per PATTERNS.md "new agent"; template
  `researcher-agent` (leaner than finance-agent).
- **ST-c — wardrobe catalogue flow.** A garment-photo intent → `mcp-media-processing` `caption` (structured
  garment extract via a `wardrobe-cataloguer` SKILL.md instruction — mirror `receipt-parser`) → write the
  item via `mcp-wardrobe`. Write-immediately for bulk upload (owner loads the whole wardrobe at once); edit
  later. **Split c1/c2 (mirror MP-c): ST-c1 DONE (PR134)** — `mcp-wardrobe` `POST /internal/item`
  add-passthrough → `add_item`. **ST-c2** — stylist-agent flow (caption extract → `/internal/item`).
- **ST-d — "analyse me" flow + analysis HTML.** Self-photo(s) + typed params → `caption` analysis
  (person/style type, colour type, suitable fabrics/textures, body shape) → `set_style_profile`; render the
  **fabric/colour/texture analysis** as an **HTML** deliverable (default template) with examples grounded in
  the profile.
- **ST-e — capsule advisor + capsule HTML.** `flow/StylistAdvisor` on the shared `Coordinator` (copy
  `InvestmentAdvisor`/`FinancialAdvisor`): gather `{wardrobe items, style_profile, trends (mcp-web), season}`
  → one LLM synthesis from `[AGENT.md, capsule-advisor SKILL.md] + {payload(occasion/request), context}` →
  a capsule, rendered as a **beautiful HTML page** (embeds garment photos) on the template. Routed via the
  stylist intent classifier.

## Out of scope (here)
- GPU image-gen / virtual try-on, marketplace search, the "saw it on the street" composite, PDF output, and
  the final template designs — all deferred (recorded above); each is its own later line with its own
  owner-locked engine/source decision.
- Real LLM synthesis quality — Stage 5 (mock LLM proves the wiring; the flows are model-agnostic).
