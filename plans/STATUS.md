# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

## Now
- **Sharing-as-a-capability epic — IN FLIGHT ([adr/ADR-0002](adr/ADR-0002-sharing-shared-capability.md),
  Accepted 2026-08-01).** Generalise ADR-0001's calendar tenant-routing into a reusable cross-domain
  capability so finance/tasks/nutrition/docs get "own vs shared" without copy-paste. Design (owner-approved):
  a shared leaf module **`libs/sharing`** (`SharingResolver` write-engine + `DefaultSharingPolicy`
  extension point + `SharingContext` + `ProfileSharingClient`) taken by **all** agents *and* read-only web
  services; `SharingScope` → `contracts/common`; each domain plugs a same-named **`sharing/`** package with
  its `<Domain>SharingPolicy`. **Mechanism deterministic (privacy boundary, never LLM-decided); only the
  default-when-unspecified is policy, later memory-driven via the same seam.** **Slices 1–2 ✅** (1 = accept
  ADR + docs; 2 = the `libs/sharing` engine — `SharingResolver` + `DefaultSharingPolicy` seam +
  `SharingContext` + `ProfileSharingClient`, `SharingScope` lifted to `contracts/common`, 13 tests, no
  domain wired yet → [HISTORY](HISTORY.md) row 2026-08-01). Slice 3 (retrofit calendar as the reference)
  **split write/read** (>5 files): **slice 3a ✅** = calendar-agent write path onto `SharingResolver` +
  new `sharing/CalendarSharingPolicy`, inline routing deleted, behaviour unchanged (7 ActionControllerTest
  cases green). **Slice 3b ✅** = calendar-web read path onto `libs/sharing`'s
  `ProfileSharingClient.households` (via `config/SharingConfig`; local `ProfileHouseholdsClient` retired,
  7 calendar-web tests green). **Calendar is now fully retrofitted — the capability's reference impl (both
  paths).** **Slice 4 = finance — IN FLIGHT.** Scoping decided (owner, 2026-08-01): **account-level** (the
  mcp-finance cross-household guard forces txn.household = account.household, so the account is the boundary),
  **report cut = own/personal by default, shared on explicit request** ("наши траты"). **Slice 4a-i ✅** =
  `FinancialAdvisor` shared-scope union read (`advise(msg, shared)` reads personal ∪ shared via
  `ProfileSharingClient.households` + `mergeByCategory` when the classifier tags `scope:"shared"`; default
  stays personal; `libs/sharing` bound in `OutboundHttpConfig`; mcp-finance untouched). **Slice 4a-ii ✅** =
  the cut + merge lifted into a shared `read/SpendingReads` helper (advisor adopts it too — no duplication);
  `MonthlyReporter`/`YearReporter` gained `report(msg, shared)` + the `report` flow reads `scope:"shared"`
  (balances have no agent read flow → nothing to retrofit). **Finance read side done.** NEXT = **4b**
  `sharing/FinanceSharingPolicy` + route account creation (needs an account-create seam first) → 5 tasks →
  6 nutrition+docs → 7 (deferred) memory owner-tag reconcile. Detail →
  [adr/ADR-0002](adr/ADR-0002-sharing-shared-capability.md) §Action Items.
- The **Identity & membership epic (ADR-0001)** is **COMPLETE** (2026-08-01) — slices 1–5 shipped
  (invite-only onboarding + per-item calendar tenant routing + per-member ICS feed, closing #295).
  Deferred by design: items 6 (`people.user_id`) and 7 (default-sharing learn/confirm inference — now the
  `DefaultSharingPolicy` seam of ADR-0002). Detail → [HISTORY.md](HISTORY.md) (rows 2026-08-01).
- **skills-vs-flows track — DONE** (shared `SkillClassifier` #358 + Bucket 2 validate-only pilot #360, both
  closed 2026-07-30). Only open thread = the Mac-gated production cutover #369 (see `## Next`). Detail →
  [HISTORY.md](HISTORY.md) + [skills-vs-flows.md](skills-vs-flows.md).

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
1. **Bucket 2 production cutover ([#369](https://github.com/fedoroff-vlad/ai-life/issues/369), model-gated)** — the pilot / validate-only half is DONE and #360 is closed; #369 tracks the remaining half: rip the Java `FinancialAdvisor` flow out and run the recipe from `SKILL.md` in production. Gated on the Mac / a stronger local MoE default.
2. **(Optional) fast/slow test split** — surefire unit vs failsafe container ITs, to speed the local inner loop; low value since full `verify` runs the same tests and iterating already uses `-Dtest=Class`. Pick up only if the dev loop hurts.

## Backlog (all mirrored as Issues — not near-term)
Future agents: **coach-agent #289 — PARKED mid-epic 2026-07-10** (CO-1 store + CO-2 reflect shipped; CO-3 intake…CO-7 proactive deferred — resume from [coach.md](coach.md) §Phased slices), health #187, travel #190, email #191, smart-home #192.
Capabilities/follow-ups: mcp-image-gen real engine + stylist try-on #293, mcp-web video transcripts #294, **off-site DB backup replication** (daily local dumps ship 2026-07-13; a second host over Tailscale or a cloud bucket is the follow-up — see [infra/README.md](../infra/README.md) §Database backups).
Tech-debt: Apache AGE upgrade #296 (gated), real-Ollama opt-in E2E #297. Older closed-out debt (incl. #323 JDK 21→25 Dockerfiles, done) → [HISTORY.md](HISTORY.md).
(The **skills-vs-flows** refactor track #358→#359→#360 is done and closed; the only open thread is the Mac-gated production cutover #369 in `## Next` above — [skills-vs-flows.md](skills-vs-flows.md).)

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR. **Update this file at the end of each PR; move the finished bullet to [HISTORY.md](HISTORY.md) (add a terse timeline row + the detail), don't let `## Now` accumulate ✅ DONE items.**
