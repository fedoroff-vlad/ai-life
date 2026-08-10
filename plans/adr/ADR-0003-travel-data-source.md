# ADR-0003: Travel-agent data source — planner-first, capability-MCP boundary, agent never books

**Status:** Accepted (2026-08-10 — owner-approved). Planner-first MVP (travel.md TR-a…TR-e) is **unblocked
and is the active track**; the live-pricing follow-on (travel.md **§TR-f**, Travelpayouts) stays gated on an
owner-obtained key + accepted T&C. The "agent never books or pays" boundary is a permanent invariant.
**Date:** 2026-08-10
**Deciders:** repo owner (holder/admin)
**Relates to:** [travel.md](../travel.md) (the domain plan this decision unblocks), the deferred
`mcp-browser` capability (roadmap §Candidate capabilities), and issue
[#190](https://github.com/fedoroff-vlad/ai-life/issues/190).

## Context

The travel-agent is the first ai-life domain whose core value — comparing real flights, hotels, and
tours — depends on **external commercial data** rather than a self-hosted free source. Every other
capability so far had a free, keyless, self-hostable backend (Open-Meteo, SearXNG, Stooq, Open Food
Facts). Travel does not:

- **Flights / hotels / tours** are behind provider APIs that require an account + key + accepted terms
  (Travelpayouts/Aviasales, Amadeus Self-Service, Booking affiliate), or are **JS-rendered with no public
  API** (most aggregators), which needs a headless browser to scrape.
- Two owner principles pull against paid/keyed dependencies: **free-OSS-first** and **tooling
  simplicity** ([[feedback_tooling_simplicity]], [[feedback_oss_reuse_shared_capabilities]]). Adding a
  key is a "confirm before doing" action (CLAUDE.md §Confirm before doing).
- The platform-safety boundary is hard: an agent **must not execute purchases or move money**
  (booking a hotel = a purchase; the global action policy prohibits autonomous financial transactions).

So two questions must be decided before writing travel code: **(1) what is the boundary of what the
agent does** (plan vs book), and **(2) where does live pricing data come from** — and can the first MVP
avoid answering (2) entirely.

### Forces / constraints
- **No autonomous booking/payment — permanent.** The agent proposes options + provider deep links; the
  owner completes any purchase on the provider's own surface. This is a safety boundary, not a roadmap item.
- **Free-first, key = confirm-gated.** A paid/keyed source can only be added after the owner obtains the
  key and accepts the provider T&C.
- **Capability-MCP boundary.** Whatever the source, raw external retrieval is a **capability-MCP** (any
  agent can bind it), and the *reasoning over it* stays in travel-agent — the standing MCP doctrine
  (architecture.md): "raw external capability → capability-MCP; reasoning over it → a specialist agent".
- **Deliver value without the source.** A useful trip plan (route + season fit + budget check against
  real finance data) is possible today with only existing free capabilities — the source is needed for
  *live pricing*, not for *planning*.

## Decision

**1. The agent never books or pays — it plans and links.** travel-agent outputs a route, a season
verdict, a budget check, and provider **deep links**; any reservation/payment happens on the provider's
own checkout. Enforced as an acceptance criterion in every travel slice (travel.md TR-d "no booking"
scenario), not just prose.

**2. Ship a planner-first MVP with zero new external dependency.** The MVP (travel.md TR-a…TR-e) uses
only what exists: the finance/calendar **`brief`** reads for budget + free dates, **`mcp-weather`**
(extended with a `climate` tool) for season fit, and **`doc-render`/`chart-render`** for the board. It
proves the agent, the profile, and the synthesis without answering the source question.

**3. Live pricing is a follow-on behind a capability-MCP; preferred source = Travelpayouts, fallback =
`mcp-browser`.** When the owner wants live search, add a `mcp-travel-search` capability-MCP whose engine
is:
- **Travelpayouts** (Aviasales flights + Hotellook hotels + tours) — *preferred*: a **single free
  affiliate API** covering all three surfaces, strong on the RU market. **Requires** an owner-obtained
  key + accepted T&C → a confirm-gated slice, not done silently.
- **`mcp-browser`** (headless browser-use) — *fallback* for no-API/JS-only sources; it also closes the
  separately-deferred general scraping gap (video/social, roadmap §Candidate capabilities). Larger, so
  built only if Travelpayouts proves insufficient.

The source pick is **deferred to that follow-on slice** and stays owner-confirmed; this ADR only fixes
the *shape* (capability-MCP + agent-never-books) and the *preference order*.

## Options Considered

### Option A: Planner-only forever (no live pricing)
**Rejected as the end state** — it delivers half the owner's ask (season/route/budget but never "find me
the cheapest min-transfer ticket"). **Accepted as the MVP** — it is the correct first vertical slice:
real value, zero new dependency, and it de-risks the agent/profile/synthesis before the source question.

### Option B: Travelpayouts affiliate API first (**recommended source**)
| Dimension | Assessment |
|-----------|------------|
| Coverage | **High** — flights + hotels + tours in one API |
| Cost | Free (affiliate) |
| RU-market fit | **High** (Aviasales/Hotellook) |
| Simplicity | One HTTP source behind one capability-MCP |
| Cons | Needs an account + **key + accepted T&C** (confirm-gated); affiliate terms; not self-hosted |

### Option C: `mcp-browser` (browser-use) first
Closes the general no-API/JS scraping gap and needs no per-provider key, but is a **much larger** build
(headless browser lifecycle, brittle selectors, heavier runtime) and overkill for the MVP. **Kept as the
fallback**, and revisited on its own merits for the video/social capability.

## Consequences

**Easier:**
- travel-agent ships now on free capabilities; the source decision is decoupled and reversible.
- The capability-MCP boundary means the source can be swapped (Travelpayouts ↔ browser) without touching
  the agent.
- The "agent never books" boundary is explicit and test-asserted from day one.

**Harder / to revisit:**
- The MVP cannot answer "cheapest ticket" until the follow-on source slice — set expectations in the plan.
- Adding Travelpayouts is a confirm-gated, non-self-hosted, keyed dependency — a first for the repo; when
  taken, it must land with its key in `.env.example` (never committed) + a README contract + the
  change-propagation entries.
- If `mcp-browser` is later built, it is a shared capability (not travel-owned) and must be scoped so
  travel and the video/social follow-on both bind it.

## Action Items
1. [x] **Accept this ADR** (owner, 2026-08-10). No `architecture.md` §Locked-decisions change needed
   (this introduces no new cross-cutting primitive — it reuses the capability-MCP doctrine); the record
   stays this ADR + travel.md. *(docs — the travel-spec PR)*
2. [ ] **Planner MVP** — travel.md TR-a…TR-e, one PR each. No external dependency.
3. [ ] **(follow-on, owner-confirmed)** `mcp-travel-search` capability-MCP over Travelpayouts — gated on
   the owner obtaining a key + accepting T&C; bind it in travel-agent and wire flight/hotel search into
   `trip-planner`. Detailed WHEN/THEN plan: [travel.md](../travel.md) **§TR-f** (TR-f1 capability + TR-f2
   wiring; tours + no-API sources → `mcp-browser`, TR-f3). `mcp-browser` only if Travelpayouts is insufficient.

## Notes
Third ADR in the repo. It introduces **no new shared contract or layer** — it records a scope + source +
safety-boundary decision for one domain, flagged here (per CLAUDE.md "new pattern → flag before code")
rather than decided silently in code. Until Accepted it is a proposal; travel.md's MVP slices are written
against Decision points 1–2 (which are owner-chosen scope), while point 3 waits on this ADR + a key.
