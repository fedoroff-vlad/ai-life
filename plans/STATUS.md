# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

## Now
- **➡️ Build/CI performance pass — [#288](https://github.com/fedoroff-vlad/ai-life/issues/288) follow-up (owner priority #1, next slice).** Now that Java 25 + Boot 4.0.7 + Spring AI 2.0.0 + Jackson 3 are the baseline, do the *measured* build-time scan + ranked speedups. Full `verify` is ~11–12 min on the new baseline; **measure first (don't guess)** where the time goes — Testcontainers container start/stop across ~12 modules almost certainly dominates, not compilation. Levers in priority order: **Testcontainers singleton/reuse** (one shared PG/Radicale/MinIO for schema-only modules — biggest expected win), **re-evaluate `-T` on `verify`** now that the Java-25 compact-object-headers heap win may make concurrent containers survivable on the small runner, **fast/slow test split** (surefire unit vs failsafe container ITs), Maven build cache. Deliverable = measured breakdown + ranked changes applied incrementally with `verify` staying green. Authority: [migration-25-boot4.md](migration-25-boot4.md) §Build/CI performance.

## Next (owner priority order — the backlog now lives in GitHub Issues)
1. **Finance year-analysis report + chart-render — [#291](https://github.com/fedoroff-vlad/ai-life/issues/291) + [#292](https://github.com/fedoroff-vlad/ai-life/issues/292)** (owner item 4; monthly-report MVP shipped under closed #196).

## Backlog (all mirrored as Issues — not near-term)
Future agents: coach-agent #289 (spec-first), health #187, travel #190, email #191, smart-home #192.
Capabilities/follow-ups: mcp-image-gen real engine + stylist try-on #293, mcp-web video transcripts #294, per-person ICS filtering #295.
Tech-debt: Apache AGE upgrade #296 (gated), real-Ollama opt-in E2E #297. Older closed-out debt → [HISTORY.md](HISTORY.md).

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR. **Update this file at the end of each PR; move the finished bullet to [HISTORY.md](HISTORY.md) (add a terse timeline row + the detail), don't let `## Now` accumulate ✅ DONE items.**
