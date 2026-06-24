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
  passthroughs land with the agent flows (CR-c/d/e, mirror NU-c1).
- **CR-b — `creator-agent` scaffold + orchestrator registration** (binds `mcp-creator` + `mcp-web`;
  `IntentController` chat fallback). Template `researcher-agent` / `nutritionist-agent`.
- **CR-c — creator-profile flow** (multi-person): a typed profile cue → one LLM extract via a
  `creator-profiler` SKILL → upsert via `/internal/creator-profile` (self / household-default), the
  `diet-profiler` shape.

Sources (each a capability-MCP, mirror MD-a / FD-a):
- **YT-a — `mcp-youtube`** `youtube_trends` over the YouTube Data API v3 behind `VideoTrendsSource` +
  `/internal/youtube-trends`. MockWebServer test (no key in CI).
- **RD-a — `mcp-reddit`** `reddit_trends` over the Reddit API behind `SocialTrendsSource` +
  `/internal/reddit-trends`.
- **FE-a — `mcp-feeds`** `feed_items` over RSS + public Telegram channels behind `FeedSource` +
  `/internal/feed-items`.

The headline flow:
- **CR-d — the trend → ideas → drafts synthesis** (`Coordinator`, multi-source): gather
  `{web, youtube, reddit, feeds}` for the profile's niche/platforms in parallel (each soft-fails
  independently) → fold the `TrendHit` corpus into `context` → ONE LLM synthesis from `[AGENT.md,
  content-strategist SKILL.md] + {payload(profile, request), context}` → 3–5 trends + 10 ideas +
  2–3 drafts + per-platform format recs → render an HTML board via `libs/doc-render` (trend list with
  source links + idea list + draft cards) → store in media-service → reply with a link. Token economy
  structural (gather = HTTP, one LLM call). New `content-strategist` SKILL.
- **CR-e — trend cache** — persist the gathered `TrendHit`s into `trend` (dedupe by `(owner, url)`),
  and the generated ideas/drafts into `content_piece`, so a run shows provenance and a later run can
  reuse a recent gather. Bind `mcp-creator`'s write passthroughs.

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
