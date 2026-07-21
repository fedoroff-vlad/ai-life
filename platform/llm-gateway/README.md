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
| POST   | `/v1/model-profile` | switch the workload profile (LC-4) — **only when `LLM_MODEL_PROFILE_ENABLED=true`, else 404** |
| GET    | `/v1/model-profile` | the profile + model in force (same condition) |
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
  (e.g. OpenAI-compatible / Ollama with `nomic-embed-text`).

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
(`claude-opus-4-7`, `minicpm-v`, …) or it falls back to `LLM_DEFAULT_MODEL`.

## Model profile — the two-tenant swap (LC-4)

ai-life shares the Mac and its Ollama with the separate **coder** contour (the coding-agent repo),
and macOS caps the GPU working set near 48 GB. ai-life's 32B plus the coder's 30B is over that, so
while the coder works this gateway serves a smaller chat model:

```sh
curl -s localhost:8081/v1/model-profile -H 'content-type: application/json' \
     -d '{"profile":"coder-active"}'      # -> {"profile":"coder-active","model":"qwen3:14b"}
curl -s localhost:8081/v1/model-profile -d '{"profile":"normal"}' -H 'content-type: application/json'
curl -s localhost:8081/v1/model-profile   # what is in force right now
```

**Evict before load — the ordering is the contract.** A switch unloads the outgoing model
(`keep_alive: 0`) and then **polls Ollama's `/api/ps` until it is really gone** before anything
pulls the incoming one; `keep_alive: 0` returns before the memory is released, so the unload
response is not proof. An eviction that does not finish within
`LLM_MODEL_PROFILE_EVICT_TIMEOUT_SECONDS` **fails the switch with 503** rather than proceeding into
a load that would put both models resident at once — the exact swap/OOM the downshift exists to
prevent. The ordering lives in `ModelProfileServiceTest`, not in a comment.

**So a 2xx means the model has actually left the engine**, not that the request was accepted — size
client timeouts for a model unload, not an HTTP round trip. The caller is coding-agent's
`lifecycle.py`, which treats any non-2xx (including the 404 below) as "the downshift did not
happen" and declines to load its own model.

