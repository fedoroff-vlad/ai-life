# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

## Now
- **➡️ skills-vs-flows Bucket 1 — lift the shared `IntentRouter` into `libs/agent-runtime` ([#358](https://github.com/fedoroff-vlad/ai-life/issues/358)).**
  The genuinely-actionable do-now track: **model- and hardware-independent, no-regret** (the Mac track below
  is parked on hardware). finance + tasks run near-identical routers (`tasks`'s is literally commented
  "Mirrors finance-agent's IntentRouter"); lift one classifier into `agent-runtime` driven purely by
  `SkillRegistry` descriptions + tool defs → kills the duplication and makes SKILL.md descriptions the true
  in-agent-routing SSOT (finishes PR#339). Three ≤5-file slices → [skills-vs-flows.md](skills-vs-flows.md)
  §Bucket 1. **Slice 1 (in flight):** extract `SkillClassifier` (prompt build + strict-JSON parse + lenient
  fallback + skill/tool result) into `agent-runtime` + unit-test it there + agent-runtime README.

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
1. **skills-vs-flows Bucket 1 slices 2–3** — migrate `finance-agent` ([#358](https://github.com/fedoroff-vlad/ai-life/issues/358) slice 2; extra flow branches → a pluggable flow-map, `GoldenRoutingTest` green) then `tasks-agent` (slice 3; `IntentRouterTest`/`GoldenInboxClarifyTest` green) onto the shared classifier.
2. **openai:-tier golden profile ([#359](https://github.com/fedoroff-vlad/ai-life/issues/359))** — let `@GoldenLlmTest` target the work OpenAI-compatible gateway (env-only, scrub-identity); enabler for Bucket 2. Feasible now.
3. **Bucket 2 pilot ([#360](https://github.com/fedoroff-vlad/ai-life/issues/360), model-gated)** — one advisory flow (`coach Reflector` / `FinancialAdvisor`) → executable SKILL.md, validated against the #359 golden; production cutover stays gated on the Mac. Depends on #358 + #359.
4. **(Optional) fast/slow test split** — surefire unit vs failsafe container ITs, to speed the local inner loop; low value since full `verify` runs the same tests and iterating already uses `-Dtest=Class`. Pick up only if the dev loop hurts.

## Backlog (all mirrored as Issues — not near-term)
Future agents: **coach-agent #289 — PARKED mid-epic 2026-07-10** (CO-1 store + CO-2 reflect shipped; CO-3 intake…CO-7 proactive deferred — resume from [coach.md](coach.md) §Phased slices), health #187, travel #190, email #191, smart-home #192.
Capabilities/follow-ups: mcp-image-gen real engine + stylist try-on #293, mcp-web video transcripts #294, per-person ICS filtering #295, **off-site DB backup replication** (daily local dumps ship 2026-07-13; a second host over Tailscale or a cloud bucket is the follow-up — see [infra/README.md](../infra/README.md) §Database backups).
Tech-debt: Apache AGE upgrade #296 (gated), real-Ollama opt-in E2E #297. Older closed-out debt (incl. #323 JDK 21→25 Dockerfiles, done) → [HISTORY.md](HISTORY.md).
(The **skills-vs-flows** refactor track #358→#359→#360 is now promoted to `## Now`/`## Next` above — [skills-vs-flows.md](skills-vs-flows.md).)

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR. **Update this file at the end of each PR; move the finished bullet to [HISTORY.md](HISTORY.md) (add a terse timeline row + the detail), don't let `## Now` accumulate ✅ DONE items.**
