# mcp-youtube

Shared **video-trends capability-MCP** (`shared/mcp/`, no schema). Read-only trending / most-relevant
videos any agent can reuse — the **first creator trend source** (CR-d gathers it alongside `mcp-web`,
`mcp-reddit`, `mcp-feeds`). A **sibling** of `mcp-food-data` / `mcp-market-data` (a narrow external
read behind a swappable source). Bound by agents over MCP/SSE; it owns no data. Plan:
[creator.md](../../../plans/creator.md) §YT-a.

**Trend data only.** `youtube_trends` is a read-only source — there is no write tool. Deciding
anything (which trends matter, the post ideas/drafts) is the calling agent's skill.

**Status (YT-a):** `youtube_trends` reads trending/most-relevant videos from the **YouTube Data API
v3** behind a swappable `VideoTrendsSource` (`youtube.source=youtubedata` default) — so a sibling
provider can replace it later with no caller change. No backing container (the API is public HTTPS,
like Stooq / Open Food Facts). **Free quota needs an API key** (`YOUTUBE_API_KEY`); when it's blank
the source returns an empty list rather than calling the API, so a multi-source gather soft-fails
this source gracefully (e.g. in CI). **Not bound yet** — the creator-agent binds it in CR-d.

## Port: `8110` (`MCP_YOUTUBE_PORT`)

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `youtube_trends` | `query` (niche / topic / keyword), `maxResults?` (caps the hit count; default 5) | `List<TrendHit>` — each `{source: "youtube", platform: "youtube", title, url, summary?, metrics?}` (`metrics` = `{channel, publishedAt}`) | trending / most-relevant videos for the query. Empty list when there are no matches or no API key. Read-only trend data. |

## HTTP passthrough

| method | path | body | returns | purpose |
|--------|------|------|---------|---------|
| POST | `/internal/youtube-trends` | `YoutubeTrendsInput{query, maxResults?}` | `List<TrendHit>` | non-MCP passthrough to `youtube_trends`. The MockWebServer-testable, deterministic path an agent calls (MCP/SSE can't be mocked). Delegates straight to the tool. |

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_YOUTUBE_PORT` | `8110` | HTTP port (MCP/SSE + actuator). |
| `YOUTUBE_API_BASE_URL` | `https://www.googleapis.com/youtube/v3` | YouTube Data API v3 base URL — `/search`. |
| `YOUTUBE_API_KEY` | _(blank)_ | Free-quota API key. Blank → no hits (graceful soft-fail; no key in CI). |
| `YOUTUBE_MAX_RESULTS` | `5` | Default hit count when the caller doesn't cap it. |
| `YOUTUBE_SOURCE` | `youtubedata` | Which `VideoTrendsSource` to wire. Swappable later via env. |

No DB / no Liquibase feature (capability-MCP). No backing container (the YouTube Data API is a public
HTTPS endpoint). Binding side: an agent adds a `spring.ai.mcp.client.sse.connections.mcp-youtube`
block + `MCP_YOUTUBE_URL` — **creator-agent** binds it this way (CR-d).

## Key classes

- `McpYoutubeApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `config/McpYoutubeProperties` — `youtube.{api-base-url, api-key, max-results, source}`.
- `config/HttpConfig` — `youtubeWebClient` bean (YouTube Data API base URL; the key is a per-request
  query param the source adds).
- `engine/VideoTrendsSource` — pluggable video-trends backend interface (read-only; mirrors
  `mcp-food-data`'s `FoodDataSource`). Returns a uniform `TrendHit` list.
- `engine/YoutubeDataApiSource` — default (`youtube.source=youtubedata`); one
  `GET /search?part=snippet&type=video&order=relevance` call → each item → `TrendHit` (the
  `watch?v=` link, channel + publish date in `metrics`). Blank key / blank query → empty list (no
  data, not an error).
- `tools/YoutubeMcpTools` — `youtube_trends(query, maxResults)` `@Tool` (blocking per the MCP
  convention) → `VideoTrendsSource.trends` → `List<TrendHit>`.
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the `@Tool`.
- `web/InternalYoutubeTrendsController` — `POST /internal/youtube-trends` passthrough (delegates on
  `Schedulers.boundedElastic()`).
