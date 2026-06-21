# mcp-market-data

Shared **market-data capability-MCP** (`shared/mcp/`, no schema). Read-only stocks / funds /
metals / crypto quotes any agent can reuse — finance investment-advisory now, a briefing "markets"
line later. A **sibling** of `mcp-web` (structured numeric reads, not web search). Bound by agents
over MCP/SSE; it owns no data. Plan: [market-data.md](../../../plans/market-data.md).

**Advisory data only.** `quote` is a read-only price source — there is no order/trade tool and
never will be. Deciding anything (a buy/hold/sell *idea*, never an order) is the calling agent's
advisory-only skill.

**Status (MD-a):** `quote` reads a latest quote from **Stooq**'s CSV endpoint (free, no API key,
no quota) behind a swappable `MarketDataSource` (`marketdata.source=stooq` default) — so Yahoo / a
keyed provider can replace it later with no caller change. No backing container (Stooq is public
HTTPS, unlike SearXNG's self-hosted container). Next: MD-c binds this to finance-agent's
`investment-advisor` skill; `history` (series for trend) is a later optional slice.

## Port: `8100` (`MCP_MARKET_DATA_PORT`)

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `quote` | `symbol` (source-native, e.g. `aapl.us` / `^spx` / `xauusd` / `btcusd`) | `Quote{symbol, price?, asOf?, open?, high?, low?, volume?}` | read the latest quote from Stooq. `price` is null when the source has no data for the symbol. Read-only data — no orders. |

## HTTP passthrough

| method | path | body | returns | purpose |
|--------|------|------|---------|---------|
| POST | `/internal/quote` | `QuoteInput{symbol}` | `Quote` | non-MCP passthrough to `quote`. The MockWebServer-testable, deterministic path an agent calls (MCP/SSE can't be mocked). Delegates straight to the tool. |

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_MARKET_DATA_PORT` | `8100` | HTTP port (MCP/SSE + actuator). |
| `MARKETDATA_STOOQ_URL` | `https://stooq.com` | Stooq base URL — `GET /q/l/?s=&f=&e=csv`. |
| `MARKETDATA_SOURCE` | `stooq` | Which `MarketDataSource` to wire. Swappable later (yahoo/keyed) via env. |

No DB / no Liquibase feature (capability-MCP). No backing container (Stooq is a public HTTPS
endpoint). Binding side: an agent adds a `spring.ai.mcp.client.sse.connections.mcp-market-data`
block + `MARKET_DATA_URL` (happens in MD-c).

## Key classes

- `McpMarketDataApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `config/McpMarketDataProperties` — `marketdata.{stooq-url, source}`.
- `config/HttpConfig` — `stooqWebClient` bean (Stooq base URL).
- `engine/MarketDataSource` — pluggable quotes backend interface (read-only; mirrors `mcp-web`'s
  `SearchEngine`). No order/trade method by design.
- `engine/StooqMarketDataSource` — default (`marketdata.source=stooq`); GET Stooq
  `/q/l/?s={symbol}&f=sd2t2ohlcv&e=csv`, parses the CSV row → `Quote`. `N/D` → null fields
  (no data, not an error).
- `tools/MarketDataMcpTools` — `quote(symbol)` `@Tool` (blocking per the MCP convention) →
  `MarketDataSource.quote` → `Quote`.
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the `@Tool`.
- `web/InternalQuoteController` — `POST /internal/quote` passthrough (delegates on
  `Schedulers.boundedElastic()`).
