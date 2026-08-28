# shared/skills — cross-cutting skills (empty by design)

**Status: mechanism available, zero tenants.** No cross-cutting skill has shipped.

`architecture.md` §"Where each kind of thing lives" lists `shared/skills/<name>/` as the home for a
**prompt/behaviour reused by several agents**. The mechanism exists but is currently **unused** — the
same status as the untranslated `SKILL.ru.md` sibling (documented as "zero shipped").

Why it's empty (not an oversight): in this codebase cross-cutting *behaviour* has consistently been
lifted into `libs/agent-runtime` as **Java**, not into a shared `SKILL.md`:

- shared skill routing → `SkillClassifier` / `intent/SkillRouter` (#475)
- shared confirm→act loop → `PickConfirmActRunner` ([ADR-0004](../../plans/adr/ADR-0004-confirm-act-flow.md))
- shared per-member personalization → `PersonalizationProfiler` ([ADR-0005](../../plans/adr/ADR-0005-personalization-profile-capability.md))

Cross-cutting *prompts* would live here, but none have been needed yet. Each agent's
`agent.skills-classpath` defaults to a **narrow domain glob** (`classpath*:skills/<domain>/*/SKILL.md`,
the anti-cross-leak default), so **no agent currently loads this folder at all**.

To add the first tenant: drop `skills/shared/<name>/SKILL.md` here and extend the consuming agent(s)'
`agent.skills-classpath` to also match `classpath*:skills/shared/*/SKILL.md`. Then point the doctrine
row in `architecture.md` at the real skill instead of this note.
