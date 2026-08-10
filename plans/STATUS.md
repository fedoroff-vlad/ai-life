# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

## Now
- **travel-agent ([#190](https://github.com/fedoroff-vlad/ai-life/issues/190)) — spec landed, MVP queued.**
  Owner picked travel as the next agent (2026-08-10). Spec written **WHEN/THEN-first**: plan
  [travel.md](travel.md) + **[ADR-0003](adr/ADR-0003-travel-data-source.md)** (**Accepted 2026-08-10** — scope
  + source + the "agent never books" boundary). Scope = **planner-first MVP** (finance/calendar `brief` +
  `mcp-weather` `climate` + `mcp-web` → itinerary + HTML board; per-owner `travel_profile`); live
  flight/hotel/tour pricing = the **§TR-f** follow-on behind a capability-MCP (Travelpayouts pref /
  `mcp-browser` fallback), still owner-key-gated. **TR-a + TR-b shipped:** TR-a = `climate` tool in
  `mcp-weather` (monthly normals over Open-Meteo Archive, soft-fail); TR-b = **`mcp-travel`** domain-MCP
  (port 8123, `travel.travel_profile` keyed `(household, owner)` — home_base/rest_types/companions/
  budget_hint; `set`/`get` tools + `/internal/travel-profile` upsert/resolve, 204 on unseen owner) →
  [HISTORY](HISTORY.md) + [mcp-travel README](../domains/travel/mcp-travel/README.md). **Next slice:
  TR-c** (`travel-agent` scaffold + `travel-profiler` skill; binds `mcp-travel` + `mcp-weather` +
  `mcp-web`, registered in orchestrator as `travel`). Also landed earlier: the repo-wide **WHEN/THEN
  acceptance-criteria** convention (CLAUDE.md §Spec each slice + PATTERNS.md recipe, PR #430).
- **Prior epics COMPLETE** (context only — detail in [HISTORY.md](HISTORY.md), don't re-open):
  - **Sharing capability ADR-0002 — DONE 2026-08-07.** Slices 2–7 retrofitted all opt-in domains
    (calendar/finance/tasks/nutrition/docs, write & read); item 8 memory-driven default (DS-0…DS-4,
    `LearnedSharingPolicy` over a `memory.sharing_decision` tally, deterministic majority); and **DS-N
    confirm-on-ambiguity** — a domain defers + asks "личное или общее?" via the reusable `SharingConfirm`
    when the default is genuinely ambiguous. DS-N consumers: tasks (reference), finance (unscoped account),
    docs (untyped document — gained its first `/resume`); calendar (inter-agent write) + nutrition
    (deterministically-shared basket) opt out by design. Detail → [HISTORY.md](HISTORY.md) +
    [ADR-0002](adr/ADR-0002-sharing-shared-capability.md). Only the separate memory/second-brain owner-tag
    reconciliation stays deferred (orthogonal).
  - identity **ADR-0001** slices 1–6 (rows 2026-08-01/04); **skills-vs-flows** #358/#360 (only the Mac-gated
    cutover #369 stays open, see `## Next`).

## Parked — blocked on hardware (Mac not yet purchased)
- **Mac deployment + hot/cold lifecycle — [lifecycle.md](lifecycle.md) (owner-signed 2026-07-10).** Target:
  Mac Studio M4 Max 64/512 running ai-life 24/7 (hot set always-on + auto cold start/stop via a new
  `platform/supervisor`, + dynamic model downshift when the coder tenant runs). **Shipped so far** →
  HISTORY: `deploy-mvp` config (`.env.mac.example`, per-JVM heap caps, infra/README), **LC-1** hot/cold
  compose profiles (2026-07-15), **LC-4** `/v1/model-profile` evict-before-load swap (2026-07-21). **Why
  parked:** the next slice **LC-2 (`platform/supervisor` + socket-proxy)** wraps `docker compose up -d` and
  can't be meaningfully built/tested without a Docker daemon — there is none on the dev box, and the Mac
  isn't purchased yet. `deploy-mvp`'s own remaining step (first real `--profile hot up` + cross-domain
  smoke) is likewise hardware-blocked. Ordering when hardware lands: LC-2 → LC-2.5 cold-tolerant discovery
  → LC-3 (+3a AOT) → LC-5. Model stack decided → [model-strategy.md](model-strategy.md) (MoE-first; two MoE
  tenants may make the LC-4 downshift optional — **measure residency live at deploy**). coach-agent parked (Backlog).

## Next (owner priority order — the backlog now lives in GitHub Issues)
1. **travel MVP slices ([#190](https://github.com/fedoroff-vlad/ai-life/issues/190), [travel.md](travel.md)):**
   TR-a `climate` tool in `mcp-weather` → TR-b `mcp-travel` + `travel` schema/profile → TR-c `travel-agent`
   + `travel-profiler` → TR-d `trip-planner` flow → TR-e HTML board (closer). Each its own PR, ≤5 files,
   WHEN/THEN criteria in the plan.
2. **Bucket 2 production cutover ([#369](https://github.com/fedoroff-vlad/ai-life/issues/369), model-gated)** — the pilot / validate-only half is DONE and #360 is closed; #369 tracks the remaining half: rip the Java `FinancialAdvisor` flow out and run the recipe from `SKILL.md` in production. Gated on the Mac / a stronger local MoE default.

_(fast/slow test split — DONE 2026-08-07, see [HISTORY.md](HISTORY.md) + [migration-25-boot4.md](migration-25-boot4.md) §Build/CI performance lever 2.)_

## Backlog (all mirrored as Issues — not near-term)
Future agents: **coach-agent #289 — PARKED mid-epic 2026-07-10** (CO-1 store + CO-2 reflect shipped; CO-3 intake…CO-7 proactive deferred — resume from [coach.md](coach.md) §Phased slices), health #187, travel #190, email #191, smart-home #192.
Capabilities/follow-ups: mcp-image-gen real engine + stylist try-on #293, mcp-web video transcripts #294. (**off-site DB backup replication** DONE 2026-08-09 → HISTORY: `offsite` compose profile, `rclone-offsite` with a `BACKUP_OFFSITE_REMOTES` flag choosing Yandex Disk and/or a Tailscale host.)
Tech-debt: Apache AGE upgrade #296 (gated). Older closed-out debt (incl. #323 JDK 21→25 Dockerfiles, done) → [HISTORY.md](HISTORY.md).
(The **skills-vs-flows** refactor track #358→#359→#360 is done and closed; the only open thread is the Mac-gated production cutover #369 in `## Next` above — [skills-vs-flows.md](skills-vs-flows.md).)

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR. **Update this file at the end of each PR; move the finished bullet to [HISTORY.md](HISTORY.md) (add a terse timeline row + the detail), don't let `## Now` accumulate ✅ DONE items.**
