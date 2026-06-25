# creator-agent

Creator / content-factory domain agent (port **8109**). Monitors trends for a creator's niche and
proposes fresh trends (with links), post ideas, ready drafts (title/text/CTA/hashtags), and
per-platform format recommendations, keeping a per-person content track. Owns the `mcp-creator`
domain-MCP; binds the shared trend sources `mcp-web` / `mcp-youtube` / `mcp-reddit` / `mcp-feeds`.
Routes via the orchestrator (registered as `creator`). The gather → synthesize shape is the
`researcher` agent, fanned out to multiple sources. See [plans/creator.md](../../../plans/creator.md).

## Status (through CR-e)

Manifest endpoint + the `chat/CreatorChat` fallback (one LLM turn, AGENT.md as system prompt) +
the **creator-profile flow** (CR-c) + the **headline trend → ideas → drafts flow** (CR-d):
- **CR-c — creator profile. DONE.** A typed message with a creator-profile cue ("моя ниша…", "мой
  контент про…", "my niche…") → one LLM extract via the `creator-profiler` SKILL → upsert the
  per-person content track via `mcp-creator`'s `/internal/creator-profile` (self → the sender,
  household → the default). `profile/CreatorProfiler`.
- **CR-d — trend → ideas → drafts (the headline). DONE.** A trend/ideas/draft cue resolves the
  creator track (self → household-default), then gathers `web` + `youtube` + `reddit` for the niche
  (and a named RSS/Telegram `feed` if the request mentions one) in parallel on the shared
  `Coordinator` — cheap-first: all gather is HTTP, ONE LLM synthesis via the `content-strategist`
  SKILL → fresh trends + post ideas + ready drafts + per-platform format recs → rendered as an HTML
  content-plan board via `libs/doc-render` (with a provenance links section built from the gathered
  hits) → stored in media-service → reply with the link. `flow/ContentStrategist`. Per-source
  soft-fail; render/store failure still returns the textual plan.
- **CR-e — trend cache persist. DONE.** After the synthesis, the flow persists the run into
  `mcp-creator`: the gathered `TrendHit` corpus → `POST /internal/trends` in one batch (the cache
  dedups per `(owner, url)`), and the synthesized plan → `POST /internal/content-piece` as a `draft`,
  both attributed to the speaker. Best-effort — the reply already carries the deliverable link, so a
  persist failure only logs. `http/CreatorCacheClient`.
- **CR-g — `draft_greeting` action.** Invoked over the orchestrator hub by the calendar birthday
  wake → drafts a greeting → notifier delivers (closes the Stage-4 chain).

## Endpoints

- `POST /agents/creator/intent` (body `NormalizedMessage`) → `IntentResponse` — the orchestrator's
  entry point. Profile cue → creator-profiler; trend/ideas/draft cue → content-strategist; else chat.
- `GET /agents/creator/manifest` → `AgentManifest` — scraped by the orchestrator on startup.

## Env

| Var | Default | Purpose |
|---|---|---|
| `CREATOR_AGENT_PORT` | `8109` | HTTP port |
| `MCP_CREATOR_URL` | `http://mcp-creator:8108` | creator domain-MCP (its data) |
| `MCP_WEB_URL` | `http://mcp-web:8098` | shared web/trend-search capability |
| `MCP_YOUTUBE_URL` | `http://mcp-youtube:8110` | YouTube trends capability |
| `MCP_REDDIT_URL` | `http://mcp-reddit:8111` | Reddit trends capability |
| `MCP_FEEDS_URL` | `http://mcp-feeds:8112` | RSS/Telegram feeds capability |
| `MEDIA_SERVICE_URL` | `http://media-service:8088` | stores the rendered content-plan HTML |
| `PUBLIC_MEDIA_BASE_URL` | `http://media-service:8088` | base the deliverable link is built from |
| `CREATOR_AGENT_MCP_CLIENT_ENABLED` | `true` | toggle the eager MCP-SSE binding off in dev |
| `CREATOR_AGENT_MEMORY_RECALL_K` | `5` | memory recall fan-out |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | llm-gateway (chat) |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` / `MEMORY_SERVICE_URL` | — | shared agent-runtime clients |

## Key classes

- `CreatorAgentApplication` — `@Import(AgentRuntimeConfig)` + `@EnableConfigurationProperties`.
- `config/CreatorAgentProperties` — the outbound base URLs (`creator-agent.*`).
- `config/OutboundHttpConfig` — one `clone()`d `WebClient` per dependency; the `profile/notifier/memory`
  qualified beans back the shared runtime clients, `mcpCreator` + the four trend sources + `mediaService`
  back the flows.
- `config/RenderConfig` — the shared `DocRenderer` bean (HTML, default `DocTheme`) for the content-plan board.
- `chat/CreatorChat` — the chat fallback (one LLM turn, AGENT.md as system prompt).
- `profile/CreatorProfiler` — the creator-profile flow: a typed profile cue → LLM extract via the
  `creator-profiler` SKILL → upsert via `/internal/creator-profile` (self or household-default).
- `flow/ContentStrategist` — the headline flow: resolve the track → gather web/youtube/reddit(+feed)
  on the `Coordinator` → one `content-strategist` synthesis → render HTML board (+ provenance links) →
  store in media-service → reply with the link.
- `http/CreatorProfileClient` — `POST` (upsert) + `GET` (read, 404→empty) `/internal/creator-profile`.
- `http/CreatorCacheClient` — the CR-e persist: `POST /internal/trends` (batch trend cache) +
  `POST /internal/content-piece` (the draft), over `mcp-creator`.
- `http/TrendGatherClient` — one client binding the four source passthroughs (`/internal/search`,
  `/internal/youtube-trends`, `/internal/reddit-trends`, `/internal/feed-items`); maps web hits to the
  uniform `TrendHit`.
- `http/MediaStoreClient` — multipart `POST /v1/media` (stores the rendered HTML board).
- `web/IntentController` — `POST /agents/creator/intent` (profile cue → profiler; trend cue →
  strategist; else chat).
- `web/ManifestController` — `GET /agents/creator/manifest`.

## Skills

- `creator-profiler` (`domains/creator/skills/creator-profiler/SKILL.md`) — strict-JSON creator-track
  extraction (scope self|household, niche, audience, tone, platforms, goals, guardrails) from a typed
  message.
- `content-strategist` (`domains/creator/skills/content-strategist/SKILL.md`) — synthesises a content
  plan (3–5 trends + 10 ideas + 2–3 drafts + per-platform format tips) from the gathered trend corpus;
  grounded in the corpus, respects guardrails, plain readable text for the HTML board.

## AGENT.md

`name: creator`, binds `mcp-creator` + `mcp-web` + `mcp-youtube` + `mcp-reddit` + `mcp-feeds`,
`skills: creator-profiler, content-strategist`. Guardrails: respect platform rules, no clickbait,
friendly-expert tone, never invent a trend or a source link.
