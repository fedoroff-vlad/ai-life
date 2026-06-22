# nutrition domain — nutritionist + chef (food)

Authority file for the **food domain**: the **`mcp-nutrition`** domain-MCP (owns the `nutrition`
schema), the **`nutritionist-agent`** (analysis / ration / shopping list), and the **`chef-agent`**
(recipes). Owner-chosen after stylist (2026-06-22); scope synced with the owner 2026-06-22 around two
concrete cases (below). The owner's full vision is recorded; **build now = the MVP**, the heavier
parts are deferred to recorded slices.

## The two driving cases (owner, 2026-06-22)
1. **Receipt → multi-agent breakdown.** I send a grocery receipt → the **finance** agent logs the
   expense (already works) → because it's a **grocery basket**, the **nutritionist** also breaks the
   basket down (what's good / what's not, КБЖУ) → nutritionist **+ chef together** propose a ration
   and recipes.
2. **Family shopping plan.** "We want to stock up at Lenta" → the agent may **check the store via web
   search** (what's available) → I describe what my wife and I eat, and that our 8-month-old is on
   **complementary feeding (прикорм)** eating X/Y/Z → the **nutritionist** gives recommendations + a
   **ration** + a **shopping list**, and the **chef** gives **recipes** (web-search links to food.ru
   etc., and/or a nicely formatted recipe card with step photos).

## Owner decisions — LOCKED (2026-06-22)
1. **MVP = both tracks** — (a) **food log** (what I eat) + (b) the **basket → breakdown → ration +
   shopping list + recipes** chain (the two cases). The basket/ration chain is the headline.
2. **Grocery receipt fans out to BOTH agents.** When a receipt is recognised **and** it's a grocery
   basket, it reaches finance **and** nutrition. Realised via the **event bus** (no duplicate vision):
   finance parses the receipt once, writes the expense, and — if grocery — publishes a
   `basket.captured` event with the line-items; `nutritionist-agent` **consumes** it off the bus and
   runs the basket breakdown. (Established consumer pattern — notifier / memory-service already
   consume bus events.) See §inter-agent.
3. **Multi-person family from the start.** Per-person data (`owner_id`) **plus** ad-hoc people
   described in the request (wife, the 8-month-old on прикорм). ⚠️ **Infant / medical safety:**
   feeding advice for a baby is sensitive — the agent gives **general guidance with an explicit
   "this is not a substitute for your pediatrician" caveat**, never acts as a medical authority.
4. **Food / macro data = Open Food Facts** (free, no key) behind a swappable `FoodDataSource`,
   exposed by a shared **`mcp-food-data`** capability-MCP (sibling of `mcp-market-data`). MVP may
   estimate macros with the LLM and bind `mcp-food-data` when precise macros earn their keep.
5. **Chef recipes (MVP) = web-search links + a formatted HTML card** (food.ru etc. via `mcp-web`).
   Generated step-by-step recipe photos are deferred to the GPU image-gen line.
6. **Shape = one domain-MCP + two agents** (`mcp-nutrition` + `nutritionist-agent` + `chef-agent`).
   Both agents are MVP (the cases need recipes); chef simply lands after the nutritionist core.
7. **Render = lifted to a shared `doc-render`** (the nutrition/chef HTML deliverables are the second
   consumer of the stylist render seam). See §doc-render lift.

## Shape
- `domains/nutrition/mcp-nutrition` — **domain-MCP**, **port 8104**, owns the **`nutrition`** schema
  (Liquibase `050-nutrition.yml`; `050-059` reserved for nutrition in PATTERNS). CRUD over food log,
  diet profile, baskets, and (Phase 2) pantry + saved recipes. Template: `domains/stylist/mcp-wardrobe`.
- `domains/nutrition/nutritionist-agent` — **domain agent**, **port 8105**. Binds `mcp-nutrition` +
  `mcp-media-processing` (meal/basket photo caption) + `mcp-web` (store/availability lookup) over SSE
  + the `/internal/*` passthroughs; **consumes `basket.captured` off the bus**; renders HTML via the
  shared doc-render; coordinates the chef via the orchestrator hub. Template: `stylist-agent`.
- `domains/nutrition/chef-agent` — **domain agent**, **port 8106**. Binds `mcp-nutrition` + `mcp-web`
  (recipe search) + the shared doc-render (recipe cards) + later `mcp-image-gen` (dish/step photos).
  Invoked by the nutritionist (ration → recipes) via the hub, and routable directly.
- `shared/mcp/mcp-food-data` — **capability-MCP**, **port 8107**, no schema. `food_lookup(query|barcode)`
  over **Open Food Facts** behind a swappable `FoodDataSource` (mirrors `mcp-market-data`/Stooq).
- Contracts in `libs/contracts/.../nutrition/`: `MealLogDto`(+`LogMealInput`), `DietProfileDto`
  (+`SetDietProfileInput`), `BasketDto`/`BasketItem`, the bus event `basket/BasketCapturedEvent`,
  and `food/FoodFacts`(+`FoodLookupInput`).

## Schema `nutrition` (050-nutrition.yml)
- `meal_log` — id, household_id, owner_id, eaten_at, source (photo|text), description, items jsonb,
  kcal, protein_g, fat_g, carbs_g, image_media_id, metadata jsonb, created_at. One row per meal.
- `diet_profile` — id, household_id, owner_id (one per person, unique `(household_id, owner_id)`,
  null-owner = household default — like `wardrobe.style_profile`), goal_kcal, goal_protein_g,
  goal_fat_g, goal_carbs_g, restrictions jsonb (allergies / halal / vegan / infant-stage / …),
  tastes jsonb, notes, updated_at. Holds the wife / infant profiles too.
- `basket` — id, household_id, owner_id, captured_at, merchant, source (receipt|manual),
  receipt_media_id, items jsonb (name/qty/est-macros per line), kcal/protein/fat/carbs totals,
  analysis jsonb (the breakdown), created_at. One row per analysed basket.
- **Phase 2:** `pantry_item` + `recipe` (saved) — added with the chef/pantry slices.

## Inter-agent: grocery receipt → finance + nutrition (the case-1 fan-out)
The established bus pattern, no duplicate vision work:
1. Receipt photo → orchestrator → **finance** receipt-parser (as today): caption → items + merchant
   + category → write the expense (confirm-flow unchanged).
2. **Finance is extended** to (a) extract **grocery line-items** (today it keeps only
   amount/merchant/date — this was the deferred "item-level receipt parsing") and (b) classify the
   receipt as **grocery**. If grocery, finance publishes **`basket.captured`** (household, owner,
   merchant, items, receipt_media_id) to `bus.outbox`.
3. **`nutritionist-agent` consumes `basket.captured`** off the bus → stores a `basket` row → runs the
   **basket breakdown** (КБЖУ + what's good / what's not, vs the diet profile) → delivers an HTML
   report + optionally invokes the chef for recipes. Same consumer shape as notifier
   (`notify.requested`) / memory-service (`message.received`).

So one receipt reaches **both** agents (finance expense + nutrition breakdown), recognised once.
The user can also send a basket/list **directly** to the nutritionist (it captions the photo itself)
— that path lands first; the automatic fan-out is the inter-agent slice on top.

## doc-render lift (the second-consumer rule)
The stylist render seam (`StylistRenderer` / `HtmlStylistRenderer` / `StylistDoc` / `RenderedDoc`)
lives in `stylist-agent`. The nutrition/chef HTML deliverables are the **second consumer** → lift it.
- **Form (recommended): a shared compile-time library `libs/doc-render`**, NOT a capability-MCP.
  Rendering HTML from a model is a **pure function** (no external resource, no schema) → a lib adds
  **no new container, no HTTP hop** (leanest — tooling-simplicity). It upgrades to a capability-MCP
  **`mcp-doc-render`** later *only* when a renderer needs process isolation (the deferred **PDF**
  path — wkhtmltopdf/Chromium). The seam (`render(model) → bytes/url`) is identical, so lib→MCP is
  internal. **Flagged for the owner** — say if you'd rather a capability-MCP now; default is the lib.
- **Generalise the board model.** `StylistDoc` is fashion-specific (KEEP/QUESTION/REMOVE verdicts,
  hero pieces). The shared **`Doc`** keeps: header (kicker/title/subtitle + featured image), keyed
  text `sections`, a colour `palette`, a generic `gallery`, a **typed grid** (verdict tiles
  generalise to labelled tiles — reused for "good / watch / cut" basket items), and a **link list**
  (recipe links). The luxury-editorial theme + `*_THEME_*` env theming move into the lib (stylist
  keeps its look via defaults).
- **Migrate stylist onto it** in the lift slice (no behaviour change — its tests stay green); then
  nutrition + chef consume the shared renderer.

## PR-sized slices
Foundation:
- **NU-0 — docs opener. DONE (PR149); revised 2026-06-22 (this).**
- **DR-a — lift render → `libs/doc-render`** (generalise `StylistDoc`→`Doc`, move renderer + theme,
  migrate stylist; tests green, no visual change). Prereq for every HTML deliverable below.

Nutrition core:
- **NU-a — `mcp-nutrition` + `050-nutrition.yml`** (`meal_log` + `diet_profile` + `basket`) + CRUD
  tools + `nutrition/*` contracts. Testcontainers repo test. Template `mcp-wardrobe`.
- **NU-b — `nutritionist-agent` scaffold + orchestrator registration** (binds `mcp-nutrition` +
  `mcp-media-processing` + `mcp-web`; chat fallback). Template `stylist-agent`.
- **NU-c — food-log flow** (c1/c2): meal photo → `caption` extract (a `meal-logger` SKILL) / typed
  meal → LLM extract → write via `/internal/meal`, write-immediately.
- **NU-d — diet profile** (multi-person): `/internal/diet-profile` → `set_diet_profile`; set
  profiles for self / wife / infant (a `diet-profiler` SKILL) from typed goals/restrictions.
- **NU-e — nutrition-analysis board** (`Coordinator`): gather `{recent meals, diet_profile}` → one
  LLM synthesis (a `nutrition-analyst` SKILL: intake vs goals, deficits/excesses, recommendations) →
  render via `libs/doc-render` → store → link.

Basket + inter-agent (case 1):
- **NU-f — basket breakdown flow** (direct): a grocery photo/list sent to the nutritionist →
  `caption`/parse items → КБЖУ + good/watch/cut vs profile (a `basket-analyst` SKILL) → HTML report.
- **IA-a — finance: grocery line-items + classify + publish `basket.captured`.** Extend
  receipt-parser to extract line-items and detect a grocery receipt; publish the bus event.
- **IA-b — nutrition consumes `basket.captured`** off the bus → runs NU-f automatically (the case-1
  fan-out). Mirrors notifier/memory consumers.

Ration + chef (cases 1 & 2):
- **NU-g — ration + shopping-list flow** (`Coordinator`, multi-person): gather `{diet profiles for
  the named people incl. ad-hoc infant context, recent basket/log, goals, store availability via
  mcp-web}` → ration + shopping list (a `meal-planner` SKILL, infant caveat) → HTML → link.
- **CH-a — `chef-agent` scaffold + orchestrator registration** (binds `mcp-nutrition` + `mcp-web` +
  doc-render).
- **CH-b — recipe flow**: ration/request → `mcp-web` recipe search (food.ru etc.) → an HTML recipe
  card (links + web photos) (a `recipe-finder` SKILL). The nutritionist's NU-g **invokes the chef**
  via the orchestrator hub (ration → recipes), so they work "together" (like gift-recommender →
  finance).

Data precision:
- **FD-a — `mcp-food-data` (Open Food Facts)** `food_lookup` + `/internal/food-lookup`; bound by the
  basket/log analysis for precise macros (barcode + product composition).

## Deferred (recorded vision — each maps to an architectural home)
| Vision item | Home | Why deferred |
|---|---|---|
| **Pantry inventory by photo** ("what I have") | `pantry_item` + `caption` (Phase 2 chef) | needs the pantry table + a fridge-photo flow; ration MVP uses the basket/log + stated items. |
| **Generated step-by-step recipe photos** | `mcp-image-gen` (stub→local GPU) | MVP uses web photos + links; generated step images ride the deferred GPU line. |
| **Deep marketplace integration / buy-links** (Lenta/Ozon cart) | a marketplace-search capability (shared with stylist) | MVP "checks the store" via plain `mcp-web`; cart/price integration needs a source lock. |
| **Apple Health (intake vs expenditure)** | a `health-agent` / Apple Health capability | separate domain (roadmap candidate); nutrition consumes it later. |
| **PDF deliverables** | the `doc-render` seam → HTML→PDF (the lib→`mcp-doc-render` upgrade) | MVP ships HTML; PDF drops into the same seam. |
| **Precise infant-feeding protocols** | the `meal-planner`/`diet-profiler` skills + a pediatric reference | MVP gives general weaning guidance **with a pediatrician caveat**, never prescriptive medical advice. |

## Out of scope (here)
- The deferred rows above — each its own later line with its own owner-locked engine/source.
- Real LLM synthesis quality — Stage 5 (mock LLM proves the wiring; the flows are model-agnostic).
