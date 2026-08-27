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
- `SkillClassifier` — the shared **in-agent routing** classifier (skills-vs-flows
  Bucket 1, #358): the *prompt-build* + *strict-JSON-parse* halves the per-agent
  `IntentRouter`s used to duplicate byte-for-byte (finance + tasks; the tasks copy
  was literally commented "Mirrors finance-agent's IntentRouter"). Pure — needs only
  the default `ObjectMapper`, so it wires for free on `@Import`; the agent keeps its
  own LLM round-trip and tool/flow dispatch around it. See the section below.
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

## SkillClassifier (the shared in-agent router)
Turns a user message into one **decision** — invoke an MCP tool, run one of the agent's own
multi-step flows/skills, or reply in chat. It owns the two halves the finance and tasks
`IntentRouter`s duplicated; the agent supplies the parts that are genuinely its own.

- `buildPrompt(intro, tools, choices, decidePrompt[, extraRules])` — assembles the classifier system
  prompt: the agent's `intro`, the tool list (name + description + inputSchema), each `Choice`'s prompt
  block, the `decidePrompt` question, then the strict-JSON contract (the `tool` shape, each choice's
  shape, the `chat` shape), any agent-specific `extraRules`, and the missing-argument rule. The shapes
  advertised here are exactly the ones `parse` accepts — they evolve together. **A skills-only agent
  (empty `tools`)** gets no tool section, no `tool` shape, and no tool-specific missing-argument rule —
  advertising a tool it can't dispatch only pushed small models to emit an undispatchable
  `{"action":"tool"}` (notes-agent, the first skills-only consumer, mis-routed to chat until this was
  gated; #475). Agents with tools get the identical prompt as before. `extraRules` is the slot
  for domain constraints the shared scaffold can't own: each string is appended **verbatim** (caller
  owns its trailing newline) **after** the shape list and **before** the shared missing-argument rule
  — the exact position finance's enum-pinning rule (stop a 7B inventing action values) occupied before
  the router lifted onto this classifier. The no-`extraRules` overload passes an empty list.
- `parse(raw, tools, choices) → Decision` — strict-JSON only (a body not starting with `{`, or
  unparseable, or lacking `"action"`, is a lenient **`Chat(raw)`** fallback so a chatty model never
  breaks routing). Recognises `action:tool` and the **flattened** `{"action":"<toolName>",…}` shape
  smaller models emit (→ `ToolCall(name, argsJson)`, `args` defaulting to `"{}"`), any registered
  `Choice` (→ `FlowCall(action, node)` carrying the whole node so the agent reads its own fields —
  `period`, `symbols`, an intent-skill `name`), else `Chat` (preferring the `text` field).

Inputs are agent-owned value types so the classifier stays domain-agnostic and pulls no `spring-ai`
into this library: `ToolSpec(name, description, inputSchema)` — the agent maps each
`ToolDefinition` to one at the call site — and `Choice(action, promptBlock, shapeExample)`, whose
`promptBlock` is sourced from the flow's SKILL.md `description` (keeping SKILL.md the routing SSOT,
finishing PR#339). Output `Decision` is a sealed `ToolCall | FlowCall | Chat`.

## SkillRouter (the shared skills-only router)
The LLM round-trip + dispatch wrapper **around** `SkillClassifier` for agents whose only routing choice
is "run one of my intent skills, or just chat" (no directly-routable MCP tools). Lifted from the
per-agent `*IntentRouter`s that `notes-agent` and `creator-agent` ran near-identically (Bucket 1 slice
4+, #475 / epic #479) once the shape settled over those two consumers — before a third copy. The agent
constructs one per-agent (it is **not** an auto-wired bean; it needs agent-specific config) and supplies
only its own parts via the constructor:

- an ordered **dispatch map** `{skillName → flow}` (`Map<String, Function<NormalizedMessage, Mono<IntentResponse>>>`).
  Its **key set is the route set**: a loaded skill absent from the map (e.g. creator's hub-invoked
  `greeting-drafter`) is neither advertised in the prompt nor dispatched — the "exclude a hub-invoked
  skill" case needs no extra plumbing. Insertion order = the order skills appear in the prompt.
- a **chat fallback** (`Function<NormalizedMessage, Mono<IntentResponse>>`) — the conversational reply
  every soft-fail lands on;
- the `intro` + `decidePrompt` framing lines for `buildPrompt`.

`route(msg)` → classify → dispatch the named flow, or the chat fallback. **Total by construction**: a
blank message (skips the LLM), no routable skill loaded (skips the LLM), an unknown/hub-only skill name,
non-routing prose, a `tool` decision (skills-only → none dispatchable), or an LLM error all soft-fail to
the chat fallback. `buildClassifierPrompt()` is public so a `@GoldenLlmTest` can replay the exact prompt.
Each routable skill's SKILL.md `description` (looked up in the `SkillRegistry`) is what the classifier
sees — SKILL.md stays the routing SSOT. Consumers: `notes-agent`'s `NotesIntentRouter` (3 flows) and
`creator-agent`'s `CreatorIntentRouter` (2 flows; `greeting-drafter` excluded), each now a thin binding.

## PickConfirmActRunner (the shared confirm-act flow)
The **pick → confirm → act** loop (ADR-0004) that five per-domain flows (tasks/finance/notes **delete** +
calendar **cancel**/**move**) copy-pasted at ~250 lines each. Two turns over the Stage-4 pending-action
lock: `pick(msg)` reads the candidate pool, asks the LLM to pick one (`{pick:n}` / `{ambiguous:[…]}` /
`{}`), and — on a single **complete** match — replies with a confirm `pendingAction` **acting on nothing**;
`resume(req)` runs the terminal act on an affirmative, leaves it otherwise, and clears the lock either way.
Every stage soft-fails. The runner owns the orchestration, the LLM round-trip + selection parse, the confirm
gate, the shared Russian wording, and the `params` passthrough (the extra LLM fields a move/edit needs,
threaded through the lock into `act`). The confirm `pendingAction` also carries the
`contracts.agent.PendingActionHints.CONFIRM` hint (`"confirm": true`), so gateway-telegram renders a Да / Нет
inline keyboard for it (#489 RU-2) — additive, ignored by `resume` (which reads only the id field).

A domain supplies a small `TargetedActionFlow<T>` adapter: `candidates()` (the domain read, own vs
personal ∪ shared), a `CandidateView<T>` (`id`/`label`/`describe`), `act()` (the delete/cancel/update), and
the **wording**. A **delete** flow writes only its `Nouns` (three Russian forms) and gets the "Удалить…"
wording free from `NounPhrasing`; a flow whose wording doesn't fit (calendar cancel/move) returns its own
`Phrasing` from `phrasing()`. A **move/edit** flow additionally overrides `missing()` (the "resolved a
target but a required field is absent → re-ask" gate, given the target + the LLM selection), `readyToAct()`
(a resume precondition beyond the id — the stashed new time), and `decorateUserMessage()` (extra LLM context
such as `now` for relative dates); the extra fields the LLM returns on selection (the new time) are threaded
through the `pendingAction` at top level and handed back to `act()`. `requiresHousehold()` opts a flow out
of the null-`householdId` short-circuit (calendar resolves its read set from the userId instead). The runner
is **not** an auto-wired bean; the domain constructs one per flow (it needs the adapter) and its
`IntentController`/`ResumeController` still dispatch to the domain object, which delegates here.
`idField()`/`labelField()` default to the ADR-0004 standard `targetId`/`label`; the already-shipped flows
override them to their legacy field names (`taskId`/`transactionId`/`noteId`/`eventId` + `title`/`summary`)
so their tests stay byte-for-byte — new flows take the defaults. Consumers: `TaskDeleter`,
`TransactionDeleter`, `NoteDeleter` (delete seam, PR-1); calendar `EventCanceller`/`EventMover` (cancel/move
— the non-delete act, PR-2).

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
- `skill/SkillRegistry` — `byTrigger(kind) → Optional<Skill>` (deterministic trigger routing) and `byName(name) → Optional<Skill>` (used where a router sources a flow's trigger phrasing from the skill's `description` — the SKILL.md is the SSOT, e.g. finance `IntentRouter`); fail-fast on declared-but-not-loaded skills.
- `http/ProfileClient` — `usersByHousehold`, `personById`.
- `http/NotifierClient` — `notify(userId, text)` (sends `proactive=true` — every push through this client is a proactive wake/ack, gate-able under #487; `notify(userId, text, proactive)` to bypass). `notify(userId, text, stream)` attributes the push to a named proactive **stream** (#487 PX-3, e.g. `"briefing"`/`"resurfacing"`/`"finance"`) — the coarse stream a user can mute per-member.
- `http/MemoryClient` — `recall(query, scope, k)` with 500ms timeout + no-throw downgrade to empty list (memory downtime must not block the trigger path). Also `observe(householdId, userId, text, source)` — fire-and-forget drop at memory-service `/v1/observations` so an agent feeds durable facts it saw into memory-from-chat (MFC-c); off the response path, soft-fail, no-op on blank text. And `remember(householdId, userId, source, text, metadata) → Mono<Void>` — durably index a piece of text at `/v1/memories` (embed-and-store) so a later `recall` surfaces it; unlike `observe` it stores `text` verbatim under `source` with optional `{kind, refId}` metadata (the caller already holds the corpus). **The second-brain write seam (SB-5): `note(WriteNoteRequest) → Mono<NoteDto>`** — the note-tier analog of `remember`: instead of an opaque `memory.memories` row it captures a first-class **authored note** at `/v1/notes`, which memory-service auto-seeds into recall (SB-2) and graph-projects (`[[wiki-links]]`, SB-3) server-side. An agent that learns a durable, human-readable fact writes a note here (carry a `{kind, refId}` back-pointer in `frontmatter`) so it lands in the ONE store every agent reads; **`getNote(id) → Mono<NoteDto>`** is the read half — resolve a `{kind:note, refId}` recall hit back to the domain row via the note's frontmatter (docs-agent is the first consumer). The note seam also carries **`listNotes(householdId, limit) → Mono<List<NoteDto>>`** (GET `/v1/notes`) + **`updateNote(id, WriteNoteRequest) → Mono<NoteDto>`** (PUT `/v1/notes/{id}`) — the find-or-create list-upsert pair, lifted here as the second consumer of notes-agent's local `NoteClient.list/update` shape (travel-agent's LI-c mirrors a packing list onto a `type=list` note so LI-a can check items off). Both soft-fail: 3s timeout, downgrade to empty, no-op on missing household / blank title. **The fact-tier read (MQ-1, #488): `listMemories(householdId, userId, personId, limit) → Mono<List<MemoryDto>>`** (GET `/v1/memories`) — a flat, most-recent-first list of the household's stored facts behind the memory-review digest ("что ты про меня запомнил"); unlike `recall` it enumerates by recency so the owner can audit + prune, `userId`/`personId` narrowing the scope. Soft-fails to empty (3s timeout). All of `recall`/`remember`/`note`/`listNotes`/`updateNote`/`listMemories` share the composable soft-fail posture so enrichment never sinks the caller's primary write.
- `http/OrchestratorInvokeClient` — `invoke(req)` (5s) / `invoke(req, timeout)` → `POST /v1/agents/invoke`; the shared inter-agent hub client. Bean is opt-in per agent (declared in the agent's `OutboundHttpConfig` alongside an `orchestratorWebClient`).
- `http/MediaStoreClient` — `upload(householdId, ownerId, filename, mimeType, bytes)` → multipart `POST /v1/media` (15s); shared by the deliverable agents. Bean is opt-in per agent (`new MediaStoreClient(mediaServiceWebClient, "<source>")`); the `source` tag is constructor-set so `upload` is signature-identical across callers.
- `http/ChartRenderClient` — `render(householdId, ownerId, ChartSpec) → ChartResult` over `mcp-chart-render`'s `POST /internal/render` (10s). Shared by every chart-rendering agent (finance reports, the travel board) instead of a per-agent copy. Bean is opt-in per agent (`new ChartRenderClient(mcpChartRenderWebClient)`).
- `http/GeocodeClient` — `geocode(name, language) → GeoLocation` over `mcp-weather`'s `POST /internal/geocode` (10s, soft-fails to empty). Shared by every agent that geocodes a profile's home base (briefing, travel). Bean is opt-in per agent (`new GeocodeClient(mcpWeatherWebClient)`).
- `http/WebSearchClient` — `search(query, limit)` / `search(query, limit, timeout) → WebSearchResult` over `mcp-web`'s `POST /internal/search` (15s default; the briefing digest tightens it to 8s). Errors/empty propagate — the caller applies its own `onErrorResume` / `defaultIfEmpty`. Shared by every agent that searches the web (researcher, chef, nutritionist, stylist, travel, briefing news, creator's trend gather). Bean is opt-in per agent (`new WebSearchClient(mcpWebWebClient)`).
- `http/CaptionClient` — `caption(mediaId, instruction) → CaptionResult` over `mcp-media-processing`'s `POST /internal/caption` (30s, a vision pass is slow). Shared by every agent that captions media (finance receipts, nutrition food photos, stylist wardrobe items). Bean is opt-in per agent (`new CaptionClient(mcpMediaProcessingWebClient)`).
- `actuate/SkillInfoContributor` — `InfoContributor` that adds the `skills.*` detail to `/actuator/info`.
- `coordinate/Coordinator` — `coordinate(...)` gather→synthesize scaffold; `coordinate/CoordinationResult` is its `(text, gathered, llmModel)` outcome. Soft-fails per gather step.
- `intent/SkillClassifier` — the shared in-agent router: `buildPrompt(...)` + `parse(...) → Decision`. Nested value types `ToolSpec` / `Choice` (inputs) and the sealed `Decision` = `ToolCall | FlowCall | Chat` (output). Pure (only `ObjectMapper`); lifted from the finance/tasks `IntentRouter`s (#358, Bucket 1). See the section above.
- `intent/SkillRouter` — the shared **skills-only** router (LLM round-trip + dispatch **around** `SkillClassifier`): `route(msg) → Mono<IntentResponse>` + public `buildClassifierPrompt()`. Constructed per-agent with an ordered `{skillName → flow}` dispatch map (its key set = the route set), a chat fallback, and intro/decide framing; total (every soft-fail → chat). Lifted from the notes/creator `*IntentRouter`s (#475, Bucket 1 slice 4+). See the section above.
- `intent/PickConfirmActRunner` — the shared **pick → confirm → act** runner (ADR-0004): `pick(msg)` + `resume(req)` over the Stage-4 lock. Constructed per-flow with a `TargetedActionFlow<T>` adapter; owns the orchestration, LLM selection parse, confirm gate, Russian wording, and the move/edit `params` passthrough. Not a bean. See the section above.
- `intent/TargetedActionFlow` — the domain seam for `PickConfirmActRunner`: `skillName`/`flow`/`candidates`/`view`/`act` + wording (`nouns` for the `NounPhrasing` default, or `phrasing` for custom), `idField`/`labelField` (default `targetId`/`label`), `requiresHousehold` (default true), the `missing` completeness gate + `readyToAct` resume precondition (move/edit), `decorateUserMessage` (extra LLM context), and `extraAffirmatives` (default none).
- `intent/CandidateView` — how a domain renders one candidate: `id(T)` · `label(T)` (the token stored in the lock; the phrasing formats display around it) · `describe(node, T)` (the disambiguating fields for the LLM).
- `intent/Phrasing` — the per-flow user-facing wording (askWhich / emptyPool / noMatch / ambiguous / confirm / declined / done / actFailed / …). Turn-1 methods get the typed candidate, turn-2 methods get the stored `pendingAction`.
- `intent/NounPhrasing` — the default `Phrasing` for a **delete** flow: the shared "Удалить … удалил" wording templated on the flow's `Nouns` + `CandidateView`.
- `intent/Nouns` — the three Russian noun forms (accusative / genitive-plural / nominative) `NounPhrasing` interpolates.
- `brief/BriefResponder` — the reusable `brief` read-action: `answer(request)` / `answer(request, extraGather)` recall the agent's second brain for `args.question` (plus any domain gather the agent adds), run one FAST `Coordinator` synthesis under a read-only instruction, and return `AgentActionResult{agent, answer, llmModel?}`. Missing question → structured `ok=false`. Wired as a bean; an agent opts in with `register("brief", briefResponder::answer)`.
- `deliver/DeliverablePublisher` — `publish(household, owner, Doc)` render→store→link over the agent's `DocRenderer` + `MediaStoreClient`; `mediaUrl(UUID|String)` public-link builder (null-safe); static `splitParagraphs(text)` / `summary(text, fallback)`. Two ctors: three-arg (pass a themed `DocRenderer`) and the two-arg convenience (default `HtmlDocRenderer`, no `RenderConfig` needed). Bean is opt-in per agent (declared in the agent's `OutboundHttpConfig`).
- `transparency/DegradedNotice` — transparency helper (road-test #485, "no silent failures"): `append(text, note)` folds a short, user-facing degraded-state note onto a reply as a trailing `⚠️ …` block (null/blank-safe; blank note → text unchanged). When a **best-effort** step on the reply path soft-fails (a render/store hiccup, a memory write that didn't land), the flow says so discreetly in its `onErrorResume` branch instead of pretending success — the wording is small per-flow copy in the user's language, this primitive owns only the consistent rendering. First consumer: `briefing-agent`'s board-store fallback.
- `web/AgentActionController` — abstract base for an agent's `POST /agents/<name>/actions/{action}` endpoint: `register(action, handler)` in the subclass ctor + `dispatch(action, request)` applies the shared envelope (unknown-action → structured `ok=false`; handler failure → `"<action> failed: <msg>"` logged with `requestedBy`). Subclasses stay `@RestController`s (the path literal carries the agent name) and only hold per-action business logic.

## Tests
`libs/agent-runtime/src/test/resources/test-skills/{good,bad}/SKILL.md` drive
the parse + fail-fast cases; see `AgentRuntimeConfigTest`.
`SkillInfoContributorTest` covers the `/actuator/info` skill-inventory shape
(sorted names/triggers, empty registry, dedup across skills, null-trigger
tolerance).
