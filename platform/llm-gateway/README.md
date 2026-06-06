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
| `anthropic`           | ready  | Messages API; no embeddings (see below)                |
| `openai-compatible`   | TODO   | DeepSeek / Ollama / Together / OpenAI                  |

`MockProvider` echoes the last user message prefixed with the channel name, and produces
stable 384-dim embeddings keyed off a CRC32 of the input. Token counts are approximate
(`chars / 4`) so usage looks realistic in tests.

`AnthropicProvider` calls `POST /v1/messages` (chat + SSE stream). Translation rules:

- All `SYSTEM` messages are concatenated (blank-line separated) into Anthropic's top-level
  `system` field — Anthropic refuses `system` entries inside `messages[]`.
- `USER` / `ASSISTANT` map straight through. `TOOL` is rejected (unused in ai-life today).
- `max_tokens` is required by the API; falls back to `LLM_MAX_TOKENS` (default 4096) when
  the caller doesn't set one.
- **Embeddings are unsupported** — Anthropic ships no embedding endpoint. The `embedding`
  channel needs a separate llm-gateway instance pointed at an embedding-capable provider
  (e.g. OpenAI-compatible / Ollama with `bge-m3`).

## Configuration (env vars)

See `infra/.env.example` for the full set and provider profiles. Minimum:

```
LLM_PROVIDER=mock
LLM_DEFAULT_MODEL=mock-large
LLM_EMBEDDING_MODEL=mock-embed
LLM_GATEWAY_PORT=8081
```

Anthropic profile:

```
LLM_PROVIDER=anthropic
LLM_API_KEY=sk-ant-...
LLM_BASE_URL=https://api.anthropic.com    # optional, this is the default
LLM_DEFAULT_MODEL=claude-opus-4-7
LLM_FAST_MODEL=claude-haiku-4-5
LLM_VISION_MODEL=claude-opus-4-7
# LLM_ANTHROPIC_VERSION=2023-06-01        # optional, pinned in x-anthropic-version
# LLM_MAX_TOKENS=4096                     # optional, fallback when callers omit it
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

## Key classes
- `LlmGatewayApplication` — `@SpringBootApplication`.
- `config/LlmGatewayProperties` — `@ConfigurationProperties("llm")` (provider id, base-url, api-key, per-channel model ids, anthropic-version, max-tokens fallback).
- `provider/LlmProvider` — provider SPI.
- `provider/ProviderRegistry` — selects active provider via `LLM_PROVIDER`.
- `provider/mock/MockProvider` — deterministic echo + 384-dim CRC32-keyed embeddings; used in every other module's tests.
- `provider/anthropic/AnthropicProvider` — Anthropic Messages API (`POST /v1/messages`, SSE stream). Active when `LLM_PROVIDER=anthropic`; embeddings rejected as unsupported.
- `web/ChatController` — `POST /v1/chat`, `POST /v1/chat/stream` (SSE).
- `web/EmbedController` — `POST /v1/embed`.
