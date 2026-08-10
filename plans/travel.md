# travel — on-demand vacation-planning agent

Authority file for the **travel-agent** (issue [#190](https://github.com/fedoroff-vlad/ai-life/issues/190))
and the one capability it extends, **`mcp-weather`** (a `climate` tool). First plan authored
**WHEN/THEN-first** (see [`CLAUDE.md`](../CLAUDE.md) §Spec each slice).

## What it is
An **on-demand vacation planner**: the owner says "хочу на море в сентябре тысяч на 200" and the agent
designs a trip — a route/itinerary, the season fit for the destination, and a budget check against real
finance data — delivered as a short reply + an HTML board. It is a **cold** agent (started on demand, not
always-on — a cold-set candidate in [lifecycle.md](lifecycle.md); in dev it is a compose service off by
default). It is the same **multi-domain _read_ coordinator** shape as briefing/creator/researcher: gather
from existing domains/capabilities on the shared `Coordinator`, then one LLM synthesis, then render.

## Doctrine (no new layer)
Everything in the MVP reuses an existing pattern — flag nothing new:
- **Gather → synthesize** on `libs/agent-runtime` `Coordinator` (same shape as briefing/creator), fanned
  out to budget + dates + season + qualitative-research sources. Per-source soft-fail is built in.
- **"По данным финансиста"** = the read-only **`brief`** cross-agent query ([stage4.md](stage4.md)) —
  travel-agent is a `brief` **caller** (like `coordinator-agent`), reading the finance budget/spending
  snapshot and the calendar free-dates snapshot. It never touches `finance.*` / `calendar.*` directly.
- **Season** = `mcp-weather` — already built (forecast + geocode over Open-Meteo, free/no-key); the MVP
  adds a schema-less **`climate`** tool (monthly normals) as the season substrate.
- **Qualitative research** = `mcp-web` (SearXNG search + `fetch_url`) for destination ideas, season
  reviews, and general deal roundups — the same cheap-first, free/keyless source briefing uses for news;
  soft-fail. This is **information** (articles, recommendations), **not** live pricing/availability — the
  latter needs a real provider source (ADR-0003) that `mcp-web` cannot replace (JS-rendered aggregators
  yield only boilerplate via jsoup — the repo's own live finding, roadmap §Evaluated tools).
- **Deliverable** = `libs/doc-render` HTML board → media-service blob → link, with a climate-by-month
  chart via `mcp-chart-render`. Both already used by briefing/finance.
- **Personalization** = a `travel_profile` keyed `(household, owner)` — the same pattern as
  briefing/diet/style/creator profiles.

## The one genuinely-new thing → external booking/pricing source (flagged, not invented)
Live flight/hotel/tour **pricing and booking** is the first place ai-life's "free self-hosted OSS, no
keys" default collides with reality — those are external commercial sources, mostly behind a key or
JS-only. That decision (which source, and the hard boundary that **the agent never books or pays**) is
**[ADR-0003](adr/ADR-0003-travel-data-source.md)** — read it before any live-pricing slice. **The MVP
below deliberately excludes live pricing/booking** (planner-only), so it needs no paid dependency and no
new capability; live search is a follow-on gated on the ADR.

## Personalization — first-class (same pattern as briefing/diet/style)
The plan is **per-person** and configurable in natural language. A `travel_profile` keyed
`(household, owner)` holds:
- **home_base** `{label, latitude, longitude}` — where trips depart from; stated in chat, geocoded via
  `mcp-weather.geocode` (no device GPS — a stated preference, exactly like the briefing location).
- **rest_types** `string[]` — preferred vacation kinds from a fixed vocabulary: `beach | active |
  family | couple | city | ski | wellness`. Drives destination/season fit.
- **companions** — `solo | couple | family` (+ optional child ages) — shapes recommendations (a family
  beach trip ≠ a couple city break).
- **budget_hint** `{amount, currency}` (optional) — a soft ceiling; the live budget check still comes
  from the finance `brief`, this is only a stated preference when finance has no signal.

The config UX is a skill (`travel-profiler`, mirrors `briefing-profiler`): "мы семья с ребёнком 4 года,
любим пляж и спокойный отдых, летаем из Москвы" → strict-JSON extract → upsert via `mcp-travel`.

## Golden tests — from the start
Each LLM seam gets an **opt-in `@GoldenLlmTest`** (`libs/golden-test-support`, gated by `GOLDEN_LLM`,
not in the default mock-LLM CI): `GoldenTravelProfileTest` (profiler JSON structure), `GoldenTripComposerTest`
(itinerary structure + grounding), and a `travel` routing golden in orchestrator. Assert **structure, not
wording** (roadmap §Risks).

## PR slices (each its own PR, ≤5 files where possible)
Acceptance criteria are WHEN/THEN per the convention; each `Scenario:` names the test that asserts it.

### TR-a — `climate` tool in `mcp-weather` (capability extension)
`climate(latitude, longitude, month?)` → monthly temperature/precipitation normals over Open-Meteo's
free Climate/Archive API behind the existing swappable `WeatherSource`; `/internal/climate` passthrough.
No DB, no key. The season substrate (mirrors the BR-c1 `geocode` extension).
- **Scenario: monthly normals**
  - WHEN `climate(lat, lon)` is called for a coastal point
  - THEN it returns 12 monthly `{avgTempC, precipMm}` entries (asserted by `ClimateSourceTest`, MockWebServer)
- **Scenario: upstream down → soft-fail**
  - WHEN Open-Meteo climate is unreachable
  - THEN the tool returns empty (not a 500) so callers degrade (asserted by `ClimateSourceTest`)

### TR-b — `mcp-travel` domain-MCP + `travel` schema
`domains/travel/mcp-travel` (next free port). `travel.travel_profile` (home_base / rest_types /
companions / budget_hint, keyed `(household, owner)`) + `setTravelProfile` / `getTravelProfile` tools +
`/internal/travel-profile` upsert/resolve. Liquibase `NNN-travel.yml`. Mirrors `mcp-briefing`. The
personalization store — no trip persistence yet (MVP plans are stateless deliverables).
- **Scenario: upsert + resolve**
  - WHEN `setTravelProfile` stores a profile for `(household, owner)` then `getTravelProfile` reads it
  - THEN the stored rest_types/home_base round-trip intact (asserted by `McpTravelIntegrationTest`, Testcontainers)
- **Scenario: unseen owner**
  - WHEN `/internal/travel-profile` resolves an owner with no profile
  - THEN it returns 204/empty (caller falls back to household default) (asserted by `McpTravelIntegrationTest`)

### TR-c — `travel-agent` scaffold + `travel-profiler` skill
`domains/travel/travel-agent` (next free port). Binds `mcp-travel` + `mcp-weather` + `mcp-web`; registered
in orchestrator as `travel` (binding `mcp-web` from a new agent updates its §who-uses-me — bump
`shared/mcp/mcp-web/README.md` in the TR-c PR). A preferences cue → `travel-profiler` extract → home_base city → geocode
(`mcp-weather /internal/geocode`, soft-fail) → upsert via `/internal/travel-profile`. Chat fallback for
non-config messages. Mirrors `briefing-agent` (BR-c2).
- **Scenario: profile config**
  - WHEN the owner writes "любим пляж, семья с ребёнком, летаем из Москвы"
  - THEN `travel-profiler` extracts `rest_types=[beach], companions=family, home_base=Москва` and upserts
    the profile (asserted by `TravelProfilerTest`, MockWebServer + `GoldenTravelProfileTest`)
- **Scenario: unrecognised rest type**
  - WHEN the owner names a rest type outside the vocabulary
  - THEN it is dropped (not invented) and the profile keeps only valid kinds (asserted by `TravelProfilerTest`)
- **Scenario: routing**
  - WHEN a travel-intent message reaches the orchestrator
  - THEN it routes to `travel` (asserted by the orchestrator `travel` routing golden)

### TR-d — `trip-planner` flow (gather → synthesize)
A plan-a-trip cue → resolve the profile (self → household-default → empty-profile default) → gather in
parallel on the `Coordinator`: **budget** via the finance `brief`, **free dates** via the calendar
`brief`, **season fit** via `mcp-weather /internal/climate` for the candidate destination(s), and
**destination ideas / season reviews** via `mcp-web /internal/search` → one `trip-composer` synthesis →
a reply with the route, season verdict, and a budget check, grounded in the web-source links. Per-source
soft-fail; budget/dates absent → the plan still returns using `budget_hint` and the stated dates.
**Booking boundary (ADR-0003):** the plan proposes options and provenance links only — it MUST NOT book,
reserve, or pay.
- **Scenario: budget-grounded plan**
  - WHEN the owner asks for a beach trip in September with a 200k budget and finance `brief` returns a
    snapshot
  - THEN the plan cites the budget from finance and marks the trip within/over it (asserted by
    `TripComposerTest`, MockWebServer + `GoldenTripComposerTest`)
- **Scenario: off-season flag**
  - WHEN the requested destination+month is off-season per `climate`
  - THEN the plan flags "не сезон" and proposes an alternative month/destination (asserted by `TripComposerTest`)
- **Scenario: finance brief unavailable → soft-fail**
  - WHEN the finance `brief` call fails
  - THEN the plan still returns, using `budget_hint`, and notes the budget is unverified (asserted by `TripComposerTest`)
- **Scenario: web-grounded ideas (info, not pricing)**
  - WHEN the owner asks for a destination suggestion and `mcp-web` returns articles
  - THEN the plan proposes destinations grounded in those links and does **not** present any figure as a
    live bookable price (asserted by `TripComposerTest`: web links cited; no price claimed as live)
- **Scenario: web search unavailable → soft-fail**
  - WHEN `mcp-web` is unreachable
  - THEN the plan still returns from budget/dates/season alone (asserted by `TripComposerTest`)
- **Scenario: no booking (the hard boundary)**
  - WHEN the plan reaches a bookable option (flight/hotel/tour)
  - THEN the agent outputs a link only and performs no reservation or payment (asserted by `TripComposerTest`:
    the reply contains links and no outbound booking call is made)

### TR-e — HTML travel board (closer)
The `trip-composer` synthesis → a `doc-render` `Doc` (itinerary sections + the season chart + grounded
links) via the shared `DeliverablePublisher` → stored in media-service → the open-link appended to the
reply. A **climate-by-month** chart via `mcp-chart-render` (the season curve). A render/store hiccup
soft-fails to the text-only reply. Same board seam as briefing/finance. **Closes the travel MVP (#190).**
- **Scenario: board with season chart**
  - WHEN a trip is planned successfully
  - THEN the reply carries an HTML-board link and the board embeds a 12-month climate chart for the
    destination (asserted by `TripComposerTest`: board stored + link in reply + chart-render invoked)
- **Scenario: render hiccup → text-only**
  - WHEN doc-render/media-store fails
  - THEN the owner still gets the text plan and no error surfaces (asserted by `TripComposerTest`)

## Follow-on — TR-f: live search over Travelpayouts (post-MVP, owner-key-gated)
The concrete plan for live flight/hotel/tour **pricing** — the ADR-0003 point-3 follow-on. **Gated, not
started:** it needs (a) ADR-0003 accepted and (b) the owner to obtain a free **Travelpayouts** token +
marker and accept its T&C (a "confirm before doing" action — keys never committed, they live in
`.env` from `.env.example`). Until then the planner MVP (TR-a…TR-e) runs without it. Source stays behind
a **capability-MCP** so it can be swapped for `mcp-browser` without touching the agent; the agent still
**never books or pays** — results are ranked options + provider **deep links** (affiliate marker) the
owner opens to buy.

### TR-f1 — `mcp-travel-search` capability-MCP over Travelpayouts
`shared/mcp/mcp-travel-search` (next free port). Schema-less shared capability behind a swappable
`TravelSearchSource` (Travelpayouts default): `resolve_place(query) → {name, iataCity/hotelLocationId}`
(city → code via Travelpayouts data/autocomplete), `search_flights(origin, destination, departDate,
returnDate?, adults) → FlightOffer[]` (Aviasales prices) and `search_hotels(location, checkIn, checkOut,
guests) → HotelOffer[]` (Hotellook prices); `/internal/*` passthroughs. Read-only; each offer carries a
`deepLink` with the marker. Key via `TRAVELPAYOUTS_TOKEN` + `TRAVELPAYOUTS_MARKER` env; **no key →
degrade** (tools return empty + an "unconfigured" flag, never a 500). Change-propagation for a new
capability-MCP: `infra/docker-compose*.yml` · root `README.md` · module `README.md` · `plans/architecture.md`
· `plans/INDEX.md` · `.env.example` (the two keys + port) · a golden test. (Tours: Travelpayouts has no
clean tour API → **deferred** to TR-f3 / `mcp-browser`; MVP-search is flights + hotels.)
- **Scenario: cheapest flight with transfers**
  - WHEN `search_flights(MOW, AYT, 2026-09-12, adults=2)` is called with a valid key
  - THEN it returns offers each with `{price, transfers, airline, dates, deepLink}` sorted by price
    (asserted by `TravelpayoutsSourceTest`, MockWebServer against a recorded Aviasales response)
- **Scenario: hotel search**
  - WHEN `search_hotels(location, checkIn, checkOut, guests)` is called
  - THEN it returns `{name, price, stars, deepLink}` offers (asserted by `TravelpayoutsSourceTest`)
- **Scenario: no key configured → degrade, not fail**
  - WHEN `TRAVELPAYOUTS_TOKEN` is unset and a search tool is called
  - THEN it returns empty + `unconfigured=true` (not an error) so callers soft-degrade (asserted by
    `TravelSearchControllerTest`) — this keeps CI/tests green with no key
- **Scenario: upstream down → soft-fail**
  - WHEN Travelpayouts is unreachable
  - THEN the tool returns empty, not a 500 (asserted by `TravelpayoutsSourceTest`)

### TR-f2 — bind in travel-agent + wire live options into `trip-planner`
travel-agent binds `mcp-travel-search`; the `trip-planner` gather gains a live-options step when the
owner asks for concrete tickets/hotels ("найди билеты", "подбери отель"): resolve places → search
flights/hotels for the chosen dates → **rank by min transfers then price**, filter to the finance-`brief`
budget → fold ranked options + deep links into the `trip-composer` synthesis and the HTML board. When
`mcp-travel-search` reports `unconfigured`/empty, the planner **degrades to the MVP plan** (route + season
+ budget, no live options) and tells the owner live search isn't set up. `mcp-travel-search` README gains
travel-agent in §who-uses-me.
- **Scenario: min-transfers preference honoured**
  - WHEN the owner asks for tickets and several offers exist
  - THEN the top pick minimises transfers before price, within the finance-`brief` budget (asserted by
    `TripComposerTest` live-options case)
- **Scenario: options carry buy links, agent does not book (the hard boundary)**
  - WHEN live flight/hotel options are presented
  - THEN each is a provider `deepLink` and the agent performs **no** reservation or payment (asserted by
    `TripComposerTest`: reply/board carry deep links; no outbound booking/payment call is made)
- **Scenario: over-budget flagged, not hidden**
  - WHEN the cheapest option exceeds the finance-`brief` budget
  - THEN the plan still shows it, flagged "над бюджетом" (asserted by `TripComposerTest`)
- **Scenario: search unconfigured → graceful MVP fallback**
  - WHEN `mcp-travel-search` returns `unconfigured`
  - THEN the owner gets the planner MVP result + a note that live search needs a Travelpayouts key
    (asserted by `TripComposerTest`)
- **Golden:** `GoldenTripComposerTest` gains a live-options fixture asserting structure (offers listed,
  links present, no price claimed without a source) — not wording.

## Deferred (further out)
- **TR-f3 — tours** (Travelpayouts has no clean tour API) and **no-API/JS sources** → `mcp-browser`
  (browser-use), which also closes the general scraping gap (roadmap §Candidate capabilities).
- **Booking hand-off** — deep links to the provider's own checkout only; the agent never transacts (a
  permanent boundary, not a deferred feature).
- **Saved trips / itinerary history** — a `travel.trip` store, once plans need to persist/compare.
- **Proactive "season is opening for your favourite destination" wake** — travel is reactive in the MVP.
- **Multi-destination route optimisation** (min-transfers across legs) — waits on live flight data.
