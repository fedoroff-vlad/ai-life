# research — web research specialist + shared web capability

Authority file for the **`researcher` specialist agent** and the shared **`mcp-web`
capability-MCP** it binds. Owner-chosen after the finance MVP (2026-06-20): "я прошу — он
находит, даёт классную выжимку, скидывает ссылки (видео/статьи), **при этом не тратит токены**."

## Why these two things (doctrine, not relitigated)
`architecture.md`: a raw external capability → a **capability-MCP** any agent binds; reasoning
over it → a **specialist agent**. So web access splits in two:
- **`mcp-web`** (`shared/mcp/`, no schema) — the shared toolbox: `web_search` + `fetch_url`. Bound
  later by chef (recipes), briefing (news), finance investment-advisory (market reads) — not just
  the researcher. This is the `web-fetch/search` capability already named in `roadmap.md`.
- **`researcher-agent`** (`domains/researcher/`) — the specialist that turns a request into a
  search → read → synthesize flow and returns a summary + links.

## Token economy is structural (the owner's hard requirement)
Search and page-fetch are **plain HTTP, no LLM**. Exactly **one** LLM call happens — at the end,
synthesizing over the already-gathered snippets/page-text (the shared `Coordinator`
gather→synthesize pattern). The LLM never "browses"; it summarizes a pre-selected corpus.
**cheap-first:** cheap retrieval picks the material, the expensive model only writes the summary.

## Decision — search engine: **SearXNG** (LOCKED, owner 2026-06-20)
Self-hosted meta-search container (like Radicale/MinIO in the stack): free, no API key, no quota,
private (queries leave from our own host). Behind a `SearchEngine` interface (`engine/SearchEngine`,
mirrors `OcrEngine`) selected by `mcp-web.search-engine` (`searxng` default) — so Tavily/Brave can
replace it later via env with no caller change. SearXNG returns snippets+links; depth comes from
`fetch_url`. Tavily (LLM-cleaned content, paid-tier) and Brave (API key, quota) were considered and
deferred — revisit only if self-hosting SearXNG proves costly to run on the target host.

## Shape
- `shared/mcp/mcp-web` — capability-MCP, **port 8098**, no DB. Talks to a **SearXNG** backing
  container (`SEARXNG_URL`). Tools `web_search(query, limit?)` + `fetch_url(url)`, each also an HTTP
  `/internal/*` passthrough (the deterministic, MockWebServer-testable path an agent calls; MCP/SSE
  can't be mocked — same doctrine as `mcp-media-processing`). Template: `shared/mcp/mcp-media-processing`.
- `domains/researcher/researcher-agent` — cross-domain specialist, **port 8099**. Binds `mcp-web`
  (SSE for future LLM tool-selection + HTTP clients for the deterministic flow). One `research`
  skill on the `Coordinator`. Registered in the orchestrator (manifest-driven; no orchestrator code).
- Contracts in `libs/contracts/.../web/`: `WebSearchHit(title, url, snippet)`,
  `WebSearchResult(query, hits)`, `WebSearchInput(query, limit?)`, `FetchUrlInput(url)`,
  `PageContent(url, title, text, truncated)`.

## PR-sized slices
- **R-a — SearXNG infra + `mcp-web` scaffold + `web_search`.** New module (no JPA), `SearchEngine`
  + `SearxngSearchEngine` (GET SearXNG `/search?format=json`), `web_search` `@Tool`,
  `POST /internal/search` passthrough, the search contracts. SearXNG service in
  `docker-compose.yml` + `docker-compose.dev.yml` (it's a backing service). MockWebServer test
  (no real network). **No agent binding yet** — the capability stands alone.
- **R-b — `fetch_url` (+ `/internal/fetch`).** `PageFetcher` (readability extraction) +
  `fetch_url` `@Tool` + passthrough + `FetchUrlInput`/`PageContent`. **Lock the readability lib with
  the owner first** (proposed: jsoup strip-and-extract; readability4j is the upgrade). MockWebServer
  HTML test.
- **R-c — `researcher-agent` scaffold + orchestrator registration.** Scaffold per PATTERNS.md
  "add a new agent" (copy finance-agent). Bind `mcp-web` (SSE + `WebSearchClient`/`PageFetchClient`).
  Register `{name: researcher}` in orchestrator `application.yml` + `RESEARCHER_AGENT_URL`.
  Minimal intent path (chat fallback until R-d).
- **R-d — `research` skill (the cheap-first flow, end-to-end value).** `flow/Researcher` on the
  `Coordinator` (copy calendar-agent's `GiftRecommender`): gather `search` → `fetch` top N (parallel,
  soft-fail per page) → one LLM synthesis → summary + grouped links (articles vs videos by host).
  `research/SKILL.md`. `ResearcherFlowTest` (MockWebServers for mcp-web `/internal/*` + llm-gateway).

## Out of scope (recorded, later)
- **Video transcripts** (YouTube/Insta deep content) — a later tool/slice (an `mcp-youtube-transcript`
  style engine, or `fetch_url` extended to video pages). MVP surfaces video **links** from search.
- **`market-data`** (stocks/crypto/metals quotes) — a sibling capability-MCP that rides in with
  finance investment-advisory (see finance.md recorded vision), not part of `mcp-web` MVP.
- Real LLM synthesis quality — Stage 5 (mock LLM proves the wiring; the flow is model-agnostic).
