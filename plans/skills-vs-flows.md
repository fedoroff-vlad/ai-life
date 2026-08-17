# Skills vs flows — the in-agent refactor track

> **Status (2026-07-30): Bucket 1 ✅ COMPLETE (#363/#365 + slice 3); Bucket 2 pilot — validate-only half ✅
> (#360, `finance FinancialAdvisor`); the model-gated production cutover stays deferred (Mac).** Both routing
> agents (finance, tasks) now share one `agent-runtime` `SkillClassifier`.
> Model- and hardware-independent. Sparked by wrapping the WORK agent with skills (opencode + `SKILL.md`, in
> the `coding-agent` repo) → "how much of that pattern applies to ai-life?"

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

Slices (per the ≤5-file rule) — **all shipped 2026-07-29**:
1. ✅ (#363) Extract `SkillClassifier` (prompt build + JSON parse + lenient fallback + `ToolSpec`/`Choice`
   inputs, sealed `ToolCall|FlowCall|Chat` output) into `agent-runtime`; unit-tested; README updated.
2. ✅ (#365) Migrate `finance-agent` — its extra flow branches became a **pluggable flow-map** the agent
   supplies (`advice`/`report`/`invest`/`category` → `BiFunction<msg,node,Mono<RouterResult>>`), not
   router-baked. `buildPrompt` gained an `extraRules` overload so finance's #199 enum-pinning keeps its exact
   pre-migration slot → routing prompt byte-identical, `GoldenRoutingTest` green.
3. ✅ Migrate `tasks-agent` — intent skills collapse into one `skill` `Choice` (one action, many skills; the
   LLM names the skill in `FlowCall.node.name`). Byte-identical prompt (no `extraRules` — no enum-pinning).
   `IntentRouterTest` + `GoldenInboxClarifyTest` green.

**Slice 4+ — the cue-routed agents ([#475](https://github.com/fedoroff-vlad/ai-life/issues/475), epic
[#479](https://github.com/fedoroff-vlad/ai-life/issues/479), in progress).** The earlier claim that "other
agents use direct invocation — no router" was wrong: **8 agents** (briefing / creator / docs / notes / chef
/ nutritionist / stylist / travel) carried a hardcoded `*_CUES` keyword heuristic in their `IntentController`
— a second, dumber routing hop that misses paraphrases. They migrate onto the same `SkillClassifier`, one PR
each, deleting the cue sets. **Done: notes-agent** (`NotesIntentRouter`, the first *skills-only* consumer —
which surfaced the `SkillClassifier` skills-only prompt fix, #481) **and creator-agent**
(`CreatorIntentRouter`; established that a hub-invoked skill with empty `triggers` — `greeting-drafter` — is
excluded from the route set by advertising only the explicitly-routable skills). Genuinely single-skill
agents (`researcher` / `calendar` / `coach` / `coordinator`) stay direct-invoke by the guardrail.
**Remaining 6:** briefing / docs / chef / nutritionist / stylist / travel. Once the router shape has settled
across these, lift the near-identical per-agent `*IntentRouter` into a shared `agent-runtime` component
(second-consumer-lifts rule — deferred until the shape is stable to avoid a premature abstraction).

### Bucket 2 — flow-class → executable `SKILL.md` recipe (MODEL-GATED)
Evolve **advisory/synthesis** flows (`FinancialAdvisor`, coach `Reflector`) from a Java class into a
`SKILL.md` recipe the model executes over the `Coordinator` + generic tools. **Gate:** validate the recipe
against the WORK LLM golden ([model-strategy.md](model-strategy.md) — the dev-time validator) before
committing; a stronger MoE default on the Mac makes it viable at runtime. **Keep DETERMINISTIC flows in
Java** (`MonthlyReporter` / `YearReporter` — reproducibility + cheapness matter more than model-driven
flexibility there).

**Pilot — validate-only half ✅ (#360, 2026-07-30, `finance FinancialAdvisor`).** The recipe form is
authored as `finance-agent/src/test/resources/recipes/financial-advisor.recipe.md` (a **test fixture** —
NOT loaded in production; the Java `FinancialAdvisor` still runs the live flow) and validated on a real
model. The only thing the recipe adds over the existing synthesis flow is that the **model plans the
gather itself** (which `spending_by_category` windows to fetch) instead of the plan being hard-coded in
Java — so the new `advisor.GoldenAdvisorRecipeTest` asserts exactly that (parseable `gather` plan, grounded
in the one tool the recipe exposes, ≥2 trend windows incl. a recent one), while the existing
`GoldenAdvisorSynthesisTest` still covers synthesis. Together the two goldens prove the recipe is correct
end-to-end. Passed on local qwen3:8b (56s); runnable against the `openai:`-tier work gateway too (#359).
**Not yet done → the production cutover** ([#369](https://github.com/fedoroff-vlad/ai-life/issues/369),
`## Next` in STATUS): replace the Java flow with a runtime that loads the recipe and executes the
model-planned gather — that's the model-gated part, kept for the Mac. (#358 + #360 are closed; #369 is the
only open thread on this track.)

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
