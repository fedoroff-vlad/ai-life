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

**memory-from-chat (MFC-b producer):** on every inbound `/v1/intent`, the orchestrator
**fire-and-forgets** the message text to memory-service's durable `POST /v1/observations`
drop-point (`MemoryClient.observe`) so durable facts get learned passively. Off the
response path (`.subscribe()`), never affects routing, soft-fail — same posture as recall.
Skipped when memory is disabled, no household, or the text is blank (an attachment-only
message — those facts are captured by the agent that processes the attachment; capture is
distributed by design).

**Route-lock lifecycle (Stage 4 / A2+A3):** before classifying, the orchestrator asks
conversation-service (`GET /v1/conversation-state`) whether this `(household, user, channel)` has an
active route-lock. If it does and names a known agent, the message is a reply to that agent's open
question — it's sent to that agent's **`POST /agents/<name>/resume`** with the stored
`pendingAction`, bypassing classification. No lock / unknown-agent lock / conversation-service
unreachable (soft-fail to empty) → normal classification. After the agent replies: a non-null
`IntentResponse.pendingAction` **locks** the conversation to it (the agent is awaiting a reply); a
resume turn that returns no pendingAction **clears** the lock (resolved). Lock writes/clears are
soft-fail — a confirmation that can't be persisted just won't survive, never a user-facing error.
Toggle with `orchestrator.conversation.enabled`. (Agents only start producing `pendingAction` /
exposing `/resume` in the per-flow A4 slices — until then this is latent.)

**Misroute-repair (road-test #484):** after a fresh specialist reply with no open question, the
orchestrator records the chosen agent + the message text to conversation-service as `last_route` (a
short `orchestrator.conversation.correction-window-seconds` TTL, default 180s; echo turns are never
recorded). On the next turn — when there is **no** active lock — that `last_route` is passed into the
classifier as a `PriorRoute`, so a correction ("не то, я про задачи") re-classifies **with the prior
route as context** and routes to the corrected intent instead of being treated as a fresh message. It
stays one classify turn (no keyword heuristic, paraphrase-robust). A correction that lands on a
different agent is logged as a `routing-correction` signal (agent_from → agent_to + phrasing).
`routeLock` still takes priority — an open question resumes and last-route is not consulted.

**Catch-all routing:** `orchestrator.catch-all-agent` (default `tasks`) names the agent
that captures any *actionable* message matching no specialized domain — the GTD
"anything not calendar/finance → inbox" fallback. When set to a registered agent the
classifier prompt routes such messages there and reserves `echo` for greetings / small
talk. It **self-disables to `echo`** if the named agent isn't registered, and the
deterministic fallbacks (LLM error, un-parseable output, no remote agents) always stay
`echo`.

**Multi-domain routing (#290):** a request that spans several domains at once routes to the
`coordinator` agent — the cross-cutting synthesis engine that reads the second brain and answers
in one shot. This is **pure data-driven routing**: `coordinator` is just another registered agent
whose manifest description tells the classifier to pick it for cross-cutting messages, so there is
**no orchestrator code for it** (cheap-first is preserved — single-domain messages still classify
to their one specialist). Agent-led coordination keeps the orchestrator a thin router.

## Endpoints

| method | path                  | purpose                                        |
|--------|-----------------------|------------------------------------------------|
| POST   | `/v1/intent`          | route a `NormalizedMessage` to an agent        |
| POST   | `/v1/agents/wake`     | dispatch a scheduler wake to an agent          |
| POST   | `/v1/agents/invoke`   | one agent asks another to perform an action    |
| GET    | `/actuator/health`    | liveness                                       |

`/v1/intent` request: `NormalizedMessage` (`libs/contracts/agent`); response: `IntentResponse` `{ agent, text, llmModel }`.
`/v1/agents/wake` request: `AgentWakeRequest` (`libs/contracts/schedule`); response: 202 on accept, 404 if agent unknown.
`/v1/agents/invoke` request: `AgentActionRequest` (`libs/contracts/agent`); response: `AgentActionResult` (200), 404 if `targetAgent` unknown.

**Inter-agent sync (Stage 4 / C1):** agents never call each other directly — the
orchestrator is the single sync hub. `/v1/agents/invoke` looks up `targetAgent` and
forwards to its `POST /agents/<name>/actions/<action>`, relaying the `AgentActionResult`
verbatim. First use: tasks-agent → calendar-agent `create_event` (turn a hard-deadline
task into a calendar event). A local agent with no actions returns an "unsupported" error
result (`ok=false`) via the default `Agent.invoke`.

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
ORCHESTRATOR_CONVERSATION_CORRECTION_WINDOW_SECONDS=180  # TTL of a recorded last_route for misroute-repair (#484)
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

**Golden test (Stage 5 / #199):** `routing/GoldenRoutingTest` exercises agent routing
against a **real model** (local Ollama `qwen3:8b` via a running llm-gateway),
asserting *structure, not text* — crisp domain messages reach the right agent and every
message resolves to a known agent name. Opt-in (`@Tag("golden")` +
`@EnabledIfEnvironmentVariable(GOLDEN_LLM)`), **skipped in CI**. Run instructions live in
the test's javadoc; it's the top-of-spine companion to finance-agent's `GoldenRoutingTest`.
`routing/GoldenMisrouteRepairTest` is the sibling for misroute-repair (#484): same gating, it proves a
real model honours a `PriorRoute` correction (re-routes "не то, это задача"; leaves an unrelated message
alone). Both reuse `GoldenRoutingTest.realManifests()`.

## Key classes
- `OrchestratorApplication`.
- `agent/Agent` — interface; default `wake()` no-op.
- `agent/RemoteAgent` — HTTP impl over a per-agent cloned `WebClient`.
- `agent/EchoAgent` — fallback when classification fails to pick anyone.
- `agent/AgentRegistryProperties` — `orchestrator.agents[]` config.
- `agent/AgentDiscovery` — startup manifest fetch.
- `agent/AgentRegistry` — name → `Agent` map; backs both routing and wake dispatch.
- `memory/MemoryProperties` — `orchestrator.memory.{enabled, url, recall-k}`.
- `memory/MemoryClient` — reactive POST `/v1/memories/recall` with 500 ms timeout; strict no-throw (errors → `Mono.just(List.of())`). Also `observe(...)` — fire-and-forget POST `/v1/observations` (memory-from-chat producer), soft-fail, off the response path.
- `routing/IntentRouter` — `NormalizedMessage` → `Agent` decision; owns the route-lock lifecycle and the misroute-repair last-route recording + `PriorRoute` hand-off (#484).
- `routing/LlmIntentClassifier` — FAST-channel call with few-shot built from manifest `intents[]`; injects recalled memories as a second system message when non-empty, and a `PriorRoute` correction message as a third (#484). Lenient parsing + echo fallback. `classify(msg)` = `classify(msg, null)`.
- `web/IntentController` — `POST /v1/intent`.
- `web/AgentWakeController` — `POST /v1/agents/wake`.
- `web/AgentInvokeController` — `POST /v1/agents/invoke`; inter-agent sync, forwards to the target agent's `/actions/<action>` (`Agent.invoke` / `RemoteAgent.invoke`).
