# creator domain — content factory (per-user trend → ideas → drafts)

Authority file for the **creator / content-factory domain**: the **`mcp-creator`** domain-MCP (owns
the `creator` schema), the **`creator-agent`** (the trend → ideas → drafts → format-recs flow), and
the source capability-MCPs it gathers from (`mcp-web` exists; `mcp-youtube` / `mcp-reddit` /
`mcp-feeds` land per slice; `mcp-browser` deferred). Owner-chosen after nutrition (2026-06-23); the
source / browser / per-user locks were set then. **Build now = the API/RSS MVP**, the JS/login-gated
social sources are deferred to a recorded `mcp-browser` slice.

This is the **researcher gather→synthesize shape, multi-source** — the cheapest reusable pattern in
the system applied to content creation. It also closes the long-deferred Stage-4 chain
`calendar.birthday_upcoming → creator.draft_greeting → notifier.send` (a `draft_greeting` action on
the hub, CR-g).

## The driving goal (owner, 2026-06-23)
Monitor trends across Threads / Telegram / Instagram / YouTube / Reddit / Pinterest for a client's
niche → in **10–15 min** deliver:
- **3–5 fresh trends** (with source links),
- **10 post ideas**,
- **2–3 ready drafts** (title / text / CTA / hashtags),
- **per-platform format recommendations**.

Example client niche: "English for IT". Tone: **friendly-expert, no fluff**. Guardrails: **respect
platform rules, no clickbait**.

## Owner decisions — LOCKED (2026-06-23)
1. **MVP = API/RSS sources only** — YouTube Data API, Reddit API, Telegram (public channels), web
   search (SearXNG via the existing `mcp-web`). **Threads / Instagram / Pinterest have no usable API
   → deferred** to a later `mcp-browser` slice: better token economy, no anti-bot/ToS risk, faster.
2. **Free software FIRST** — GitHub libs, MCP-in-Docker, official sources before anything paid
   (reinforces tooling-simplicity + OSS-reuse). SerpAPI / Bing stay optional behind the swappable
   `SearchEngine` already in `mcp-web`. Each source capability hides its upstream behind a swappable
   `*Source` interface (the Stooq / SearXNG / Open Food Facts precedent).
3. **Per-user content tracks** — a per-owner `creator_profile` keyed `(household_id, owner_id)`
   (null-owner = household default), the same pattern as `diet_profile` / `style_profile`. This is
   the owner's broader "personalise part of the info per user" idea (stylist / nutrition / creator
   share it). MVP serves me + wife; more owners are just more rows.
4. **browser-use is an engine, not a rebuild.** When the deferred `mcp-browser` lands it **reuses
   browser-use behind a swappable capability** (same as whisper-sidecar / Stooq). `mcp-web` (SearXNG
   search + jsoup static fetch) stays the lean default; a browser engine is only for JS/login-gated
   social platforms, and even then **official APIs come first**.
5. **Token economy is STRUCTURAL** — every gather step is plain HTTP/API (no model cost); the LLM
   fires **once**, on the final synthesis. Exactly the `researcher` flow, fanned out to N sources.
6. **Deliverable = HTML via the shared `libs/doc-render`** (the creator is the next doc-render
   consumer after stylist / nutrition / chef). PDF later behind the same seam.

## Shape
- `domains/creator/mcp-creator` — **domain-MCP**, **port 8108**, owns the **`creator`** schema
  (Liquibase `060-creator.yml`; `060-069` reserved for creator in PATTERNS). CRUD over the per-owner
  profile/track, a trend cache, and the idea/draft history. Template: `domains/nutrition/mcp-nutrition`.
- `domains/creator/creator-agent` — **domain agent**, **port 8109**. Binds `mcp-creator` (its data) +
  the source capabilities (`mcp-web` now; `mcp-youtube` / `mcp-reddit` / `mcp-feeds` as they land)
  over SSE + their `/internal/*` passthroughs; gathers trends on the shared `Coordinator`, synthesises
  the deliverable, renders HTML via `libs/doc-render`, stores in media-service, replies with a link.
  Template: `domains/researcher/researcher-agent` (extended to multi-source, like the nutritionist).
