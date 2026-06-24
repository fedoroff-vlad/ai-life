# nutritionist-agent

Nutrition domain agent (port **8105**). Logs meals, breaks grocery baskets down (КБЖУ +
good/watch/cut), keeps per-person diet profiles, and (over the coming slices) plans rations +
shopping lists. Owns the `mcp-nutrition` domain-MCP; binds the shared `mcp-media-processing`
(vision caption for meal/receipt photos) + `mcp-web` (store/availability lookup) + `mcp-food-data`
(precise per-100g КБЖУ for the basket breakdown). Routes via the orchestrator (registered as
`nutritionist`). See [plans/nutrition.md](../../../plans/nutrition.md).

## Status (through FD-c)

Manifest endpoint + chat fallback (NU-b) + the **food-log flow** (NU-c) + **diet profiles** (NU-d) +
the **nutrition-analysis board** (NU-e) + the **basket breakdown** (NU-f, direct) + the **ration +
shopping-list flow** (NU-g). Remaining flows replace the fallback branch-by-branch:
- **NU-c — food log. DONE.** A meal photo → `mcp-media-processing` caption extract, or a typed meal
  ("съел…", "на обед…", "запиши…") → one LLM extract, both via the `meal-logger` SKILL → write
  **write-immediately** to `mcp-nutrition`'s `/internal/meal` (attributed to the sender). `foodlog/FoodLogger`.
