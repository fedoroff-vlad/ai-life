# mcp-creator

MCP server: source-of-truth creator CRUD over the `creator.*` schema (per-person content tracks +
a trend cache + the idea/draft history). The trend → ideas → drafts gather/synthesis flow lives in
`creator-agent`; this MCP just persists what the agent gathers and generates. First module of the
creator domain (Stage 6). See [plans/creator.md](../../../plans/creator.md).

## Tools (MCP)

- `set_creator_profile(householdId, ownerId?, niche?, audience?, tone?, platforms?, goals?,
  guardrails?, notes?)` — upsert a person's content track, keyed on (householdId, ownerId); null
  ownerId = household-default. Full set (every field overwrites). `platforms` (target platforms) and
  `guardrails` (no-clickbait / brand rules) are free-form JSON.
- `get_creator_profile(householdId, ownerId?)` — the person's track, or null if unset.
- `save_trend(householdId, ownerId?, source?, platform?, title, url?, summary?, metrics?,
  capturedAt?)` — cache a gathered trend. Only `householdId` + `title` are required; `capturedAt`
  defaults to now. `source` is web|youtube|reddit|telegram|rss; `metrics` the free-form per-source
  signal. **Idempotent on the source link:** a trend with the same `(householdId, ownerId, url)`
  already cached is returned as-is rather than duplicated, so re-running a gather is safe (a null
  `url` never dedups).
- `list_trends(householdId, ownerId?, limit?)` — cached trends in a household, most recently captured
  first; `ownerId` scopes to one person, `limit` caps the count (default 20, max 200).
- `save_content_piece(householdId, ownerId?, kind, platform?, title?, body?, cta?, hashtags?,
  status?, trendId?)` — save a generated piece. `householdId` + `kind` (idea|draft) required;
  `status` defaults to `new`. `body`/`cta`/`hashtags` carry a draft's full content; `trendId` is the
  optional provenance pointer.
- `list_content_pieces(householdId, kind?, limit?)` — pieces in a household, most recently created
  first; `kind` scopes to idea|draft.
- `get_content_piece(id)` — one piece by id, or null.
- `delete_content_piece(id)` — delete and return the deleted row (so the agent can confirm/undo).
  Throws on unknown id; confirming the destructive action is the agent layer's job.

Scope rule: every tool takes a `householdId` and reads/writes only within that household (mirrors
mcp-nutrition / mcp-wardrobe). Per-person attribution is the optional `ownerId`.

## Internal REST passthroughs

Non-MCP, no LLM tax — for an agent that already has a concrete input and just needs to persist/read
it (the MCP/SSE transport can't be MockWebServer'd, so deterministic calls use HTTP).

- `POST /internal/creator-profile` (body `SetCreatorProfileInput`) → `CreatorProfileDto` | 400 —
  upserts a person's creator track via `set_creator_profile` ((household,owner) keying applies). Used
  by the creator-profiler flow (CR-c).
- `GET /internal/creator-profile?householdId=&ownerId=` → `CreatorProfileDto` | 404 — reads the
  person's track (null ownerId = household-default); 404 when unset. Used by the trend gather (CR-d).
- `POST /internal/trends` (body `List<SaveTrendInput>`) → `List<TrendDto>` | 400 — caches a whole
  gathered batch in one round-trip via `save_trend` per item (the per-`(owner, url)` dedup applies).
  Used by the content-strategist flow's persist (CR-e).
- `POST /internal/content-piece` (body `SaveContentPieceInput`) → `ContentPieceDto` | 400 — saves a
  generated idea/draft via `save_content_piece`. Used by the content-strategist flow's persist (CR-e).

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_CREATOR_PORT` | `8108` | HTTP port |
| `MCP_CREATOR_DB_URL` | `jdbc:postgresql://localhost:5432/ailife` | Postgres |
| `MCP_CREATOR_DB_USER` / `MCP_CREATOR_DB_PASSWORD` | `ailife` | DB credentials |

## Key classes

- `McpCreatorApplication`.
- `domain/CreatorProfile` + `CreatorProfileRepository` — JPA over `creator.creator_profile`;
  `findForOwner` resolves the (household, owner) track (null owner = household-default, native-SQL
  `CAST` for the NULL-safe bind — same pgjdbc workaround as mcp-nutrition). `platforms`/`guardrails`
  jsonb → `JsonNode`.
- `domain/Trend` + `TrendRepository` — JPA over `creator.trend` (list by household, optionally by
  owner, newest-captured first; `findForUrl` is the native-SQL `CAST` dedup lookup by
  `(household, owner, url)`). `metrics` jsonb → `JsonNode`.
- `domain/ContentPiece` + `ContentPieceRepository` — JPA over `creator.content_piece` (list by
  household, optionally by kind, newest-created first). `hashtags` jsonb → `JsonNode`.
- `tools/CreatorMcpTools` — eight `@Tool` methods: creator profile (`set_creator_profile`,
  `get_creator_profile`), trend cache (`save_trend`, `list_trends`), content pieces
  (`save_content_piece`, `list_content_pieces`, `get_content_piece`, `delete_content_piece`). The
  only invariants enforced here are the household scope and required-field checks.
- `tools/ToolsConfig` — `MethodToolCallbackProvider`.
- `web/InternalCreatorProfileController` — `POST /internal/creator-profile` (upsert, 400 on bad
  input) + `GET /internal/creator-profile` (read, 404 when unset), over `set_creator_profile` /
  `get_creator_profile`. Mirrors mcp-nutrition's `InternalDietProfileController`.
- `web/InternalTrendController` — `POST /internal/trends` (batch cache, 400 on a bad item) over
  `save_trend` per item; the content-strategist persist (CR-e).
- `web/InternalContentPieceController` — `POST /internal/content-piece` (save a draft, 400 on bad
  input) over `save_content_piece`; the content-strategist persist (CR-e).

## Schema

- [060-creator.yml](../../../infra/liquibase/features/060-creator.yml) —
  `creator.creator_profile` (one per person via the unique `(household_id, owner_id)` index:
  niche/audience/tone + `platforms`/`guardrails`) + `creator.trend` (one row per cached trend:
  source + link + `metrics`) + `creator.content_piece` (one row per generated idea/draft: kind +
  body/cta + `hashtags` + `status` + soft `trend_id` provenance, no FK — the trend cache is evictable).