- `shared/mcp/mcp-youtube` — **capability-MCP**, **port 8110**, no schema. `youtube_trends(query|topic)`
  over the **YouTube Data API v3** behind a swappable `VideoTrendsSource` (key via env, free quota).
- `shared/mcp/mcp-reddit` — **capability-MCP**, **port 8111**, no schema. `reddit_trends(subreddit|query)`
  over the **Reddit API** behind a swappable `SocialTrendsSource` (OAuth app creds via env, free).
- `shared/mcp/mcp-feeds` — **capability-MCP**, **port 8112**, no schema. `feed_items(url|channel)`
  over **RSS + public Telegram channels** behind a swappable `FeedSource` (Rome/jsoup; no key).
- `shared/mcp/mcp-browser` — **DEFERRED** capability-MCP (port 8113 reserved), `browser-use` engine,
  for Threads / Instagram / Pinterest. Not built in the MVP.
- Contracts in `libs/contracts/.../creator/`: `CreatorProfileDto`(+`SetCreatorProfileInput`),
  `TrendDto`(+`SaveTrendInput`), `ContentPieceDto`(+`SaveContentPieceInput`), and the source DTOs
  `trends/TrendHit`(+ the per-source `*Input`).

## Schema `creator` (060-creator.yml)
- `creator_profile` — id, household_id, owner_id (one per person, unique `(household_id, owner_id)`,
  null-owner = household default — like `diet_profile` / `style_profile`), niche, audience, tone,
  platforms jsonb (target platforms), goals, guardrails jsonb (no-clickbait / brand rules), notes,
  updated_at. The per-user content track.
- `trend` — id, household_id, owner_id, source (web|youtube|reddit|telegram|rss), platform, title,
  url, summary, metrics jsonb (score / engagement when the source gives it), captured_at, created_at.
  The trend cache (dedupe by `(owner, url)`); lets a run reuse recent gathers and show provenance.
- `content_piece` — id, household_id, owner_id, kind (idea|draft), platform, title, body, cta,
  hashtags jsonb, status (new|kept|posted), trend_id (nullable provenance), created_at. The idea /
  draft history (a draft is an idea promoted with body/CTA/hashtags).
- **Phase 2:** a `schedule`/`calendar` of planned posts — added with the planning slice.

## Capabilities the agent gathers from
The agent is a thin multi-source gatherer; each source is a **capability-MCP** any agent can reuse:
- **`mcp-web`** (have) — SearXNG `web_search` + jsoup `fetch_url`. General trend/article search.
- **`mcp-youtube`** (YT-a) — `youtube_trends`: trending/most-relevant videos for a query/topic.
- **`mcp-reddit`** (RD-a) — `reddit_trends`: hot/top posts for a subreddit/search.
- **`mcp-feeds`** (FE-a) — `feed_items`: latest items from an RSS feed or a public Telegram channel.
- **`mcp-browser`** (deferred) — Threads / Instagram / Pinterest via a browser-use engine.

Each source returns a uniform `trends/TrendHit{source, platform, title, url, summary?, metrics?}` so
the Coordinator folds them into one corpus regardless of origin (the researcher's `WebSearchHit`
generalised across sources).

## PR-sized slices
Foundation:
- **CR-0 — docs opener. DONE (this PR):** creator.md + INDEX + roadmap + PATTERNS (060-069 = creator)
  + STATUS.
