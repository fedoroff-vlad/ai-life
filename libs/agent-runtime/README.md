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
- `profileServiceWebClient`, `notifierWebClient`, `memoryServiceWebClient` —
  the three platform-service `WebClient`s, each `clone()`d off the shared
  auto-configured builder and bound to a base URL read through
  `SharedClientProperties` (the agent's `*AgentProperties` implements it). These
  used to be re-declared identically in every agent's `OutboundHttpConfig`; they
  live here now, so an agent declares only the URL *values* (its own
  `@ConfigurationProperties` prefix), not the wiring.
- `ProfileClient`, `NotifierClient`, `MemoryClient` — outbound HTTP clients
  consuming the three `WebClient` beans above.
- `OrchestratorInvokeClient` — the shared inter-agent hub client
  (`invoke(req[, timeout])` → `POST /v1/agents/invoke`); the locked path for one
  agent to reach another. Optional: only the agents that talk to the hub declare
  the `@Bean` (+ an `orchestratorWebClient`) in their own `OutboundHttpConfig`.
- `MediaStoreClient` — shared multipart upload to media-service
  (`upload(...)` → `POST /v1/media`) for agents that deliver a rendered HTML
  board/report. Optional: the deliverable agents declare the `@Bean`
  (`new MediaStoreClient(mediaServiceWebClient, "<source>")`) in their
  `OutboundHttpConfig`; the `source` tag (the owning agent) is constructor-set so
  the `upload` signature is identical across callers.
- `SkillInfoContributor` — exposes the loaded skill inventory under
  `/actuator/info` as `skills.{count, names, triggers}` so a deploy smoke check
  can verify the registry from outside the JVM (the quiet observability lane
  complementing PR32's loud-at-startup fail-fast). Appears only when the agent
  exposes the `info` endpoint — both agents do.
- `Coordinator` — the reusable **gather → synthesize** scaffold for agent-led
  multi-source flows. Needs the agent's `LlmClient` + the default `ObjectMapper`
  (every agent has both), so it wires for free on `@Import`.
- `BriefResponder` — the reusable **`brief` read-action** (#290, Slice B): answers a
  focused sub-question from the agent's persona + its second-brain recall in one
  **FAST** synthesis (the cheap leg; the coordinator's cross-domain synthesis is the
  DEFAULT call). Read-only by contract. An agent exposes it by registering
  `register("brief", briefResponder::answer)` on its `ActionController`; the coordinator
  gathers these live specialist answers through the hub.
- `DeliverablePublisher` — the shared **render → store → link** seam for the
  gather→synthesize agents that hand the user an HTML board/report. Wraps the
  agent's `DocRenderer` + `MediaStoreClient` + public-media base URL into one
  `publish(household, owner, Doc)` (render → upload → public link) plus the
  `mediaUrl(id)` URL builder and the `splitParagraphs`/`summary` text helpers all
  four deliverable agents copy-pasted. Optional: the deliverable agents declare the
  `@Bean` in their own `OutboundHttpConfig`, so base URLs stay per-agent and
  `publish` is signature-identical across callers. Default-theme agents use the
  convenience ctor `new DeliverablePublisher(mediaStoreClient, baseUrl)` (it builds
  the default `HtmlDocRenderer`, so they need no `RenderConfig`/`DocRenderer` bean);
  an agent that themes its boards (stylist) passes its own `DocRenderer` to the
  three-arg ctor instead.

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
- `config/SharedClientProperties` — interface (`getProfileServiceUrl/getNotifierUrl/getMemoryServiceUrl`) each agent's `*AgentProperties` implements, so `AgentRuntimeConfig` builds the three platform-service `WebClient` beans once instead of every agent re-declaring them.
- `manifest/ManifestParser` — splits YAML frontmatter + body, validates required fields, returns `AgentManifest` (contract lives in `libs/contracts`).
- `skill/SkillParser` — same shape for `SKILL.md`.
- `skill/Skill` — record (name, description, version, triggers, languages, body).
- `skill/SkillRegistry` — `byTrigger(kind) → Optional<Skill>`, fail-fast on declared-but-not-loaded skills.
- `http/ProfileClient` — `usersByHousehold`, `personById`.
- `http/NotifierClient` — `notify(userId, text)`.
- `http/MemoryClient` — `recall(query, scope, k)` with 500ms timeout + no-throw downgrade to empty list (memory downtime must not block the trigger path). Also `observe(householdId, userId, text, source)` — fire-and-forget drop at memory-service `/v1/observations` so an agent feeds durable facts it saw into memory-from-chat (MFC-c); off the response path, soft-fail, no-op on blank text. And `remember(householdId, userId, source, text, metadata) → Mono<Void>` — durably index a piece of text at `/v1/memories` (embed-and-store) so a later `recall` surfaces it; unlike `observe` it stores `text` verbatim under `source` with optional `{kind, refId}` metadata (the caller already holds the corpus). **The second-brain write seam (SB-5): `note(WriteNoteRequest) → Mono<NoteDto>`** — the note-tier analog of `remember`: instead of an opaque `memory.memories` row it captures a first-class **authored note** at `/v1/notes`, which memory-service auto-seeds into recall (SB-2) and graph-projects (`[[wiki-links]]`, SB-3) server-side. An agent that learns a durable, human-readable fact writes a note here (carry a `{kind, refId}` back-pointer in `frontmatter`) so it lands in the ONE store every agent reads; **`getNote(id) → Mono<NoteDto>`** is the read half — resolve a `{kind:note, refId}` recall hit back to the domain row via the note's frontmatter (docs-agent is the first consumer). Both soft-fail: 3s timeout, downgrade to empty, no-op on missing household / blank title. All of `recall`/`remember`/`note` share the composable soft-fail posture so enrichment never sinks the caller's primary write.
- `http/OrchestratorInvokeClient` — `invoke(req)` (5s) / `invoke(req, timeout)` → `POST /v1/agents/invoke`; the shared inter-agent hub client. Bean is opt-in per agent (declared in the agent's `OutboundHttpConfig` alongside an `orchestratorWebClient`).
- `http/MediaStoreClient` — `upload(householdId, ownerId, filename, mimeType, bytes)` → multipart `POST /v1/media` (15s); shared by the deliverable agents. Bean is opt-in per agent (`new MediaStoreClient(mediaServiceWebClient, "<source>")`); the `source` tag is constructor-set so `upload` is signature-identical across callers.
- `actuate/SkillInfoContributor` — `InfoContributor` that adds the `skills.*` detail to `/actuator/info`.
- `coordinate/Coordinator` — `coordinate(...)` gather→synthesize scaffold; `coordinate/CoordinationResult` is its `(text, gathered, llmModel)` outcome. Soft-fails per gather step.
- `brief/BriefResponder` — the reusable `brief` read-action: `answer(request)` / `answer(request, extraGather)` recall the agent's second brain for `args.question` (plus any domain gather the agent adds), run one FAST `Coordinator` synthesis under a read-only instruction, and return `AgentActionResult{agent, answer, llmModel?}`. Missing question → structured `ok=false`. Wired as a bean; an agent opts in with `register("brief", briefResponder::answer)`.
- `deliver/DeliverablePublisher` — `publish(household, owner, Doc)` render→store→link over the agent's `DocRenderer` + `MediaStoreClient`; `mediaUrl(UUID|String)` public-link builder (null-safe); static `splitParagraphs(text)` / `summary(text, fallback)`. Two ctors: three-arg (pass a themed `DocRenderer`) and the two-arg convenience (default `HtmlDocRenderer`, no `RenderConfig` needed). Bean is opt-in per agent (declared in the agent's `OutboundHttpConfig`).
- `web/AgentActionController` — abstract base for an agent's `POST /agents/<name>/actions/{action}` endpoint: `register(action, handler)` in the subclass ctor + `dispatch(action, request)` applies the shared envelope (unknown-action → structured `ok=false`; handler failure → `"<action> failed: <msg>"` logged with `requestedBy`). Subclasses stay `@RestController`s (the path literal carries the agent name) and only hold per-action business logic.

## Tests
`libs/agent-runtime/src/test/resources/test-skills/{good,bad}/SKILL.md` drive
the parse + fail-fast cases; see `AgentRuntimeConfigTest`.
`SkillInfoContributorTest` covers the `/actuator/info` skill-inventory shape
(sorted names/triggers, empty registry, dedup across skills, null-trigger
tolerance).
