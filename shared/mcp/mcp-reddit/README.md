# mcp-reddit

Shared **social-trends capability-MCP** (`shared/mcp/`, no schema). Read-only hot / most-relevant
Reddit posts any agent can reuse — the **second creator trend source** (CR-d gathers it alongside
`mcp-youtube`, `mcp-web`, `mcp-feeds`). A **sibling** of `mcp-youtube` (a narrow external read behind
a swappable source). Bound by agents over MCP/SSE; it owns no data. Plan:
[creator.md](../../../plans/creator.md) §RD-a.

**Trend data only.** `reddit_trends` is a read-only source — there is no write tool. Deciding
anything (which trends matter, the post ideas/drafts) is the calling agent's skill.

**Status (RD-a):** `reddit_trends` reads hot/most-relevant posts from the **Reddit API** behind a
swappable `SocialTrendsSource` (`reddit.source=redditapi` default) — so a sibling provider can replace
it later with no caller change. App-only OAuth: one `POST /api/v1/access_token`
(grant `client_credentials`) mints a bearer token, then one listing call. No backing container (the
API is public HTTPS, like Stooq / YouTube). **Free API needs an app id + secret**
(`REDDIT_CLIENT_ID` / `REDDIT_CLIENT_SECRET`); when either is blank the source returns an empty list
rather than calling Reddit, so a multi-source gather soft-fails this source gracefully (e.g. in CI).
**Not bound yet** — the creator-agent binds it in CR-d.

## Port: `8111` (`MCP_REDDIT_PORT`)

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `reddit_trends` | `subreddit?` (hot posts in it), `query?` (search), both (search within the subreddit), `maxResults?` (caps the hit count; default 5) | `List<TrendHit>` — each `{source: "reddit", platform: "reddit", title, url, summary?, metrics?}` (`metrics` = `{subreddit, score, numComments}`) | hot / most-relevant posts for the subreddit/query. Empty list when there are no matches or no app credentials. Read-only trend data. |

## HTTP passthrough

| method | path | body | returns | purpose |
|--------|------|------|---------|---------|
| POST | `/internal/reddit-trends` | `RedditTrendsInput{subreddit?, query?, maxResults?}` | `List<TrendHit>` | non-MCP passthrough to `reddit_trends`. The MockWebServer-testable, deterministic path an agent calls (MCP/SSE can't be mocked). Delegates straight to the tool. |

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_REDDIT_PORT` | `8111` | HTTP port (MCP/SSE + actuator). |
| `REDDIT_API_BASE_URL` | `https://oauth.reddit.com` | Reddit OAuth API base URL — listings. |
| `REDDIT_AUTH_BASE_URL` | `https://www.reddit.com` | Reddit auth base URL — `/api/v1/access_token`. |
| `REDDIT_CLIENT_ID` | _(blank)_ | Free app client id. Blank → no hits (graceful soft-fail; no creds in CI). |
| `REDDIT_CLIENT_SECRET` | _(blank)_ | Free app client secret. Blank → no hits. |
| `REDDIT_USER_AGENT` | `ai-life/mcp-reddit 0.0.1 (by /u/ai-life)` | The identifying `User-Agent` Reddit's policy requires. |
| `REDDIT_MAX_RESULTS` | `5` | Default hit count when the caller doesn't cap it. |
| `REDDIT_SOURCE` | `redditapi` | Which `SocialTrendsSource` to wire. Swappable later via env. |

No DB / no Liquibase feature (capability-MCP). No backing container (the Reddit API is a public HTTPS
endpoint). Binding side: an agent adds a `spring.ai.mcp.client.sse.connections.mcp-reddit` block +
`MCP_REDDIT_URL` — **creator-agent** binds it this way (CR-d).

## Key classes

- `McpRedditApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `config/McpRedditProperties` — `reddit.{api-base-url, auth-base-url, client-id, client-secret,
  user-agent, max-results, source}`.
- `config/HttpConfig` — `redditAuthWebClient` (basic-auth + UA, mints the token) + `redditApiWebClient`
  (UA; bearer added per request) beans.
- `engine/SocialTrendsSource` — pluggable social-trends backend interface (read-only; mirrors
  `mcp-youtube`'s `VideoTrendsSource`). Returns a uniform `TrendHit` list.
- `engine/RedditApiSource` — default (`reddit.source=redditapi`); app-only OAuth token →
  `/r/{sub}/hot` | `/r/{sub}/search` | `/search` → each post → `TrendHit` (the permalink, subreddit +
  score + comment count in `metrics`). Blank creds / no target → empty list (no data, not an error).
- `tools/RedditMcpTools` — `reddit_trends(subreddit, query, maxResults)` `@Tool` (blocking per the MCP
  convention) → `SocialTrendsSource.trends` → `List<TrendHit>`.
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the `@Tool`.
- `web/InternalRedditTrendsController` — `POST /internal/reddit-trends` passthrough (delegates on
  `Schedulers.boundedElastic()`).
