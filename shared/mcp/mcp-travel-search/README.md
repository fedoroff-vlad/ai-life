# mcp-travel-search

Shared **travel-search capability-MCP** (`shared/mcp/`, no schema). Read-only **live flight/hotel search**
over **Travelpayouts** (Aviasales flights + Hotellook hotels) — the travel-agent's live-pricing source
([ADR-0003](../../../plans/adr/ADR-0003-travel-data-source.md) §3, travel [§TR-f](../../../plans/travel.md)).
A **sibling** of `mcp-market-data` / `mcp-web` (structured external reads behind a swappable source). Bound
by agents over MCP/SSE; it owns no data. Plan: [travel.md](../../../plans/travel.md) §TR-f.

**Options + links only — never books or pays.** Each offer carries a provider **deep link** (with the
affiliate marker) the owner opens on the provider's own checkout; there is no book/pay tool and never will
be (ADR-0003, a permanent boundary).

**Owner-key-gated → degrades with no key.** The affiliate `TRAVELPAYOUTS_TOKEN` + `TRAVELPAYOUTS_MARKER`
are env-only, **never committed**. With **no token** wired every tool returns `unconfigured=true` + empty
(never a 500), so CI/tests stay green with no key and callers (travel-agent) degrade to the planner MVP.
A configured-but-down/empty upstream **soft-fails** to an empty (configured) result.

**Status (TR-f1):** the capability-MCP + its three tools, behind a swappable `TravelSearchSource`
(`travelsearch.source=travelpayouts` default) so `mcp-browser` can replace it later with no caller change.
Travelpayouts field-mapping is modeled from the public API shapes; the live parsing is validated once the
owner wires a real key (the no-key / degrade / soft-fail paths are fully covered without one). Wired into
travel-agent's `trip-planner` in **TR-f2** (see §who-uses-me). Tours + no-API/JS sources → `mcp-browser`
(**TR-f3**, deferred).

## §who-uses-me

- **travel-agent** (`trip-planner`, TR-f2) — binds it (SSE + the HTTP `/internal/*` passthroughs via
  `TravelSearchClient`) for live options when the owner asks for concrete tickets/hotels. The planner
  resolves places → searches flights/hotels → ranks min-transfers→price → folds ranked options + deep
  links into the synthesis + HTML board; `unconfigured` → degrades to the planner MVP.

## Port: `8125` (`MCP_TRAVEL_SEARCH_PORT`)

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `resolve_place` | `query` (place name, e.g. `Antalya`) | `PlaceResult{query, name?, iataCity?, hotelLocationId?, unconfigured}` | place name → the codes the searches need. |
| `search_flights` | `origin`, `destination` (IATA city), `departDate` (`yyyy-MM-dd`/`yyyy-MM`), `returnDate?`, `adults?` | `FlightSearchResult{unconfigured, offers:[FlightOffer{price?, currency?, transfers?, airline?, departDate?, returnDate?, deepLink?}]}` | real flight offers, cheapest-first, each with a buy link. |
| `search_hotels` | `location`, `checkIn`, `checkOut` (`yyyy-MM-dd`), `guests?` | `HotelSearchResult{unconfigured, offers:[HotelOffer{name?, price?, currency?, stars?, deepLink?}]}` | real hotel offers, each with a buy link. |

All read-only — no order/book/pay tool by design. `unconfigured=true` when no key is wired.

## HTTP passthrough

| method | path | body | returns | purpose |
|--------|------|------|---------|---------|
| POST | `/internal/resolve-place` | `PlaceQueryInput{query}` | `PlaceResult` | non-MCP passthrough to `resolve_place`. |
| POST | `/internal/search-flights` | `FlightSearchInput{origin, destination, departDate, returnDate?, adults?}` | `FlightSearchResult` | non-MCP passthrough to `search_flights`. |
| POST | `/internal/search-hotels` | `HotelSearchInput{location, checkIn, checkOut, guests?}` | `HotelSearchResult` | non-MCP passthrough to `search_hotels`. |

The deterministic, MockWebServer-testable path an agent calls (MCP/SSE can't be mocked).

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_TRAVEL_SEARCH_PORT` | `8125` | HTTP port (MCP/SSE + actuator). |
| `TRAVEL_SEARCH_SOURCE` | `travelpayouts` | Which `TravelSearchSource` to wire. Swappable later (`mcp-browser`) via env. |
| `TRAVELPAYOUTS_TOKEN` | _(empty)_ | Affiliate API token — **env only, never committed**. Blank → capability degrades. |
| `TRAVELPAYOUTS_MARKER` | _(empty)_ | Affiliate marker appended to every buy deep link. |
| `TRAVEL_SEARCH_CURRENCY` | `rub` | Result currency passed to the provider. |
| `TRAVEL_SEARCH_MAX_RESULTS` | `10` | Cap on offers per search. |
| `TRAVELPAYOUTS_AVIASALES_API_URL` | `https://api.travelpayouts.com` | Aviasales flight-prices data API. |
| `TRAVELPAYOUTS_AVIASALES_SITE_URL` | `https://www.aviasales.com` | Aviasales site host (flight deep-link base). |
| `TRAVELPAYOUTS_HOTELLOOK_API_URL` | `https://engine.hotellook.com` | Hotellook cache-prices data API. |
| `TRAVELPAYOUTS_HOTELLOOK_SITE_URL` | `https://search.hotellook.com` | Hotellook site host (hotel deep-link base). |
| `TRAVELPAYOUTS_AUTOCOMPLETE_URL` | `https://autocomplete.travelpayouts.com` | Travelpayouts place autocomplete. |

No DB / no Liquibase feature (capability-MCP). No backing container (Travelpayouts is public HTTPS).
Binding side (TR-f2): an agent adds a `spring.ai.mcp.client.sse.connections.mcp-travel-search` block +
`MCP_TRAVEL_SEARCH_URL`.

## Key classes

- `McpTravelSearchApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `config/McpTravelSearchProperties` — `travelsearch.{source, token, marker, currency, max-results, *-url}`;
  `isConfigured()` = token present.
- `config/HttpConfig` — `aviasales`/`hotellook`/`autocomplete` `WebClient` beans (different hosts).
- `engine/TravelSearchSource` — pluggable search backend interface (read-only; mirrors `mcp-market-data`'s
  `MarketDataSource`). No book/pay method by design.
- `engine/TravelpayoutsTravelSearchSource` — default (`travelsearch.source=travelpayouts`): flights over
  Aviasales `prices_for_dates`, hotels over Hotellook `cache.json`, places over autocomplete `places2`.
  No token → `unconfigured`; upstream failure → empty (configured) result. Builds affiliate deep links.
- `tools/TravelSearchMcpTools` — `resolve_place` / `search_flights` / `search_hotels` `@Tool`s (blocking
  per the MCP convention) → the source.
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the three tools.
- `web/InternalTravelSearchController` — the three `POST /internal/*` passthroughs (reactive, delegate to
  the source).
