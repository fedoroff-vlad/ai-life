# libs/agent-runtime

Shared scaffolding pulled in by every Spring Boot agent (`calendar-agent`,
`finance-agent`, …). Holds the AGENT.md / SKILL.md loaders + registry plus the
HTTP clients to `profile-service`, `notifier-service`, and `memory-service` —
everything that was previously duplicated byte-for-byte across agents.

Pure library: no `@Component` scan, no auto-config. Agents opt in with
`@Import(AgentRuntimeConfig.class)` on their main class because the runtime's
package sits outside their `@SpringBootApplication` scan root.

## Bean wiring (what `AgentRuntimeConfig` registers)
- `AgentManifest` — parsed `AGENT.md`. Throws at startup if the file is missing
  or malformed (surfacing config errors loudly is the point).
- `SkillRegistry` — scans `agent.skills-classpath` for `SKILL.md` files, indexes
  by trigger kind. Cross-checks against `manifest.skills()` and **aborts startup**
  if a declared skill failed to load (the silent-failure mode PR32 closed).
- `ProfileClient`, `NotifierClient`, `MemoryClient` — outbound HTTP clients
  consuming per-agent-named `WebClient` beans (`profileServiceWebClient`,
  `notifierWebClient`, `memoryServiceWebClient`) so each agent binds its own
  base URL.

## Configuration (`agent.*`)
| Property | Default | Purpose |
|---|---|---|
| `agent.manifest-classpath` | `AGENT.md` | Classpath path to the agent's manifest. |
| `agent.skills-classpath` | _empty_ | Spring resource pattern for `SKILL.md` files (e.g. `classpath*:skills/calendar/*/SKILL.md`). Empty = silent registry — set per agent to avoid cross-domain skill leakage. |
| `agent.memory-recall-k` | `5` | Top-k requested from memory-service when enriching the skill prompt. |

## Key classes
- `config/AgentRuntimeConfig` — `@Configuration` + `@EnableConfigurationProperties`. Single entry point agents `@Import`.
- `config/AgentRuntimeProperties` — `agent.*` binding.
- `manifest/ManifestParser` — splits YAML frontmatter + body, validates required fields, returns `AgentManifest` (contract lives in `libs/contracts`).
- `skill/SkillParser` — same shape for `SKILL.md`.
- `skill/Skill` — record (name, description, version, triggers, languages, body).
- `skill/SkillRegistry` — `byTrigger(kind) → Optional<Skill>`, fail-fast on declared-but-not-loaded skills.
- `http/ProfileClient` — `usersByHousehold`, `personById`.
- `http/NotifierClient` — `notify(userId, text)`.
- `http/MemoryClient` — `recall(query, scope, k)` with 500ms timeout + no-throw downgrade to empty list (memory downtime must not block the trigger path).

## Tests
`libs/agent-runtime/src/test/resources/test-skills/{good,bad}/SKILL.md` drive
the parse + fail-fast cases; see `AgentRuntimeConfigTest`.
