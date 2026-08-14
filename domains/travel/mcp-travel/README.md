# mcp-travel

Travel domain-MCP (port **8123**). Source-of-truth CRUD over the `travel.*` schema. Three stores: the
**per-person travel preferences** (`travel_profile`) that personalize the on-demand trip planner, the
**multi-currency family trip wallet** (`trip` + ledger tables, #437), and the **route/itinerary import**
(`route`, #436). The gather → synthesize trip-planning flow lives in `travel-agent`; this MCP just
persists. Mirrors `mcp-briefing`. Plan: [plans/travel.md](../../../plans/travel.md).

## Status (TR-b + EX-a + RT-a)

- **TR-b** — the personalization store: `travel_profile` keyed `(household_id, owner_id)` (null owner =
  household-default) + `set`/`get` tools + `/internal/travel-profile`. Vocabulary enforcement for
  `rest_types`/`companions` lives in the profiler, not this low-level store.
- **EX-a** — the **trip wallet store**: `trip` + `trip_member`/`trip_funding`/`trip_exchange`/`trip_expense`
  ledger tables + the `createTrip`/roster/funding/exchange/expense/read tools + `/internal/trips/*`.
  Persistence only — the per-currency balance and the ₽ tally are deterministic Java in `travel-agent`
  (EX-b), not here. On-site currency purchase is a first-class **exchange** (source outflow + acquired
  inflow) so the ₽ tally never double-counts; **no `paid_by`** on expenses (settlement is cut).
- **RT-a** — the **route/itinerary import store**: a `route` table (geometry as jsonb) +
  `importRoute`/`getRoute`/`listRoutes` tools + `/internal/routes`. Parses owner-supplied **GPX**/**GeoJSON**
  into a normalized geometry (track polyline + named waypoints) — **zero-dependency** (GeoJSON via Jackson,
  GPX via JDK StAX, XXE-hardened). A new format = one new `RouteParser` bean (KML/KMZ = RT-b). Point count +
  haversine track distance are derived at import. Parses owner-supplied bytes only — it never fetches.

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `setTravelProfile` | `SetTravelProfileInput` | `TravelProfileDto` | upsert a person's prefs, keyed `(householdId, ownerId)`. Full set — every field overwrites. `homeBaseLatitude`/`homeBaseLongitude` are the geocoded coords of `homeBaseLabel`; `restTypes` (beach\|active\|family\|couple\|city\|ski\|wellness) and `childAges` are JSON arrays; `companions` is solo\|couple\|family; `budgetAmount`/`budgetCurrency` are a soft budget hint. |
| `getTravelProfile` | `householdId`, `ownerId?` | `TravelProfileDto` \| null | read a person's prefs (null ownerId = household-default). null when unset. |
| `createTrip` | `CreateTripInput` | `TripDto` | create a family trip. `householdId`+`title` required; `homeCurrency` defaults RUB; starts `planning`. |
| `addTripMember` | `AddTripMemberInput` | `TripMemberDto` | add a roster participant: at most one of `userId`/`personId` + a required `label`. No role/share/settlement. |
| `removeTripMember` | `memberId` | `boolean` | remove a roster member; false if none existed. |
| `addFunding` | `AddFundingInput` | `TripFundingDto` | record a currency brought from home (inflow); optional owner-stated `rateToHome`. |
| `logExchange` | `LogExchangeInput` | `TripExchangeDto` | on-site swap: outflow of `fromCurrency` + inflow of `toCurrency` (currencies must differ). |
| `logExpense` | `LogExpenseInput` | `TripExpenseDto` | log a spend (outflow). No paid-by. |
| `getTrip` | `tripId`, `householdId` | `TripDto` \| null | tenant-scoped trip header; null if absent/out-of-tenant. |
| `getActiveTrip` | `householdId` | `TripDto` \| null | the household's most recent non-`closed` trip (the wallet flow's "current trip"); null if none open. |
| `closeTrip` | `tripId`, `householdId` | `TripDto` \| null | set `status='closed'` (tenant-scoped, idempotent) so it drops out of `getActiveTrip`; null if absent/out-of-tenant. The travel-agent's close-flow (EX-c) uses it before surfacing the trip's ₽ spend to finance. |
| `getTripLedger` | `tripId`, `householdId` | `TripLedgerDto` \| null | full wallet: header + roster + funding/exchange/expense rows (raw; no balance math). |
| `importRoute` | `ImportRouteInput` | `RouteDto` | parse a GPX/GeoJSON file into a normalized geometry and store it (optionally attached to a `tripId` in the same household). `householdId`/`format`/`content` required; empty (no points) rejected; unsupported format rejected. Derives point count + haversine distance. |
| `getRoute` | `routeId`, `householdId` | `RouteDto` \| null | tenant-scoped route read; null if absent/out-of-tenant. |
| `listRoutes` | `householdId`, `tripId?` | `RouteDto[]` | household's routes newest-first; filtered to `tripId` when given. |

## HTTP passthrough

| method | path | body / params | returns | purpose |
|--------|------|---------------|---------|---------|
| POST | `/internal/travel-profile` | `SetTravelProfileInput` | `TravelProfileDto` (400 on missing `householdId`) | deterministic upsert (the travel-profiler flow). |
| GET | `/internal/travel-profile` | `householdId`, `ownerId?` | `TravelProfileDto` (204 if unset) | read a person's prefs; 204 lets the caller fall back to the household default. |
| POST | `/internal/trips` | `CreateTripInput` | `TripDto` (400 on missing field) | create a trip. |
| POST | `/internal/trips/members` | `AddTripMemberInput` | `TripMemberDto` | add a roster member. |
| DELETE | `/internal/trips/members/{memberId}` | — | 204 removed / 404 absent | remove a roster member. |
| POST | `/internal/trips/fundings` | `AddFundingInput` | `TripFundingDto` | record a funding inflow. |
| POST | `/internal/trips/exchanges` | `LogExchangeInput` | `TripExchangeDto` | log an on-site swap. |
| POST | `/internal/trips/expenses` | `LogExpenseInput` | `TripExpenseDto` | log a spend. |
| GET | `/internal/trips/active` | `householdId` | `TripDto` (204 if none open) | the household's active (most recent non-closed) trip. |
| POST | `/internal/trips/{tripId}/close` | `householdId` | `TripDto` (204 if absent/out-of-tenant) | close a trip (idempotent); it drops out of `/active`. |
| GET | `/internal/trips/{tripId}` | `householdId` | `TripDto` (204 if absent/out-of-tenant) | read the trip header. |
| GET | `/internal/trips/{tripId}/ledger` | `householdId` | `TripLedgerDto` (204 if absent/out-of-tenant) | read the full wallet. |
| POST | `/internal/routes` | `ImportRouteInput` | `RouteDto` (400 on bad input/format/empty) | import a route file. |
| GET | `/internal/routes/{routeId}` | `householdId` | `RouteDto` (204 if absent/out-of-tenant) | read an imported route. |
| GET | `/internal/routes` | `householdId`, `tripId?` | `RouteDto[]` | list a household's routes (filtered to `tripId` when given). |

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_TRAVEL_PORT` | `8123` | HTTP port (MCP/SSE + actuator). |
| `MCP_TRAVEL_DB_URL` | `jdbc:postgresql://localhost:5432/ailife` | Postgres (`travel` schema). |
| `MCP_TRAVEL_DB_USER` / `MCP_TRAVEL_DB_PASSWORD` | `ailife` / `ailife` | DB credentials. |

Schema owned: `travel` (`travel_profile`; `trip` + `trip_member`/`trip_funding`/`trip_exchange`/
`trip_expense`; `route`). Migrations:
[`110-travel.yml`](../../../infra/liquibase/features/110-travel.yml) (profile),
[`111-travel-trip.yml`](../../../infra/liquibase/features/111-travel-trip.yml) (trip wallet),
[`112-travel-route.yml`](../../../infra/liquibase/features/112-travel-route.yml) (route import). `travel`
schema created in [`infra/postgres/init.sql`](../../../infra/postgres/init.sql).

## Key classes

- `McpTravelApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `domain/TravelProfile` (+ `Repository`) — the `travel_profile` entity; `findForOwner` (null-owner
  CAST workaround).
- `domain/Trip`, `TripMember`, `TripFunding`, `TripExchange`, `TripExpense` (+ their `Repository`) — the
  trip-wallet entities; `TripRepository.findByIdAndHouseholdId` is the tenant-scoped read.
- `tools/TravelMcpTools` — profile `set`/`get` `@Tool`s; `(household, owner)` upsert keying.
- `tools/TripMcpTools` — trip-wallet `@Tool`s (create/roster/funding/exchange/expense/read/close); currency
  normalization + at-most-one-identity + non-negative-amount guards. Persistence only, no balance math.
- `tools/RouteMcpTools` — route-import `@Tool`s (`importRoute`/`getRoute`/`listRoutes`); parses via
  `RouteImporter`, rejects empty/unsupported, tenant-checks a supplied `tripId`, stores geometry as jsonb.
- `tools/ToolsConfig` — `MethodToolCallbackProvider` bean exposing all three tool objects.
- `domain/Route` (+ `Repository`) — the `route` entity (geometry jsonb); household-scoped reads + trip filter.
- `parse/` — the format-independent parser SPI: `RouteParser` (iface) + `GpxRouteParser` (JDK StAX,
  XXE-hardened) + `GeoJsonRouteParser` (Jackson) + `RouteImporter` (format dispatch + haversine distance);
  `RouteGeometry`/`RoutePoint`/`ParsedRoute` are the normalized value types.
- `web/InternalTravelProfileController` — `POST`/`GET /internal/travel-profile` (204 on unseen owner).
- `web/InternalTripController` — `/internal/trips/*` passthroughs (400 on bad input, 204 on out-of-tenant read).
- `web/InternalRouteController` — `/internal/routes*` passthroughs (400 on bad input/format, 204 on out-of-tenant read).
