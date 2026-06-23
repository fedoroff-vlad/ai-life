# mcp-web

Shared **web capability-MCP** (`shared/mcp/`, no schema). Cheap web retrieval any agent can
reuse. **Bound by:** the `researcher` (its first consumer), the chef (recipe search, CH-b), the
nutritionist (store-availability lookup, NU-g), and the stylist (trends); briefing news later. Bound
over MCP/SSE; it owns no data. Plan: [research.md](../../../plans/research.md).

**Status:** `web_search` runs against a self-hosted **SearXNG** backing service (free, no
key/quota, private) behind a swappable `SearchEngine`; `fetch_url` reads a page (jsoup, behind
`PageFetcher`) → readable text with boilerplate stripped; `transcribe_video` pulls a video's
subtitles/captions via **yt-dlp** (behind `VideoTranscriptEngine`) → plain transcript text — the way
to read video content, which `fetch_url` can't (JS-rendered pages return only boilerplate). **Cheap
retrieval, no LLM** — the calling agent does the synthesis.

## Port: `8098` (`MCP_WEB_PORT`)

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `web_search` | `query`, `limit?` | `WebSearchResult{query, hits[]}` | search the web (SearXNG) → ranked `WebSearchHit{title, url, snippet}`. Links + snippets only; read a page with `fetch_url`. |
| `fetch_url` | `url` | `PageContent{url, title, text, truncated}` | fetch a page (jsoup) → readable text, boilerplate stripped. Empty text if unreachable; `truncated` set when capped. |
| `transcribe_video` | `url`, `lang?` | `VideoTranscript{url, title?, text, lang?, truncated}` | yt-dlp pulls the video's subtitles/captions → plain transcript text. Use instead of `fetch_url` for video links. Empty text when no transcript. |

## HTTP passthroughs

| method | path | body | returns | purpose |
|--------|------|------|---------|---------|
| POST | `/internal/search` | `WebSearchInput{query, limit?}` | `WebSearchResult` | non-MCP passthrough to `web_search`. The MockWebServer-testable, deterministic path an agent calls (MCP/SSE can't be mocked). Delegates straight to the tool. |
| POST | `/internal/fetch` | `FetchUrlInput{url}` | `PageContent` | non-MCP passthrough to `fetch_url` (blocking jsoup on `boundedElastic`). |
| POST | `/internal/transcribe` | `TranscribeInput{url, lang?}` | `VideoTranscript` | non-MCP passthrough to `transcribe_video` (blocking yt-dlp on `boundedElastic`). |

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_WEB_PORT` | `8098` | HTTP port (MCP/SSE + actuator). |
| `SEARXNG_URL` | `http://searxng:8080` | SearXNG base URL — `GET /search?format=json`. |
| `MCP_WEB_SEARCH_ENGINE` | `searxng` | Which `SearchEngine` to wire. Swappable later (tavily/brave) via env. |
| `MCP_WEB_DEFAULT_LIMIT` | `8` | Hits returned when the caller omits `limit`. |
| `MCP_WEB_MAX_LIMIT` | `20` | Hard cap on hits per query. |
| `MCP_WEB_FETCH_TIMEOUT_MS` | `8000` | `fetch_url` connect/read timeout (ms). |
| `MCP_WEB_FETCH_MAX_CHARS` | `8000` | `fetch_url` max extracted chars; longer → truncated. |
| `MCP_WEB_TRANSCRIPT_ENGINE` | `yt-dlp` | `transcribe_video` engine: `yt-dlp` (binary in image) or `stub`. |
| `MCP_WEB_TRANSCRIPT_LANGS` | `en.*,ru.*` | yt-dlp `--sub-langs` (comma-separated, regex ok). |
| `MCP_WEB_TRANSCRIPT_TIMEOUT_SEC` | `60` | yt-dlp subprocess timeout (s). |
| `MCP_WEB_TRANSCRIPT_MAX_CHARS` | `12000` | `transcribe_video` max chars; longer → truncated. |

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
- `engine/PageFetcher` — pluggable page-reader interface (mirrors `SearchEngine`).
- `engine/JsoupPageFetcher` — default; jsoup fetch+parse, strips boilerplate, prefers
  `<article>`/`<main>`, caps length. Best-effort: failure → empty text (not an error).
- `engine/VideoTranscriptEngine` — pluggable transcript backend interface.
- `engine/YtDlpTranscriptEngine` — default (`transcript-engine=yt-dlp`); shells out to the bundled
  yt-dlp binary (`--skip-download --write-(auto-)subs`), parses the WebVTT with `SubtitleParser`,
  caps length. Best-effort: no subs / error / timeout → empty text.
- `engine/StubTranscriptEngine` — native-free marker (`transcript-engine=stub`; wiring test).
- `engine/SubtitleParser` — WebVTT → plain text (drops header/timings/cue-ids/inline-tags, collapses
  auto-caption repeats). Pure function, unit-tested.
- `tools/WebMcpTools` — `web_search` + `fetch_url` + `transcribe_video` `@Tool`s (blocking per the
  MCP convention) → `WebSearchResult` / `PageContent` / `VideoTranscript`.
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the `@Tool`s.
- `web/InternalSearchController` + `InternalFetchController` + `InternalTranscribeController` — the
  `POST /internal/{search,fetch,transcribe}` passthroughs (delegate on `Schedulers.boundedElastic()`).
