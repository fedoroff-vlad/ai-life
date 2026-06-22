# nutrition domain — nutritionist + chef (food)

Authority file for the **food domain**: the **`mcp-nutrition`** domain-MCP (owns the `nutrition`
schema), the **`nutritionist-agent`** (analysis/advice — the MVP), and the **`chef-agent`** (recipes
/ meal plans — Phase 2). Owner-chosen after stylist (2026-06-22). The owner's full vision is recorded
below; **build now = the nutritionist MVP** (food log + diet profile + a nutrition-analysis HTML
deliverable), everything heavier is deferred to recorded slices.

## Owner decisions — LOCKED (2026-06-22)
1. **MVP = nutritionist first** (food **log** + **diet profile** + a **nutrition-analysis** HTML
   report). The **chef** half (pantry → recipe/meal-plan suggestions) is **Phase 2**.
2. **Food / nutrition data source = Open Food Facts** (free, no API key, barcode + product
   composition) — behind a swappable `FoodDataSource` interface, exposed by a new shared
   capability-MCP **`mcp-food-data`** (sibling of `mcp-market-data`). MVP can ship on **LLM estimate**
   and bind `mcp-food-data` as the first slice that needs precise macros — but the source is locked.
3. **Shape = one domain-MCP + two agents** — `mcp-nutrition` (the `nutrition` schema) bound by both
   `nutritionist-agent` (now) and `chef-agent` (Phase 2). Matches the roadmap (chef + nutritionist as
   separate specialists); co-location ≠ merge (each is its own app/container).
4. **Render = lift to a shared `doc-render`** — the stylist render seam was flagged "lift to a shared
   `doc-render` on the second consumer"; the nutrition HTML deliverables are that second consumer.
   See §doc-render lift.

## Owner's full vision (recorded — not all MVP)
A personal food assistant that (a) **knows what I eat** — log meals by photo or text; (b) **knows me**
— calorie/macro goals, allergies & dietary restrictions (halal / vegan / lactose-free / …), tastes;
(c) **analyses my nutrition** — daily/weekly report: intake, deficits/excesses, recommendations;
(d) **cooks for me** — "what can I make from what I have", meal plans, recipe cards grounded in the
pantry + the diet profile + current ideas from the web; (e) later: barcode/label scan → precise
macros from a product DB, shopping lists with marketplace links, Apple Health intake/expenditure,
plated-dish images, PDF cards.

## MVP — nutritionist (ordered, owner 2026-06-22)
The cheapest vertical: vision/LLM **analysis** + structured storage + an **HTML deliverable**. Reuses
existing capabilities (`mcp-media-processing` caption, the `Coordinator`, the render seam); the only
new code is the `mcp-nutrition` domain-MCP + `nutritionist-agent`.

1. **Food log** — a meal **photo** (→ `mcp-media-processing` `caption` identifies the dishes) or a
   **typed** meal ("овсянка с бананом") → an LLM extract of the items + an estimated
   calories/protein/fat/carbs draft → a `meal_log` row (write-immediately, mirrors `receipt-parser`).
2. **Diet profile** — goals (kcal/macros), allergies & restrictions, tastes — **data, not prose**
   (`diet_profile`, one per person), set from typed text (and editable later).
3. **Nutrition analysis** — a daily/weekly **HTML report** on the shared `Coordinator`: gather
   `{recent meal_log window, diet_profile, (later) food-data lookups}` → one LLM synthesis →
   intake summary, deficits/excesses vs the goals, concrete recommendations → render via the
   **shared doc-render** → store in media-service → reply summary + link.

## Shape
- `domains/nutrition/mcp-nutrition` — **domain-MCP**, **port 8104**, owns the new **`nutrition`**
  schema (Liquibase feature `050-nutrition.yml`; the `050-059` range is reserved for nutrition in
  PATTERNS). CRUD over the food log + the diet profile + (Phase 2) pantry + recipes. Template:
  `domains/stylist/mcp-wardrobe`.
- `domains/nutrition/nutritionist-agent` — **domain agent**, **port 8105**. Binds `mcp-nutrition`
  (its data) + `mcp-media-processing` (meal-photo caption) over SSE + the deterministic `/internal/*`
  HTTP passthroughs; renders the analysis HTML via the shared doc-render. Registered in the
  orchestrator (manifest-driven). Template: `domains/stylist/stylist-agent`.
