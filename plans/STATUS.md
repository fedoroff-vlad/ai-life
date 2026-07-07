# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

## Now
- **➡️ coach-agent — [#289](https://github.com/fedoroff-vlad/ai-life/issues/289), SPEC APPROVED, CO-1 next.** Spec [coach.md](coach.md) signed off by the owner (2026-07-07, all decisions resolved): a self-understanding agent reading the second brain (journal/reflection/goal notes) + cross-domain `brief`s, applying evidence-based methods (CBT/ACT/MI/SFBT/IFS) → Reflect then Develop; **strictly private per-person** (subject = the authenticated sender, no cross-member read, sharing subject-initiated), each subject has their own `coach_profile` **vector**; a deliberate **intake** questionnaire seeds values/vector into `coach_intake`; owns a subject-scoped `coach.*` store (`mcp-coach`); chat-first (HTML seam laid); not therapy, no diagnosis, crisis→refer. **Next: CO-1** = `coach.*` schema (profile/value/observation/hypothesis/action/session/intake, `100-109` range) + `mcp-coach` domain-MCP (start a fresh session). *(Prior owner-priority queue items 1–4 all shipped — finance #291/#292 complete, see [HISTORY.md](HISTORY.md).)*

## Next (owner priority order — the backlog now lives in GitHub Issues)
1. **(Optional) fast/slow test split** — surefire unit vs failsafe container ITs, to speed the local inner loop; low value since full `verify` runs the same tests and iterating already uses `-Dtest=Class`. Pick up only if the dev loop hurts.

## Backlog (all mirrored as Issues — not near-term)
Future agents: coach-agent #289 (spec-first), health #187, travel #190, email #191, smart-home #192.
Capabilities/follow-ups: mcp-image-gen real engine + stylist try-on #293, mcp-web video transcripts #294, per-person ICS filtering #295.
Tech-debt: Apache AGE upgrade #296 (gated), real-Ollama opt-in E2E #297. Older closed-out debt → [HISTORY.md](HISTORY.md).

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR. **Update this file at the end of each PR; move the finished bullet to [HISTORY.md](HISTORY.md) (add a terse timeline row + the detail), don't let `## Now` accumulate ✅ DONE items.**
