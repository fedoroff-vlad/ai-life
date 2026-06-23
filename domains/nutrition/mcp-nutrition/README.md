# mcp-nutrition

MCP server: source-of-truth nutrition CRUD over the `nutrition.*` schema (logged meals +
per-person diet profiles + analysed grocery baskets). The food-log / "analyse me" / basket-
breakdown / ration flows live in `nutritionist-agent` (and `chef-agent`); this MCP just
persists what the agents extract. See [plans/nutrition.md](../../../plans/nutrition.md).

## Tools (MCP)

- `log_meal(householdId, ownerId?, eatenAt?, source?, description, items?, kcal?, proteinG?,
  fatG?, carbsG?, imageMediaId?)` — log a meal. Only `householdId` + `description` are required;
  `eatenAt` defaults to now, `source` (photo|text) to `text`. `items` is the free-form parsed
  breakdown; macros are best-effort estimates. `ownerId` null = household-shared.
- `list_meals(householdId, ownerId?, limit?)` — meals in a household, most recently eaten first;
  `ownerId` scopes to one person, `limit` caps the count (default 20, max 200).
- `delete_meal(id)` — delete and return the deleted row (so the agent can confirm/undo). Throws
  on unknown id; confirming the destructive action is the agent layer's job.
- `set_diet_profile(householdId, ownerId?, goalKcal?, goalProteinG?, goalFatG?, goalCarbsG?,
  restrictions?, tastes?, notes?)` — upsert the diet profile, keyed on (householdId, ownerId);
  null ownerId = household-default. Full set (every field overwrites). `restrictions` (allergies /
  halal / vegan / infant-stage / …) and `tastes` are free-form JSON. Holds the wife/infant profiles.
- `get_diet_profile(householdId, ownerId?)` — the person's profile, or null if unset.
- `save_basket(householdId, ownerId?, capturedAt?, merchant?, source?, receiptMediaId?, items?,
  kcal?, proteinG?, fatG?, carbsG?, analysis?)` — save an analysed grocery basket. Only
  `householdId` is required; `capturedAt` defaults to now, `source` (receipt|manual) to `manual`.
  `items` are the parsed line items (`BasketItem`s), the macro fields are basket totals, `analysis`
  the optional breakdown.
- `list_baskets(householdId, limit?)` — baskets in a household, most recently captured first.
- `get_basket(id)` — one basket by id, or null.

Scope rule: every tool takes a `householdId` and reads/writes only within that household
(mirrors mcp-wardrobe / mcp-tasks). Per-person attribution is the optional `ownerId`.

## Internal REST passthroughs

Non-MCP, no LLM tax — for an agent that already has a concrete input and just needs to persist it
(the MCP/SSE transport can't be MockWebServer'd, so deterministic calls use HTTP).

- `POST /internal/meal` (body `LogMealInput`) → `MealLogDto` | 400 — logs a meal, delegating to the
  `log_meal` tool (required-field guards apply). Used by nutritionist-agent's food-log flow (NU-c).
  Mirrors mcp-wardrobe's `/internal/item`.
- `POST /internal/diet-profile` (body `SetDietProfileInput`) → `DietProfileDto` | 400 — upserts a
  person's diet profile via `set_diet_profile` ((household,owner) keying applies). Used by the
  diet-profiler flow (NU-d).
- `GET /internal/diet-profile?householdId=&ownerId=` → `DietProfileDto` | 404 — reads the person's
  profile (null ownerId = household-default); 404 when unset. Used by the analysis/ration gathers (NU-e/g).
- `GET /internal/meals?householdId=&ownerId=&limit=` → `List<MealLogDto>` — recent meals, newest-eaten
  first, via `list_meals` (same `ownerId` scope + `limit`). Used by the nutrition-analysis gather (NU-e).
- `POST /internal/basket` (body `SaveBasketInput`) → `BasketDto` | 400 — saves an analysed grocery
  basket via `save_basket` (required-field guard applies). Used by the basket-breakdown flow (NU-f).

