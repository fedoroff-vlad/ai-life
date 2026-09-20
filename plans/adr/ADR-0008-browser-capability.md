# ADR-0008: Browser capability (`mcp-browser`) — deterministic headless fetch vs agentic browser-use

**Status:** Proposed (2026-09-20 — awaiting owner pick between Option A and Option B below). ADR-0003
already fixed the *shape* (`mcp-browser` is a shared capability-MCP) and the *boundary* (the agent never
books or pays); this ADR decides the *how* — the browser runtime and its tool surface — before any code.
**Date:** 2026-09-20
**Deciders:** repo owner (holder/admin)
**Relates to:** [ADR-0003](ADR-0003-travel-data-source.md) (named `mcp-browser` as the no-API/JS fallback +
the general-scraping closer), [ADR-0006](ADR-0006-runtime-topology-footprint.md) (the footprint budget this
must not blow), the injection doctrine ([architecture.md](../architecture.md) §Security, #599),
[travel.md](../travel.md) §TR-f3 + §RT-d (the concrete deferred needs), [research.md](../research.md)
(`mcp-web`'s JS gap), and [creator.md](../creator.md) (`mcp-browser` deferred there too).

## Context

Three separate deferred threads all point at the **same missing capability — render/resolve a page a
plain HTTP fetch cannot**:

- **travel.md §RT-d (deferred half):** short map links (`maps.app.goo.gl/…`, `yandex.ru/maps/-/…`) that
  only resolve via a redirect, and route/place polylines that render only in JS. The `MapLinkRouteParser`
  returns empty for those; the agent has to say "короткую ссылку не могу разобрать".
- **travel.md §TR-f3:** tours (Travelpayouts has no clean tour API) and other no-API / JS-only sources.
- **`mcp-web`'s own finding (roadmap §Evaluated tools, live-verified 2026-06-20):** JS-rendered pages
  (e.g. YouTube, most aggregators) yield only boilerplate via jsoup — a real, recorded gap.

ADR-0003 already decided this is a **shared capability-MCP** named `mcp-browser`, kept as the fallback for
no-API/JS sources and "the general scraping gap", and reasoned about the *reasoning-over-it* staying in the
binding agent (researcher/travel/creator). What it did **not** decide is the runtime — and that choice is
material because a headless browser collides with two standing constraints:

### Forces / constraints
- **Footprint (ADR-0006, #584).** The active epic is *reducing* RAM (47 JVMs → ~12 hosts) to free memory
  for the local model. Any headless browser drags in **Chromium (~300–500 MB resident when open)**. A
  browser capability that is *always resident* would directly undo part of that win. Whatever we build
  **must be cold** (started on demand, torn down after) and never join the resident hot set.
- **Injection surface (#599).** Rendered page text is the most untrusted input in the system — an
  attacker controls the DOM. The standing doctrine already frames retrieved web text as data via
  `agent-runtime` `UntrustedContent.GUARD` (+ `fence`), enforced by `check-consistency.sh` check 7. A
  browser capability widens this surface (full JS execution, redirects to attacker hosts); an **LLM that
  reads the page and then decides what to click** widens it much further (prompt-injection can now steer
  *actions*, not just poison text).
- **The agent never acts (ADR-0003, ADR-0004).** travel/creator never book, pay, submit, or log in. A
  read-only scraper honours this by construction; an agentic browser that *can* click must be fenced to
  read-only navigation, and that fence is exactly what injection tries to break.
- **Free-first + tooling simplicity** ([[feedback_tooling_simplicity]],
  [[feedback_oss_reuse_shared_capabilities]]). Prefer a lean, keyless, self-hostable foundation; reuse OSS
  over rebuilding; a broadly-useful capability is a shared capability-MCP, not embedded in one agent.
- **Polyglot-by-design** ([architecture.md](../architecture.md) §Principles). A non-JVM upstream is fine —
  run it as its own service behind a thin capability-MCP (as SearXNG, whisper do). So "it's Python" is not
  by itself a blocker; the cost/footprint/safety of *what* it runs is.

The near-term need (RT-d short-links + JS render, the `mcp-web` gap) is **deterministic**: follow a
redirect, render JS, hand back the final URL + text/coordinates. It needs **no** LLM in the loop. The
farther need (interactive tours behind forms) is **agentic**: navigate, click, extract. These are different
runtimes with very different cost — hence the fork.

## Decision (to be picked by the owner)

`mcp-browser` is a **cold, read-only, shared capability-MCP** (settled — follows ADR-0003 + ADR-0006). The
open question is its engine, and the two options are genuinely different foundations:

### Option A (recommended): deterministic headless fetch, no LLM
A **Java capability-MCP driving Playwright-for-Java / headless Chromium**, launched per request and torn
down. Deterministic tools, no model in the loop:
- `resolve_url(url) → {finalUrl, redirectChain}` — follow redirects (closes RT-d short-links directly).
- `fetch_rendered(url) → {finalUrl, text, title}` — load, let JS settle, return extracted text (closes the
  `mcp-web` JS gap; the binding agent guards the text via `UntrustedContent.GUARD`).
- (optional, later) `extract_coords(url) → waypoint[]` — a thin helper for the RT-d map-link case, or leave
  coordinate parsing in `MapLinkRouteParser` fed by `resolve_url`'s `finalUrl`.

Read-only by construction: it never submits a form, logs in, or clicks a purchase. Chromium is only alive
for the duration of a fetch. **Immediately closes RT-d short-links + the `mcp-web` JS gap**; covers tours
that are merely JS-rendered listings (fetch + parse), but **not** tours behind interactive multi-step forms.

### Option B: full agentic browser-use (Python sidecar) behind a thin Java proxy
Run **[browser-use](https://github.com/browser-use/browser-use)** (Python, Playwright, LLM-driven) as its
own service in its MCP/HTTP mode; a thin Java `mcp-browser` capability-MCP proxies to it (polyglot-by-design,
as `mcp-web`→SearXNG). It can *navigate and interact* — click through a tour flow, fill a search form — and
return a synthesized result.
- **Pros:** handles genuinely interactive no-API sources (the hardest TR-f3 tours); one tool ("achieve this
  on this site") instead of hand-written scrapers per source.
- **Cons:** the heaviest option — **Chromium + a Python runtime + an LLM call-loop per task**. It puts the
  local model on the browsing hot path (latency, cost, contention with the resident model budget of #584),
  and it maximises the injection surface (a poisoned page can steer the agent's *clicks*). Even cold, a
  single run is far more expensive than a deterministic fetch, and the read-only fence must be enforced
  against an adversarial DOM.

### Recommendation
**Start with Option A.** It closes every *recorded, concrete* gap we actually have today (RT-d short-links,
JS-rendered coordinates/text, the `mcp-web` boilerplate finding) with the least footprint, no LLM on the
browsing path, and a read-only-by-construction safety story that matches ADR-0003/0004. It is the lean
foundational capability the tooling-simplicity principle asks for. **Defer Option B as an escalation**:
add the agentic engine (or a `fetch_rendered`→browser-use hand-off) only if a real interactive-tour need
survives Option A — behind the same capability-MCP boundary, so binding agents don't change. This mirrors
ADR-0003's own "browser only if the simpler source is insufficient" stance, one level down.

## Options Considered

### Option A — deterministic Playwright-Java fetch (**recommended**)
| Dimension | Assessment |
|-----------|------------|
| Closes RT-d short-links / JS coords | **Yes** (`resolve_url` + `fetch_rendered`) |
| Closes `mcp-web` JS-render gap | **Yes** |
| Interactive tours (multi-step forms) | **No** (fetch/parse only) |
| Footprint | Chromium only while a fetch runs; cold; **no Python, no LLM** |
| Injection surface | Untrusted text only (same class as `mcp-web`); no LLM-steered actions |
| Simplicity | One JVM service in the monorepo; deterministic, unit/IT-testable without a model |
| Cost | Free, keyless, self-hosted |

### Option B — agentic browser-use (Python sidecar)
| Dimension | Assessment |
|-----------|------------|
| Closes RT-d / `mcp-web` gap | Yes (overkill for it) |
| Interactive tours | **Yes** — its distinctive value |
| Footprint | Chromium **+ Python runtime + per-task LLM loop**; heaviest; contends with #584 model budget |
| Injection surface | **Widest** — a poisoned DOM can steer the agent's clicks; read-only fence must hold against adversarial pages |
| Simplicity | A second runtime + a proxy; brittle selectors abstracted but non-deterministic runs |
| Cost | Free/keyless engine, but real local-LLM time per task |

### Option C — no browser capability (status quo, rejected)
Leave RT-d short-links, JS coords, and the `mcp-web` gap permanently unhandled. Rejected: three separate
plan threads already record the need, and the owner has picked the TR-f3 track.

## Consequences

**Easier (either option):**
- The capability-MCP boundary (ADR-0003) means the engine can be swapped A→B (or A gains a B hand-off tool)
  **without touching** the binding agents (researcher/travel/creator) — the same seam that let travel swap
  Travelpayouts↔browser.
- RT-d's deferred half and the `mcp-web` JS gap get a real answer; `creator.md`'s deferred `mcp-browser`
  note resolves.

**Harder / to watch:**
- **Footprint discipline is mandatory** — `mcp-browser` must be a **cold** capability-MCP (its own cold
  host-unit under ADR-0006, never resident) and must tear down Chromium after each use; this is an explicit
  acceptance criterion, not an aspiration. It is a new *kind* of backing (a browser engine) in the stack.
- **Injection tests are mandatory** — a binding agent that consumes `fetch_rendered` text must frame it via
  `UntrustedContent.GUARD` (check 7) and ship a model-proven injection golden, exactly as the #599 flows did.
- **Option B additionally** puts the local model on the browsing path and widens the action-injection
  surface — if chosen, it needs a hard read-only fence (no form submit / no auth / no purchase) asserted as
  a boundary test, and a footprint measurement against the #584 budget before it can be considered for any
  non-trivial use.

## Action Items
1. [ ] **Owner picks Option A or Option B** (this ADR moves to Accepted with the pick recorded). No
   `architecture.md` §Locked-decisions change — it reuses the capability-MCP doctrine; the record stays this
   ADR + travel.md.
2. [ ] **Slice the chosen engine** in a new `plans/` section (travel.md §TR-f3 expands, or a short
   `plans/browser.md` if the capability grows beyond travel) — WHEN/THEN-first, one PR per slice, with the
   change-propagation for a new capability-MCP (compose · root README · module README · architecture.md ·
   INDEX.md · `.env.example` port · a golden/injection test) and the **cold + read-only** acceptance criteria.
3. [ ] **Wire the first consumer** — RT-d's `MapLinkRouteParser`/agent short-link path (the smallest real
   need) or `mcp-web`'s JS-render fallback, whichever the owner wants first.

## Notes
Eighth ADR. It introduces **no new shared contract or layer** — it records the runtime + tool-surface +
footprint/safety stance for one capability already sanctioned by ADR-0003, flagged here (per CLAUDE.md "new
pattern → flag before code") rather than decided silently in code. Until Accepted it is a proposal; no
`mcp-browser` code is written against it yet.
