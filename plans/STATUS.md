# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

## Now
- **Sharing-as-a-capability epic (ADR-0002) — COMPLETE** (2026-08-02, [adr/ADR-0002](adr/ADR-0002-sharing-shared-capability.md),
  Accepted 2026-08-01). Generalised ADR-0001's calendar tenant-routing into a reusable cross-domain
  capability: the light leaf **`libs/sharing`** (deterministic `SharingResolver` write-engine +
  per-domain `DefaultSharingPolicy` seam + `SharingContext` + `ProfileSharingClient`), `SharingScope` in
  `contracts/common`, each domain a same-named **`sharing/`** policy + a `read/*Reads` union helper.
  **All opt-in domains retrofitted** — calendar (reference, slices 3a/3b), finance (4a/4b), tasks (5a/5b),
  nutrition (6a/6b), docs (7a/7b) — each split write (route to shared vs personal household) + read (union
  personal ∪ shared on an explicit family cut, default = own). Mechanism deterministic (privacy boundary,
  never LLM-decided); only the default-when-unspecified is policy, later memory-driven via the same seam.
  **Deferred by design: item 8** — reconcile memory/second-brain's owner-tag model onto the primitive
  (when the default-policy graduates to memory-driven, ADR-0001 item 7, it plugs into the same seam).
  Slice-by-slice detail → [adr/ADR-0002](adr/ADR-0002-sharing-shared-capability.md) §Action Items +
  [HISTORY.md](HISTORY.md) (rows 2026-08-01/02).
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
