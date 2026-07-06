# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

## Now
- **➡️ Finance year-analysis report — [#291](https://github.com/fedoroff-vlad/ai-life/issues/291) (owner priority, in flight — sliced).** Owner item 4. Shipped so far: `chart-render` capability ([#292](https://github.com/fedoroff-vlad/ai-life/issues/292), ✅ DONE) and **PR1 — chart binding + monthly-report chart embed** (✅ DONE, see [HISTORY.md](HISTORY.md)): `finance-agent` binds `mcp-chart-render` (`ChartRenderClient` → `/internal/render`), `MonthlyReporter` leads its board with a spending bar chart via the new `doc-render` `Doc.charts` element. **Remaining slices:** PR2 — **year-analysis report** (`YearReporter` + skill + `report`-action period detection + a monthly-trend line chart over the year); PR3 — **chat-driven category grouping** (`upsert_category` skill). Pick up [finance.md](finance.md) + `finance-agent`/`mcp-finance`/`mcp-chart-render` READMEs when continuing.

## Next (owner priority order — the backlog now lives in GitHub Issues)
1. **(Optional) fast/slow test split** — surefire unit vs failsafe container ITs, to speed the local inner loop; low value since full `verify` runs the same tests and iterating already uses `-Dtest=Class`. Pick up only if the dev loop hurts.

## Backlog (all mirrored as Issues — not near-term)
Future agents: coach-agent #289 (spec-first), health #187, travel #190, email #191, smart-home #192.
Capabilities/follow-ups: mcp-image-gen real engine + stylist try-on #293, mcp-web video transcripts #294, per-person ICS filtering #295.
Tech-debt: Apache AGE upgrade #296 (gated), real-Ollama opt-in E2E #297. Older closed-out debt → [HISTORY.md](HISTORY.md).

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR. **Update this file at the end of each PR; move the finished bullet to [HISTORY.md](HISTORY.md) (add a terse timeline row + the detail), don't let `## Now` accumulate ✅ DONE items.**
