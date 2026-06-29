# golden-test-support

**Status:** Active (Stage 5, introduced #199)

Shared scaffolding for the Stage 5 **golden tests** — opt-in tests that exercise a real surface
against a **real model** (local Ollama via a running llm-gateway), asserting *structure, not text*
(roadmap §Risks). Consumed **test-scope** by the agent modules + orchestrator.

## Purpose

Golden tests duplicate ~40 lines of identical plumbing — the opt-in gate, the gateway `LlmClient`, the
`AGENT.md`/`SKILL.md` classpath loaders, a `NormalizedMessage` builder. This module owns that plumbing
so each test carries only what's unique to it. The **per-test fixtures and assertions stay in each
test** — they validate that surface's own contract (a routing token vs strict JSON vs a grounded
free-text answer), and are deliberately *not* shared.

- **`@GoldenLlmTest`** — class annotation composing `@Tag("golden")` + `@EnabledIfEnvironmentVariable`
  on `GOLDEN_LLM`. A normal `mvn test` (CI default, `GOLDEN_LLM` unset) **skips** the test.
- **`GoldenLlm`** — static helpers: `client()` (an `LlmClient` on `gatewayUrl()`, from
  `GOLDEN_LLM_GATEWAY_URL`, default `:8081`), `message(...)` (a `NormalizedMessage` builder),
  `agentBody(cl)` (load `AGENT.md`, strip frontmatter), `skill(cl, path)` (parse a `SKILL.md`).

## Usage

```java
@GoldenLlmTest
class GoldenMyFlowTest {
    private final LlmClient llm = GoldenLlm.client();
    private final AgentManifest manifest = new AgentManifest("x", ..., 
            GoldenLlm.agentBody(GoldenMyFlowTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenMyFlowTest.class.getClassLoader(), "skills/x/<name>/SKILL.md")));
    // ... mock the flow's collaborators, drive a fixture in, assert the surface's contract ...
    //     var r = flow.run(GoldenLlm.message("...")).block(...);
}
```

Run them against local Ollama — see `platform/llm-gateway/README.md` §Golden tests for the full runbook.

## Consumers (test-scope)

- `platform/orchestrator` — `routing.GoldenRoutingTest`
- `domains/finance/finance-agent` — `intent.GoldenRoutingTest`, `advisor.GoldenAdvisorSynthesisTest`
- `domains/tasks/tasks-agent` — `intent.GoldenInboxClarifyTest`
- `domains/researcher/researcher-agent` — `flow.GoldenResearchSynthesisTest`
- `domains/nutrition/nutritionist-agent` — `foodlog.GoldenMealLogTest`
