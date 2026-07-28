# Skills vs flows — the in-agent refactor track

> **Status: track defined (2026-07-28), analysis done, not yet sliced into PRs.** Model- and
> hardware-independent — Bucket 1 is feasible now. Sparked by wrapping the WORK agent with skills (opencode
> + `SKILL.md`, in the `coding-agent` repo) → "how much of that pattern applies to ai-life?"

## The insight
ai-life's **routing layer is already data-driven** — the orchestrator's LLM classifier reads agent
manifests, so adding an agent needs no code ([architecture.md](architecture.md) §Orchestrator routing). The
work-agent's "skills-around-an-agent" pattern (the model executes a `SKILL.md` recipe over generic tools,
config-as-data) therefore has nothing to add *there*. Where it applies is **below** routing, **inside** each
agent, where multi-step flows are still hand-coded Java.

## Current state (verified 2026-07-28)
- **Per-agent `IntentRouter` duplication.** finance and tasks run near-identical routers — the `tasks`
  `IntentRouter` is literally commented "Mirrors finance-agent's IntentRouter (PR35)". Each re-implements the
  classifier prompt + strict-JSON parse + lenient fallback + skill/tool selection.
- **Flow-classes partly duplicate the shared `Coordinator`.** The gather→synthesize `Coordinator` already
  lives in `libs/agent-runtime`, yet advisory/synthesis flows are bespoke Java classes — `FinancialAdvisor`,
  `InvestmentAdvisor`, `MonthlyReporter`, `YearReporter`, `CategoryManager`, coach `Reflector`.
- **finance still hard-branches** action types in Java (`advice`/`report`/`invest`/`category`), though the
  trigger *phrasing* is already SKILL.md-sourced (PR#339 — the 5 finance skill descriptions are the SSOT).
- **`SKILL.md` is an Anthropic-compatible superset** ([architecture.md](architecture.md) §Conventions) → the
  same format the work-agent skills use; portable by construction.

## Target — two buckets

### Bucket 1 — lift the shared classifier into `libs/agent-runtime` (NO-REGRET, do first)
One classifier component in `agent-runtime`, driven purely by `SkillRegistry` descriptions + the agent's
tool definitions. Removes the finance/tasks duplication and makes SKILL.md descriptions the **true SSOT** for
in-agent routing (finishes the direction PR#339 started). **Model-independent** — the classifier is one small
decision the local model already makes.

Slices (per the ≤5-file rule):
1. Extract `SkillClassifier` (prompt build + JSON parse + lenient fallback + skill/tool result) into
   `agent-runtime`; unit-test it there; update the agent-runtime README.
2. Migrate `finance-agent` onto it — its extra flow branches become a small **pluggable flow-map** the agent
   supplies, not router-baked. Keep `GoldenRoutingTest` green.
3. Migrate `tasks-agent` onto it. Keep `IntentRouterTest` / `GoldenInboxClarifyTest` green.
   *(Other agents use direct invocation — no router — so nothing to migrate there.)*

### Bucket 2 — flow-class → executable `SKILL.md` recipe (MODEL-GATED)
Evolve **advisory/synthesis** flows (`FinancialAdvisor`, coach `Reflector`) from a Java class into a
`SKILL.md` recipe the model executes over the `Coordinator` + generic tools. **Gate:** validate the recipe
against the WORK LLM golden ([model-strategy.md](model-strategy.md) — the dev-time validator) before
committing; a stronger MoE default on the Mac makes it viable at runtime. **Keep DETERMINISTIC flows in
Java** (`MonthlyReporter` / `YearReporter` — reproducibility + cheapness matter more than model-driven
flexibility there).

## Guardrails
- **Value = simplicity + a simpler lifecycle layer** (fewer flow-JVMs / classes / tests, less drift) — **not**
  a model upgrade and **not** a JVM-RAM win (the hot set is only ~6 GB — see [lifecycle.md](lifecycle.md)).
- **Do not merge agents into one process.** Monorepo ≠ monolith; the hot/cold-per-container deploy depends on
  separate services. The simplification is *within* an agent (flow-class → skill) and *shared* (the router
  lift), never service-merging.

## Feasible now vs gated
- **Now (model/hardware-independent):** Bucket 1 (all slices); Bucket 2's *design* + a *work-LLM-validated
  pilot* (one flow); the `openai:`-tier golden profile that enables that validation.
- **Gated on the Mac / a stronger local model:** Bucket 2's *production* runtime — ripping the Java flow out
  once the recipe is validated and the MoE default is live.

Related: [model-strategy.md](model-strategy.md), [lifecycle.md](lifecycle.md),
[architecture.md](architecture.md) §Orchestrator routing / §LLM strategy.
