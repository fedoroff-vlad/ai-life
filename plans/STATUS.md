# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

## Now
- **🚧 IN PROGRESS — ambient / intuitive capture (owner-picked after resurfacing, 2026-07-03).** Make the second brain fill itself *without* the "запомни" keyword: from ordinary conversation the system decides **what** to keep and **about whom**, dedups, records. Decision logic: **explicit fixation → auto-save** (`source=user`); **important inferred → approve first** (`source=ambient`); **trivial → ignore**. Approach = evolve memory-from-chat (`CaptureService`) with a third output (curated notes), leaving `FactExtractor`/`RelationExtractor` untouched. Phased plan: AC-1 decision-engine → AC-2 auto-write explicit + attribution → AC-3 dedup → AC-4 approval-of-inferred [ties to conversation-state] → AC-5 merge/supersede. It's the *input/quality* half of the owner's north-star (quality storage → memory-driven reasoning); the *output* half (memory-driven orchestration: read memory → pick agents → one-shot) is a separate follow-on (item 3). Authority: **[ambient-capture.md](ambient-capture.md)**. coach-agent deferred until this lands.
  - **✅ AC-1 (decision engine) DONE (PR#274).** `platform/memory-service/.../capture/NoteWorthinessExtractor` (mirrors `FactExtractor`: LlmClient DEFAULT channel, lenient JSON, best-effort → empty on junk) emits 0..N `NoteCandidate`s `{title, type, body, subject, importance, explicitFixation}`; `NoteCandidate.outcome()` derives the three-way `CaptureOutcome` (`EXPLICIT_FIXATION`/`IMPORTANT_INFERRED`/`TRIVIAL`). Extract + classify only, no writes yet. `NoteWorthinessExtractorTest` (11 cases, green) + opt-in `GoldenNoteWorthinessTest`; `golden-test-support` added to memory-service test scope.
  - **➡️ NEXT: AC-2** — write explicit-fixation candidates via `NoteService.create` + attribution (`subject=="self"` → owner-scoped; a name → `ProfileClient.resolvePersonId` sets `personId` + appends `[[<name>]]`; unresolved name stays a dangling link, never auto-create a person). Gated by `memory.ambient-capture.enabled`.

## Next (queue — detail deferred until picked)
- **AC-3 → AC-5** — dedup on write (~0.15 cosine) → approval of inferred facts (needs conversation-state) → merge/supersede. See [ambient-capture.md](ambient-capture.md).
- **health-agent (#187)** — future-agent build order (briefing → docs → **health** → family-memory); **deferred, owner redesigning the scope.**
- **coach-agent** — deferred, spec-first, after ambient capture lands.
- **conversation-state / inter-agent (owner item 3)** — the memory-driven-orchestration *output* side (read memory → pick agents → one-shot); AC-4 depends on its pending-confirmation primitive. See [stage4.md](stage4.md), [architecture.md](architecture.md) §Orchestrator routing.
- **finance report (owner item 4)** — deferred.

## Deferred work
- **Real-Ollama opt-in E2E variant** — drive PR20's `E2EStage1ClosingFlowTest` flow against a Testcontainers Ollama instance behind a JUnit `@Tag("slow")` so CI doesn't pay the model-pull cost. Worth doing once we see a regression class the mock-LLM closer misses (timeouts / retries / JSON-shape drift). Until then the existing per-seam tests + the wire-contract closer keep the build honest.
- **Apache AGE graph upgrade** — still deferred; gated on the promotion criteria in [platform/memory-service/README.md](../platform/memory-service/README.md) (2+ hop traversal / graph algorithms / ~100k rows).
- Older closed-out debt (receipt-parser, task-to-event, media-understanding capability, etc.) → [HISTORY.md](HISTORY.md).

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR. **Update this file at the end of each PR; move the finished bullet to [HISTORY.md](HISTORY.md) (add a terse timeline row + the detail), don't let `## Now` accumulate ✅ DONE items.**
