# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

## Now
- **➡️ coach-agent — [#289](https://github.com/fedoroff-vlad/ai-life/issues/289), CO-1+CO-2 shipped, CO-3 next.** Spec [coach.md](coach.md) (owner-signed 2026-07-07): a self-understanding agent on the second brain, evidence-based methods (CBT/ACT/MI/SFBT/IFS), **strictly private per-person** (subject = the authenticated sender). CO-1 (mcp-coach store) + CO-2 (`coach-agent` 8122: safety gate → Reflect → persist; E2E closer + golden) both DONE 2026-07-07 — detail in [coach.md](coach.md) + the module READMEs. **Next: CO-3** = `intake` (light one-question-per-turn questionnaire on conversation-state → `coach_intake`, seeds `coach_value` + `coach_profile`); then CO-4 briefs-gather, CO-5 develop.

## Next (owner priority order — the backlog now lives in GitHub Issues)
1. **(Optional) fast/slow test split** — surefire unit vs failsafe container ITs, to speed the local inner loop; low value since full `verify` runs the same tests and iterating already uses `-Dtest=Class`. Pick up only if the dev loop hurts.

## Backlog (all mirrored as Issues — not near-term)
Future agents: coach-agent #289 (spec-first), health #187, travel #190, email #191, smart-home #192.
Capabilities/follow-ups: mcp-image-gen real engine + stylist try-on #293, mcp-web video transcripts #294, per-person ICS filtering #295.
Tech-debt: **JDK 21→25 in all 43 Dockerfiles + stale docs #323** (leftover from #288 — build/CI are on 25 but images still pin `eclipse-temurin-21`, so `docker compose up` is broken; also fix PATTERNS recipes + `docs/REFERENCE.md`), Apache AGE upgrade #296 (gated), real-Ollama opt-in E2E #297. Older closed-out debt → [HISTORY.md](HISTORY.md).

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR. **Update this file at the end of each PR; move the finished bullet to [HISTORY.md](HISTORY.md) (add a terse timeline row + the detail), don't let `## Now` accumulate ✅ DONE items.**
