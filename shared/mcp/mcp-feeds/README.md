# mcp-feeds

Shared **feeds capability-MCP** (`shared/mcp/`, no schema). Read-only latest items from an RSS/Atom
feed or a public Telegram channel that any agent can reuse — the **third creator trend source** (CR-d
gathers it alongside `mcp-youtube`, `mcp-reddit`, `mcp-web`). A **sibling** of `mcp-youtube` /
`mcp-reddit` (a narrow external read behind a swappable source). Bound by agents over MCP/SSE; it owns
no data. Plan: [creator.md](../../../plans/creator.md) §FE-a.

**Trend data only.** `feed_items` is a read-only source — there is no write tool. Deciding anything
(which items matter, the post ideas/drafts) is the calling agent's skill.

**Status (FE-a):** `feed_items` reads the latest items from an **RSS/Atom feed** (parsed with Rome)
or a **public Telegram channel** (the `t.me/s/{channel}` web preview, parsed with jsoup) behind a
swappable `FeedSource` (`feeds.source=romejsoup` default) — so a sibling source can replace it later
with no caller change. **No key** — both surfaces are public; no backing container (public HTTPS, like
Stooq / YouTube). **Not bound yet** — the creator-agent binds it in CR-d.

## Port: `8112` (`MCP_FEEDS_PORT`)

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `feed_items` | `source` (an RSS/Atom feed URL — starts with `http` — or a public Telegram channel handle like `durov` / `@durov`), `maxResults?` (caps the item count; default 5) | `List<TrendHit>` — each `{source: "rss"\|"telegram", platform, title, url, summary?, metrics?}` (RSS `metrics` = `{publishedAt, author}`, Telegram = `{channel}`) | latest items from the feed/channel. Empty list when the feed is empty / channel unknown. Read-only trend data. |

## HTTP passthrough

| method | path | body | returns | purpose |
|--------|------|------|---------|---------|
| POST | `/internal/feed-items` | `FeedItemsInput{source, maxResults?}` | `List<TrendHit>` | non-MCP passthrough to `feed_items`. The MockWebServer-testable, deterministic path an agent calls (MCP/SSE can't be mocked). Delegates straight to the tool. |

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_FEEDS_PORT` | `8112` | HTTP port (MCP/SSE + actuator). |
| `FEEDS_TELEGRAM_BASE_URL` | `https://t.me` | Public Telegram channel web-preview base — `/s/{channel}`. |
| `FEEDS_USER_AGENT` | `ai-life/mcp-feeds 0.0.1 (+https://github.com/vlad94fed)` | `User-Agent` for feed / channel fetches. |
| `FEEDS_MAX_RESULTS` | `5` | Default item count when the caller doesn't cap it. |
| `FEEDS_SOURCE` | `romejsoup` | Which `FeedSource` to wire. Swappable later via env. |

No DB / no Liquibase feature (capability-MCP). No backing container (RSS URLs / Telegram web preview
are public HTTPS endpoints). Binding side: an agent adds a
`spring.ai.mcp.client.sse.connections.mcp-feeds` block + `MCP_FEEDS_URL` — **creator-agent** binds it
this way (CR-d).

## Key classes

- `McpFeedsApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `config/McpFeedsProperties` — `feeds.{telegram-base-url, user-agent, max-results, source}`.
- `config/HttpConfig` — `feedsWebClient` bean (no base URL — RSS URLs are absolute, the Telegram URL
  is built from the base; pins the policy `User-Agent`).
- `engine/FeedSource` — pluggable feed backend interface (read-only; mirrors `mcp-youtube`'s
  `VideoTrendsSource`). Returns a uniform `TrendHit` list.
- `engine/RomeJsoupFeedSource` — default (`feeds.source=romejsoup`); an `http(s)` source → fetch +
  Rome `SyndFeedInput` (RSS/Atom) → each entry → `TrendHit` (platform `rss`, publishedAt + author in
  `metrics`); a channel handle → fetch `t.me/s/{channel}` + jsoup parse `.tgme_widget_message` →
  `TrendHit` (platform `telegram`, newest-first, `channel` in `metrics`). Empty feed / channel →
  empty list; a parse/transport failure propagates.
- `tools/FeedsMcpTools` — `feed_items(source, maxResults)` `@Tool` (blocking per the MCP convention)
  → `FeedSource.items` → `List<TrendHit>`.
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the `@Tool`.
- `web/InternalFeedItemsController` — `POST /internal/feed-items` passthrough (delegates on
  `Schedulers.boundedElastic()`).
