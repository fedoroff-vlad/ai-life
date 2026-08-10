# mcp-weather

Shared **weather capability-MCP** (`shared/mcp/`, no schema). Read-only today's forecast any agent
can reuse — the **briefing-agent** morning digest first. A sibling of `mcp-market-data` (structured
numeric reads, not web search). Bound by agents over MCP/SSE; it owns no data. Plan:
[briefing.md](../../../plans/briefing.md).

**Status (TR-a):** `forecast` reads today's forecast, `geocode` resolves a city name to coordinates,
and `climate` returns monthly temperature/precipitation normals — all from **Open-Meteo** (free, no
API key, no quota) behind a swappable `WeatherSource` (`weather.source=open-meteo` default), so a keyed
provider can replace them later with no caller change. No backing container (Open-Meteo is public
HTTPS, unlike SearXNG's self-hosted container). **Bound by `briefing-agent`** (BR-c: geocode at
profile-set time; BR-d: forecast in the digest gather); `climate` is the season substrate for the
**travel-agent** (TR-a, added ahead of its consumer).

## Port: `8113` (`MCP_WEATHER_PORT`)

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `forecast` | `latitude`, `longitude` (decimal degrees) | `Weather{latitude, longitude, date?, tempMaxC?, tempMinC?, precipitationProbabilityPct?, windSpeedMaxKmh?, weatherCode?, summary?}` | today's high/low (°C), max precip probability (0–100), max wind (km/h), WMO code + label. Fields null when the source has no data. Read-only DATA — no narrative. |
| `geocode` | `name`, `language?` | `GeoLocation{name?, country?, latitude?, longitude?, timezone?}` | resolve a stated city name to coordinates + IANA timezone (feed lat/lon to `forecast`). Fields null when no match. |
| `climate` | `latitude`, `longitude`, `month?` (1–12) | `ClimateNormals{latitude, longitude, months:[MonthlyNormal{month, avgTempC?, precipMm?}]}` | monthly climate normals over a recent multi-year window (Open-Meteo Archive): per month the avg daily-mean temp (°C) + avg monthly precipitation total (mm). 12 entries (Jan→Dec), or 1 when `month` is set. `months` **empty** when the source is down/has no data (soft-fail, never a 500). The season substrate. Read-only DATA — no narrative. |

## HTTP passthrough

| method | path | body | returns | purpose |
|--------|------|------|---------|---------|
| POST | `/internal/forecast` | `ForecastInput{latitude, longitude}` | `Weather` | non-MCP passthrough to `forecast`. The MockWebServer-testable, deterministic path an agent calls (MCP/SSE can't be mocked). Delegates straight to the tool. |
| POST | `/internal/geocode` | `GeocodeInput{name, language?}` | `GeoLocation` | non-MCP passthrough to `geocode`. The briefing-agent calls this at profile-set time to turn a stated city into coordinates. |
| POST | `/internal/climate` | `ClimateInput{latitude, longitude, month?}` | `ClimateNormals` | non-MCP passthrough to `climate`. The travel-agent calls this for a candidate destination's season fit. Missing coordinates → empty normals (not an error). |

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_WEATHER_PORT` | `8113` | HTTP port (MCP/SSE + actuator). |
| `WEATHER_OPEN_METEO_URL` | `https://api.open-meteo.com` | Open-Meteo base URL — `GET /v1/forecast?latitude=&longitude=&daily=...`. |
| `WEATHER_GEOCODE_URL` | `https://geocoding-api.open-meteo.com` | Open-Meteo Geocoding base URL — `GET /v1/search?name=`. |
| `WEATHER_CLIMATE_URL` | `https://archive-api.open-meteo.com` | Open-Meteo Archive base URL — `GET /v1/archive?...&daily=temperature_2m_mean,precipitation_sum` (aggregated into monthly normals). |
| `WEATHER_SOURCE` | `open-meteo` | Which `WeatherSource` to wire. Swappable later (keyed) via env. |

No DB / no Liquibase feature (capability-MCP). No backing container (Open-Meteo is a public HTTPS
endpoint). Binding side: an agent adds a `spring.ai.mcp.client.sse.connections.mcp-weather` block +
`WEATHER_URL` (happens in BR-b).

## Key classes

- `McpWeatherApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `config/McpWeatherProperties` — `weather.{open-meteo-url, geocode-url, climate-url, source}`.
- `config/HttpConfig` — `openMeteoWebClient` (forecast host) + `geocodeWebClient` (geocoding host) +
  `climateWebClient` (archive host) beans.
- `engine/WeatherSource` — pluggable forecast backend interface (read-only; mirrors
  `mcp-market-data`'s `MarketDataSource`). `forecast` + `geocode` + `climate`.
- `engine/OpenMeteoWeatherSource` — default (`weather.source=open-meteo`); GET Open-Meteo
  `/v1/forecast` (1-day `daily` block, index 0 → `Weather`, WMO code → label) + `/v1/search` (first
  result → `GeoLocation`) + `/v1/archive` (last 10 complete years of daily mean-temp/precip →
  aggregated per-month `ClimateNormals`). Missing values → null fields (no data, not an error);
  `climate` maps a transport failure to empty normals (soft-fail).
- `tools/WeatherMcpTools` — `forecast(latitude, longitude)` + `geocode(name, language)` +
  `climate(latitude, longitude, month)` `@Tool`s (blocking per the MCP convention).
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the `@Tool`s.
- `web/InternalForecastController` / `web/InternalGeocodeController` / `web/InternalClimateController`
  — `POST /internal/forecast` + `POST /internal/geocode` + `POST /internal/climate` passthroughs
  (delegate on `Schedulers.boundedElastic()`).
