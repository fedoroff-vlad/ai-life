# mcp-travel

Travel domain-MCP (port **8123**). Source-of-truth CRUD over the `travel.*` schema — the **per-person
travel preferences** that personalize the on-demand trip planner. Owns `travel_profile` (home base +
rest types + companions + budget hint). The gather → synthesize trip-planning flow lives in
`travel-agent`; this MCP just persists. Mirrors `mcp-briefing`. Plan:
[plans/travel.md](../../../plans/travel.md).

## Status (TR-b)

The personalization store: `travel_profile` keyed `(household_id, owner_id)` (null owner =
household-default) + the `set`/`get` tools + the `/internal/travel-profile` upsert/resolve
passthrough. No trip persistence yet — MVP plans are stateless deliverables. **To be bound by
`travel-agent`** (TR-c profiler write). Vocabulary enforcement for `rest_types`/`companions` lives in
the profiler, not this low-level store.

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `setTravelProfile` | `SetTravelProfileInput` | `TravelProfileDto` | upsert a person's prefs, keyed `(householdId, ownerId)`. Full set — every field overwrites. `homeBaseLatitude`/`homeBaseLongitude` are the geocoded coords of `homeBaseLabel`; `restTypes` (beach\|active\|family\|couple\|city\|ski\|wellness) and `childAges` are JSON arrays; `companions` is solo\|couple\|family; `budgetAmount`/`budgetCurrency` are a soft budget hint. |
| `getTravelProfile` | `householdId`, `ownerId?` | `TravelProfileDto` \| null | read a person's prefs (null ownerId = household-default). null when unset. |

## HTTP passthrough

| method | path | body / params | returns | purpose |
|--------|------|---------------|---------|---------|
| POST | `/internal/travel-profile` | `SetTravelProfileInput` | `TravelProfileDto` (400 on missing `householdId`) | deterministic upsert (the travel-profiler flow). |
| GET | `/internal/travel-profile` | `householdId`, `ownerId?` | `TravelProfileDto` (204 if unset) | read a person's prefs; 204 lets the caller fall back to the household default. |

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_TRAVEL_PORT` | `8123` | HTTP port (MCP/SSE + actuator). |
| `MCP_TRAVEL_DB_URL` | `jdbc:postgresql://localhost:5432/ailife` | Postgres (`travel` schema). |
| `MCP_TRAVEL_DB_USER` / `MCP_TRAVEL_DB_PASSWORD` | `ailife` / `ailife` | DB credentials. |

Schema owned: `travel` (`travel_profile`). Migration:
[`110-travel.yml`](../../../infra/liquibase/features/110-travel.yml). `travel` schema created in
[`infra/postgres/init.sql`](../../../infra/postgres/init.sql).

## Key classes

- `McpTravelApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `domain/TravelProfile` (+ `Repository`) — the `travel_profile` entity; `findForOwner` (null-owner
  CAST workaround).
- `tools/TravelMcpTools` — `set`/`get` `@Tool`s; `(household, owner)` upsert keying.
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the `@Tool`s.
- `web/InternalTravelProfileController` — `POST`/`GET /internal/travel-profile` (204 on unseen owner).
