# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

## Now
- **✅ ambient / intuitive capture — COMPLETE (AC-1..AC-5, 2026-07-03…07-04).** The second brain now fills itself without the "запомни" keyword: `CaptureService` classifies every message (explicit fixation → auto-save `source=user`; important inferred → **approve-first** via proactive push + notes-agent `/resume` → `source=ambient`; trivial → ignore), and on a near-duplicate **reconciles** (`NoteReconciler` enrich/supersede/skip) instead of blindly writing. All flag-gated (`memory.ambient-capture.enabled`, off by default). Detail → **[ambient-capture.md](ambient-capture.md)** + [memory-service README](../platform/memory-service/README.md) + [notes-agent README](../domains/knowledge/notes-agent/README.md); shipped timeline → [HISTORY.md](HISTORY.md). This completes the *input/quality* half of the owner's north-star; the *output* half (memory-driven orchestration) is a separate follow-on (item 3).
- **✅ memory-driven orchestration — [#290](https://github.com/fedoroff-vlad/ai-life/issues/290) COMPLETE (owner item 3), the *output* half of the north-star.** All Track-E slices shipped: **A** (`coordinator-agent` 8119, data-driven multi-domain routing → one memory-driven synthesis), **B1** (read-only `brief` primitive — `BriefResponder` in agent-runtime), **B2** (coordinator plans + gathers live specialist `brief`s into `context.briefs`), **B2-followup** (calendar the 2nd exposer → planner picks among ≥2 specialists), and **E-later** (this PR — the bounded `gather → synthesize → self-check → maybe-re-gather` loop: a FAST `SufficiencyAssessor` gates one re-gather within `coordinator-agent.max-rounds`, default 2; `max-rounds: 1` = one-shot). Detail → [coordinator-agent README](../domains/assistant/coordinator-agent/README.md), [stage4.md](stage4.md) Track E; timeline → [HISTORY.md](HISTORY.md). **➡️ NEXT stage = platform migration [#288](https://github.com/fedoroff-vlad/ai-life/issues/288)** (owner item 1.7), see `## Next`.

## Next (owner priority order, set 2026-07-04 — the backlog now lives in GitHub Issues)
1. **⛰️ Platform migration: Java 25 + Spring Boot 4 + Spring AI 2 — [#288](https://github.com/fedoroff-vlad/ai-life/issues/288)** (owner item 1.7). Own stage: Java 25 isolated first slice, then Boot 4 + Spring AI 2, **then a build/CI performance pass** — full `mvn verify` is now ~15 min and the repo (51 modules, ~12 with Testcontainers) keeps growing, so treat build time as a first-class constraint: after the bumps land, scan + propose ranked speedups (Testcontainers reuse/singleton, re-evaluate `-T` on the Java-25 heap baseline, fast/slow test split). [migration-25-boot4.md](migration-25-boot4.md) §Build/CI performance.
2. **Finance year-analysis report + chart-render — [#291](https://github.com/fedoroff-vlad/ai-life/issues/291) + [#292](https://github.com/fedoroff-vlad/ai-life/issues/292)** (owner item 4; monthly-report MVP shipped under closed #196).

## Backlog (all mirrored as Issues — not near-term)
Future agents: coach-agent #289 (spec-first), health #187, travel #190, email #191, smart-home #192.
Capabilities/follow-ups: mcp-image-gen real engine + stylist try-on #293, mcp-web video transcripts #294, per-person ICS filtering #295.
Tech-debt: Apache AGE upgrade #296 (gated), real-Ollama opt-in E2E #297. Older closed-out debt → [HISTORY.md](HISTORY.md).

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR. **Update this file at the end of each PR; move the finished bullet to [HISTORY.md](HISTORY.md) (add a terse timeline row + the detail), don't let `## Now` accumulate ✅ DONE items.**
