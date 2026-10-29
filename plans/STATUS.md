# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

## Deployment reality — built ≠ live (read before trusting a ✅)
Two different claims live in these docs; keep them apart (FUT-2, arch-checkup 2026-09-04 "built vs running" gap):
- **`built+green`** — code merged into `main`, CI (unit/slice/golden/E2E) green. This is what **every ✅ / DONE /
  COMPLETE here and in HISTORY means** unless a bullet says otherwise.
- **`live`** — actually running in a deployed instance serving the household.
**Nothing is `live` yet.** There is **no 24/7 deployment** — the target host (Mac Studio) isn't purchased, so
the whole system is `built+green`, dev/CI-only ([#483](https://github.com/fedoroff-vlad/ai-life/issues/483),
[lifecycle.md](lifecycle.md)). A "✅ COMPLETE" agent/skill means *the code is proven in tests*, **not** "an agent
is using it in production". The **`## Parked`** Mac-gated tracks are a stricter case — blocked even for
build/validation (no Docker on the dev VDI): they are `designed`/`partially-built`, not fully `built+green`. When
the Mac lands, the deploy/lifecycle slices (LC-2…LC-5) are what flip `built+green` → `live`.

## Now
- **Runtime consolidation ([#584](https://github.com/fedoroff-vlad/ai-life/issues/584), [ADR-0006](adr/ADR-0006-runtime-topology-footprint.md)) — Path B / B1 in flight (owner direction 2026-09-08).** Goal: collapse ~50 JVMs (~10–15 GB) into ~13 co-hosting JVM hosts (~2–3 GB), zero contract change. **Slice 1** (measurement harness [`scripts/measure-footprint.sh`](../scripts/measure-footprint.sh)) and **slice 2** (the topology map) are authored; **slice 3 has shipped 3a…3k** — the packaging enabler a spike found to be the prerequisite (`exec` classifier → plain classes jar as the main artifact, `-exec.jar` for Docker) + the **entire resident tier** (Domain-MCP-hot 3c · Agent-hot 3d · Platform-hot 3e) + **six cold host-units** (Docs 3f · Content 3g · Lifestyle 3h · Brief+Travel 3i · Finance-aux 3j · Inventory 3k), each proven by its own `*HostFootprintIntegrationTest`. Per-slice detail and the three reusable launcher fixes (the `application.yml` config-name skip · the per-module `web-application-type` re-supply · the per-agent `manifest/<name>/AGENT.md` path) live in **[topology-map.md](topology-map.md) §Slice 3 — the SSOT; this bullet links, it does not restate.** **Next:** (i) the RAM-delta measurement (env-wired host `main` + `measure-footprint.sh` on the running stack — the "~2–3 GB vs ~10–15 GB" number, Mac-gated), (ii) **3l** — wire `mcp-media-fetch` into `domain-mcp-host` (that host's image then needs `yt-dlp`+`ffmpeg`), (iii) the last cold unit — Coach (parked [#289](https://github.com/fedoroff-vlad/ai-life/issues/289)). Native/GraalVM (path C) stays the later, mac-gated lever.

- **browser capability ([TR-f3](https://github.com/fedoroff-vlad/ai-life/issues/190)) — ADR-0008 flagged, awaiting owner pick (2026-09-20).** Owner picked the TR-f3 (`mcp-browser`) track; it's a genuinely new layer (headless Chromium in the stack → footprint tension with #584, widened injection surface), so per "flag before code" it's an ADR first, not code. **[ADR-0008](adr/ADR-0008-browser-capability.md) (Proposed)** fixes the shape (cold, read-only, shared capability-MCP — per ADR-0003 + ADR-0006) and forks the engine: **Option A** deterministic Playwright-Java fetch (`resolve_url`+`fetch_rendered`, no LLM — closes RT-d short-links + the `mcp-web` JS gap; **recommended**) vs **Option B** agentic browser-use (Python + LLM, interactive tours; heaviest). **Next:** owner picks A or B → ADR to Accepted → slice the engine (WHEN/THEN-first, cold+read-only acceptance criteria + injection golden) → wire the first consumer (RT-d short-link path or `mcp-web` JS fallback). Detail → [travel.md](travel.md) §TR-f3 + §Deferred.

- **typed-decision channel ([ADR-0010](adr/ADR-0010-typed-decision-channel.md)) — flagged, awaiting owner pick (2026-10-28).** A new model class (open-weight *System One* / typed-decision models: `/v1/systemone`, `noul`/`choice`/`score`, calibrated probabilities, no text generation) would replace the strict-JSON classifier prompts the `fast` channel exists for — `SkillClassifier`'s format-drift branch, finance's enum-pinning `extraRules` and the prose fallback are all workarounds for using a text generator as a classifier. It is a new layer → per "flag before code" it is an **ADR first, not code**. Settled in the ADR: a fifth `llm-gateway` channel (`decision`, never a per-agent client), open weights self-hosted, never in the resident GPU budget, existing goldens as the acceptance gate. Engine forked: **Option A** Decis container (recommended) vs **Option B** EdgeJev ONNX/INT8 on CPU (would be the repo's first own non-JVM module); **Option C** status quo as fallback. First site picked: **`SkillClassifier`** as a stage-split (decision picks the action, LLM fills args) — three consumers behind one shared class. **The calibration spike has run (2026-10-29, no Mac needed → ADR §Spike result, harness in [`scripts/decision-spike/`](../scripts/decision-spike)):** `laya-multilingual` **rejected** (33% RU / 58% EN on asserted labels, anti-calibrated, and its 256-token per-question limit cannot even express the finance/tasks option sets); **`kev-0.8b` viable** — 100% tasks / 92% orchestrator / 83% finance on asserted labels, RU ≈ EN, hard tool-vs-flow discriminations right, 1.4–2.0 s/decision float32-on-CPU vs **≈5.6 s warm / ≈15 s cold** for today's path (3–7×). Pilot shape changed to a **confidence-gated hybrid** (decision model above that surface's threshold, fall through to today's classifier below it), because 83% against a 6/6 gate would otherwise turn it red. Three side-findings the ADR records: batching is ~2.6–2.8× for 3 questions (**not** free), confidence thresholds must be **per surface** (more options spread the mass thinner), and the opt-in golden lane is **green only 2 of 3 runs** on the tasks router — a 1-in-3 flake on the very case kev got right, so a candidate must be compared against a **multi-run** baseline measured in the same session. **Next:** owner picks Option A (Decis + `kev-0.8b`) / B (EdgeJev INT8 leaf) / C (stay put) → ADR to Accepted. Detail → the ADR; it is the SSOT, this bullet links.

- **release & deploy — [ADR-0011](adr/ADR-0011-release-and-deploy.md) Accepted, machinery not built (2026-10-29).** The Mac's arrival gives the project a prod instance for the first time and there was no release policy at all: 72 poms on one literal `0.0.1-SNAPSHOT`, **zero git tags**, all 50 app images tagged `:local` with `--build` as the deploy, 42 Liquibase changelogs with **no** `rollback:` or `tagDatabase`, and off-site replication that covers the DB dumps but **not** `infra/.env` / `infra/secrets/`. Release machinery is a new layer → per "flag before code" the policy landed as an **ADR first, not a script**. **All three forks picked by the owner:** **RG-A → RG-B staged** (build on the Mac for the first live release, GitHub Actions + GHCR once the ADR-0006 host-units land — gated on host-units · the arm64-runner check · a build-vs-resident-model measurement), **MG-1** forward-only + expand/contract (now a standing authoring rule in [PATTERNS.md](PATTERNS.md) §Recipe: add a Liquibase migration, because that is the file read before a changeset is written), **SR-1** host-only secrets + an out-of-band copy (SR-2 deferred, SOPS-in-git rejected — the repo is public). Also settled: one repo-wide version with the git tag as the version of record, semver as **operator impact**, the portable `release-version` skill keeps the procedure (ai-life adds only a `project-version` coupling — no second SSOT), the release unit is the **deployable unit**, a gate with a **CI half and a host half** (the golden lane can only run on the Mac), the owner deploys by hand and **`supervisor` is never the deployer** (ADR-0007 withholds exactly that surface), cold units staged before the switch, dump-then-migrate. **Next:** **RL-1** (versioned image tags — authorable now, verifiable only on the Mac) → RL-2 → RL-3/RL-4 (host-gated); RL-5 parked behind the RG-B gates. Slices + WHEN/THEN → [release.md](release.md); the ADR is the SSOT, this bullet links.

## Deferred — open threads left behind by shipped work
Each bullet is the **whole** live claim; the authoritative detail is at the linked source. The shipped work
itself lives in [HISTORY.md](HISTORY.md) (rows 2026-08-17…2026-09-27 cover everything this section used to
restate — epics [#479](https://github.com/fedoroff-vlad/ai-life/issues/479) and
[#491](https://github.com/fedoroff-vlad/ai-life/issues/491), the inventory domain, video understanding
[#294](https://github.com/fedoroff-vlad/ai-life/issues/294) and the [#599](https://github.com/fedoroff-vlad/ai-life/issues/599)
injection guard, all closed).
- **inventory** — the native TSPL printer template (hardware not bought; the MVP hands a QR image over to
  print from the phone) + the [ADR-0002](adr/ADR-0002-sharing-shared-capability.md) sharing retrofit, so
  reads stay envelope-household scoped. Both deferred by choice. → [inventory.md](inventory.md) §Deferred
- **video understanding** — the *link* visual tier needs a `fetch_video` acquisition tool; the
  uploaded-**file** path already reaches the visual tier normally.
  → [research.md](research.md) §Video understanding
- **coordination** — I3 live-read brief enrichment (the `answer(request, extraGather)` overload, applies to
  every `brief` exposer). Optional; [#477](https://github.com/fedoroff-vlad/ai-life/issues/477) closed
  without it. → [stage4.md](stage4.md) §Track I
- **memory quality — Mac-gated** — flip `MEMORY_AMBIENT_CAPTURE_ENABLED` on once
  `GoldenAmbientPrecisionTest` is green on the deploy model (the dev box is CPU-only).
  → [ambient-capture.md](ambient-capture.md) §MQ-3
- **proactive UX — PX-4 (snooze/dismiss + mute-via-button), now unblocked.** It was deferred because its
  Telegram inline-button/callback infra overlapped
  [#489](https://github.com/fedoroff-vlad/ai-life/issues/489), to be built once as a shared primitive —
  **that primitive shipped with RU-2** (`ConfirmKeyboard` + the gateway's callback-query dispatch, whose
  javadoc already names PX-4 as its next tenant), so the stated blocker is gone and this is buildable now.
  → [platform.md](platform.md) §Proactive UX

## Parked — blocked on hardware (Mac not yet purchased)
- **The full golden lane — throughput-gated, not broken (measured 2026-09-24).** The dev VDI has no GPU:
  `qwen3:8b` Q4 generates **~7.4 tok/s**, so only the cheap subset (`Golden*RoutingTest` /
  `Golden*InjectionResistanceTest`, ~30–60 s a class off the warm stack) is runnable locally. A
  full-reactor attempt stopped after 13 classes (26 tests green) with **two environment failures, both
  timeouts** — finance's `GoldenAdvisorSynthesisTest` (the advisor's own `TimeoutException` → soft-failed
  short reply → the "implausibly short" assertion fired correctly) and finance's `GoldenRoutingTest`
  (120 s `block` expired while the box was still busy). **Neither is a logic regression**, and retrying on
  the same hardware cannot clear them. The lane runs for real on the deploy box against the stronger MoE
  default — step 3 of [model-strategy.md](model-strategy.md) §Rollout; per-class recipe + the `-fae` note →
  [`platform/llm-gateway/README.md`](../platform/llm-gateway/README.md) §Golden tests.
- **Mac deployment + hot/cold lifecycle — [lifecycle.md](lifecycle.md) (owner-signed 2026-07-10).** Target:
  Mac Studio M4 Max 64/512 running ai-life 24/7 (hot set always-on + auto cold start/stop via a new
  `platform/supervisor`, + dynamic model downshift when the coder tenant runs). **Shipped so far** →
  HISTORY: `deploy-mvp` config (`.env.mac.example`, per-JVM heap caps, infra/README), **LC-1** hot/cold
  compose profiles (2026-07-15), **LC-4** `/v1/model-profile` evict-before-load swap (2026-07-21). **Why
  parked:** the next slice **LC-2 (`platform/supervisor` + socket-proxy)** wraps `docker compose up -d` and
  can't be meaningfully built/tested without a Docker daemon — there is none on the dev box, and the Mac
  isn't purchased yet. `deploy-mvp`'s own remaining step (first real `--profile hot up` + cross-domain
  smoke) is likewise hardware-blocked. **Note (2026-10-07):** that first `--profile hot up` would have
  failed on arrival — #294 left the hot set's dependency closure broken (`invalid compose project`) for a
  month. Fixed + now machine-checked by `check-consistency.sh` check 13; see
  [lifecycle.md](lifecycle.md) §Hot/cold. Ordering when hardware lands: LC-2 → LC-2.5 cold-tolerant discovery
  → LC-3 (+3a AOT) → LC-5. Model stack decided → [model-strategy.md](model-strategy.md) (MoE-first; two MoE
  tenants may make the LC-4 downshift optional — **measure residency live at deploy**). coach-agent parked (Backlog).

## Next (owner priority order — the backlog now lives in GitHub Issues)
1. **Pick the next future agent** — travel [#190](https://github.com/fedoroff-vlad/ai-life/issues/190) is fully closed (MVP + live search + trip wallet + route import + packing list; its issues #436/#438 closed with the work). Backlog (owner priority): health [#187](https://github.com/fedoroff-vlad/ai-life/issues/187), email [#191](https://github.com/fedoroff-vlad/ai-life/issues/191), smart-home [#192](https://github.com/fedoroff-vlad/ai-life/issues/192); or resume the parked coach-agent [#289](https://github.com/fedoroff-vlad/ai-life/issues/289) (CO-3+). See the [`future-agent`](https://github.com/fedoroff-vlad/ai-life/labels/future-agent) label + `## Backlog`.
2. **Bucket 2 production cutover ([#369](https://github.com/fedoroff-vlad/ai-life/issues/369), model-gated)** — the pilot / validate-only half is DONE and #360 is closed; #369 tracks the remaining half: rip the Java `FinancialAdvisor` flow out and run the recipe from `SKILL.md` in production. Gated on the Mac / a stronger local MoE default.
3. **travel TR-f3 → `mcp-browser`** — tours + no-API/JS sources + the remaining RT-d short-links / JS polylines, also closes the general scraping gap. **Flagged in [ADR-0008](adr/ADR-0008-browser-capability.md) (Proposed) — see `## Now`;** awaiting owner pick between the deterministic (Option A) and agentic (Option B) engine before any code. Spec/ideas: [travel.md](travel.md) §Deferred + §Ideas from TREK.

_(fast/slow test split — DONE 2026-08-07, see [HISTORY.md](HISTORY.md) + [migration-25-boot4.md](migration-25-boot4.md) §Build/CI performance lever 2.)_

## Backlog (all mirrored as Issues — not near-term)
Future agents: **coach-agent [#289](https://github.com/fedoroff-vlad/ai-life/issues/289) — PARKED mid-epic
2026-07-10** (CO-1 store + CO-2 reflect shipped; CO-3 intake…CO-7 proactive deferred — resume from
[coach.md](coach.md) §Phased slices), health #187, email #191, smart-home #192. (travel #190 shipped — see
`## Next`.)
Capabilities/follow-ups: mcp-image-gen real engine + stylist try-on #293 (GPU-gated, parked).
Tech-debt: Apache AGE upgrade #296 (gated). Older closed-out debt (incl. #323 JDK 21→25 Dockerfiles, done)
→ [HISTORY.md](HISTORY.md).

**Closed epics — pointers only** (detail → [HISTORY.md](HISTORY.md); kept here because each left something
live):
- **Assistant hardening / road-test [#491](https://github.com/fedoroff-vlad/ai-life/issues/491) ✅
  (2026-08-28)** — #485/#484 → #486 → #487/#488 → #489/#490 all shipped. What is still live: the 24/7
  instance [#483](https://github.com/fedoroff-vlad/ai-life/issues/483) (hardware-blocked, `## Parked`) and
  PX-4 (`## Deferred`). **It unblocked Jarvis-style autonomy** — smart-home #192 (open, above) and
  coordination #477 (shipped).
- **Architecture hardening [#479](https://github.com/fedoroff-vlad/ai-life/issues/479) ✅ (2026-08-28)** —
  all 5 findings shipped (#475 routing · #476 ADR-0005 profile capability · #477 coordination · #478
  `shared/skills/` doctrine · #547 ADR-0004 confirm-act dedup). What is still live: the mac-gated Bucket 2
  cutover [#369](https://github.com/fedoroff-vlad/ai-life/issues/369) (`## Next`), which also carries the
  closed **skills-vs-flows** track #358→#360 — [skills-vs-flows.md](skills-vs-flows.md).
- **Lists capability ✅** (LI-a…LI-c, [lists.md](lists.md)) and **off-site DB backup replication ✅**
  (2026-08-09, `offsite` compose profile + `BACKUP_OFFSITE_REMOTES`) — nothing live.

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR. **Update this file at the end of each PR; move the finished bullet to [HISTORY.md](HISTORY.md) (add a terse timeline row + the detail), don't let `## Now` accumulate ✅ DONE items.**
