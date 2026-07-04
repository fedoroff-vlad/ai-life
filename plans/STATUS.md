# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

## Now
- **✅ ambient / intuitive capture — COMPLETE (AC-1..AC-5, 2026-07-03…07-04).** The second brain now fills itself without the "запомни" keyword: `CaptureService` classifies every message (explicit fixation → auto-save `source=user`; important inferred → **approve-first** via proactive push + notes-agent `/resume` → `source=ambient`; trivial → ignore), and on a near-duplicate **reconciles** (`NoteReconciler` enrich/supersede/skip) instead of blindly writing. All flag-gated (`memory.ambient-capture.enabled`, off by default). Detail → **[ambient-capture.md](ambient-capture.md)** + [memory-service README](../platform/memory-service/README.md) + [notes-agent README](../domains/knowledge/notes-agent/README.md); shipped timeline → [HISTORY.md](HISTORY.md). This completes the *input/quality* half of the owner's north-star; the *output* half (memory-driven orchestration) is a separate follow-on (item 3).
- **➡️ NEXT (front of queue): platform migration** — the ambient-capture / second-memory stage is complete, so the owner-scheduled Java 25 + Spring Boot 4 + Spring AI 2 migration is next. See below.

## Next (owner priority order, set 2026-07-04 — the backlog now lives in GitHub Issues)
1. **Verify memory-driven orchestration / conversation-state — [#290](https://github.com/fedoroff-vlad/ai-life/issues/290)** (owner item 3). **Next-session audit first:** how much of Stage-4 Track A / inter-agent is already built (the pending-confirmation primitive exists — AC-4 reused it), prune the TODO; then build the *routing* side (read memory → pick agents → one-shot). [stage4.md](stage4.md), [architecture.md](architecture.md) §Orchestrator routing.
2. **Docs cleanup: stale `stage4.md` reality-check table — [#298](https://github.com/fedoroff-vlad/ai-life/issues/298)** (D-3, small — the table marks conversation-state ❌ though it's built).
3. **⛰️ Platform migration: Java 25 + Spring Boot 4 + Spring AI 2 — [#288](https://github.com/fedoroff-vlad/ai-life/issues/288)** (owner item 1.7). Own stage: Java 25 isolated first slice, then Boot 4 + Spring AI 2. [migration-25-boot4.md](migration-25-boot4.md).
4. **Finance year-analysis report + chart-render — [#291](https://github.com/fedoroff-vlad/ai-life/issues/291) + [#292](https://github.com/fedoroff-vlad/ai-life/issues/292)** (owner item 4; monthly-report MVP shipped under closed #196).

## Backlog (all mirrored as Issues — not near-term)
Future agents: coach-agent #289 (spec-first), health #187, travel #190, email #191, smart-home #192.
Capabilities/follow-ups: mcp-image-gen real engine + stylist try-on #293, mcp-web video transcripts #294, per-person ICS filtering #295.
Tech-debt: Apache AGE upgrade #296 (gated), real-Ollama opt-in E2E #297. Older closed-out debt → [HISTORY.md](HISTORY.md).

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR. **Update this file at the end of each PR; move the finished bullet to [HISTORY.md](HISTORY.md) (add a terse timeline row + the detail), don't let `## Now` accumulate ✅ DONE items.**
