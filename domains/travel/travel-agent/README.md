# travel-agent

On-demand **vacation planner** + **multi-currency trip wallet** (port **8124**). Designs a trip from a
stated wish, keeps the per-person travel preferences, and tracks a family trip's money across currencies.
A **cold** agent (started on demand, not always-on). Owns the `mcp-travel` domain-MCP; binds the shared
`mcp-weather` (geocoding + climate), `mcp-web` (destination research), `mcp-chart-render` (the board's
climate chart) and `mcp-travel-search` (TR-f2 live flight/hotel options) capabilities. Routes via the
orchestrator as `travel`. **Never books or pays** — proposes options and provider links only
([ADR-0003](../../../plans/adr/ADR-0003-travel-data-source.md)). Plan:
[plans/travel.md](../../../plans/travel.md).

## Status (TR-f2 planner + EX-b/EX-c trip wallet + RT-c route import + PK-a packing list)

Six flows behind the intent split (**route file** → preferences → wallet → packing → plan → chat). A `file`
attachment is checked first (an unambiguous import), then the text cues:

- **route-import** (RT-c/RT-d2) `file|link → store → board` flow: a `file` attachment (a route/itinerary
  sent as a document) **or** a **map link in the message text** (Google/Yandex/OSM/`geo:`, RT-d2) → for a
  file, fetch the bytes from media-service (`GET /v1/media/{id}`) and **sniff the format from the content**
  (ZIP magic → KMZ; leading `{`/`[` → GeoJSON; `<gpx>`/`<kml>` → GPX/KML — no filename needed); for a link,
  import it as `maplink` (coordinates parsed out of the URL, RT-d1) → `importRoute` into `mcp-travel`,
  attaching it to the household's **active trip** when there is one → render a route board (point count,
  distance, an **OpenStreetMap map link** built from the first point — a plain URL, no external call) via
  the shared `DeliverablePublisher` → reply. Unknown formats, empty files and unparseable **short links**
  (`goo.gl`, `yandex.ru/maps/-/…` — need `mcp-browser`, TR-f3) soft-fail with a friendly message pointing at
  sending a GPX/KML file; the board soft-fails to text-only. KMZ bytes are base64-encoded into the store's
  `content` (RT-b). The agent parses owner-supplied bytes/URLs only — it never fetches a remote map or
  transmits the file. The map-link check runs **after** the plan/wallet cues, so "хочу на море `<link>`"
  still plans while a bare link pins the place.

- **travel-profiler** (TR-c) write path: a preferences cue → one llm-gateway extract via the
  `travel-profiler` SKILL → geocode the stated home-base city via `mcp-weather /internal/geocode`
  (soft-fail) → upsert via `mcp-travel /internal/travel-profile`. Vocabulary enforcement lives here (the
  write path): `restTypes` is filtered to `beach|active|family|couple|city|ski|wellness` and `companions`
  to `solo|couple|family` before the upsert, so an out-of-vocabulary value never reaches the store.
