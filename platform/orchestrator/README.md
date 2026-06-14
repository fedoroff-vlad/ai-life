# orchestrator

The "brain". Takes a `NormalizedMessage` from gateway-telegram, **recalls top-k
long-term context from memory-service** (gated on having >1 known agent), classifies
the intent via the FAST llm-gateway channel using few-shot drawn from each registered
agent's manifest, and forwards to that agent's `/agents/<name>/intent`. Also dispatches
scheduler wake-ups via `POST /v1/agents/wake → /agents/<name>/triggers/{kind}`. Agent
discovery: at startup, fetch `/agents/<name>/manifest` for every entry in
`orchestrator.agents[]` (3 s timeout, soft-fail).

Memory recall is **strict no-throw**: any error (disabled, no household on the
message, network, 5xx, 500 ms timeout) collapses to "no memories" and classification
proceeds without the second system message. Routing never blocks on memory.

**Route-lock first (Stage 4 / A2):** before classifying, the orchestrator asks conversation-service
(`GET /v1/conversation-state`) whether this `(household, user, channel)` has an active route-lock. If
it does and names a known agent, the message is a reply to that agent's open question — it's routed
**straight there, bypassing classification**. No lock, a lock to an unknown agent, or
conversation-service unreachable (the client soft-fails to empty) → normal classification. Toggle
with `orchestrator.conversation.enabled`. (Agents setting/clearing the lock + a resume endpoint are
later Track-A slices.)

**Catch-all routing:** `orchestrator.catch-all-agent` (default `tasks`) names the agent
that captures any *actionable* message matching no specialized domain — the GTD
"anything not calendar/finance → inbox" fallback. When set to a registered agent the
classifier prompt routes such messages there and reserves `echo` for greetings / small
talk. It **self-disables to `echo`** if the named agent isn't registered, and the
deterministic fallbacks (LLM error, un-parseable output, no remote agents) always stay
`echo`.

## Endpoints

| method | path                  | purpose                                        |
|--------|-----------------------|------------------------------------------------|
| POST   | `/v1/intent`          | route a `NormalizedMessage` to an agent        |
| POST   | `/v1/agents/wake`     | dispatch a scheduler wake to an agent          |
| GET    | `/actuator/health`    | liveness                                       |

`/v1/intent` request: `NormalizedMessage` (`libs/contracts/agent`); response: `IntentResponse` `{ agent, text, llmModel }`.
`/v1/agents/wake` request: `AgentWakeRequest` (`libs/contracts/schedule`); response: 202 on accept, 404 if agent unknown.

## Configuration

```
ORCHESTRATOR_PORT=8083
LLM_GATEWAY_URL=http://llm-gateway:8081
MEMORY_SERVICE_URL=http://memory-service:8087
ORCHESTRATOR_MEMORY_ENABLED=true
ORCHESTRATOR_MEMORY_RECALL_K=3
ORCHESTRATOR_CATCH_ALL_AGENT=tasks   # actionable-unmatched → this agent; empty = echo-only
CONVERSATION_SERVICE_URL=http://conversation-service:8089
ORCHESTRATOR_CONVERSATION_ENABLED=true   # route-lock check before classify; false = always classify
```

## Run locally

```sh
mvn -B -pl platform/orchestrator -am spring-boot:run
```

## Configuration — agent registry

Each agent has a `{name, base-url}` entry in `orchestrator.agents[]`:

```yaml
orchestrator:
  agents:
    - name: calendar
      base-url: ${CALENDAR_AGENT_URL:http://calendar-agent:8086}
```

Add a new agent → append an entry, set `<NAME>_AGENT_URL`, restart. No code change.

## Tests

`IntentControllerTest` boots the full Spring context and uses OkHttp MockWebServer to
fake `llm-gateway`. Asserts that `/v1/intent` routes through the classifier and the
chosen agent, and returns the LLM content with the right `agent` / `llmModel`.
`WakeDispatchTest` covers `/v1/agents/wake`.

## Key classes
- `OrchestratorApplication`.
- `agent/Agent` — interface; default `wake()` no-op.
- `agent/RemoteAgent` — HTTP impl over a per-agent cloned `WebClient`.
- `agent/EchoAgent` — fallback when classification fails to pick anyone.
- `agent/AgentRegistryProperties` — `orchestrator.agents[]` config.
- `agent/AgentDiscovery` — startup manifest fetch.
- `agent/AgentRegistry` — name → `Agent` map; backs both routing and wake dispatch.
- `memory/MemoryProperties` — `orchestrator.memory.{enabled, url, recall-k}`.
- `memory/MemoryClient` — reactive POST `/v1/memories/recall` with 500 ms timeout; strict no-throw (errors → `Mono.just(List.of())`).
- `routing/IntentRouter` — `NormalizedMessage` → `Agent` decision.
- `routing/LlmIntentClassifier` — FAST-channel call with few-shot built from manifest `intents[]`; injects recalled memories as a second system message when non-empty. Lenient parsing + echo fallback.
- `web/IntentController` — `POST /v1/intent`.
- `web/AgentWakeController` — `POST /v1/agents/wake`.
