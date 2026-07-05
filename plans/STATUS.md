# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

## Now
- **✅ Platform migration — Java 25 + Spring Boot 4.0.7 + Spring AI 2.0.0 + Jackson 3 — COMPLETE (this PR, [#288](https://github.com/fedoroff-vlad/ai-life/issues/288), owner item 1.7).** Landed as **one coordinated bump** (Java 25 can't be isolated — Boot 3.4's `repackage` can't read Java 25 bytecode). Full local `mvn verify` green: **51/51 modules, ~1192 tests, all Testcontainers ITs**. All breakage was mechanical (Boot-4 test-slice/client relocations declared **per-module**; `WebClient.Builder`→`spring-boot-webclient`; `@MockBean`→`@MockitoBean`; Jackson `com.fasterxml.jackson.databind`→`tools.jackson.databind` across 259 files + a Jackson-3 Hibernate `Jackson3JsonFormatMapper` in platform-common). Full change inventory → **[migration-25-boot4.md](migration-25-boot4.md) §Status: DONE**; timeline → [HISTORY.md](HISTORY.md). Prior stages (ambient-capture AC-1..5, memory-driven orchestration #290) are done — see [HISTORY.md](HISTORY.md).
- **➡️ Immediate follow-up = build/CI performance pass** (deferred within #288): full `verify` is ~11–12 min on the new Java-25 baseline. Measure where the time goes (Testcontainers dominate) → ranked speedups (container reuse/singleton, re-evaluate `-T` on the Java-25 heap, fast/slow split). [migration-25-boot4.md](migration-25-boot4.md) §Build/CI performance.

## Next (owner priority order — the backlog now lives in GitHub Issues)
1. **Build/CI performance pass — [#288](https://github.com/fedoroff-vlad/ai-life/issues/288) follow-up** (see `## Now`): now that Java 25 + Boot 4 are the baseline, do the measured build-time scan + ranked speedups. [migration-25-boot4.md](migration-25-boot4.md) §Build/CI performance.
2. **Finance year-analysis report + chart-render — [#291](https://github.com/fedoroff-vlad/ai-life/issues/291) + [#292](https://github.com/fedoroff-vlad/ai-life/issues/292)** (owner item 4; monthly-report MVP shipped under closed #196).

## Backlog (all mirrored as Issues — not near-term)
Future agents: coach-agent #289 (spec-first), health #187, travel #190, email #191, smart-home #192.
Capabilities/follow-ups: mcp-image-gen real engine + stylist try-on #293, mcp-web video transcripts #294, per-person ICS filtering #295.
Tech-debt: Apache AGE upgrade #296 (gated), real-Ollama opt-in E2E #297. Older closed-out debt → [HISTORY.md](HISTORY.md).

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR. **Update this file at the end of each PR; move the finished bullet to [HISTORY.md](HISTORY.md) (add a terse timeline row + the detail), don't let `## Now` accumulate ✅ DONE items.**
