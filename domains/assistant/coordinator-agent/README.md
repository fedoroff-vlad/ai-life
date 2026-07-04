# coordinator-agent

The **cross-cutting synthesis engine** (#290, Stage-4 memory-driven orchestration). When a request
spans *several* domains at once, the orchestrator's data-driven router picks `coordinator`; this agent
reads the second brain (long-term memory), gathers the relevant context, and synthesizes **one**
grounded answer instead of routing to a single specialist. It owns no domain data and binds no
domain-MCP — it is "the assistant" as one more routable agent, reaching specialists (Slice B) only
through the orchestrator hub.

Two entry points, one engine (`MultiDomainCoordinator`):
- **Reactive** — `POST /agents/coordinator/intent`: a cross-cutting user message → gather → synthesize.
- **Proactive** — `POST /agents/coordinator/triggers/coordinator.surface`: scheduler wakes it to surface
  a useful connection/idea from memory, delivered via notifier **only when it clears a relevance bar**
  (precision over volume). A surface is a *proposal* — no external side effect, so no confirmation
  needed.

**Model-agnostic:** the synthesis is one `DEFAULT`-channel LLM call; its quality scales with whatever
provider llm-gateway points at (mock / local Ollama / API) with no code change. Per-source soft-fail is
inherited from the shared `Coordinator` — a slow/broken memory recall is simply omitted, never faked.

**Loop-ready:** Slice A is one-shot `gather → synthesize`. The `gatherFor` / `synthesize` split behind
`MultiDomainCoordinator.run` is the seam a later bounded `plan → gather → re-gather` loop wraps without
touching the controllers. **Slice B** adds live cross-agent answers to the same gather map (a generic
read-only cross-agent query action).

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
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | LLM gateway (DEFAULT channel for synthesis) |
| `MEMORY_SERVICE_URL` | `http://memory-service:8087` | second-brain recall |
| `NOTIFIER_URL` | `http://notifier-service:8084` | proactive-surface delivery |
| `PROFILE_SERVICE_URL` | `http://profile-service:8082` | household fan-out for a surface with no `ownerId` |

Registered in the orchestrator via `orchestrator.agents[]` (`name: coordinator`, `COORDINATOR_AGENT_URL`)
— no orchestrator code change; the manifest description drives routing.

## Key classes

- `CoordinatorAgentApplication` — `@SpringBootApplication` + `@Import(AgentRuntimeConfig)`.
- `config/CoordinatorAgentProperties` — `coordinator-agent.*` base URLs (implements `SharedClientProperties`).
- `flow/MultiDomainCoordinator` — the engine: `handle` (reactive) / `surface` (proactive) → `run`
  (`gatherFor` → `synthesize` on the shared `Coordinator`, DEFAULT channel) + `isWorthSurfacing` gate.
- `web/IntentController` — `POST /agents/coordinator/intent`.
- `web/TriggerController` — `POST /agents/coordinator/triggers/{kind}` (`coordinator.surface`).
- `web/ManifestController` — `GET /agents/coordinator/manifest`.

## Tests

- `flow/MultiDomainCoordinatorTest` — slice test over both entries (MockWebServer memory + gateway):
  the synthesis carries the recalled context; the proactive surface delivers only when it clears the bar.
- `web/ManifestControllerTest` — the manifest loads + exposes the routing description.
- `E2ECoordinateFlowTest` — real coordinator context; an inbound cross-cutting message flows
  recall → synthesis → reply across HTTP with the `libs/contracts` DTOs.
- `flow/GoldenCoordinatorSynthesisTest` — opt-in (`@GoldenLlmTest`, skipped in CI): on a real 7b the
  synthesis is grounded in the supplied recall context (structure, not text).
