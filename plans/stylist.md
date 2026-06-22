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
| **"Fits"-like wardrobe app** (owner ref 2026-06-21): browsable catalogue split **by person** (me / wife / daughter), each with an item list **and saved outfits** | per-person split is **data-ready** — `wardrobe_item.owner_id` + `style_profile.owner_id` key by `core.users` within the household (today's flows write household-shared; just need owner-aware flows/views). **Saved outfits** = a new `wardrobe.outfit` table (household, owner, name, item ids) + CRUD — a future slice. The browsable **app UI** = a web front-end over `mcp-wardrobe` (beyond the Telegram + HTML-deliverable surface). | natural extension once the MVP boards land; no MVP work. |

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
  add-passthrough → `add_item`. **ST-c2 DONE (PR135)** — stylist-agent `catalogue/WardrobeCataloguer`
  (photo → `caption` extract via the `wardrobe-cataloguer` SKILL → `/internal/item`, write-immediately).
  **ST-c COMPLETE.**
- **ST-d — "analyse me" flow + analysis HTML. DONE (PR136).** Self-photo(s) + typed params → `caption` analysis
  (person/style type, colour type, suitable fabrics/textures, body shape; the `style-analyst` SKILL also copies
  through any height/weight/measurements the user typed in the note → params from BOTH sources) →
  `set_style_profile` (via `mcp-wardrobe` `POST /internal/profile`); render the **fabric/colour/texture analysis**
  as a **responsive HTML** deliverable through the **render seam** (`StylistRenderer` interface +
  `HtmlStylistRenderer`), store it in media-service, reply summary + link. Concrete-examples LLM prose pass
  deferred (MVP page grounded in the structured profile).
- **ST-e — capsule advisor + capsule HTML. DONE (PR137) — MVP COMPLETE.** `flow/StylistAdvisor` on the
  shared `Coordinator` (copy `InvestmentAdvisor`/`FinancialAdvisor`): gather `{wardrobe items, style_profile,
  trends (mcp-web), season}` → one LLM synthesis from `[AGENT.md, capsule-advisor SKILL.md] +
  {payload(request, season), context}` → a capsule, rendered as a responsive **HTML page** (embeds garment
  photos via the render-seam gallery) → stored in media-service → reply summary + link. Empty wardrobe →
  invite to catalogue. Routed via a capsule-cue heuristic in `IntentController`. Added `mcp-wardrobe` read
  passthroughs `GET /internal/items` + `GET /internal/profile`.

## Phase 2 — luxury-editorial deliverable boards (owner refs 2026-06-21)
The owner sent reference boards (luxury fashion-magazine aesthetic — The Row / Toteme / Dior:
ivory/beige ground, serif display caps, grid composition, KEEP/QUESTION/REMOVE verdict tags,
HERO PIECES with a gold accent, palette swatches, Kibbe type + colour season + Jungian archetype,
silhouette/fabric strategies, "what not to wear", gap analysis with priority + price tier,
coverage before/after). **Aesthetic LOCKED = luxury editorial** (this replaces the MVP's plain
default template). **Build now = editorial HTML on the owner's REAL uploaded photos + rich LLM
analysis; the reference boards' photorealistic renders (garment-on-body, "you in different looks"
model shots, palette-on-face) stay on the deferred GPU image-gen line** (owner 2026-06-21: editorial
first, renders later). Menswear knowledge frames the skills' prompts (Kibbe; Найденская/Трубецкова
"Библия стиля"; Alan Flusser "Dressing the Man") — apply the methodology, never reproduce the
copyrighted text. Boards build in this order:
- **ST-f — editorial render foundation.** Extend the `StylistDoc` model (palette swatches, verdict
  tiles, hero row, keyed sections) + rewrite `HtmlStylistRenderer` to the locked luxury-editorial
  template (ivory/serif/grid/gold-hero, responsive, dark-mode aware). Pure-unit-tested. The existing
  analyse/capsule pages immediately render in the new style (back-compat constructors).
- **ST-g — Body & Style Analysis board.** Upgrade the `style-analyst` skill (Kibbe type + subtype,
  colour season + undertone/contrast, archetype, silhouette/neckline/waist strategies, fabric logic,
  "what not to wear", styling principles, style-codes, final direction) + `AnalyseMe` to populate the
  rich board — modelled on the owner's favourite reference ("Body Shape & Styling Analysis Board":
  body analysis · geometry · clothing architecture · fabric & texture logic · colour strategy ·
  styling principles · you-in-different-looks · final direction).
- **Theme LOCKED (owner 2026-06-21):** noble warm-beige ground, **light only** (no dark-mode flip),
  display font **Oranienbaum** + body **Manrope** (both Cyrillic — Cormorant/Jost lacked it and fell
  back). **Follow-up polish (owner-suggested):** lift theme (colours + fonts) into env vars on
  `StylistAgentProperties` so a redeploy can re-skin without a code change — do after the content boards.
- **ST-h — Wardrobe Audit board. DONE (PR140).** New `flow/WardrobeAuditor` on the `Coordinator` +
  `wardrobe-auditor` skill: gather catalogued items + profile → LLM verdict KEEP/QUESTION/REMOVE +
  one-line reason per item, hero pieces, systemic-pattern diagnosis, power palette → render the audit
  board (verdict grid with garment photos matched by name, gold hero, palette, "Системная ошибка").
  Routed by an audit cue ("ревизия / разбор гардероба"). Empty wardrobe → invite to catalogue.
- **ST-i — Capsule board (editorial). DONE (PR141).** The ST-e capsule already renders on the editorial
  template (since ST-f); ST-i gives it the board chrome — `StylistAdvisor.store` builds the doc via the
  `StylistDoc` builder with a kicker ("Curated · Strategic · Aligned") + a season/occasion subtitle +
  an "Образы" section + the garment-photo gallery, so it's consistent with the analysis/audit boards.
- **ST-j — Wardrobe Gap Analysis board. DONE (PR142) — Phase 2 COMPLETE.** New `flow/GapAnalyst` on the
  `Coordinator` + `gap-analyst` skill: gather wardrobe + profile → missing items + why + priority
  (ESSENTIAL/STRONG ADD/NICE TO HAVE) + price tier + a "do not buy" row + coverage before/after →
  render the gap board ("Что докупить" / "Не покупать" / "Фокус" + coverage subtitle + palette).
  Routed by a gap cue ("что докупить / список покупок"). **Marketplace buy-links stay deferred.**

- **ST-k — poster layout (owner-driven redesign 2026-06-22). DONE (PR143).** Owner reacted that the clean
  stacked page didn't read like the reference *board*; reworked `HtmlStylistRenderer` to an **airy
  editorial poster**: hairline dividers instead of boxed cards (single beige ground), a **centered
  photo anchor** (`StylistDoc.featuredImageUrl`, new), and text sections in a **hairline-separated
  multi-column flow**; verdict grid / hero / palette strip / gallery as full-width rows. `AnalyseMe`
  sets the featured photo = the analysed self-photo. **Honest boundary (owner-agreed):** the
  reference's drawn mini-figures / "you in different looks" model shots are **image-generation** (the
  deferred line) — ST-k delivers the structure; figures + web-ref look images + generated imagery are
  later layers. **Theme env-var follow-up still open.**

## Out of scope (here)
- GPU image-gen / virtual try-on, marketplace search, the "saw it on the street" composite, PDF output, and
  the final template designs — all deferred (recorded above); each is its own later line with its own
  owner-locked engine/source decision.
- Real LLM synthesis quality — Stage 5 (mock LLM proves the wiring; the flows are model-agnostic).
