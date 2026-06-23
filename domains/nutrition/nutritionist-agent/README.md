# nutritionist-agent

Nutrition domain agent (port **8105**). Logs meals, breaks grocery baskets down (КБЖУ +
good/watch/cut), keeps per-person diet profiles, and (over the coming slices) plans rations +
shopping lists. Owns the `mcp-nutrition` domain-MCP; binds the shared `mcp-media-processing`
(vision caption for meal/receipt photos) + `mcp-web` (store/availability lookup). Routes via the
orchestrator (registered as `nutritionist`). See [plans/nutrition.md](../../../plans/nutrition.md).

## Status (through NU-f)

Manifest endpoint + chat fallback (NU-b) + the **food-log flow** (NU-c) + **diet profiles** (NU-d) +
the **nutrition-analysis board** (NU-e) + the **basket breakdown** (NU-f, direct). Remaining flows
replace the fallback branch-by-branch:
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
- **IA-b** — basket breakdown auto-triggered off the `basket.captured` bus event (the case-1 fan-out).
- **NU-g** — ration + shopping list (multi-person `Coordinator` flow; invokes `chef-agent`).

## Endpoints

- `POST /agents/nutritionist/intent` (body `NormalizedMessage`) → `IntentResponse` — the
  orchestrator's entry point. A photo with a basket cue → basket breakdown; any other photo →
  food-log; a profile cue → diet-profiler; an analysis cue → nutrition-analysis; a basket cue →
  basket breakdown; a food-log cue → food-log; else chat.
- `GET /agents/nutritionist/manifest` → `AgentManifest` — scraped by the orchestrator on startup.

## Env

| Var | Default | Purpose |
|---|---|---|
| `NUTRITIONIST_AGENT_PORT` | `8105` | HTTP port |
| `MCP_NUTRITION_URL` | `http://mcp-nutrition:8104` | nutrition domain-MCP (its data) |
| `MCP_MEDIA_PROCESSING_URL` | `http://mcp-media-processing:8097` | shared vision capability |
| `MCP_WEB_URL` | `http://mcp-web:8098` | shared web/store-lookup capability |
| `MEDIA_SERVICE_URL` | `http://media-service:8088` | stores the rendered HTML deliverables |
| `NUTRITIONIST_PUBLIC_MEDIA_BASE_URL` | `http://media-service:8088` | public base for the deliverable link (`<base>/v1/media/{id}`) |
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
- `basket/BasketBreakdown` — the basket-breakdown flow: a basket photo (caption) / typed list (LLM)
  → one extraction+breakdown pass via the `basket-analyst` SKILL (diet profile folded in) → save via
  `/internal/basket` → render a good/watch/cut verdict board via `libs/doc-render` → store → link.
- `config/RenderConfig` — the shared `DocRenderer` bean (lib default `DocTheme`).
- `http/CaptionClient` — `POST /internal/caption` on mcp-media-processing (vision).
- `http/MealClient` — `POST /internal/meal` on mcp-nutrition (write meal).
- `http/MealReadClient` — `GET /internal/meals` on mcp-nutrition (read recent meals).
- `http/BasketClient` — `POST /internal/basket` on mcp-nutrition (save analysed basket).
- `http/DietProfileClient` — `POST` (upsert) + `GET` (read, 404→empty) `/internal/diet-profile` on mcp-nutrition.
- `http/MediaStoreClient` — multipart `POST /v1/media` on media-service (store the rendered HTML).
- `web/IntentController` — `POST /intent` (basket-cue photo → basket; photo → food-log; profile cue →
  diet-profiler; analysis cue → nutrition-analysis; basket cue → basket; food-log cue → food-log; else chat).
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

## AGENT.md

`name: nutritionist`, binds `mcp-nutrition` + `mcp-media-processing` + `mcp-web`, `skills:
meal-logger, diet-profiler, nutrition-analyst, basket-analyst`. ⚠️ Carries the infant/medical-safety
rule (general guidance only + pediatrician caveat).
