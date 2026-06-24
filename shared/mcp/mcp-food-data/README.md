# mcp-food-data

Shared **food-data capability-MCP** (`shared/mcp/`, no schema). Read-only nutrition facts (КБЖУ per
100 g) any agent can reuse — the nutrition basket/log analysis now, a fitness/health line later. A
**sibling** of `mcp-market-data` (structured numeric reference reads). Bound by agents over MCP/SSE;
it owns no data. Plan: [nutrition.md](../../../plans/nutrition.md) §FD-a.

**Reference data only.** `food_lookup` is a read-only facts source — there is no write tool. Deciding
anything (a basket's КБЖУ, deficits vs a diet profile) is the calling agent's skill.

**Status (FD-a, scaffold):** `food_lookup` reads nutrition facts from **Open Food Facts** (free, no
API key, no quota) behind a swappable `FoodDataSource` (`fooddata.source=openfoodfacts` default) — so
USDA / a keyed provider can replace it later with no caller change. No backing container (Open Food
Facts is public HTTPS, like Stooq). **Not yet bound** — the nutrition agents call LLM macro estimates
today; binding `food_lookup` for precise macros is the follow-up (mirror MD-c).

## Port: `8107` (`MCP_FOOD_DATA_PORT`)

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `food_lookup` | `query` (a numeric barcode — precise, or a product name — best-effort first hit) | `FoodFacts{query, name?, brand?, barcode?, quantity?, nutriScore?, kcal100g?, protein100g?, fat100g?, carbs100g?}` | read nutrition facts (per 100 g) for one product from Open Food Facts. `name` (and macros) null when the source has no product. Read-only reference data. |

## HTTP passthrough

| method | path | body | returns | purpose |
|--------|------|------|---------|---------|
| POST | `/internal/food-lookup` | `FoodLookupInput{query}` | `FoodFacts` | non-MCP passthrough to `food_lookup`. The MockWebServer-testable, deterministic path an agent calls (MCP/SSE can't be mocked). Delegates straight to the tool. |

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_FOOD_DATA_PORT` | `8107` | HTTP port (MCP/SSE + actuator). |
| `FOODDATA_OPEN_FOOD_FACTS_URL` | `https://world.openfoodfacts.org` | Open Food Facts base URL — `/api/v2/product/{barcode}.json` + `/cgi/search.pl`. |
| `FOODDATA_SOURCE` | `openfoodfacts` | Which `FoodDataSource` to wire. Swappable later (usda/keyed) via env. |

No DB / no Liquibase feature (capability-MCP). No backing container (Open Food Facts is a public
HTTPS endpoint). Binding side: an agent adds a `spring.ai.mcp.client.sse.connections.mcp-food-data`
block + `MCP_FOOD_DATA_URL` (happens when a flow binds it).

## Key classes

- `McpFoodDataApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `config/McpFoodDataProperties` — `fooddata.{open-food-facts-url, source}`.
- `config/HttpConfig` — `openFoodFactsWebClient` bean (Open Food Facts base URL + the API-policy
  `User-Agent`).
- `engine/FoodDataSource` — pluggable food-facts backend interface (read-only; mirrors
  `mcp-market-data`'s `MarketDataSource`).
- `engine/OpenFoodFactsDataSource` — default (`fooddata.source=openfoodfacts`); a numeric query →
  `GET /api/v2/product/{barcode}.json`, a text query → `GET /cgi/search.pl?...&json=1&page_size=1`
  (first hit). Parses `nutriments` per-100g macros → `FoodFacts`. Missing product/fields → null
  (no data, not an error).
- `tools/FoodDataMcpTools` — `food_lookup(query)` `@Tool` (blocking per the MCP convention) →
  `FoodDataSource.lookup` → `FoodFacts`.
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the `@Tool`.
- `web/InternalFoodLookupController` — `POST /internal/food-lookup` passthrough (delegates on
  `Schedulers.boundedElastic()`).