- **NU-d — diet profiles. DONE.** A typed message with a diet-profile cue ("моя цель…", "у меня
  аллергия…") → one LLM extract via the `diet-profiler` SKILL → upsert via `/internal/diet-profile`,
  for the sender (`self`) or the household-default (`household`). `profile/DietProfiler`.
- **NU-e — nutrition analysis. DONE.** A typed message with an analysis cue ("разбор питания", "как
  я питаюсь") → gather recent meals (`GET /internal/meals`) + the diet profile (`GET
  /internal/diet-profile`) on the shared `Coordinator` → one LLM synthesis via the `nutrition-analyst`
  SKILL (intake vs goals, deficits/excesses, recommendations) → render an HTML board via the shared
  `libs/doc-render` → store in media-service → reply with a link. Empty food log → an invite to log
  first (no LLM call). `analysis/NutritionAnalyst`.
- **NU-f — basket breakdown (direct). DONE.** A grocery basket sent straight to the nutritionist as
  a **photo** with a basket cue ("продукты", "корзина", "чек") → `mcp-media-processing` caption, or a
  **typed list** ("разбери продукты", "список покупок") → one LLM turn — both via the `basket-analyst`
  SKILL (with the diet profile folded in) → КБЖУ + good/watch/cut verdict → save via `/internal/basket`
  → HTML verdict board → link. `basket/BasketBreakdown`.
- **FD-c — precise macros. DONE.** Before rendering, the basket breakdown grounds its numbers in real
  reference data: each item is looked up in the shared `mcp-food-data` capability (Open Food Facts) for
  per-100g КБЖУ via `http/FoodDataClient` → `POST /internal/food-lookup` (parallel, soft-failed per
  item, bounded), folded into the board as a "Точные КБЖУ (Open Food Facts)" section. A no-match just
  omits the section (the LLM's own estimate still ships). Both the direct and the bus-fan-out paths
  enrich (shared `render`).
- **IA-b — basket breakdown off the bus (case-1 fan-out). Endpoint DONE (IA-b1).** `POST
  /internal/basket-event` (body `BasketCapturedEvent`) → `BasketBreakdown.breakdownFromEvent`: one LLM
  breakdown over the line items finance already extracted (no re-vision) → save basket → render verdict
  board → **notify the household** (no user reply channel on a bus consume, so it fans out like the
  gift-recommender). Best-effort; returns 202. mcp-nutrition's bus consumer (IA-b2) forwards the
  `basket.captured` event here — agents stay DB-less, so the bus listener lives in the domain-MCP.
- **NU-g — ration + shopping list (multi-person). DONE.** A ration cue ("составь рацион", "план
  питания", "закупиться в Ленте") → gather the sender's diet profile + the household-default profile
  + recent meals (`mcp-nutrition`) and, when a store is named, its availability (`mcp-web`) on the
  shared `Coordinator` → one LLM synthesis via the `meal-planner` SKILL (multi-person ration +
  grouped shopping list, ad-hoc people read from the request, infant caveat) → render an HTML board
  via the shared `libs/doc-render` → store in media-service → reply with a link. **Once the ration is
  rendered it invokes the chef** (`recommend_recipes`) over the orchestrator hub
  (`http/OrchestratorInvokeClient` → `/v1/agents/invoke`) and folds the returned recipe-card link into
  the reply (CH-b2, gift-recommender→finance shape) — soft-failed, so a chef outage just drops the
  recipes line. `flow/MealPlanner`.

## Endpoints

- `POST /agents/nutritionist/intent` (body `NormalizedMessage`) → `IntentResponse` — the
  orchestrator's entry point. A photo with a basket cue → basket breakdown; any other photo →
  food-log; a profile cue → diet-profiler; an analysis cue → nutrition-analysis; a ration cue →
  ration + shopping list; a basket cue → basket breakdown; a food-log cue → food-log; else chat.
- `GET /agents/nutritionist/manifest` → `AgentManifest` — scraped by the orchestrator on startup.
- `POST /internal/basket-event` (body `BasketCapturedEvent`) → 202 — the IA-b fan-out entry;
  mcp-nutrition's bus consumer forwards a `basket.captured` event here, the agent runs the breakdown
  and notifies the household.

## Env

| Var | Default | Purpose |
|---|---|---|
| `NUTRITIONIST_AGENT_PORT` | `8105` | HTTP port |
| `MCP_NUTRITION_URL` | `http://mcp-nutrition:8104` | nutrition domain-MCP (its data) |
| `MCP_MEDIA_PROCESSING_URL` | `http://mcp-media-processing:8097` | shared vision capability |
| `MCP_WEB_URL` | `http://mcp-web:8098` | shared web/store-lookup capability |
| `MCP_FOOD_DATA_URL` | `http://mcp-food-data:8107` | shared nutrition-facts capability (precise per-100g КБЖУ for the basket breakdown) |
| `MEDIA_SERVICE_URL` | `http://media-service:8088` | stores the rendered HTML deliverables |
| `NUTRITIONIST_PUBLIC_MEDIA_BASE_URL` | `http://media-service:8088` | public base for the deliverable link (`<base>/v1/media/{id}`) |
| `ORCHESTRATOR_URL` | `http://orchestrator:8083` | hub for the ration → recipes inter-agent call (NU-g invokes chef) |
| `NUTRITIONIST_AGENT_MCP_CLIENT_ENABLED` | `true` | toggle the eager MCP-SSE binding off in dev |
| `NUTRITIONIST_AGENT_MEMORY_RECALL_K` | `5` | memory recall fan-out |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | llm-gateway (chat) |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` / `MEMORY_SERVICE_URL` | — | shared agent-runtime clients |

## Key classes

- `NutritionistAgentApplication` — `@Import(AgentRuntimeConfig)` + `@EnableConfigurationProperties`.
- `config/NutritionistAgentProperties` — the outbound base URLs (`nutritionist-agent.*`).
- `config/OutboundHttpConfig` — one `clone()`d `WebClient` per dependency; the
  `profile/notifier/memory` qualified beans back the shared runtime clients.
- `chat/NutritionistChat` — the chat fallback (one LLM turn, AGENT.md as system prompt).
- `foodlog/FoodLogger` — the food-log flow: photo → caption / typed → LLM extract, both via the
  `meal-logger` SKILL, write-immediately to `/internal/meal` (attributed to the sender).
- `profile/DietProfiler` — the diet-profile flow: typed goals/restrictions → LLM extract via the
  `diet-profiler` SKILL → upsert via `/internal/diet-profile` (self or household-default).
- `analysis/NutritionAnalyst` — the nutrition-analysis flow: gather recent meals + diet profile on
  the shared `Coordinator` → one LLM synthesis via the `nutrition-analyst` SKILL → render HTML via
  the shared `libs/doc-render` → store in media-service → reply with a link.
- `flow/MealPlanner` — the ration + shopping-list flow: gather the sender + household diet profiles
  + recent meals + (when a store is named) its availability via `mcp-web` on the shared `Coordinator`
  → one LLM synthesis via the `meal-planner` SKILL → render an HTML board via `libs/doc-render` →
  store in media-service → reply with a link.
- `basket/BasketBreakdown` — the basket-breakdown flow: a basket photo (caption) / typed list (LLM)
  → one extraction+breakdown pass via the `basket-analyst` SKILL (diet profile folded in) → save via
  `/internal/basket` → render a good/watch/cut verdict board via `libs/doc-render` → store → link.
  `breakdownFromEvent` (IA-b) is the bus-fan-out variant: given the line items finance already
  extracted, it runs the breakdown and **notifies the household** instead of replying.
- `web/InternalBasketEventController` — `POST /internal/basket-event`, the IA-b consume entry.
- `config/RenderConfig` — the shared `DocRenderer` bean (lib default `DocTheme`).
- `http/CaptionClient` — `POST /internal/caption` on mcp-media-processing (vision).
- `http/MealClient` — `POST /internal/meal` on mcp-nutrition (write meal).
- `http/MealReadClient` — `GET /internal/meals` on mcp-nutrition (read recent meals).
- `http/WebSearchClient` — `POST /internal/search` on mcp-web (store-availability lookup for the ration flow).
- `http/FoodDataClient` — `POST /internal/food-lookup` on mcp-food-data (precise per-100g КБЖУ for the basket breakdown, FD-c).
- `http/OrchestratorInvokeClient` — `POST /v1/agents/invoke` on the orchestrator (NU-g → chef recipes).
- `http/BasketClient` — `POST /internal/basket` on mcp-nutrition (save analysed basket).
- `http/DietProfileClient` — `POST` (upsert) + `GET` (read, 404→empty) `/internal/diet-profile` on mcp-nutrition.
- `http/MediaStoreClient` — multipart `POST /v1/media` on media-service (store the rendered HTML).
- `web/IntentController` — `POST /intent` (basket-cue photo → basket; photo → food-log; profile cue →
  diet-profiler; analysis cue → nutrition-analysis; ration cue → ration; basket cue → basket;
  food-log cue → food-log; else chat).
- `web/ManifestController` — `GET /manifest`.

## Skills

- `meal-logger` (`domains/nutrition/skills/meal-logger/SKILL.md`) — strict-JSON meal extraction
  (description, items, best-effort КБЖУ), shared by both the photo (caption instruction) and typed
  (LLM system prompt) paths.
- `diet-profiler` (`domains/nutrition/skills/diet-profiler/SKILL.md`) — strict-JSON diet-profile
  extraction (scope self|household, macro goals, restrictions, tastes) from a typed message.
- `nutrition-analyst` (`domains/nutrition/skills/nutrition-analyst/SKILL.md`) — synthesises a
  nutrition analysis (intake vs goals, deficits/excesses, recommendations) from the gathered recent
  meals + diet profile, as readable text the agent renders into an HTML report.
- `basket-analyst` (`domains/nutrition/skills/basket-analyst/SKILL.md`) — strict-JSON basket
  extraction (line items + best-effort КБЖУ totals) + a good/watch/cut breakdown against the diet
  profile, shared by the photo (caption instruction) and typed-list (LLM system prompt) paths.
- `meal-planner` (`domains/nutrition/skills/meal-planner/SKILL.md`) — synthesises a multi-person
  ration + a grouped shopping list from the gathered diet profiles, recent meals, and (optional)
  store availability, handling ad-hoc people (wife / infant on прикорм) with a pediatrician caveat.

## AGENT.md

`name: nutritionist`, binds `mcp-nutrition` + `mcp-media-processing` + `mcp-web` + `mcp-food-data`,
`skills: meal-logger, diet-profiler, nutrition-analyst, basket-analyst, meal-planner`. ⚠️ Carries the
infant/medical-safety rule (general guidance only + pediatrician caveat).
