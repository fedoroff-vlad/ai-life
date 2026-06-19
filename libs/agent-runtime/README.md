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
- `SkillInfoContributor` — exposes the loaded skill inventory under
  `/actuator/info` as `skills.{count, names, triggers}` so a deploy smoke check
  can verify the registry from outside the JVM (the quiet observability lane
  complementing PR32's loud-at-startup fail-fast). Appears only when the agent
  exposes the `info` endpoint — both agents do.
- `Coordinator` — the reusable **gather → synthesize** scaffold for agent-led
  multi-source flows. Needs the agent's `LlmClient` + the default `ObjectMapper`
  (every agent has both), so it wires for free on `@Import`.

## Coordinator (the agent-led multi-source pattern)
`coordinate(systemPrompts, payload, gather, channel)` runs a `Map<String, Mono<JsonNode>>`
of named **gather steps** in parallel (a memory recall, an inter-agent
`/v1/agents/invoke`, a tool call), folds the successful ones into a `context` object,
then asks the LLM to synthesize one answer from `[systemPrompts…] + {payload, context}`.
Returns `CoordinationResult(text, gathered, llmModel)`. **Per-step soft-fail**: a step that
errors or returns empty is logged and omitted — one broken source never sinks the
synthesis (generalises the by-hand memory-recall enrichment in `TriggerController`).
Coordination is **agent-led** (architecture.md routing doctrine): the agent owns the flow
and reaches specialists via the hub; the orchestrator stays a thin router.

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
- `http/MemoryClient` — `recall(query, scope, k)` with 500ms timeout + no-throw downgrade to empty list (memory downtime must not block the trigger path). Also `observe(householdId, userId, text, source)` — fire-and-forget drop at memory-service `/v1/observations` so an agent feeds durable facts it saw into memory-from-chat (MFC-c); off the response path, soft-fail, no-op on blank text.
- `actuate/SkillInfoContributor` — `InfoContributor` that adds the `skills.*` detail to `/actuator/info`.
- `coordinate/Coordinator` — `coordinate(...)` gather→synthesize scaffold; `coordinate/CoordinationResult` is its `(text, gathered, llmModel)` outcome. Soft-fails per gather step.

## Tests
`libs/agent-runtime/src/test/resources/test-skills/{good,bad}/SKILL.md` drive
the parse + fail-fast cases; see `AgentRuntimeConfigTest`.
`SkillInfoContributorTest` covers the `/actuator/info` skill-inventory shape
(sorted names/triggers, empty registry, dedup across skills, null-trigger
tolerance).
