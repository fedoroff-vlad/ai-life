# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

## Now
- **Identity & membership epic — IN FLIGHT ([adr/ADR-0001](adr/ADR-0001-identity-membership-scope.md),
  Accepted 2026-07-31).** Multi-tenant **workspace** identity, surfaced while scoping the #295 per-person
  ICS feed: `user → household` becomes **1:N** (personal household per user + M:N `core.household_members`),
  an item lives in **exactly one household = its visibility boundary** (tenant routing: private → personal,
  shared → family; read = union over memberships), onboarding is **invite-only** (deep-link token,
  owner-gated), a friend registers into their own isolated household. 6-slice plan owner-approved.
  **Slices 1–3 ✅.** Slice 1 (accept ADR + doc alignment, PR#373); slice 2 (`core.household_members`
  schema + backfill + profile-service read `GET /v1/users/{id}/households`, self-membership on user
  create; PR#376); slice 3 (registration → **personal** household: `gateway-telegram/IdentityResolver`
  names the new user's own household after them + they are its `admin`, never auto-attached; membership
  recorded by profile-service on user create). `users.household_id` kept as read-through default.
  NEXT = **slice 4: invite + approve flow** — deep-link `start` token store, owner "invite &lt;person&gt;
  as &lt;relationship&gt;", `/start &lt;token&gt;` binds the new registration, holder notified on join →
  inserts the `household_members(family, invitee, relationship)` row (reuses conversation-state
  pending-action + notifier). Then calendar per-item scope → **#295 feed filter** (closer). Detail →
  [adr/ADR-0001](adr/ADR-0001-identity-membership-scope.md) §Action Items.
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
Capabilities/follow-ups: mcp-image-gen real engine + stylist try-on #293, mcp-web video transcripts #294, per-person ICS filtering #295, **off-site DB backup replication** (daily local dumps ship 2026-07-13; a second host over Tailscale or a cloud bucket is the follow-up — see [infra/README.md](../infra/README.md) §Database backups).
Tech-debt: Apache AGE upgrade #296 (gated), real-Ollama opt-in E2E #297. Older closed-out debt (incl. #323 JDK 21→25 Dockerfiles, done) → [HISTORY.md](HISTORY.md).
(The **skills-vs-flows** refactor track #358→#359→#360 is done and closed; the only open thread is the Mac-gated production cutover #369 in `## Next` above — [skills-vs-flows.md](skills-vs-flows.md).)

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR. **Update this file at the end of each PR; move the finished bullet to [HISTORY.md](HISTORY.md) (add a terse timeline row + the detail), don't let `## Now` accumulate ✅ DONE items.**