- `domains/nutrition/chef-agent` — **Phase 2**, **port 8106**. Binds `mcp-nutrition` + `mcp-web`
  (recipe search) + `mcp-image-gen` (dish illustration, stub→local) + the shared doc-render.
- `shared/mcp/mcp-food-data` — **capability-MCP**, **port 8107**, no schema. `food_lookup(query|barcode)`
  over **Open Food Facts** behind a swappable `FoodDataSource` (mirrors `mcp-market-data`/Stooq).
  Bound first when precise macros are needed (a nutritionist slice or chef).
- Contracts in `libs/contracts/.../nutrition/`: `MealLogDto` (+ `LogMealInput`), `DietProfileDto`
  (+ `SetDietProfileInput`), and the caption/LLM-extract shapes the log flow parses; `food/FoodFacts`
  (+ `FoodLookupInput`) for the capability.

## Schema `nutrition` (050-nutrition.yml)
- `meal_log` — id, household_id, owner_id, eaten_at, source (photo|text), description, items jsonb
  (the parsed dishes), kcal, protein_g, fat_g, carbs_g, image_media_id (the meal photo when present),
  metadata jsonb, created_at. One row per logged meal.
- `diet_profile` — id, household_id, owner_id (one per person via a unique `(household_id, owner_id)`
  index, null-owner = household default — same shape as `wardrobe.style_profile`), goal_kcal,
  goal_protein_g, goal_fat_g, goal_carbs_g, restrictions jsonb (allergies / halal / vegan / …),
  tastes jsonb, notes, updated_at.
- **Phase 2:** `pantry_item` (household, owner, name, qty, unit, image_media_id) + `recipe` (saved
  recipes) — added with the chef slices, not now.

## Capability `mcp-food-data` (Open Food Facts) — sibling of `mcp-market-data`
Read-only product/nutrition lookup. `FoodDataSource` interface; `OpenFoodFactsSource`
(`@ConditionalOnProperty fooddata.source=openfoodfacts`, matchIfMissing) — GET Open Food Facts
(`/api/v2/product/{barcode}` for a barcode, `/cgi/search.pl?search_terms=…&json=1` for a name) → map
to `food/FoodFacts(name, kcal?, protein?, fat?, carbs?, perGrams?, barcode?)`; "not found" → null
fields, not an error. `food_lookup` `@Tool` + `POST /internal/food-lookup` passthrough
(MockWebServer-testable; OFF the MCP/SSE transport). No new backing container (OFF is public HTTPS),
like Stooq.

## doc-render lift (the second-consumer rule)
The stylist render seam (`StylistRenderer` / `HtmlStylistRenderer` / `StylistDoc` / `RenderedDoc`)
lives inside `stylist-agent`. The nutrition HTML reports are the **second consumer** → time to lift.
- **Form (recommended): a shared compile-time library `libs/doc-render`**, NOT a capability-MCP.
  Rendering HTML from a model is a **pure function** with no external resource and no schema; a lib
  adds **no new container and no HTTP hop** (leanest — tooling-simplicity). It upgrades to a
  capability-MCP **`mcp-doc-render`** later *only* when a renderer needs process isolation (the
  deferred **PDF** path — wkhtmltopdf/Chromium native binary). The seam (`render(model) → bytes/url`)
  stays identical, so flipping lib→MCP is internal. **Flagged for the owner** — if you prefer the
  capability-MCP now, say so; default is the lib.
- **Generalise the board model.** `StylistDoc` is fashion-specific (KEEP/QUESTION/REMOVE verdicts,
  hero pieces). The shared model becomes a generic **`Doc`**: header (kicker/title/subtitle +
  featured image), keyed text `sections`, a colour `palette`, a generic `gallery`, and an optional
  **typed grid** (the verdict grid generalises to labelled tiles) — enough for both fashion boards
  and nutrition cards. The luxury-editorial theme + `*_THEME_*` env theming move into the lib too
  (stylist keeps its current look via defaults).
- **Migrate stylist onto it** in the same lift slice (no behaviour change — its tests stay green),
  then nutrition + chef consume the shared renderer.

