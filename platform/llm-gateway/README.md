# llm-gateway

Single entry point for all LLM calls in ai-life. Agents and skills never speak to a
specific provider — they call this service with a logical *channel*
(`default`, `fast`, `vision`, `embedding`). The provider is chosen by `LLM_PROVIDER`
and can be swapped at redeploy without changing any client code (see plan §5).

## Endpoints

| method | path                | purpose                                |
|--------|---------------------|----------------------------------------|
| POST   | `/v1/chat`          | JSON request/response                  |
| POST   | `/v1/chat/stream`   | SSE stream of text deltas              |
| POST   | `/v1/embed`         | embeddings (always `embedding` channel)|
| GET    | `/actuator/health`  | liveness                               |
| GET    | `/actuator/prometheus` | metrics                            |

## Providers

| `LLM_PROVIDER`        | status | notes                                                  |
|-----------------------|--------|--------------------------------------------------------|
| `mock`                | ready  | deterministic stub for dev and golden tests            |
| `anthropic`           | TODO   | added in Stage 5                                       |
| `openai-compatible`   | TODO   | DeepSeek / Ollama / Together / OpenAI                  |

`MockProvider` echoes the last user message prefixed with the channel name, and produces
stable 384-dim embeddings keyed off a CRC32 of the input. Token counts are approximate
(`chars / 4`) so usage looks realistic in tests.

## Configuration (env vars)

See `infra/.env.example` for the full set and provider profiles. Minimum:

```
LLM_PROVIDER=mock
LLM_DEFAULT_MODEL=mock-large
LLM_EMBEDDING_MODEL=mock-embed
LLM_GATEWAY_PORT=8081
```

## Run locally

```sh
mvn -B -pl platform/llm-gateway -am spring-boot:run
# or
mvn -B -pl platform/llm-gateway -am package && \
java -jar platform/llm-gateway/target/llm-gateway.jar
```

## Quick smoke test

```sh
curl -s http://localhost:8081/v1/chat \
  -H 'content-type: application/json' \
  -d '{"channel":"default","messages":[{"role":"user","content":"hi"}]}'
```
