# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

## Now
- **EX-c2 in flight** (branch `feat/travel-ex-c2-finance-signal`) — the agent half of travel↔finance EX-c:
  a `close` cue → final tally → `closeTrip` → deposit a finance **spend-signal** note (`MemoryClient.note`,
  decoupled via the shared second brain — finance's `brief` recall surfaces it) → final board; plus
  `TripLedger.totalSpentInHome` (expenses only, excluding exchange transfers). Tests: `TripLedgerTest` +
  `WalletFlowTest` green locally. **EX-c1 (store close) SHIPPED** — PR446 merged. This closes EX-c → trip
  wallet #437 fully done.
- **Trip wallet #437 core COMPLETE** — EX-a store (PR443) + EX-b wallet flow (PR445) + EX-c1 store close
  (PR446); detail → [HISTORY.md](HISTORY.md) / [travel.md](travel.md) §Trip wallet.
- **travel MVP + live search + trip wallet (EX-a+b) COMPLETE** (context only — detail in [travel.md](travel.md) / [HISTORY.md](HISTORY.md)):
  TR-a…e MVP + TR-f1/f2 live flight/hotel over Travelpayouts. To *enable* live search the owner must obtain a
  free **Travelpayouts** token + marker and accept T&C (a "confirm before doing" step — keys live in `.env`,
  never committed); until then the planner degrades to the MVP. Only **TR-f3** (tours/JS sources → `mcp-browser`) deferred.
- **Prior epics COMPLETE** (context only — detail in [HISTORY.md](HISTORY.md), don't re-open):
  - **travel #190 — DONE 2026-08-12 (MVP TR-a…e + live-search TR-f1/f2).** Planner-first vacation agent:
    `mcp-weather` `climate` (TR-a) + `mcp-travel` profile store (TR-b) + `travel-agent`/`travel-profiler`
    (TR-c) + `trip-planner` gather→synthesize (TR-d) + the **HTML travel board** with a climate-by-month
    chart (TR-e); **live flight/hotel options** via `mcp-travel-search` over Travelpayouts, owner-key-gated
    (TR-f1 capability + TR-f2 planner wiring: rank min-transfers→price, over-budget flag, deep links,
    degrade-to-MVP when `unconfigured`). Booking boundary permanent (ADR-0003). Only **TR-f3** (tours /
    no-API sources → `mcp-browser`) stays deferred.
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
1. **Pick the next future agent** — travel #190 is fully closed (MVP + live search). Backlog (owner priority): health [#187](https://github.com/fedoroff-vlad/ai-life/issues/187), email [#191](https://github.com/fedoroff-vlad/ai-life/issues/191), smart-home [#192](https://github.com/fedoroff-vlad/ai-life/issues/192); or resume the parked coach-agent [#289](https://github.com/fedoroff-vlad/ai-life/issues/289) (CO-3+). See the [`future-agent`](https://github.com/fedoroff-vlad/ai-life/labels/future-agent) label + `## Backlog`.
2. **Bucket 2 production cutover ([#369](https://github.com/fedoroff-vlad/ai-life/issues/369), model-gated)** — the pilot / validate-only half is DONE and #360 is closed; #369 tracks the remaining half: rip the Java `FinancialAdvisor` flow out and run the recipe from `SKILL.md` in production. Gated on the Mac / a stronger local MoE default.
3. **travel TR-f3 (deferred)** — tours + no-API/JS sources → `mcp-browser` (browser-use), which also closes the general scraping gap. Not near-term (see `## Backlog`).

_(fast/slow test split — DONE 2026-08-07, see [HISTORY.md](HISTORY.md) + [migration-25-boot4.md](migration-25-boot4.md) §Build/CI performance lever 2.)_

## Backlog (all mirrored as Issues — not near-term)
Future agents: **coach-agent #289 — PARKED mid-epic 2026-07-10** (CO-1 store + CO-2 reflect shipped; CO-3 intake…CO-7 proactive deferred — resume from [coach.md](coach.md) §Phased slices), health #187, travel #190, email #191, smart-home #192.
Capabilities/follow-ups: mcp-image-gen real engine + stylist try-on #293, mcp-web video transcripts #294. (**off-site DB backup replication** DONE 2026-08-09 → HISTORY: `offsite` compose profile, `rclone-offsite` with a `BACKUP_OFFSITE_REMOTES` flag choosing Yandex Disk and/or a Tailscale host.)
Tech-debt: Apache AGE upgrade #296 (gated). Older closed-out debt (incl. #323 JDK 21→25 Dockerfiles, done) → [HISTORY.md](HISTORY.md).
(The **skills-vs-flows** refactor track #358→#359→#360 is done and closed; the only open thread is the Mac-gated production cutover #369 in `## Next` above — [skills-vs-flows.md](skills-vs-flows.md).)

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR. **Update this file at the end of each PR; move the finished bullet to [HISTORY.md](HISTORY.md) (add a terse timeline row + the detail), don't let `## Now` accumulate ✅ DONE items.**