## Bus consumer (IA-b)

mcp-nutrition consumes the **`basket.captured`** event off `bus.outbox` (finance is the producer,
IA-a) and forwards it to nutritionist-agent's `POST /internal/basket-event`, where the breakdown
runs. Agents stay DB-less, so the bus listener lives here in the domain-MCP (owner-chosen
2026-06-23). Retry policy mirrors notifier/memory consumers: a transient forward failure (agent
5xx / timeout / down) throws → the row stays PENDING and is retried; a foreign topic / bad payload
is accepted (marked PUBLISHED), not retried.

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_NUTRITION_PORT` | `8104` | HTTP port |
| `MCP_NUTRITION_DB_URL` | `jdbc:postgresql://localhost:5432/ailife` | Postgres |
| `MCP_NUTRITION_DB_USER` / `MCP_NUTRITION_DB_PASSWORD` | `ailife` | DB credentials |
| `NUTRITIONIST_AGENT_URL` | `http://nutritionist-agent:8105` | where the basket.captured consumer forwards (IA-b) |

## Key classes

- `McpNutritionApplication`.
- `domain/MealLog` + `MealLogRepository` — JPA over `nutrition.meal_log` (list by household,
  optionally by owner, newest-eaten first; `Pageable` caps the count). `items` jsonb → `JsonNode`.
- `domain/DietProfile` + `DietProfileRepository` — JPA over `nutrition.diet_profile`; `findForOwner`
  resolves the (household, owner) profile (null owner = household-default, native-SQL `CAST` for the
  NULL-safe bind — same pgjdbc workaround as mcp-wardrobe). `restrictions`/`tastes` jsonb → `JsonNode`.
- `domain/Basket` + `BasketRepository` — JPA over `nutrition.basket` (list by household,
  newest-captured first). `items`/`analysis` jsonb → `JsonNode`; the tool converts `items` to/from
  the contract's `List<BasketItem>` via the shared `ObjectMapper`.
- `tools/NutritionMcpTools` — eight `@Tool` methods: meal log (`log_meal`, `list_meals`,
  `delete_meal`), diet profile (`set_diet_profile`, `get_diet_profile`), basket (`save_basket`,
  `list_baskets`, `get_basket`). The only invariants enforced here are the household scope and
  required-field checks; everything else relies on DB constraints.
- `tools/ToolsConfig` — `MethodToolCallbackProvider`.
- `web/InternalMealController` — `POST /internal/meal`, delegates to `log_meal` (400 on bad input).
- `web/InternalMealsController` — `GET /internal/meals`, delegates to `list_meals` (ownerId scope + limit).
- `web/InternalBasketController` — `POST /internal/basket`, delegates to `save_basket` (400 on bad input).
- `bus/BasketCapturedHandler` + `config/EventBusListenerConfig` — the IA-b bus consumer: drains
  `basket.captured` and forwards it to nutritionist-agent's `/internal/basket-event` (throw-on-transient
  retry). `config/HttpConfig` (`nutritionistAgentWebClient`) + `config/McpNutritionProperties`
  (`mcp-nutrition.nutritionist-agent-url`). `McpNutritionApplication` `@Import(EventBusConfig)`.
- `web/InternalDietProfileController` — `POST /internal/diet-profile` (set, 400 on bad input) +
  `GET /internal/diet-profile` (read, 404 when unset), over `set_diet_profile` / `get_diet_profile`.

## Schema

- [050-nutrition.yml](../../../infra/liquibase/features/050-nutrition.yml) —
  `nutrition.meal_log` (one row per meal: description + source + `items`/macros + `image_media_id`)
  + `nutrition.diet_profile` (one per person via the unique `(household_id, owner_id)` index: macro
  goals + `restrictions`/`tastes`) + `nutrition.basket` (one analysed basket: merchant + source +
  `items`/totals + `analysis` + `receipt_media_id`). `image_media_id` / `receipt_media_id` carry no
  cross-schema FK (media-service owns blob lifecycle), mirroring `wardrobe_item.image_media_id`.