## PR-sized slices
- **NU-0 — docs opener (this).** `nutrition.md` + INDEX row + roadmap (mark in-progress, MVP =
  nutritionist, locks) + PATTERNS `050-059` row + STATUS. No code.
- **DR-a — lift the render seam to `libs/doc-render`.** Generalise `StylistDoc` → `Doc`, move the
  renderer + theme into the lib, migrate `stylist-agent` onto it (tests green, no visual change).
  Prereq for the nutrition analysis board. (Self-contained; can land before NU-a.)
- **NU-a — `mcp-nutrition` domain-MCP + schema.** `050-nutrition.yml` (`meal_log` + `diet_profile`)
  + CRUD tools (`log_meal`, `list_meals`, `set_diet_profile`, `get_diet_profile`) + the `nutrition/*`
  contracts. Testcontainers repo test. Scaffold per PATTERNS "new MCP module"; template `mcp-wardrobe`.
- **NU-b — `nutritionist-agent` scaffold + orchestrator registration.** Manifest +
  `@Import(AgentRuntimeConfig)`; binds `mcp-nutrition` + `mcp-media-processing` over SSE; chat
  fallback until the flows land; registered `{name: nutritionist}` + `NUTRITIONIST_AGENT_URL`.
  Template `stylist-agent`.
- **NU-c — food-log flow (split c1/c2 like ST-c/MP-c).** **c1:** `mcp-nutrition`
  `POST /internal/meal` add-passthrough → `log_meal`. **c2:** `nutritionist-agent` log flow — a meal
  **photo** → `caption` extract (instruction = a `meal-logger` SKILL.md, strict-JSON dishes + macro
  estimate) and a **typed meal** → LLM extract → write via `/internal/meal`, write-immediately.
- **NU-d — diet profile.** `mcp-nutrition` `POST /internal/diet-profile` → `set_diet_profile`;
  `nutritionist-agent` sets the profile from typed goals/restrictions (a `diet-profiler` SKILL.md).
- **NU-e — nutrition-analysis board.** `mcp-nutrition` read passthroughs `GET /internal/meals` +
  `GET /internal/diet-profile`; `nutritionist-agent` `flow/NutritionAnalyst` on the `Coordinator`:
  gather `{recent meals window, diet_profile}` → one LLM synthesis (a `nutrition-analyst` SKILL.md:
  intake vs goals, deficits/excesses, recommendations) → render via `libs/doc-render` → store →
  link. **Nutritionist MVP COMPLETE.**
- **FD-a — `mcp-food-data` (Open Food Facts) capability.** `food_lookup` over OFF +
  `/internal/food-lookup`; bound by the analyst (precise macros) when it earns its keep.
- **Phase 2 — chef.** `pantry_item` + `recipe` schema; `chef-agent` (port 8106) binds `mcp-nutrition`
  + `mcp-web` (recipe search) + `mcp-image-gen` (dish card illustration) + doc-render; the
  "what-can-I-make" Coordinator flow → recipe/meal-plan HTML cards. Pantry photo → `caption` inventory.

## Deferred (recorded vision — each maps to an architectural home)
| Vision item | Home | Why deferred |
|---|---|---|
| **Barcode / label scan → precise macros** | `mcp-food-data` (Open Food Facts barcode) + `mcp-media-processing` OCR | MVP estimates macros; precise DB rides in with FD-a. |
| **Plated-dish images / recipe-card photos** | `mcp-image-gen` (stub→local), the deferred GPU line | same engine seam as stylist; real images once the GPU model is up. |
| **Shopping lists + marketplace buy-links** | the deferred marketplace-search capability (shared with stylist) | needs a source lock (Ozon/Yandex/…), same as stylist's. |
| **Apple Health (intake vs expenditure)** | a `health-agent` / Apple Health capability | separate domain (roadmap candidate); nutrition consumes it later. |
| **PDF deliverables** | the `doc-render` seam → an HTML→PDF renderer (the lib→`mcp-doc-render` upgrade) | MVP ships HTML; PDF drops into the same seam. |

## Out of scope (here)
- Chef Phase 2 details beyond the slice sketch, GPU dish images, marketplace links, Apple Health, PDF
  — all deferred (recorded above), each its own later line with its own owner-locked engine/source.
- Real LLM synthesis quality — Stage 5 (mock LLM proves the wiring; the flows are model-agnostic).
