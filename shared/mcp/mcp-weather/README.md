# mcp-weather

Shared **weather capability-MCP** (`shared/mcp/`, no schema). Read-only today's forecast any agent
can reuse — the **briefing-agent** morning digest first. A sibling of `mcp-market-data` (structured
numeric reads, not web search). Bound by agents over MCP/SSE; it owns no data. Plan:
[briefing.md](../../../plans/briefing.md).

**Status (BR-a):** `forecast` reads today's forecast from **Open-Meteo**'s JSON endpoint (free, no
API key, no quota) behind a swappable `WeatherSource` (`weather.source=open-meteo` default) — so a
keyed provider can replace it later with no caller change. No backing container (Open-Meteo is public
HTTPS, unlike SearXNG's self-hosted container). **No caller yet** — bound by `briefing-agent` in BR-b.

## Port: `8113` (`MCP_WEATHER_PORT`)

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `forecast` | `latitude`, `longitude` (decimal degrees) | `Weather{latitude, longitude, date?, tempMaxC?, tempMinC?, precipitationProbabilityPct?, windSpeedMaxKmh?, weatherCode?, summary?}` | today's high/low (°C), max precip probability (0–100), max wind (km/h), WMO code + label. Fields null when the source has no data. Read-only DATA — no narrative. |

## HTTP passthrough

| method | path | body | returns | purpose |
|--------|------|------|---------|---------|
| POST | `/internal/forecast` | `ForecastInput{latitude, longitude}` | `Weather` | non-MCP passthrough to `forecast`. The MockWebServer-testable, deterministic path an agent calls (MCP/SSE can't be mocked). Delegates straight to the tool. |

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_WEATHER_PORT` | `8113` | HTTP port (MCP/SSE + actuator). |
| `WEATHER_OPEN_METEO_URL` | `https://api.open-meteo.com` | Open-Meteo base URL — `GET /v1/forecast?latitude=&longitude=&daily=...`. |
| `WEATHER_SOURCE` | `open-meteo` | Which `WeatherSource` to wire. Swappable later (keyed) via env. |

No DB / no Liquibase feature (capability-MCP). No backing container (Open-Meteo is a public HTTPS
endpoint). Binding side: an agent adds a `spring.ai.mcp.client.sse.connections.mcp-weather` block +
`WEATHER_URL` (happens in BR-b).

## Key classes

- `McpWeatherApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `config/McpWeatherProperties` — `weather.{open-meteo-url, source}`.
- `config/HttpConfig` — `openMeteoWebClient` bean (Open-Meteo base URL).
- `engine/WeatherSource` — pluggable forecast backend interface (read-only; mirrors
  `mcp-market-data`'s `MarketDataSource`).
- `engine/OpenMeteoWeatherSource` — default (`weather.source=open-meteo`); GET Open-Meteo
  `/v1/forecast` with a 1-day `daily` block, parses index 0 → `Weather`, maps the WMO code → a label.
  Missing values → null fields (no data, not an error).
- `tools/WeatherMcpTools` — `forecast(latitude, longitude)` `@Tool` (blocking per the MCP
  convention) → `WeatherSource.forecast` → `Weather`.
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the `@Tool`.
- `web/InternalForecastController` — `POST /internal/forecast` passthrough (delegates on
  `Schedulers.boundedElastic()`).
