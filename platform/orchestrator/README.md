# orchestrator

The "brain". Takes a `NormalizedMessage` from the gateway, picks an agent, and returns
the agent's response. Stage 0 ships only `EchoAgent` to prove the pipe; real intent
classification and multi-agent flows arrive in Stage 1+.

## Endpoint

| method | path                | purpose                                  |
|--------|---------------------|------------------------------------------|
| POST   | `/v1/intent`        | route a NormalizedMessage to an agent    |
| GET    | `/actuator/health`  | liveness                                 |

Request body: `NormalizedMessage` (`libs/contracts/agent`).
Response body: `IntentResponse` `{ agent, text, llmModel }`.

## Configuration

```
ORCHESTRATOR_PORT=8083
LLM_GATEWAY_URL=http://llm-gateway:8081
```

## Run locally

```sh
mvn -B -pl platform/orchestrator -am spring-boot:run
```

## Tests

`IntentControllerTest` boots the full Spring context and uses OkHttp MockWebServer to
fake `llm-gateway`. Asserts that `/v1/intent` routes to `EchoAgent`, calls the gateway,
and returns the LLM content with the right `agent` / `llmModel`.
