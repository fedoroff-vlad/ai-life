# mcp-weather

Shared **weather capability-MCP** (`shared/mcp/`, no schema). Read-only today's forecast any agent
can reuse — the **briefing-agent** morning digest first. A sibling of `mcp-market-data` (structured
numeric reads, not web search). Bound by agents over MCP/SSE; it owns no data. Plan:
[briefing.md](../../../plans/briefing.md).

**Status (BR-c1):** `forecast` reads today's forecast + `geocode` resolves a city name to
coordinates, both from **Open-Meteo** (free, no API key, no quota) behind a swappable `WeatherSource`
(`weather.source=open-meteo` default) — so a keyed provider can replace it later with no caller
change. No backing container (Open-Meteo is public HTTPS, unlike SearXNG's self-hosted container).
**Bound by `briefing-agent`** (BR-c: geocode at profile-set time; BR-d: forecast in the digest gather).

## Port: `8113` (`MCP_WEATHER_PORT`)

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `forecast` | `latitude`, `longitude` (decimal degrees) | `Weather{latitude, longitude, date?, tempMaxC?, tempMinC?, precipitationProbabilityPct?, windSpeedMaxKmh?, weatherCode?, summary?}` | today's high/low (°C), max precip probability (0–100), max wind (km/h), WMO code + label. Fields null when the source has no data. Read-only DATA — no narrative. |
| `geocode` | `name`, `language?` | `GeoLocation{name?, country?, latitude?, longitude?, timezone?}` | resolve a stated city name to coordinates + IANA timezone (feed lat/lon to `forecast`). Fields null when no match. |

## HTTP passthrough

| method | path | body | returns | purpose |
|--------|------|------|---------|---------|
| POST | `/internal/forecast` | `ForecastInput{latitude, longitude}` | `Weather` | non-MCP passthrough to `forecast`. The MockWebServer-testable, deterministic path an agent calls (MCP/SSE can't be mocked). Delegates straight to the tool. |
| POST | `/internal/geocode` | `GeocodeInput{name, language?}` | `GeoLocation` | non-MCP passthrough to `geocode`. The briefing-agent calls this at profile-set time to turn a stated city into coordinates. |

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_WEATHER_PORT` | `8113` | HTTP port (MCP/SSE + actuator). |
| `WEATHER_OPEN_METEO_URL` | `https://api.open-meteo.com` | Open-Meteo base URL — `GET /v1/forecast?latitude=&longitude=&daily=...`. |
| `WEATHER_GEOCODE_URL` | `https://geocoding-api.open-meteo.com` | Open-Meteo Geocoding base URL — `GET /v1/search?name=`. |
| `WEATHER_SOURCE` | `open-meteo` | Which `WeatherSource` to wire. Swappable later (keyed) via env. |

No DB / no Liquibase feature (capability-MCP). No backing container (Open-Meteo is a public HTTPS
endpoint). Binding side: an agent adds a `spring.ai.mcp.client.sse.connections.mcp-weather` block +
`WEATHER_URL` (happens in BR-b).

## Key classes

- `McpWeatherApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `config/McpWeatherProperties` — `weather.{open-meteo-url, geocode-url, source}`.
- `config/HttpConfig` — `openMeteoWebClient` (forecast host) + `geocodeWebClient` (geocoding host) beans.
- `engine/WeatherSource` — pluggable forecast backend interface (read-only; mirrors
  `mcp-market-data`'s `MarketDataSource`). `forecast` + `geocode`.
- `engine/OpenMeteoWeatherSource` — default (`weather.source=open-meteo`); GET Open-Meteo
  `/v1/forecast` (1-day `daily` block, index 0 → `Weather`, WMO code → label) + `/v1/search` (first
  result → `GeoLocation`). Missing values → null fields (no data, not an error).
- `tools/WeatherMcpTools` — `forecast(latitude, longitude)` + `geocode(name, language)` `@Tool`s
  (blocking per the MCP convention).
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the `@Tool`s.
- `web/InternalForecastController` / `web/InternalGeocodeController` — `POST /internal/forecast` +
  `POST /internal/geocode` passthroughs (delegate on `Schedulers.boundedElastic()`).