**Off by default, and that is the point.** With `LLM_MODEL_PROFILE_ENABLED` unset the endpoint does
not exist and this gateway serves `LLM_DEFAULT_MODEL` forever — ai-life runs standalone, unaffected
by a neighbour it may not have. Enabling it requires `LLM_DEFAULT_MODEL_DOWNSHIFT` (**no tag is
hardcoded** — someone else's pair will be different models) and fails at startup without one.
Enabling only this half is inert but *not* safe on its own: ai-life steps down when asked, so a
coder that never signals still loads on top of the 32B. Both halves go on together.

```
LLM_MODEL_PROFILE_ENABLED=true
LLM_DEFAULT_MODEL_DOWNSHIFT=qwen3:14b
# LLM_MODEL_PROFILE_EVICT_TIMEOUT_SECONDS=120
```

Unloading a model is Ollama-native and has no OpenAI-dialect equivalent, so this is the one place
that speaks Ollama's own API; its root URL is `LLM_BASE_URL` minus the `/v1` suffix.

Measured on the dev box against a real Ollama (`qwen3:8b` ⇄ `qwen2.5:7b`): a full switch — unload,
confirm gone, load the incoming model — takes **~10 s**, and the next chat call comes back tagged
with the new model. That is the wait a coder session pays once at start and once at stop.

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
LLM_DEFAULT_MODEL=qwen3:32b
LLM_FAST_MODEL=qwen3:8b
LLM_VISION_MODEL=minicpm-v                 # small on-demand vision/OCR (qwen3 has no vision variant; a 32B VL busts the 64 GB budget)
LLM_EMBEDDING_MODEL=nomic-embed-text       # 768-dim — see memory-service README §Dim
# LLM_SUPPRESS_THINKING=true               # qwen3 thinks by default; sends reasoning_effort:none (faster on CPU, JSON-safe)
# LLM_API_KEY=                             # optional — Ollama ignores Authorization
```

`LLM_SUPPRESS_THINKING=true` makes the openai-compatible provider send `"reasoning_effort":"none"` in
the request body — the OpenAI reasoning-control field, which Ollama honours to disable a Qwen3-style
thinking pass entirely. On CPU that is decisive: a routing call drops from ~144s to ~4s (hidden reasoning
tokens 448→8) and the reply stays clean JSON. **Note:** the Qwen3 `/no_think` prompt tag does *not* work
through Ollama's `/v1` endpoint (it still generates the reasoning, just moves it to a `reasoning` field) —
only the body field works. Off by default.

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
`finance-agent`'s `advisor.GoldenAdvisorSynthesisTest` (grounded synthesis — the `financial-advisor`
skill must name the real top category + currency from the supplied spend windows, never an analysis
invented from thin air), `nutritionist-agent`'s `foodlog.GoldenMealLogTest` (JSON extract — the `meal-logger` skill must turn a
typed meal into a parseable entry with a usable description + macros), `chef-agent`'s
`flow.GoldenRecipeCardTest` (grounded synthesis — the `recipe-finder` card must pick from the supplied
recipe hits, not invent a dish), `stylist-agent`'s `flow.GoldenWardrobeAuditTest` (JSON synthesis —
the `wardrobe-auditor` must return a parseable KEEP/QUESTION/REMOVE verdict set over the catalogued
garments), and `creator-agent`'s `profile.GoldenCreatorProfileTest` (JSON extract — the
`creator-profiler` must turn a typed track description into a parseable profile). Later agents follow the
same pattern — `docs-agent` (`GoldenDocArchiverTest`/`GoldenDocFinderTest`) and `notes-agent`
(`GoldenNoteWriterTest`/`GoldenNoteFinderTest`, second-brain SB-4). **Every domain agent plus the
orchestrator now has golden coverage.** They share their plumbing via `libs/golden-test-support`.

Run them against local Ollama (free):

**Fast path — [`scripts/golden.sh`](../../scripts/golden.sh)** (one command; instant on reuse). It brings
up the whole local stack — `ollama serve` on `:11434` (only if it isn't already running) and an
Ollama-backed gateway on `:8081` (resolving the JDK via `$JAVA_HOME/bin/java` when `java` isn't on PATH —
the default on a bare Git Bash shell) — then runs the tests with `GOLDEN_LLM=true`. Both are left
running, so the *next* run skips startup entirely:

```sh
scripts/golden.sh -pl domains/knowledge/notes-agent -Dtest='GoldenNoteWriterTest,GoldenNoteFinderTest'
scripts/golden.sh -pl platform/orchestrator -Dtest=GoldenRoutingTest    # reuses the warm stack → instant
scripts/golden.sh down         # stop the gateway (and Ollama, if the script started it)
```

The models (`qwen3:8b` + `nomic-embed-text`) must be pulled already — the script checks and, if one is
missing, prints the `ollama pull` to run rather than downloading multi-GB blobs behind your back. It
starts the gateway with `LLM_SUPPRESS_THINKING=true` (qwen3 is a thinking model — see the flag note
above; without it a routing golden runs ~144s and blows its 90s block timeout). Under the hood it is the
manual flow below — reach for these when you need to tweak a step:

```sh
# 1. bring Ollama up. The CLI `ollama serve` resolves models from the default root
#    (…/.ollama/models) — no OLLAMA_MODELS override needed (that misconfig is specific to the
#    tray app, not the CLI). Leave it running in its own terminal / background:
ollama serve &                 # starts the daemon on 127.0.0.1:11434
ollama list                    # must show qwen3:8b (chat) + nomic-embed-text (embeddings)
#    If a model is missing: `ollama pull qwen3:8b` / `ollama pull nomic-embed-text`.

# 2. build + start a llm-gateway pointed at it (bare JVM → Ollama on localhost). On a CPU box raise
#    the upstream timeout — an 8B generating multi-item JSON (the skill golden tests) exceeds the 60 s
#    default. LLM_SUPPRESS_THINKING=true sends reasoning_effort:none (qwen3 thinks by default → ~144s vs
#    ~4s per routing call on CPU). (`java` must be on PATH; from a bare shell without it, use "$JAVA_HOME/bin/java".)
mvn -q -pl platform/llm-gateway -am -DskipTests package     # builds target/llm-gateway.jar
LLM_PROVIDER=openai-compatible LLM_BASE_URL=http://localhost:11434/v1 \
LLM_DEFAULT_MODEL=qwen3:8b LLM_FAST_MODEL=qwen3:8b LLM_SUPPRESS_THINKING=true \
LLM_EMBEDDING_MODEL=nomic-embed-text LLM_REQUEST_TIMEOUT_SECONDS=180 LLM_GATEWAY_PORT=8081 \
  java -jar platform/llm-gateway/target/llm-gateway.jar
#    Wait for health: curl -s http://localhost:8081/actuator/health   # {"status":"UP"}

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
GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=http://localhost:8081 \
  mvn -q -pl domains/nutrition/nutritionist-agent -Dtest=GoldenMealLogTest test
GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=http://localhost:8081 \
  mvn -q -pl domains/nutrition/chef-agent -Dtest=GoldenRecipeCardTest test
GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=http://localhost:8081 \
  mvn -q -pl domains/stylist/stylist-agent -Dtest=GoldenWardrobeAuditTest test
GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=http://localhost:8081 \
  mvn -q -pl domains/creator/creator-agent -Dtest=GoldenCreatorProfileTest test
GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=http://localhost:8081 \
  mvn -q -pl domains/briefing/briefing-agent -Dtest=GoldenBriefingProfileTest test
GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=http://localhost:8081 \
  mvn -q -pl domains/docs/docs-agent -Dtest='GoldenDocArchiverTest,GoldenDocFinderTest' test
GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=http://localhost:8081 \
  mvn -q -pl domains/knowledge/notes-agent -Dtest='GoldenNoteWriterTest,GoldenNoteFinderTest' test
```

> The golden tests share their plumbing via **`libs/golden-test-support`** — the `@GoldenLlmTest` gate
> annotation + a `GoldenLlm` helper (gateway `LlmClient`, AGENT.md/SKILL.md loaders, `NormalizedMessage`
> builder). The **fixtures and assertions stay unique per surface by design** — each validates that
> surface's own contract (routing token vs JSON shape vs grounded free-text), so only the plumbing is
> shared, never the assertions.

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

Re-validated on **`qwen3:8b`** when the deploy config moved off qwen2.5 (2026-07-13). qwen3 is a
*thinking* model — the one code change was `LLM_SUPPRESS_THINKING` (see the flag note above; the golden
lane sets it). With it, the surfaces above pass unchanged on qwen3:8b/CPU: orchestrator routing (2/2,
89s), `inbox-clarify` strict JSON (70s), `financial-advisor` synthesis (112s). Without it a routing call
runs ~144s and trips the harness's 90s block timeout — the reasoning tokens, not the answer, dominate.

## Run locally

```sh
# Build deps first (-am), then run the single module WITHOUT -am. `-am spring-boot:run` fails with
# "Unable to find a suitable main class" — with -am the run goal targets the parent reactor (no main).
mvn -B -pl platform/llm-gateway -am -DskipTests install
mvn -B -pl platform/llm-gateway spring-boot:run
# or run the packaged jar:
mvn -B -pl platform/llm-gateway -am -DskipTests package && \
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
- `config/LlmGatewayProperties` — `@ConfigurationProperties("llm")` (provider id, base-url, api-key, per-channel model ids, anthropic-version, max-tokens fallback, the `model-profile` block). Also holds the **runtime DEFAULT-model override** the profile switch applies: model resolution already lives here, so every provider follows a downshift without knowing the mechanism exists.
- `model/ModelProfileService` — the LC-4 swap: evict the outgoing model, confirm it left Ollama, adopt the incoming tag, warm it. Only bean that talks to Ollama's native API; absent unless `llm.model-profile.enabled`.
- `config/LangfuseProperties` — `@ConfigurationProperties("langfuse")` (enabled, base-url, public/secret key) for the trace export.
- `trace/LangfuseTracer` — fire-and-forget, soft-fail export of each chat call to Langfuse's batch ingestion API; no-op when `langfuse.enabled=false`.
- `provider/LlmProvider` — provider SPI.
- `provider/ProviderRegistry` — selects active provider via `LLM_PROVIDER`.
- `provider/mock/MockProvider` — deterministic echo + 384-dim CRC32-keyed embeddings; used in every other module's tests.
- `provider/anthropic/AnthropicProvider` — Anthropic Messages API (`POST /v1/messages`, SSE stream). Active when `LLM_PROVIDER=anthropic`; embeddings rejected as unsupported.
- `provider/openai/OpenAiCompatibleProvider` — OpenAI Chat Completions dialect (`POST /chat/completions` + SSE, `POST /embeddings`). Active when `LLM_PROVIDER=openai-compatible`; covers local Ollama / DeepSeek / Together / OpenAI.
- `web/ChatController` — `POST /v1/chat`, `POST /v1/chat/stream` (SSE).
- `web/EmbedController` — `POST /v1/embed`.
- `web/ModelProfileController` — `POST`/`GET /v1/model-profile`; conditional on the flag, so the path is a 404 when the feature is off. A failed switch answers 503/409/400 — never 2xx.
