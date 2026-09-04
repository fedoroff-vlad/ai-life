# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

## Now
- **spec→test discipline (mechanize the SDD half) — IN FLIGHT (arch-checkup 2026-09-04, [#618](https://github.com/fedoroff-vlad/ai-life/issues/618)).**
  Full 7-axis findings + the future backlog live in #618 (axes 3/4/5/6/7 clean). A checkup found the
  spec-before-code link is a human convention, not an automat: only travel.md follows "each `Scenario:` names
  its asserting test" (60/60); ~99 of ~168 Scenarios name no test, and nothing lints it (one dangling ref,
  HtmlStylistRendererTest, had already rotted). Slices: **1 ✅** cheap Check 9 (`plan-test-reference` — every
  backticked `…Test` in a live plan resolves to a real class; HISTORY.md exempt) + fix the dangling ref;
  **2** authoring rule in PATTERNS.md §spec-a-slice + the `new-golden` skill; **3–5** backfill `(asserted by
  `XTest`)` across domains; **6** reconcile 6 AGENT.md `skills:` lists vs their `skills/` folders (finance/
  calendar/tasks/notes/chef/nutritionist — #486 CRUD skills never re-listed); **7** flip Check 9 to strict.

## Done (awaiting move to HISTORY at next closer)
- **video understanding [#294](https://github.com/fedoroff-vlad/ai-life/issues/294) — ✅ COMPLETE (2026-09-02).**
  Any video source (YouTube/Instagram/Threads/TikTok **link** or uploaded **file**) → one "о чём это видео".
  Shipped V-0…V-d: new acquisition capability **`mcp-media-fetch`** (yt-dlp `transcribe_video` relocated out
  of `mcp-web` + `fetch_audio`→mediaId) + **`frames`** ffmpeg keyframe tool in `mcp-media-processing` (MP-e)
  + a **`video` skill on researcher** (`flow/VideoUnderstanding`: three-tier cheap-first captions→STT→visual,
  each soft-failing → one guarded synthesis). Injection-guarded (GUARD + `fence`, model-proven by
  `GoldenVideoInjectionResistanceTest`); `E2EVideoUnderstandingFlowTest` proves the link chain's contract
  handoffs. **Link visual tier deferred** (needs a `fetch_video` acquisition tool; the file path reaches the
  visual tier normally). Detail → [HISTORY.md](HISTORY.md) + [research.md](research.md) §Video understanding +
  [media.md](media.md) §MP-e.
- **hardening §#599 injection guard — ✅ COMPLETE (2026-08-31), #599 closed.** All untrusted-ingestion
  flows (researcher web + 5 web synthesis + docs OCR) frame retrieved text as data via
  `agent-runtime` `UntrustedContent.GUARD`(+`fence`); enforced by `check-consistency.sh` check 7; two
  model-proven injection goldens. Detail → [HISTORY.md](HISTORY.md) + [architecture.md](architecture.md)
  §Security. **Sibling findings from the same 2026-08-31 architecture-checkup — both ✅ closed
  (2026-08-31):** [#600](https://github.com/fedoroff-vlad/ai-life/issues/600) model-strategy↔`.env.mac.example`
  drift (reconciled with cross-references — interim tags vs MoE deploy targets, hardware-gated) +
  [#601](https://github.com/fedoroff-vlad/ai-life/issues/601) root `AGENTS.md` (thin pointer to `CLAUDE.md`).
  **The architecture-checkup is fully resolved.** Detail → [HISTORY.md](HISTORY.md).
- **arch §#477 real agent-led multi-domain coordination — ✅ COMPLETE (2026-08-28), #477 closed (Track I,
  [stage4.md](stage4.md) §Track I).** The coordinator substrate (#290) was built but the cross-domain path was
  thin (only finance + calendar exposed `brief`). Both slices shipped: **I1** tasks-agent as the 3rd `brief`
  exposer + roster entry (FAST planner now picks among ≥3 real specialists) and **I2** the multi-domain
  stage-closer `E2ECoordinateMultiDomainTest` — a "спланируй выходные" ask fans out to finance+calendar+tasks
  **through the hub** and returns one synthesis grounded in every brief, proving per-source soft-fail (one
  specialist 500s → survivors still synthesize, never a 500) + the no-direct-agent-to-agent invariant. The
  genuine cross-domain path is now proven, not just the coordinator demo. **I3 (live-read brief enrichment —
  the `answer(request, extraGather)` overload, applies to every exposer) deferred/optional; not needed to prove
  #477.** Detail → [HISTORY.md](HISTORY.md) + [stage4.md](stage4.md) §Track I. **Epic
  [#479](https://github.com/fedoroff-vlad/ai-life/issues/479) closed (2026-08-28)** — all 5 review findings
  shipped; only the standalone mac-gated Bucket 2 cutover #369 carries on (see `## Next`).
- **arch §#476 personalization-profile capability — ✅ COMPLETE (2026-08-28), #476 closed.** All 7 slices
  shipped (per [ADR-0005](adr/ADR-0005-personalization-profile-capability.md)): foundation
  (`libs/profile` leaf `ProfileScope`/`ProfileScopeResolver`/`PersonalizationProfileClient` +
  `agent-runtime` `PersonalizationProfiler`/`ProfileSpec` template) + briefing reference + creator +
  nutrition + travel + stylist. The repeated `(household, owner)` per-member profile pattern is now one
  shared mechanism (store stays per-domain); **#490 FO-3 family-default inheritance reaches
  briefing/creator/nutrition/travel** (stylist opts out by design — per-person style profile). Detail →
  [HISTORY.md](HISTORY.md) + [ADR-0005](adr/ADR-0005-personalization-profile-capability.md). Epic #479 closed
  (2026-08-28); the standalone mac-gated Bucket 2 cutover #369 is the only surviving thread (see `## Next`).
- **road-test §#490 family-onboarding — ✅ COMPLETE (2026-08-28).** UX/onboarding layer on the shipped
  identity ([ADR-0001](adr/ADR-0001-identity-membership-scope.md)) + sharing
  ([ADR-0002](adr/ADR-0002-sharing-shared-capability.md)) plumbing so a 2nd member (wife) is productive in
  minutes: **FO-1** join orientation (gateway redeem reply orients the new member), **FO-3** sensible
  defaults on join (a member who set nothing inherits the family/shared briefing default via
  `ProfileClient.householdRouting`), **FO-2** keyword-free per-member prefs (the `briefing-profiler` SKILL
  now captures a cue-less stated preference as a `self` config; golden-proved on qwen3:8b). Detail →
  [HISTORY.md](HISTORY.md) + [core.md](core.md) §Onboarding UX. **This closes the assistant-hardening
  road-test epic [#491](https://github.com/fedoroff-vlad/ai-life/issues/491)** (#483…#490) — **Jarvis-style
  autonomy (smart-home [#192](https://github.com/fedoroff-vlad/ai-life/issues/192), coordination
  [#477](https://github.com/fedoroff-vlad/ai-life/issues/477)) is now unblocked.** Next work = pick from
  `## Next` / the [`future-agent`](https://github.com/fedoroff-vlad/ai-life/labels/future-agent) backlog.
- **road-test §#489 multimodal/reply-UX — ✅ COMPLETE (2026-08-28).** All four slices shipped: **RU-1** typing
  indicator, **RU-2** inline Да/Нет confirm buttons, **RU-3** STT reliability gate (empty/low-confidence voice →
  ask to repeat), **RU-4** photo/receipt robustness (unreadable captionless document photo → ask for a clearer
  shot; OCR-confidence twin of RU-3, on the docs path — the receipt/finance path already asked). Detail →
  [HISTORY.md](HISTORY.md) + [platform.md](platform.md) §Multimodal & reply UX. (Superseded by the §#490
  COMPLETE bullet above — #490 was the last road-test item and is now done.)
- **road-test §#488 memory-quality — ✅ CORE COMPLETE (2026-08-26); only the Mac-gated ambient enable-flip remains.**
  Made what the assistant remembers **visible + correctable** ([#488](https://github.com/fedoroff-vlad/ai-life/issues/488)):
  **MQ-1** review digest ("что ты про меня запомнил"), **MQ-2** forget/correct a fact on the shared
  [ADR-0004](adr/ADR-0004-confirm-act-flow.md) runner, **MQ-3** ambient-precision eval (labelled corpus + pure
  `AmbientCaptureScore` + gated `GoldenAmbientPrecisionTest` asserting act-precision/recall thresholds). Detail →
  [HISTORY.md](HISTORY.md) + [second-brain.md](second-brain.md) §MQ + [ambient-capture.md](ambient-capture.md) §MQ-3.
  **Deferred (Mac-gated):** flipping `MEMORY_AMBIENT_CAPTURE_ENABLED` on once `GoldenAmbientPrecisionTest` is green on
  the deploy model (stronger local MoE; dev box CPU-only). **Next road-test item** (epic
  [#491](https://github.com/fedoroff-vlad/ai-life/issues/491)): multimodal/reply-UX
  [#489](https://github.com/fedoroff-vlad/ai-life/issues/489) + family-onboarding
  [#490](https://github.com/fedoroff-vlad/ai-life/issues/490).
- **road-test §#487 proactive UX — ✅ CORE COMPLETE (2026-08-25); PX-4 deferred.** notifier is the single
  seam every proactive push flows through, so a send-time gate (`NotificationGate`) makes proactivity
  controllable — deterministic, never an LLM call, inert when reactive/unconfigured/no-DB: **PX-1**
  quiet-hours *hold + redeliver* (a proactive push in the user's quiet window is parked in
  `core.notification_held` and redelivered by a `@Scheduled` tick when it opens, dropped if stale) · **PX-2**
  daily-cap *suppress* (over the per-user ceiling → dropped, counted in `core.notification_sent`) · **PX-3**
  per-stream *opt-out* (a muted stream in `core.notification_stream_optout` is suppressed first; every
  producer now attributes its coarse stream). Detail → [HISTORY.md](HISTORY.md) + [platform.md](platform.md)
  §Proactive UX. **PX-4 (snooze/dismiss buttons + mute-via-button) deferred** — its Telegram inline-button/
  callback infra overlaps [#489](https://github.com/fedoroff-vlad/ai-life/issues/489), to be built once as a
  shared primitive. (§#488 memory-quality is now the active bullet above.)
- **road-test §#486 CRUD/undo — ✅ COMPLETE (2026-08-25).** The cross-cutting **"отмени последнее" undo
  primitive** (H1 storage → H2 orchestrator → H3 tasks → H-rollout to finance/notes) + the **per-domain chat
  CRUD holes**: calendar create/undo/cancel/move (HC-1…HC-4), delete-by-description ×3 (tasks/finance/notes),
  and the edit surface (notes/tasks/finance edit + finance re-categorise + tasks GTD state-move) — all
  confirm-gated, on each domain's own SkillRouter path, the confirm-act flows riding the shared
  [ADR-0004](adr/ADR-0004-confirm-act-flow.md) `PickConfirmActRunner`. **Create/edit/delete + undo now complete
  across tasks/finance/notes/calendar.** Detail → [HISTORY.md](HISTORY.md) +
  [tasks.md](tasks.md)/[finance.md](finance.md)/[second-brain.md](second-brain.md)/[calendar.md](calendar.md)
  §H.2 + [stage4.md](stage4.md) §Track H. (Next road-test item is carried by the §#487 bullet above.)
- **arch epic [#479](https://github.com/fedoroff-vlad/ai-life/issues/479) → #475 in-agent routing — ✅ DONE
  (2026-08-17).** All 8 cue-routed agents (notes / creator / docs / nutritionist / briefing / stylist / chef
  / travel) migrated off their `*_CUES` keyword heuristics onto the shared `agent-runtime`
  `intent/SkillRouter`; every keyword router deleted. **Bucket 1 is now fully complete** (finance + tasks
  were the original two). Detail → [skills-vs-flows.md](skills-vs-flows.md) §Bucket 1 + per-agent HISTORY rows
  (2026-08-17). **Epic #479 closed (2026-08-28)** — #475 ✅, #476 ✅ (ADR-0005), #477 ✅, #478 ✅, #547 ✅;
  the only surviving thread is the standalone mac-gated Bucket 2 cutover
  [#369](https://github.com/fedoroff-vlad/ai-life/issues/369) (see `## Next`).
  **confirm-act flow dedup [#547](https://github.com/fedoroff-vlad/ai-life/issues/547) — ✅ DONE, #547 closed
  (2026-08-23).** ADR-0004 lifted the copy-pasted `read candidates → LLM picks → pendingAction confirm →
  resume → act` loop into a generic `PickConfirmActRunner` (`libs/agent-runtime/intent/`, act as a delete|update
  seam + a `missing`-field re-ask gate) and retrofitted all five confirm-act flows onto it (delete×3 + calendar
  cancel/move; the H.2 edits above then rode it too). Detail → [HISTORY.md](HISTORY.md) +
  [ADR-0004](adr/ADR-0004-confirm-act-flow.md).
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
1. **travel follow-ups on the now-built `travel.trip` store — both near-term items DONE.**
   - **[#436](https://github.com/fedoroff-vlad/ai-life/issues/436) route/itinerary import** — **DONE (RT-a…RT-d2)** for files (GPX/GeoJSON/KML/KMZ) + map links (see [HISTORY.md](HISTORY.md)); only browser-resolved short links / JS polylines remain → **TR-f3** (`mcp-browser`). #436 can be closed (TR-f3 tracks the remainder).
   - **[#438](https://github.com/fedoroff-vlad/ai-life/issues/438) packing-list** — **DONE (PK-a, PR457)**: a deterministic list seeded by the active trip's season + rest types + companions on the TR-e board seam. #438 closed.
   - Spec/ideas: [travel.md](travel.md) §Deferred + §Ideas from TREK.
2. **Pick the next future agent** — travel #190 is fully closed (MVP + live search + trip wallet). Backlog (owner priority): health [#187](https://github.com/fedoroff-vlad/ai-life/issues/187), email [#191](https://github.com/fedoroff-vlad/ai-life/issues/191), smart-home [#192](https://github.com/fedoroff-vlad/ai-life/issues/192); or resume the parked coach-agent [#289](https://github.com/fedoroff-vlad/ai-life/issues/289) (CO-3+). See the [`future-agent`](https://github.com/fedoroff-vlad/ai-life/labels/future-agent) label + `## Backlog`.
3. **Bucket 2 production cutover ([#369](https://github.com/fedoroff-vlad/ai-life/issues/369), model-gated)** — the pilot / validate-only half is DONE and #360 is closed; #369 tracks the remaining half: rip the Java `FinancialAdvisor` flow out and run the recipe from `SKILL.md` in production. Gated on the Mac / a stronger local MoE default.
4. **travel TR-f3 (deferred)** — tours + no-API/JS sources → `mcp-browser` (browser-use), which also closes the general scraping gap. Not near-term (see `## Backlog`).

_(fast/slow test split — DONE 2026-08-07, see [HISTORY.md](HISTORY.md) + [migration-25-boot4.md](migration-25-boot4.md) §Build/CI performance lever 2.)_

## Backlog (all mirrored as Issues — not near-term)
Future agents: **coach-agent #289 — PARKED mid-epic 2026-07-10** (CO-1 store + CO-2 reflect shipped; CO-3 intake…CO-7 proactive deferred — resume from [coach.md](coach.md) §Phased slices), health #187, travel #190, email #191, smart-home #192.
Capabilities/follow-ups: mcp-image-gen real engine + stylist try-on #293 (GPU-gated, parked). (**#294 video
understanding is now IN FLIGHT — see `## Now`.**)
**Lists capability** (owner idea, 2026-08-14) — **COMPLETE** (LI-a + LI-b + LI-c), see [lists.md](lists.md):
grocery/things lists as **structured item lists** (add/check-off/clear) on the `memory.note` tier, owned by
**notes-agent**; LI-a explicit ops (#466) + LI-b ambient keyword-free capture + LI-c travel packing-list
mirrored as a `type=list` note. Detail → [HISTORY.md](HISTORY.md). (**off-site DB backup replication** DONE 2026-08-09 → HISTORY: `offsite` compose profile, `rclone-offsite` with a `BACKUP_OFFSITE_REMOTES` flag choosing Yandex Disk and/or a Tailscale host.)
**Assistant hardening / road-test** (owner direction 2026-08-16: daily-drive it as a reliable personal
assistant *before* any autonomy/actuation) — **epic [#491](https://github.com/fedoroff-vlad/ai-life/issues/491)
✅ COMPLETE (2026-08-28)**: transparency #485 + routing #484 → CRUD/undo #486 → proactive-UX #487 +
memory-quality #488 → multimodal/reply-UX #489 + family-onboarding #490 all shipped (only the 24/7 instance
[#483](https://github.com/fedoroff-vlad/ai-life/issues/483) remains, hardware-blocked). Detail →
[HISTORY.md](HISTORY.md). **Jarvis-style autonomy (smart-home #192, coordination #477) is now unblocked.**
**Architecture hardening** (2026-08-16 review) — **epic [#479](https://github.com/fedoroff-vlad/ai-life/issues/479) ✅ CLOSED (2026-08-28)**: all 5 review findings shipped — in-agent routing [#475](https://github.com/fedoroff-vlad/ai-life/issues/475) ✅; personalization-profile capability [#476](https://github.com/fedoroff-vlad/ai-life/issues/476) ✅ (ADR-0005); agent-led multi-domain coordination [#477](https://github.com/fedoroff-vlad/ai-life/issues/477) ✅; `shared/skills/` doctrine reconcile [#478](https://github.com/fedoroff-vlad/ai-life/issues/478) ✅ (trimmed to "mechanism available, zero tenants"); confirm-act dedup [#547](https://github.com/fedoroff-vlad/ai-life/issues/547) ✅ (ADR-0004). The one deferred checklist item, Bucket 2 cutover [#369](https://github.com/fedoroff-vlad/ai-life/issues/369), was mac-gated / not near-term and carries on standalone (see `## Next`) — no value holding the epic open on it.
**Runtime topology / footprint** (owner concern 2026-08-28) — **epic [#584](https://github.com/fedoroff-vlad/ai-life/issues/584)**, [ADR-0006](adr/ADR-0006-runtime-topology-footprint.md) (Proposed, mac-gated): decouple runtime process count from code-module structure to free RAM for the local model — measurement-first → process consolidation (few JVM hosts, code untouched) → native-image for the resident hot set; CDS/AOT (LC-3a) stays as the cold-start-latency lever. Correctness (types/goldens/E2E/privacy) preserved; domain logic never rewritten. **Slice 2 (topology map) drafted — [topology-map.md](topology-map.md)** (the non-blocked design half: 47 JVMs → ~12 hosts, `memory-service` isolated). **Slice 1 (measurement harness) authored — [`scripts/measure-footprint.sh`](../scripts/measure-footprint.sh)** (per-process RSS + JVM/model/Postgres split, `--json` for slice-3 diffing; spec: [topology-map.md](topology-map.md) §Slice 1); it *runs* at deploy — real RSS needs the Mac (no Docker on the dev VDI). Slices 3/5 (consolidation spike, native lane) stay mac-gated.
Tech-debt: Apache AGE upgrade #296 (gated). Older closed-out debt (incl. #323 JDK 21→25 Dockerfiles, done) → [HISTORY.md](HISTORY.md).
(The **skills-vs-flows** refactor track #358→#359→#360 is done and closed; the only open thread is the Mac-gated production cutover #369 in `## Next` above — [skills-vs-flows.md](skills-vs-flows.md).)

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR. **Update this file at the end of each PR; move the finished bullet to [HISTORY.md](HISTORY.md) (add a terse timeline row + the detail), don't let `## Now` accumulate ✅ DONE items.**
