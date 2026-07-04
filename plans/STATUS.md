# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

**Scope:** only what is *in flight or the next slice/stage*. Shipped work → **[HISTORY.md](HISTORY.md)**
(archive, out of the reading order). Authoritative detail for anything done lives in the **domain plan
file** ([INDEX.md](INDEX.md)) + the **module README** — go to the source for specifics; STATUS stays lean.

## Now
- **✅ ambient / intuitive capture — working cut (AC-1..AC-4) SHIPPED (2026-07-03…07-04).** The second brain now fills itself without the "запомни" keyword: `CaptureService` classifies every message (explicit fixation → auto-save `source=user`; important inferred → **approve-first** via proactive push + notes-agent `/resume` → `source=ambient`; trivial → ignore), dedups near-duplicates on write, and attributes to self/person. All flag-gated (`memory.ambient-capture.enabled`, off by default). Detail → **[ambient-capture.md](ambient-capture.md)** + [memory-service README](../platform/memory-service/README.md) + [notes-agent README](../domains/knowledge/notes-agent/README.md); shipped timeline → [HISTORY.md](HISTORY.md). **Only AC-5 (merge/supersede) remains, deferred.** This completes the *input/quality* half of the owner's north-star; the *output* half (memory-driven orchestration) is a separate follow-on (item 3).
- **➡️ NEXT (front of queue): platform migration** — the ambient-capture / second-memory stage is complete, so the owner-scheduled Java 25 + Spring Boot 4 + Spring AI 2 migration is next. See below.

## Next (queue — detail deferred until picked)
- **⛰️ Platform migration: Java 25 + Spring Boot 4 + Spring AI 2** — owner-scheduled (2026-07-03) **after the ambient-capture / second-memory stage completes** (now done), as its own dedicated stage (Java 25 as an isolated first slice, then Boot 4 + Spring AI 2). Scope + rationale + gating: [migration-25-boot4.md](migration-25-boot4.md).
- **AC-5 (ambient capture, deferred)** — merge/update/supersede a near-duplicate (new detail enriches, contradiction supersedes; an LLM decides). Own slice, not in the first working cut. See [ambient-capture.md](ambient-capture.md).
- **health-agent (#187)** — future-agent build order (briefing → docs → **health** → family-memory); **deferred, owner redesigning the scope.**
- **coach-agent** — deferred, spec-first, after ambient capture lands.
- **conversation-state / inter-agent (owner item 3)** — the memory-driven-orchestration *output* side (read memory → pick agents → one-shot). NB: the pending-confirmation primitive itself already exists (conversation-service + route-lock + `/resume`) — AC-4 reused it; item 3 is the *routing* side that reads memory to pick agents. See [stage4.md](stage4.md), [architecture.md](architecture.md) §Orchestrator routing.
- **finance report (owner item 4)** — deferred.

## Deferred work
- **Real-Ollama opt-in E2E variant** — drive PR20's `E2EStage1ClosingFlowTest` flow against a Testcontainers Ollama instance behind a JUnit `@Tag("slow")` so CI doesn't pay the model-pull cost. Worth doing once we see a regression class the mock-LLM closer misses (timeouts / retries / JSON-shape drift). Until then the existing per-seam tests + the wire-contract closer keep the build honest.
- **Apache AGE graph upgrade** — still deferred; gated on the promotion criteria in [platform/memory-service/README.md](../platform/memory-service/README.md) (2+ hop traversal / graph algorithms / ~100k rows).
- Older closed-out debt (receipt-parser, task-to-event, media-understanding capability, etc.) → [HISTORY.md](HISTORY.md).

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR. **Update this file at the end of each PR; move the finished bullet to [HISTORY.md](HISTORY.md) (add a terse timeline row + the detail), don't let `## Now` accumulate ✅ DONE items.**
