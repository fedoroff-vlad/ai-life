# orchestrator

The "brain". Takes a `NormalizedMessage` from gateway-telegram, classifies the intent
via the FAST llm-gateway channel using few-shot drawn from each registered agent's
manifest, and forwards to that agent's `/agents/<name>/intent`. Also dispatches
scheduler wake-ups via `POST /v1/agents/wake → /agents/<name>/triggers/{kind}`. Agent
discovery: at startup, fetch `/agents/<name>/manifest` for every entry in
`orchestrator.agents[]` (3 s timeout, soft-fail).

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
- `routing/IntentRouter` — `NormalizedMessage` → `Agent` decision.
- `routing/LlmIntentClassifier` — FAST-channel call with few-shot built from manifest `intents[]`; lenient parsing + echo fallback.
- `web/IntentController` — `POST /v1/intent`.
- `web/AgentWakeController` — `POST /v1/agents/wake`.