- **packing-list** (PK-a, [#438](https://github.com/fedoroff-vlad/ai-life/issues/438)) `gather → compose`
  flow: a packing cue ("что взять с собой", "собери список вещей", "packing list") → resolve the
  household's **active trip** (optional) + the `travel_profile` (self → household-default → empty) → derive
  the trip's **season band** from its destination+month via the existing geocode→climate chain →
  `PackingListComposer` builds a **categorized list** (documents/electronics/hygiene + climate-band
  clothing/footwear + rest-type activity gear + children items, globally deduped) → reply + an HTML
  **packing board** via the shared `DeliverablePublisher`. The list is **deterministic Java, never the LLM**
  (a correctness boundary like the wallet's `TripLedger`; also why PK-a needs no golden). Every source
  soft-fails: no active trip → a profile-only generic list + a nudge to create one; no destination/date or a
  climate hiccup → the climate-driven items drop and the list notes the weather is unconfirmed; a render
  hiccup → text-only. Reuses the wallet/planner clients (`TripWalletClient`, `TravelProfileClient`,
  `GeocodeClient`, `ClimateClient`) — no new store, client, or contract.
- **trip-planner** (TR-d) `gather → synthesize` flow: a plan-a-trip cue → resolve the profile (self →
  household-default → empty) → one **FAST** scope extract (named destination + month) → geocode the
  destination → **gather in parallel** on the shared `Coordinator`: the household **budget** and **free
  dates** as read-only cross-agent `brief` answers from the `finance`/`calendar` agents (through the
  orchestrator hub), the destination's **season** from `mcp-weather /internal/climate`, and qualitative
  **research** from `mcp-web /internal/search` → one `trip-composer` **DEFAULT** synthesis → a concise
  plan (route + season verdict + budget check) grounded in the web links. Per-source soft-fail; a missing
  finance brief falls back to the profile's `budgetHint` (unverified); no named destination skips climate.
  **Never books or pays** ([ADR-0003](../../../plans/adr/ADR-0003-travel-data-source.md)) — options +
  links only.
  - **TR-f2 live options:** when the FAST scope spots that the owner wants concrete tickets/hotels
    (`найди билеты`, `подбери отель`, `find flights`) for a named destination + month, the gather grows a
    live-options step over the shared `mcp-travel-search` capability (`/internal/resolve-place` →
    `/internal/search-flights` + `/internal/search-hotels`): resolve the destination (+ home-base origin) to
    search codes, search flights + hotels, **rank flights min-transfers→price**, flag any offer over the
    profile's `budgetHint` (marked "над бюджетом", never hidden), and fold the ranked options + provider
    **deep links** into both the `trip-composer` synthesis context and the board (a link per offer). The
    capability is **owner-key-gated**: with no Travelpayouts key it reports `unconfigured` and the planner
    **degrades to the MVP plan** + tells the owner live search isn't set up. Still **never books** — options
    + links only. Routing cues for the live ask live in `web/IntentController` (`PLAN_CUES`).
  - **TR-e HTML travel board (closer):** the synthesis is rendered to an HTML board — the plan text as a
    section, the gathered web sources as grounded provenance links, and the destination's **climate-by-month
    curve** as a line chart (rendered by the shared `mcp-chart-render` capability) — via the shared
    `DeliverablePublisher` (render → store in media-service → link); the open-link is appended to the reply.
    Chart and board are soft-failed independently: a render/store hiccup ships the text-only plan. The
    full 12-month climate curve is fetched for the board and also grounds the season verdict. Same board
    seam as briefing/finance.

- **trip-wallet** (EX-b) `cue → action` flow: a wallet cue ("создай поездку", "завёл 500 $ по 90",
  "поменял 36000 ₽ на 40000 бат", "потратил 2000 бат на …", "сколько осталось") → one `WalletExtractor`
  LLM turn classifies the message into a create / fund / exchange / spend / tally action (ISO-4217
  currency codes) → the store call over `mcp-travel /internal/trips/*`. fund/exchange/spend/tally attach
  to the household's **active trip** (`/internal/trips/active`, most recent non-closed) without the owner
  naming it. A **tally** reads the raw ledger and runs the deterministic `TripLedger` — per-currency
  remaining + a single **₽ total** by the owner's stated acquisition rates, unset-rate currencies flagged
  "курс не задан" — then renders an HTML **wallet board** via the shared `DeliverablePublisher`
  (soft-failing to text-only on a render hiccup). An on-site exchange is one paired op (source outflow +
  acquired inflow) so the ₽ tally never double-counts; **balance math is deterministic Java, never the
  LLM**; there is **no "who owes whom"** — one family budget, no settlement.
  A **close** action (EX-c — "закрой поездку", "заверши поездку", "close/finish the trip") wraps a
  finished trip: final tally → `closeTrip` in the store (drops it from the active set) → deposit a finance
  **spend-signal** note (the trip's ₽ spend = `TripLedger.totalSpentInHome`, expenses only, excluding
  exchange transfers) into the shared second brain via `MemoryClient.note`, where finance's read-only
  `brief` recall surfaces it — the travel↔finance seam, **decoupled** (travel never calls finance
  directly). The note write is best-effort (soft-fails; closing never blocks on memory-service).

Non-plan, non-config messages fall through to the conversational chat fallback. Mirrors `briefing-agent`
(BR-c2 profiler + BR-d `BriefingComposer`) and the finance report board (`MonthlyReporter`).

## HTTP surface

| method | path | body | purpose |
|--------|------|------|---------|
| POST | `/agents/travel/intent` | `NormalizedMessage` | reactive entrypoint: a `file` attachment → `RouteFlow.handle` (route import); else preferences cue → `travel-profiler`; wallet cue → `WalletFlow`; packing cue → `PackingFlow` (PK-a); plan-a-trip cue → `trip-composer`; else a **map link** in the text → `RouteFlow.handleLink` (RT-d2); else → chat fallback. |
| GET | `/agents/travel/manifest` | — | the `AgentManifest` (AGENT.md frontmatter) the orchestrator scrapes for routing. |

## Env

| Var | Default | Purpose |
|---|---|---|
| `TRAVEL_AGENT_PORT` | `8124` | HTTP port. |
| `MCP_TRAVEL_URL` | `http://mcp-travel:8123` | travel domain-MCP (`/internal/travel-profile`). |
| `MCP_WEATHER_URL` | `http://mcp-weather:8113` | shared weather capability (`/internal/geocode`; TR-d `/internal/climate`). |
| `MCP_WEB_URL` | `http://mcp-web:8098` | shared web capability (`/internal/search` for the TR-d research gather). |
| `MCP_TRAVEL_SEARCH_URL` | `http://mcp-travel-search:8125` | shared travel-search capability (TR-f2 `/internal/resolve-place`, `/internal/search-flights`, `/internal/search-hotels`). Owner-key-gated → degrades. |
| `MEDIA_SERVICE_URL` | `http://media-service:8088` | blob store for the TR-e HTML board (`POST /v1/media`). |
| `TRAVEL_PUBLIC_MEDIA_BASE_URL` | `http://media-service:8088` | externally-reachable base for the board open-link. |
| `MCP_CHART_RENDER_URL` | `http://mcp-chart-render:8120` | shared chart-render capability (`/internal/render`) for the TR-e climate chart. |
| `ORCHESTRATOR_URL` | `http://orchestrator:8083` | the hub the TR-d planner reaches to invoke the `finance`/`calendar` `brief` action. |
| `TRAVEL_AGENT_MCP_CLIENT_ENABLED` | `true` | toggle the Spring AI MCP-SSE client (off in dev/degraded envs). |
| `TRAVEL_AGENT_MEMORY_RECALL_K` | `5` | memory-recall depth for the shared agent-runtime clients. |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` / `MEMORY_SERVICE_URL` | internal | the shared agent-runtime platform clients (`MEMORY_SERVICE_URL` also backs the EX-c finance spend-signal note write). |

## Key classes

- `TravelAgentApplication` — `@SpringBootApplication` + `@Import(AgentRuntimeConfig)`.
- `config/TravelAgentProperties` (`travel-agent.*`, implements `SharedClientProperties`) +
  `config/OutboundHttpConfig` (`mcpTravel`/`mcpWeather`/`mcpWeb`/`orchestrator`/`mediaService`/
  `mcpChartRender` WebClient beans + the `OrchestratorInvokeClient`, `MediaStoreClient` and the TR-e
  `DeliverablePublisher`).
- `profile/TravelProfiler` — the LLM extract → geocode → upsert flow; **vocabulary filtering** for
  `restTypes`/`companions`.
- `flow/WalletFlow` — the trip-wallet flow: extract → store dispatch (create/fund/exchange/spend) or
  tally (fetch ledger → `TripLedger` → text + HTML wallet board via `DeliverablePublisher`). Resolves the
  household's active trip for non-create actions. **close** (EX-c) tallies → `closeTrip` → deposits a
  finance spend-signal note via `MemoryClient.note` (best-effort) → final board.
- `flow/WalletExtractor` (+ nested `WalletAction`) — the one LLM turn classifying a wallet message
  (create/fund/exchange/spend/tally/close) with ISO-4217 codes; no balance math.
- `flow/TripLedger` (+ `WalletTally`) — the **deterministic** multi-currency balance engine over the raw
  ledger rows: per-currency remaining (fundings + exchange-in − expenses − exchange-out), a weighted-average
  ₽ home-rate per currency (exchange-in priced single-level from the source), the ₽ total remaining,
  `totalSpentInHome` (the EX-c spend signal — expenses only, excluding exchange transfers), and unset-rate
  flags. Pure Java, never the LLM (a correctness/privacy boundary).
- `http/TripWalletClient` — the `mcp-travel /internal/trips/*` store calls (create / active / funding /
  exchange / expense / ledger / close).
- `flow/PackingFlow` (PK-a) — the packing-list flow: resolve active trip (optional) + profile → derive the
  season band from the trip's destination+month (geocode→climate, soft-fail) → `PackingListComposer` →
  reply + HTML packing board via `DeliverablePublisher`. Reuses `TripWalletClient`/`TravelProfileClient`/
  `GeocodeClient`/`ClimateClient`; no new client.
- `flow/PackingListComposer` (PK-a) — the **deterministic** seed-and-combine list engine (essentials +
  `ClimateBand` clothing/footwear + rest-type activity gear + children items, globally deduped in category
  order). Pure Java, never the LLM; nested `PackingContext`/`PackingList`/`Category`/`ClimateBand`.
- `flow/RouteFlow` (RT-c/RT-d2) — the route-import flow: `handle` (a file: fetch bytes → `sniffFormat`) /
  `handleLink` (a map URL → `maplink`) → resolve the active trip → `importRoute` → route board (point count
  + distance + OpenStreetMap map link) via `DeliverablePublisher`. `http/RouteImportClient`
  (`mcp-travel /internal/routes`) + `http/MediaFetchClient` (`media-service GET /v1/media/{id}`, reuses the
  `mediaServiceWebClient`). `IntentController.mapLink` detects a Google/Yandex/OSM/`geo:` URL in the text.
- `flow/TripComposer` — the TR-d/TR-e/TR-f2 `gather → synthesize` planner: profile resolve → FAST scope
  extract (destination + month + `live`) → parallel gather (finance/calendar `brief`, 12-month climate,
  web search, **+ TR-f2 live flight/hotel options when `live`**) → one `trip-composer` synthesis → the
  TR-e HTML board (climate-by-month chart + plan + option deep links + provenance links) via
  `DeliverablePublisher`, open-link appended to the reply (soft-failed to text-only). Live options are
  ranked min-transfers→price, flagged over-budget, and degrade to the MVP plan when the capability is
  `unconfigured`.
- `http/TravelProfileClient` (upsert/resolve `mcp-travel`) + `http/GeocodeClient` (`mcp-weather` geocode)
  + `http/ClimateClient` (`mcp-weather` climate) + `http/WebSearchClient` (`mcp-web` search) +
  `http/ChartRenderClient` (`mcp-chart-render` `/internal/render` for the board's climate chart) +
  `http/TravelSearchClient` (`mcp-travel-search` resolve-place/search-flights/search-hotels, TR-f2).
- `chat/TravelChat` — conversational fallback for non-config, non-plan messages.
- `web/IntentController` (`/agents/travel/intent`, cue split) + `web/ManifestController`
  (`/agents/travel/manifest`).
- `AGENT.md` (manifest) + `../skills/travel-profiler/SKILL.md` (the extract prompt) +
  `../skills/trip-composer/SKILL.md` (the synthesis prompt) + `../skills/trip-wallet/SKILL.md` (the EX-b
  wallet-action extract prompt).
