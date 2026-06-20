# mcp-web

Shared **web capability-MCP** (`shared/mcp/`, no schema). Cheap web retrieval any agent can
reuse — a `researcher` query, chef recipe search, briefing news, finance market reads. Bound by
agents over MCP/SSE; it owns no data. Plan: [research.md](../../../plans/research.md).

**Status (R-a):** `web_search` runs against a self-hosted **SearXNG** backing service (free, no
key/quota, private) behind a swappable `SearchEngine` interface. Returns ranked title/url/snippet
hits — **cheap retrieval, no LLM**; the calling agent does the synthesis. Next: R-b adds
`fetch_url` (read a page → readable text); R-c binds the `researcher` agent.

## Port: `8098` (`MCP_WEB_PORT`)

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `web_search` | `query`, `limit?` | `WebSearchResult{query, hits[]}` | search the web (SearXNG) → ranked `WebSearchHit{title, url, snippet}`. Links + snippets only; read a page with `fetch_url` (R-b). |

## HTTP passthrough

| method | path | body | returns | purpose |
|--------|------|------|---------|---------|
| POST | `/internal/search` | `WebSearchInput{query, limit?}` | `WebSearchResult` | non-MCP passthrough to `web_search`. The MockWebServer-testable, deterministic path an agent calls (MCP/SSE can't be mocked). Delegates straight to the tool. |

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_WEB_PORT` | `8098` | HTTP port (MCP/SSE + actuator). |
| `SEARXNG_URL` | `http://searxng:8080` | SearXNG base URL — `GET /search?format=json`. |
| `MCP_WEB_SEARCH_ENGINE` | `searxng` | Which `SearchEngine` to wire. Swappable later (tavily/brave) via env. |
| `MCP_WEB_DEFAULT_LIMIT` | `8` | Hits returned when the caller omits `limit`. |
| `MCP_WEB_MAX_LIMIT` | `20` | Hard cap on hits per query. |

No DB / no Liquibase feature (capability-MCP). Backing service: a **SearXNG** container
(`infra/docker-compose.yml` + `docker-compose.dev.yml`), configured with the JSON format enabled
(`infra/searxng/settings.yml`). Binding side: an agent adds a
`spring.ai.mcp.client.sse.connections.mcp-web` block + `MCP_WEB_URL` (happens in R-c).

## Key classes

- `McpWebApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `config/McpWebProperties` — `mcp-web.{searxng-url, search-engine, default-limit, max-limit}`.
- `config/HttpConfig` — `searxngWebClient` bean (SearXNG base URL).
- `engine/SearchEngine` — pluggable search backend interface (mirrors `OcrEngine`).
- `engine/SearxngSearchEngine` — default (`mcp-web.search-engine=searxng`); GET SearXNG
  `/search?format=json`, maps `results[]` → `WebSearchHit`s (drops URL-less hits), trims to `limit`.
- `tools/WebMcpTools` — `web_search(query, limit?)` `@Tool` (blocking `.block()` per the MCP
  convention) → `WebSearchResult`.
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the `@Tool`s.
- `web/InternalSearchController` — `POST /internal/search` passthrough (delegates to the tool on
  `Schedulers.boundedElastic()`).
