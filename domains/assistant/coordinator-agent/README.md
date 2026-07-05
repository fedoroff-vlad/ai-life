# coordinator-agent

The **cross-cutting synthesis engine** (#290, Stage-4 memory-driven orchestration). When a request
spans *several* domains at once, the orchestrator's data-driven router picks `coordinator`; this agent
reads the second brain (long-term memory) **and consults the relevant domain specialists live**, gathers
the context, and synthesizes **one** grounded answer instead of routing to a single specialist. It owns
no domain data and binds no domain-MCP — it is "the assistant" as one more routable agent, reaching
specialists only through the orchestrator hub.

**Two gather sources feed the synthesis (Slice B2):**
- `memories` — long-term recall from the second brain.
- `briefs` — live, read-only answers from the domain specialists judged relevant to *this* request. A
  FAST **planning** step reads the configured roster (`coordinator-agent.specialists[]`: each agent's
  `name` + one-line `expertise`) and picks the subset that bears on the request (precision over volume —
  no fan-out to everyone); each picked specialist's `brief` action is then invoked in parallel through
  the hub (`POST /v1/agents/invoke`). Both sources soft-fail per-step, so a memory-only or
  specialist-only synthesis is a natural degradation, and an empty roster keeps the agent memory-only.
  `finance` and `calendar` are the wired `brief` exposers (the planner picks among ≥2 real specialists);
  more join by exposing `brief` + landing in the roster.

Two entry points, one engine (`MultiDomainCoordinator`):
- **Reactive** — `POST /agents/coordinator/intent`: a cross-cutting user message → gather → synthesize.
- **Proactive** — `POST /agents/coordinator/triggers/coordinator.surface`: scheduler wakes it to surface
  a useful connection/idea from memory, delivered via notifier **only when it clears a relevance bar**
  (precision over volume). A surface is a *proposal* — no external side effect, so no confirmation
  needed.

**Model-agnostic:** the synthesis is one `DEFAULT`-channel LLM call; its quality scales with whatever
provider llm-gateway points at (mock / local Ollama / API) with no code change. Per-source soft-fail is
inherited from the shared `Coordinator` — a slow/broken memory recall is simply omitted, never faked.

**Bounded confidence loop (Slice E-later):** `MultiDomainCoordinator.run` now wraps the
`gatherFor → synthesize` seam in a bounded `gather → synthesize → self-check → maybe-re-gather` loop.
After each synthesis a cheap FAST **`SufficiencyAssessor`** judges whether the draft is grounded and
complete; when it is not — and the `coordinator-agent.max-rounds` budget still allows — the loop
re-gathers with a sharpened focus (memory re-recall + specialist re-plan) and re-synthesizes, folding the
prior draft into the payload so the model refines rather than restarts. `max-rounds: 1` reproduces the
original one-shot behaviour exactly (the self-check is never called — cheap-first), and the assessor
soft-fails toward "sufficient" so a broken judge never drives extra rounds. Slice A was one-shot
memory-only `gather → synthesize`; **Slice B2** added the live specialist-brief leg to the gather map;
**Slice E-later** wraps the whole thing in the loop.

## Endpoints

| method | path | purpose |
|--------|------|---------|
| POST   | `/agents/coordinator/intent` | cross-cutting message → one grounded synthesis (`IntentResponse`) |
| POST   | `/agents/coordinator/triggers/{kind}` | proactive wake; the one kind is `coordinator.surface` → 202 |
| GET    | `/agents/coordinator/manifest` | `AgentManifest` (orchestrator scrapes it on startup) |
| GET    | `/actuator/health` | liveness |

## Env

| Var | Default | Purpose |
|---|---|---|
| `COORDINATOR_AGENT_PORT` | `8119` | HTTP port |
| `COORDINATOR_AGENT_MEMORY_RECALL_K` | `5` | how many second-brain recalls feed the synthesis |
| `COORDINATOR_AGENT_MAX_ROUNDS` | `2` | max synthesis rounds in the bounded loop; `1` = one-shot (self-check off) |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | LLM gateway (FAST planning + DEFAULT synthesis) |
| `MEMORY_SERVICE_URL` | `http://memory-service:8087` | second-brain recall |
| `ORCHESTRATOR_URL` | `http://orchestrator:8083` | hub the specialist `brief` invokes route through |
| `NOTIFIER_URL` | `http://notifier-service:8084` | proactive-surface delivery |
| `PROFILE_SERVICE_URL` | `http://profile-service:8082` | household fan-out for a surface with no `ownerId` |

The consultable specialist roster is config, not env: `coordinator-agent.specialists[]` (`name` +
`expertise`) in `application.yml` — `finance` + `calendar` by default; an empty roster keeps the agent
memory-only.

Registered in the orchestrator via `orchestrator.agents[]` (`name: coordinator`, `COORDINATOR_AGENT_URL`)
— no orchestrator code change; the manifest description drives routing.

## Key classes

- `CoordinatorAgentApplication` — `@SpringBootApplication` + `@Import(AgentRuntimeConfig)`.
- `config/CoordinatorAgentProperties` — `coordinator-agent.*` base URLs + the `orchestratorUrl` and the
  `specialists[]` roster (implements `SharedClientProperties`).
- `config/OutboundHttpConfig` — the `orchestratorWebClient` + `OrchestratorInvokeClient` bean (the hub
  the specialist briefs invoke through).
- `flow/MultiDomainCoordinator` — the engine: `handle` (reactive) / `surface` (proactive) → `run`, the
  bounded `gather → synthesize → self-check → maybe-re-gather` loop (`gatherFor` → `synthesize` on the
  shared `Coordinator`, DEFAULT channel; `SufficiencyAssessor` gates each re-gather up to `max-rounds`) +
  `isWorthSurfacing` gate. `gatherFor` folds two sources: memory recall + `SpecialistBriefs`. A re-gather
  round sharpens the query with the self-check's `missing` hint and folds the prior draft
  (`payload.priorDraft` / `refineFocus`) into the synthesis so the model refines rather than restarts.
- `flow/SufficiencyAssessor` — the confidence self-check (Slice E-later): one FAST verdict
  `{sufficient, missing}` over the request + draft answer. Fail-safe toward `sufficient` (blank draft / LLM
  error / unparseable reply → stop), so a broken judge never inflates the round count.
- `flow/SpecialistBriefs` — the live specialist-gather leg: FAST `pick` (plan the relevant roster
  specialists) → parallel `brief` invokes through the hub → fold into a `{name: answer}` object. Each
  stage soft-fails; empty roster / no picks / all-failed → empty (memory-only synthesis).
- `web/IntentController` — `POST /agents/coordinator/intent`.
- `web/TriggerController` — `POST /agents/coordinator/triggers/{kind}` (`coordinator.surface`).
- `web/ManifestController` — `GET /agents/coordinator/manifest`.

## Tests

- `flow/MultiDomainCoordinatorTest` — slice test over both entries (MockWebServer memory + gateway +
  hub, the LLM dispatcher telling planning / self-check / synthesis turns apart by their markers): the
  synthesis carries the recalled context; a picked specialist's live `brief` answer is gathered through
  the hub and folded into the synthesis; an under-confident self-check triggers exactly one re-gather
  (two synthesis turns, the second carrying `priorDraft` + focus, two recalls); the proactive surface
  delivers only when it clears the bar.
- `flow/SufficiencyAssessorTest` — unit coverage for the self-check: the verdict parse (prose around the
  JSON, `sufficient`/insufficient + `missing`) and the fail-safe-toward-stopping paths (blank draft skips
  the LLM; LLM error / unparseable reply → `sufficient`); FAST channel.
- `flow/SpecialistBriefsTest` — unit coverage for the planning parse (noisy reply, unknown names,
  case-drift, dedup) and per-specialist soft-fail (hub error / planner error / empty roster → omitted).
- `web/ManifestControllerTest` — the manifest loads + exposes the routing description.
- `E2ECoordinateFlowTest` — real coordinator context; an inbound cross-cutting message flows
  recall → synthesis → reply across HTTP with the `libs/contracts` DTOs.
- `flow/GoldenCoordinatorSynthesisTest` — opt-in (`@GoldenLlmTest`, skipped in CI): on a real 7b the
  synthesis is grounded in the supplied recall context (structure, not text).
