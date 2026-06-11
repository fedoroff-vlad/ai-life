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
| `openai-compatible`   | ready  | local Ollama (free) / DeepSeek / Together / OpenAI     |

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

`OpenAiCompatibleProvider` calls `POST /chat/completions` (+ SSE) and `POST /embeddings`
against any OpenAI-dialect server: local Ollama (the free baseline for tests + dev),
DeepSeek cloud, Together, vLLM, OpenAI proper. `LLM_API_KEY` is **optional** — when set
it goes through as `Authorization: Bearer <key>`; Ollama works without one. `LLM_BASE_URL`
is required (must include the OpenAI version prefix, e.g. `http://ollama:11434/v1`). The
SSE stream extracts `choices[0].delta.content` and terminates on `data: [DONE]`.

## Vision channel (inline images)

Any `user` message can carry one or more inline images for the `vision` channel — used by
the finance receipt-parser. Images travel as base64 (the gateway never fetches a URL on the
caller's behalf, so it stays stateless):

```json
{
  "channel": "vision",
  "messages": [
    {"role": "system", "content": "extract amount/date/merchant from the receipt"},
    {"role": "user", "content": "what is this?",
     "images": [{"mediaType": "image/jpeg", "dataBase64": "<...>"}]}
  ]
}
```

Build the message with `LlmMessage.userWithImages(text, List.of(new LlmImage(mediaType, base64)))`.
Text-only messages serialise exactly as before (no `images` key). Each provider translates a
multimodal turn into its native shape:

- **anthropic** → `content` array of blocks: optional leading `{"type":"text"}` then one
  `{"type":"image","source":{"type":"base64","media_type":…,"data":…}}` per attachment.
- **openai-compatible** → `content` array of parts: optional leading `{"type":"text"}` then one
  `{"type":"image_url","image_url":{"url":"data:<media-type>;base64,<data>"}}` per attachment.
- **mock** → echoes ` [images=N]` after the text so callers can assert deterministically.

The concrete model is the `vision` channel's (`LLM_VISION_MODEL`); pick a vision-capable model
(`claude-opus-4-7`, `qwen2.5-vl:32b`, …) or it falls back to `LLM_DEFAULT_MODEL`.

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

OpenAI-compatible profile (local Ollama, free):

```
LLM_PROVIDER=openai-compatible
LLM_BASE_URL=http://ollama:11434/v1
LLM_DEFAULT_MODEL=qwen2.5:72b-instruct
LLM_FAST_MODEL=qwen2.5:7b-instruct
LLM_VISION_MODEL=qwen2.5-vl:32b
LLM_EMBEDDING_MODEL=bge-m3
# LLM_API_KEY=                             # optional — Ollama ignores Authorization
```

OpenAI-compatible profile (DeepSeek cloud):

```
LLM_PROVIDER=openai-compatible
LLM_API_KEY=sk-...
LLM_BASE_URL=https://api.deepseek.com/v1
LLM_DEFAULT_MODEL=deepseek-chat
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
- `provider/openai/OpenAiCompatibleProvider` — OpenAI Chat Completions dialect (`POST /chat/completions` + SSE, `POST /embeddings`). Active when `LLM_PROVIDER=openai-compatible`; covers local Ollama / DeepSeek / Together / OpenAI.
- `web/ChatController` — `POST /v1/chat`, `POST /v1/chat/stream` (SSE).
- `web/EmbedController` — `POST /v1/embed`.