- **CR-a — `mcp-creator` + `060-creator.yml`** (`creator_profile` + `trend` + `content_piece`) + CRUD
  tools + `creator/*` contracts. Testcontainers repo test. Template `mcp-nutrition`. `/internal/*`
  passthroughs land with the agent flows (CR-c/d/e, mirror NU-c1). **DONE (PR174):**
  `domains/creator/mcp-creator` (port 8108, owns the new `creator` schema — first creator module).
  Migration [060-creator.yml](../infra/liquibase/features/060-creator.yml) — three tables:
  `creator_profile` (one per person via the unique `(household_id, owner_id)` index, null-owner =
  household-default: niche/audience/tone + `platforms`/`guardrails` jsonb), `trend` (cached trend:
  source/platform/title/url/summary + `metrics` jsonb + captured_at), `content_piece` (idea/draft:
  kind + body/cta + `hashtags` jsonb + status + soft `trend_id` provenance with no FK — the cache is
  evictable). `creator` schema added to [postgres/init.sql](../infra/postgres/init.sql). Eight `@Tool`
  methods on `CreatorMcpTools` — profile (`set_creator_profile`/`get_creator_profile`, keyed
  (household,owner) via the native-SQL `CAST` finder, mirrors mcp-nutrition), trend cache
  (`save_trend`/`list_trends`), content pieces (`save_content_piece`/`list_content_pieces`/
  `get_content_piece`/`delete_content_piece`). New contracts `creator/{CreatorProfileDto,
  SetCreatorProfileInput, TrendDto, SaveTrendInput, ContentPieceDto, SaveContentPieceInput}` (jsonb
  fields = `JsonNode`). Scaffold per PATTERNS.md (template `mcp-nutrition`, minus the bus consumer —
  no inbound events yet); webflux MCP server, JPA over Postgres, **no `/internal/*` passthroughs yet**
  (they land with the agent flows CR-c/d/e). Tested (`McpCreatorIntegrationTest`, 7 cases: profile
  upsert-in-place with jsonb roundtrip + one-row-per-(household,owner), get-null-then-profile,
  required-field guards, trend store/defaults + list scoping by household/owner + newest-captured +
  limit + isolation, content-piece status default + list-by-kind + get, delete returns-row). Wired
  into root pom `<modules>`, compose (`depends_on: postgres+liquibase`, no agent deps), `.env.example`,
  infra/README (port 8108 + `creator` schema row). Full reactor compiles; module suite green (8 tools
  registered).
- **CR-b — `creator-agent` scaffold + orchestrator registration** (binds `mcp-creator` + `mcp-web`;
  `IntentController` chat fallback). Template `researcher-agent` / `nutritionist-agent`. **DONE (PR175):**
  `domains/creator/creator-agent` (port 8109), `@Import(AgentRuntimeConfig)`; AGENT.md
  (`name: creator`, mcp: `mcp-creator` + `mcp-web`, `skills: []` until CR-c, + the no-clickbait /
  platform-rules guardrails) served at `GET /agents/creator/manifest`. Binds the two MCPs over SSE
  (future LLM-driven selection; the CR-c.. flows call them over `/internal/*` HTTP passthroughs).
  `IntentController` ships the `chat/CreatorChat` fallback (one LLM turn, AGENT.md as system prompt —
  replaced branch-by-branch as flows land). `config/CreatorAgentProperties` (mcp-creator/mcp-web +
  profile/notifier/memory) + `config/OutboundHttpConfig` (`mcpCreator`/`mcpWeb` WebClients + the
  qualified profile/notifier/memory beans the shared runtime clients pick up). Registered in
  orchestrator `application.yml` (`{name: creator}` + `CREATOR_AGENT_URL`, manifest-driven — no
  orchestrator Java change). Scaffold per PATTERNS.md "new agent" (template `researcher-agent`/
  `nutritionist-agent`). Tested (`ManifestControllerTest`: full context boots MCP-client-disabled,
  AGENT.md parsed, runtime beans + LlmClient + CreatorChat resolve, manifest lists mcp-creator +
  mcp-web). Wired into root pom, compose (creator-agent service `depends_on` llm-gateway +
  profile/notifier/memory + mcp-creator + mcp-web; `CREATOR_AGENT_URL` in orchestrator env),
  `.env.example`, infra/README (port 8109). Full reactor compiles; module suite green.
