# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

## Now
- **➡️ Build/CI performance pass — [#288](https://github.com/fedoroff-vlad/ai-life/issues/288) follow-up (owner priority #1).** **Scan DONE + measured (2026-07-06)** — full breakdown + ranked plan in [migration-25-boot4.md](migration-25-boot4.md) §Build/CI performance. Headline: serial `verify` = **11:13**; the cost is **flat across 51 modules** (per-module JVM+Spring-context startup, run serially), **not** Testcontainers churn (reuse already amortises that). `-T4` cut it to **5:24 (≈2×), all 1192 tests green — but only with reuse OFF** (reuse+parallel share one PG and corrupt each other; measured). Remaining slices, incremental with `verify` green:
  - **(a) build hygiene (do first, zero-risk):** dedupe duplicate `spring-boot-webtestclient` in 14 poms (14 warnings). *(The `testcontainers.version` pin is NOT dead — Boot 4 doesn't manage the TC modules; removing it broke the build. A possible core-vs-module version skew is a separate slice — see [migration-25-boot4.md](migration-25-boot4.md) §Build/CI performance.)*
  - **(b) `-T` parallelism:** document `mvn -T4 verify` (reuse off) for the local loop = uncontroversial; flipping CI serial→`-T2` + removing the reuse flag reverses CLAUDE.md's "serial on purpose" policy → **owner decision** (also runner-sizing).
  - **(c) fast/slow test split** (surefire unit vs failsafe IT) — helps the dev loop; optional.

## Next (owner priority order — the backlog now lives in GitHub Issues)
1. **Finance year-analysis report + chart-render — [#291](https://github.com/fedoroff-vlad/ai-life/issues/291) + [#292](https://github.com/fedoroff-vlad/ai-life/issues/292)** (owner item 4; monthly-report MVP shipped under closed #196).

## Backlog (all mirrored as Issues — not near-term)
Future agents: coach-agent #289 (spec-first), health #187, travel #190, email #191, smart-home #192.
Capabilities/follow-ups: mcp-image-gen real engine + stylist try-on #293, mcp-web video transcripts #294, per-person ICS filtering #295.
Tech-debt: Apache AGE upgrade #296 (gated), real-Ollama opt-in E2E #297. Older closed-out debt → [HISTORY.md](HISTORY.md).

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR. **Update this file at the end of each PR; move the finished bullet to [HISTORY.md](HISTORY.md) (add a terse timeline row + the detail), don't let `## Now` accumulate ✅ DONE items.**
