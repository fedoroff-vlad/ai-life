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

## Tracing (Langfuse)

Every LLM call is exported to [Langfuse](https://langfuse.com) as a trace plus a `GENERATION`
observation carrying model, channel, input, output, token usage (where available), and latency
(start/end). All three surfaces are covered:

| call | trace name | notes |
|------|------------|-------|
| `POST /v1/chat` | `llm-gateway.chat` | full input turns + output + usage |
| `POST /v1/chat/stream` | `llm-gateway.chat.stream` | accumulated delta output; **no usage** (stream emits text only), model resolved from the channel |
| `POST /v1/embed` | `llm-gateway.embed` | input texts + usage; metadata carries `vectorCount`/`dimensions` |

Export is **off by default** — `mock` dev runs and every other module's test stay silent — and turns
on with a project key pair:

```
LANGFUSE_ENABLED=true
LANGFUSE_BASE_URL=https://cloud.langfuse.com   # or a self-hosted instance
LANGFUSE_PUBLIC_KEY=pk-lf-...
LANGFUSE_SECRET_KEY=sk-lf-...
```

Tracing is **best-effort**: the tracer fires fire-and-forget after the response is produced and
swallows any ingestion error (network / 4xx-5xx / 5s timeout) at DEBUG, so a Langfuse outage never
slows or breaks an LLM call. It POSTs the batch ingestion API (`POST /api/public/ingestion`, HTTP
Basic public/secret key).

## Configuration (env vars)

See `infra/.env.example` for the full set and provider profiles. Minimum:

```
LLM_PROVIDER=mock
LLM_DEFAULT_MODEL=mock-large
LLM_EMBEDDING_MODEL=mock-embed
LLM_GATEWAY_PORT=8081
# LLM_REQUEST_TIMEOUT_SECONDS=60          # optional, upstream chat/embed timeout (all providers); raise for slow local CPU models
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

## Golden tests (real model) — Stage 5

The behavioral surface (intent routing, skill prompts, tool selection, synthesis) is validated on the
**mock** provider in CI. Stage 5 (#199) adds opt-in **golden tests** that run the same surface against a
**real model** and assert **structure, not text** (the router emits parseable routing JSON; the action is
a contract value; a tool name is real; unambiguous requests route to the right action). They are
`@Tag("golden")` + `@EnabledIfEnvironmentVariable(GOLDEN_LLM)`, so a normal `mvn test` **skips** them — CI
stays green without a model. Harnesses: `orchestrator`'s `routing.GoldenRoutingTest` (top-of-spine
agent routing across the 8 real manifests), `finance-agent`'s `GoldenRoutingTest` (in-agent
intent/tool routing), `tasks-agent`'s `intent.GoldenInboxClarifyTest` (skill output — the
`inbox-clarify` skill must return strict `{"proposals":[…]}` JSON with verbatim task ids + valid GTD
statuses), `researcher-agent`'s `flow.GoldenResearchSynthesisTest` (free-text synthesis — the
`research` skill must write a grounded answer citing **only** corpus links, never a hallucinated URL),
and `finance-agent`'s `advisor.GoldenAdvisorSynthesisTest` (grounded synthesis — the `financial-advisor`
skill must name the real top category + currency from the supplied spend windows, never an analysis
invented from thin air).

Run them against local Ollama (free):

```sh
# 1. a model — local Ollama with qwen2.5:7b pulled (chat) + nomic-embed-text (embeddings).
#    NOTE: point Ollama's model dir at the models ROOT (…/.ollama/models), not a sub-folder.
ollama list            # must show qwen2.5:7b

# 2. a llm-gateway pointed at it (bare JVM → Ollama on localhost). On a CPU box raise the upstream
#    timeout — a 7B generating multi-item JSON (the skill golden tests) exceeds the 60 s default:
LLM_PROVIDER=openai-compatible LLM_BASE_URL=http://localhost:11434/v1 \
LLM_DEFAULT_MODEL=qwen2.5:7b LLM_FAST_MODEL=qwen2.5:7b \
LLM_EMBEDDING_MODEL=nomic-embed-text LLM_REQUEST_TIMEOUT_SECONDS=180 LLM_GATEWAY_PORT=8081 \
  java -jar platform/llm-gateway/target/llm-gateway.jar

# 3. the golden tests, pointed at the gateway:
GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=http://localhost:8081 \
  mvn -q -pl platform/orchestrator -Dtest=GoldenRoutingTest test
GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=http://localhost:8081 \
  mvn -q -pl domains/finance/finance-agent -Dtest=GoldenRoutingTest test
GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=http://localhost:8081 \
  mvn -q -pl domains/tasks/tasks-agent -Dtest=GoldenInboxClarifyTest test
GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=http://localhost:8081 \
  mvn -q -pl domains/researcher/researcher-agent -Dtest=GoldenResearchSynthesisTest test
GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=http://localhost:8081 \
  mvn -q -pl domains/finance/finance-agent -Dtest=GoldenAdvisorSynthesisTest test
```

What the first run surfaced on `qwen2.5:7b` (and the fixes, per #199 part 3): the model **flattens** the
tool shape to `{"action":"<toolName>"}` (IntentRouter now tolerates it) and once invented `"analysis"` for
the analysis flow (the classifier prompt now pins the action to an exact enum). See `infra/.env.example`
§"local Ollama (… golden tests)" for the env block. The orchestrator routing harness passed clean on
`qwen2.5:7b` (no fixes) — the FAST-channel agent picker holds on the real model. The `inbox-clarify`
skill harness surfaced one fix: a 7B generating the proposals JSON for a multi-item inbox blew past the
gateway's previously-hardcoded 60 s upstream timeout — that timeout is now `LLM_REQUEST_TIMEOUT_SECONDS`
(default 60); with it raised the model returns well-formed proposals (verbatim ids, valid statuses).
The `research` synthesis harness passed clean on `qwen2.5:7b` (no fixes) — the model grounds in the
supplied corpus and cites only its links, no hallucinated URLs. The `financial-advisor` synthesis
harness also passed clean — the model returns a full grounded analysis (top categories, deltas vs the
prior window, hints) over the supplied spend data; the one adjustment was test-side (accept the `€`
symbol as well as the `EUR` code — both satisfy the "show the currency" rule).

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
- `config/LangfuseProperties` — `@ConfigurationProperties("langfuse")` (enabled, base-url, public/secret key) for the trace export.
- `trace/LangfuseTracer` — fire-and-forget, soft-fail export of each chat call to Langfuse's batch ingestion API; no-op when `langfuse.enabled=false`.
- `provider/LlmProvider` — provider SPI.
- `provider/ProviderRegistry` — selects active provider via `LLM_PROVIDER`.
- `provider/mock/MockProvider` — deterministic echo + 384-dim CRC32-keyed embeddings; used in every other module's tests.
- `provider/anthropic/AnthropicProvider` — Anthropic Messages API (`POST /v1/messages`, SSE stream). Active when `LLM_PROVIDER=anthropic`; embeddings rejected as unsupported.
- `provider/openai/OpenAiCompatibleProvider` — OpenAI Chat Completions dialect (`POST /chat/completions` + SSE, `POST /embeddings`). Active when `LLM_PROVIDER=openai-compatible`; covers local Ollama / DeepSeek / Together / OpenAI.
- `web/ChatController` — `POST /v1/chat`, `POST /v1/chat/stream` (SSE).
- `web/EmbedController` — `POST /v1/embed`.