- **CR-c — creator-profile flow** (multi-person): a typed profile cue → one LLM extract via a
  `creator-profiler` SKILL → upsert via `/internal/creator-profile` (self / household-default), the
  `diet-profiler` shape. Split c1/c2 (mirror NU-d). **CR-c1 DONE (PR176): mcp-creator
  `POST` + `GET /internal/creator-profile` passthroughs** → `set_creator_profile` / `get_creator_profile`
  (`web/InternalCreatorProfileController`, 400 on bad set / 404 on unset read; (household,owner)
  keying; mirror mcp-nutrition's `InternalDietProfileController`). Tested (`McpCreatorIntegrationTest`
  now 8: upsert-in-place + 400-guard + 404-then-200 read). **CR-c2 DONE (PR177): creator-agent
  creator-profiler flow — CR-c COMPLETE.** New `profile/CreatorProfiler`: a typed creator-profile cue
  → one llm-gateway `DEFAULT` turn with the new `creator-profiler` SKILL → extract `{scope, niche,
  audience, tone, platforms, goals, guardrails, notes}` → upsert via new `http/CreatorProfileClient`
  → `/internal/creator-profile`. Scope `self` → `ownerId = userId`, `household` → `ownerId = null`
  (default). `IntentController` routes a profile cue ("моя ниша…", "мой контент про…", "my niche…") →
  creator-profiler, else the `CreatorChat` fallback. New skill `domains/creator/skills/creator-profiler/SKILL.md`;
  AGENT.md `skills:` += creator-profiler. The `diet-profiler` shape. Tested (`CreatorProfilerTest`, 3:
  self-scope upsert with platforms/guardrails; household-scope → ownerId omitted; "not a profile" →
  reply, no write). Module suite green (4).

Sources (each a capability-MCP, mirror MD-a / FD-a):
- **YT-a — `mcp-youtube`** `youtube_trends` over the YouTube Data API v3 behind `VideoTrendsSource` +
  `/internal/youtube-trends`. MockWebServer test (no key in CI). **DONE (PR178):**
  `shared/mcp/mcp-youtube` (port 8110, no schema — first creator source). One
  `GET /search?part=snippet&type=video&order=relevance` call → each item → a uniform
  `trends/TrendHit{source, platform, title, url, summary?, metrics?}` (the `watch?v=` link, channel +
  publishedAt in `metrics`; the researcher's `WebSearchHit` generalised across sources). Behind a
  swappable `engine/VideoTrendsSource` (`youtube.source=youtubedata` default, sibling of
  `FoodDataSource`/`MarketDataSource`). **Free quota needs `YOUTUBE_API_KEY`; blank key → empty list**
  (graceful soft-fail for the multi-source gather; no key in CI). New contracts
  `trends/{TrendHit, YoutubeTrendsInput}`. `POST /internal/youtube-trends` passthrough (mirror
  `InternalFoodLookupController`). Scaffold per PATTERNS "capability-MCP" (template `mcp-food-data` —
  webflux MCP server, no JPA/datasource, no backing container). Tested
  (`InternalYoutubeTrendsControllerTest`, 3 cases via MockWebServer: search response → 2 hits with
  metrics + correct query params; no matches → empty; blank query → empty, no upstream call). Wired
  into root pom, compose (no `depends_on`), `.env.example`, infra/README (8110). **Not bound yet** —
  creator-agent binds it in CR-d.
- **RD-a — `mcp-reddit`** `reddit_trends` over the Reddit API behind `SocialTrendsSource` +
  `/internal/reddit-trends`. **DONE (PR179):** `shared/mcp/mcp-reddit` (port 8111, no schema —
  second creator source, sibling of YT-a). App-only OAuth: one `POST /api/v1/access_token`
  (grant `client_credentials`, HTTP Basic with the app id:secret) mints a bearer token → one listing
  call (`/r/{sub}/hot` | `/r/{sub}/search` | global `/search`) → each post → a uniform
  `trends/TrendHit` (the permalink, subreddit + score + comment count in `metrics`). Behind a swappable
  `engine/SocialTrendsSource` (`reddit.source=redditapi` default). **Free API needs
  `REDDIT_CLIENT_ID` + `REDDIT_CLIENT_SECRET`; blank → empty list** (graceful soft-fail; no creds in
  CI). New contract `trends/RedditTrendsInput{subreddit?, query?, maxResults?}`;
  `POST /internal/reddit-trends` passthrough. Scaffold per PATTERNS "capability-MCP" (template
  `mcp-youtube`). Tested (`InternalRedditTrendsControllerTest`, 3 via one MockWebServer for both auth
  + API hosts: subreddit hot → token then listing → 1 hit w/ metrics + Bearer header; query-only →
  global `/search`; no target → empty, no upstream call). Wired into root pom, compose (no
  `depends_on`), `.env.example`, infra/README (8111). Not bound yet (creator-agent binds it in CR-d).
- **FE-a — `mcp-feeds`** `feed_items` over RSS + public Telegram channels behind `FeedSource` +
  `/internal/feed-items`. **DONE (PR180):** `shared/mcp/mcp-feeds` (port 8112, no schema — third
  creator source, sibling of YT-a/RD-a). `feed_items(source)` takes an RSS/Atom feed URL (starts with
  `http` → fetch + Rome `SyndFeedInput`) or a public Telegram channel handle (`durov`/`@durov` →
  fetch `t.me/s/{channel}` + jsoup parse `.tgme_widget_message`, newest-first) → each item → a uniform
  `trends/TrendHit` (platform `rss` with `{publishedAt, author}` metrics, or `telegram` with
  `{channel}`). Behind a swappable `engine/FeedSource` (`feeds.source=romejsoup` default). **No key**
  (both surfaces public). Added `com.rometools:rome` to the parent dependencyManagement (jsoup already
  managed). New contract `trends/FeedItemsInput{source, maxResults?}`; `POST /internal/feed-items`
  passthrough. Scaffold per PATTERNS "capability-MCP" (template `mcp-youtube`). Tested
  (`InternalFeedItemsControllerTest`, 3 via one MockWebServer: RSS XML → Rome → 2 hits w/ author +
  publishedAt; Telegram HTML → jsoup → 2 hits newest-first w/ channel; blank source → empty no fetch).
  Wired into root pom, compose (no `depends_on`), `.env.example`, infra/README (8112). Not bound yet
  (creator-agent binds it in CR-d). **All three sources (YT-a/RD-a/FE-a) now done — next is CR-d.**

The headline flow:
- **CR-d — the trend → ideas → drafts synthesis** (`Coordinator`, multi-source): gather
  `{web, youtube, reddit, feeds}` for the profile's niche/platforms in parallel (each soft-fails
  independently) → fold the `TrendHit` corpus into `context` → ONE LLM synthesis from `[AGENT.md,
  content-strategist SKILL.md] + {payload(profile, request), context}` → 3–5 trends + 10 ideas +
  2–3 drafts + per-platform format recs → render an HTML board via `libs/doc-render` (trend list with
  source links + idea list + draft cards) → store in media-service → reply with a link. Token economy
  structural (gather = HTTP, one LLM call). New `content-strategist` SKILL. **DONE (PR181):**
  `flow/ContentStrategist` on the shared `Coordinator`. A trend/ideas/draft cue (the new
  `IntentController` branch, after the profile cue) → resolve the creator track (self →
  household-default via `CreatorProfileClient`) → niche = profile.niche or the request text → gather
  `web` + `youtube` + `reddit` for the niche (and a named RSS/`@channel` `feed` if the request
  mentions one) in parallel via the new single `http/TrendGatherClient` (binds all four
  `/internal/*` passthroughs; maps web `WebSearchHit`→`TrendHit`) → ONE `DEFAULT` synthesis with the
  new `content-strategist` SKILL → render an HTML board via `libs/doc-render` (`config/RenderConfig`
  `DocRenderer` bean; the "План" section from the synthesis + a **provenance links section built
  deterministically from the gathered corpus** so links are real even on a mock LLM) → store via the
  new `http/MediaStoreClient` (`POST /v1/media`) → reply with first-line summary + the link. Per-source
  soft-fail (Coordinator omits a failed/empty source); render/store failure still returns the textual
  plan. `creator-agent` now binds `mcp-youtube`/`mcp-reddit`/`mcp-feeds` (AGENT.md `mcp:` + SSE
  connections) + `media-service`; `CreatorAgentProperties`/`OutboundHttpConfig` gain the source +
  media URLs + `publicMediaBaseUrl`; doc-render dep added. Tested (`ContentStrategistTest`, 2 via 6
  MockWebServers: full gather→synth→render→store→link with niche+corpus in the LLM body + HTML upload;
  one source 500 → still produces a plan). Wired into compose (`depends_on` the sources + media) +
  `.env.example`. **Persisting the gathered trends + generated pieces into the `mcp-creator` cache is
  CR-e.** **Feed gather is request-token-driven for now** (the profile has no monitored-feed list);
  a profile-level feed list can fold in later.
- **CR-e — trend cache. DONE (PR182).** After the synthesis, `creator-agent` persists the run into
  the `mcp-creator` cache via the new `http/CreatorCacheClient`: the gathered `TrendHit` corpus →
  `POST /internal/trends` in one batch (`web/InternalTrendController`, loops `save_trend`), and the
  synthesized plan → `POST /internal/content-piece` as a `draft` (`web/InternalContentPieceController`),
  both attributed to the speaker. `save_trend` is now **idempotent on the source link** — same
  `(householdId, ownerId, url)` returns the existing row instead of duplicating (`TrendRepository.findForUrl`,
  native-SQL `CAST` for the null-owner default; a null `url` never dedups), so re-running a gather is
  safe. Persist runs before render/store but **soft-fails** (the reply already carries the deliverable
  link). The content-piece keeps a single `draft` per run for now (extracting discrete ideas/drafts
  from the synthesis is a later refinement; the trend cache is the reuse target).

Inter-agent (closes the Stage-4 chain):
- **CR-g — `draft_greeting` action over the hub.** `creator-agent` `POST /agents/creator/actions/draft_greeting`
  (args `{person, occasion}`) → one LLM draft → returns the text. The calendar birthday wake
  (gift-recommender's sibling) invokes it via the orchestrator `/v1/agents/invoke`, then notifier
  delivers — closing `calendar.birthday_upcoming → creator.draft_greeting → notifier.send`. Mirrors
  CH-b2 (nutritionist → chef).

## Deferred (recorded vision — each maps to an architectural home)
| Vision item | Home | Why deferred |
|---|---|---|
| **Threads / Instagram / Pinterest sources** | `mcp-browser` (browser-use engine) | no usable API; needs a JS/login-capable browser engine — API/RSS sources cover the MVP. |
| **Generated post imagery** (thumbnails / carousels) | `mcp-image-gen` (stub→local GPU) | MVP ships text drafts + format recs; generated images ride the deferred GPU line. |
| **Post scheduling / calendar of planned content** | a `creator.schedule` table + scheduler-service | MVP delivers the ideas/drafts; scheduling is a Phase-2 planning slice. |
| **Auto-posting to platforms** | per-platform publish capabilities (outbound, confirm-before-act) | MVP is advisory (drafts the human posts); outbound actions need the confirm-before-act gate. |
| **Paid search/trend providers** (SerpAPI / Bing / social-listening) | the swappable `SearchEngine` / `*Source` interfaces | MVP is free-OSS-first; a keyed provider drops in behind the same seam when it earns its keep. |

## Out of scope (here)
- The deferred rows above — each its own later line with its own owner-locked engine/source.
- Real LLM synthesis quality — Stage 5 (mock LLM proves the wiring; the flow is model-agnostic).
