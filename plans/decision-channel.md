# decision-channel — typed decisions instead of strict-JSON classifier prompts

Implementation plan for **[ADR-0010](adr/ADR-0010-typed-decision-channel.md) (Accepted 2026-10-29,
Option A / engine `kev-0.8b`)**. The ADR owns the *decision* and the evidence (§Spike result); this
file owns the *slicing* and is the spec each slice is judged against. In-flight status:
[STATUS.md](STATUS.md). Nothing here is built yet — **DC-a is the first slice.**

## What it is
A fifth `llm-gateway` channel, `decision`, served by a small open-weight **System One** model that
returns a calibrated probability per option instead of generating text. Every "classify / judge / route
/ gate / score" surface in the system can then ask a typed question and get a number back, instead of
prompting a general chat model for strict JSON and parsing the result.

It arrives as a **hybrid, not a replacement**: the decision model answers when its confidence clears
that surface's threshold, and everything below the threshold falls through to today's classifier. The
spike measured why (ADR §Recommendation): 83% on finance against a gate that asserts 6/6 would turn
that gate red on its own, while both misses arrived at 0.24/0.29 confidence against a 0.61 mean on
correct answers.

## The shape, settled by the ADR (do not re-derive)
A channel of `llm-gateway` reached through `libs/llm-client`, never a per-agent client · open weights,
self-hosted, no hosted API · never in the resident **GPU** budget · existing goldens as the acceptance
gate, with the baseline **re-measured over several runs in the same session** as the candidate · the
lenient fallback stays, so a decision-model failure degrades to today's behaviour. Rationale for each:
the ADR.

## Two constraints that shape the slices
1. **No compose `depends_on` edge to the engine.** The gate degrades to *undecided* when the engine is
   absent (DC-c), so hot agents must not declare a dependency on it — otherwise a cold engine would
   make `--profile hot` an invalid project, which `check-consistency.sh` check 13 rejects for the whole
   stack rather than one feature. The engine being optional is what keeps the hot set dependency-closed.
2. **CPU-pinned, by configuration.** The engine must not take GPU working set from the one universal
   model ([model-strategy.md](model-strategy.md) §One model for both tenants). It is a 0.8B model; CPU
   is enough and keeps the ~48 GB ceiling untouched. The exact knob comes from Decis's own `DECIS_*`
   configuration — confirm it at DC-a rather than guessing here. Disk cost is real: **~9.67 GB** for
   the engine image (weights inside, no startup download).

## PR slices

### DC-a — the engine as a cold backing service
**Requirement:** the stack SHALL be able to answer a typed question from a self-hosted engine that
costs nothing when unused.

- **Scenario: a typed question gets a calibrated answer**
  - WHEN a `choice` question with N named options is posted to the engine
  - THEN the response names one of exactly those N options and carries a probability per option
    (not yet asserted — DC-a not built)
- **Scenario: the engine is cold and optional**
  - WHEN the hot profile is brought up without the engine
  - THEN the compose project stays valid and every hot service starts (no `depends_on` edge; check 13
    stays green) (not yet asserted — DC-a not built)
- **Scenario: no credential, no answer**
  - WHEN a request arrives without the bearer token
  - THEN it is refused, and the token never appears in a log line (not yet asserted — DC-a not built)
- **Scenario: it does not touch the GPU budget**
  - WHEN the engine is loaded and serving
  - THEN it reports a CPU device and the GPU working set available to the chat model is unchanged
    (not yet asserted — DC-a not built, and this one is measured on the deploy host)

**Propagation:** `infra/docker-compose*.yml` (+ cold profile membership, no `depends_on`) ·
`infra/.env.example` + `infra/.env.mac.example` (engine URL, image tag, token) · `infra/README.md` ·
root `README.md` (layout) · [architecture.md](architecture.md) · this file.

### DC-b — the `decision` channel in `llm-gateway`
**Requirement:** callers SHALL ask typed questions through the gateway's logical channel, never the
engine directly.

Where it lands (so the slice needs no re-derivation): `LlmChannel` in
`libs/contracts/.../contracts/llm/` gains the new channel beside `DEFAULT`/`FAST`/`VISION`/`EMBEDDING`,
the request/response DTOs join that same package next to `LlmEmbedRequest`/`LlmEmbedResponse` (the
closest existing shape — a non-chat channel with its own endpoint), and `LlmClient` gains the one
method. A typed question/answer does not fit `/v1/chat`, so it gets its own gateway endpoint rather
than overloading that one.

- **Scenario: the channel answers a typed question**
  - WHEN a caller posts a `choice` question through `libs/llm-client` on the `decision` channel
  - THEN it receives the chosen option plus its confidence, over exactly the options it supplied
    (not yet asserted — DC-b not built)
- **Scenario: CI has no engine and still runs**
  - WHEN the provider is `mock`
  - THEN the channel returns a deterministic answer, so every test touching it stays model-free
    (not yet asserted — DC-b not built; the `mock` provider is load-bearing for CI)
- **Scenario: the engine is down**
  - WHEN the engine does not answer
  - THEN the call fails fast with a typed error the caller can branch on — it does not hang and it does
    not retry into the reply path's budget (not yet asserted — DC-b not built)
- **Scenario: the question does not fit the engine**
  - WHEN a question exceeds the engine's per-question token limit
  - THEN the caller gets a typed "too long" error naming the limit, not a truncated answer
    (not yet asserted — DC-b not built; the spike hit exactly this on the rejected engine, at 324
    tokens against a 256 limit)

**Propagation:** `platform/llm-gateway/README.md` (channel list + provider table + the new endpoint) ·
`libs/llm-client` + its README · `libs/contracts` for the request/response DTOs — **and because that is
a new cross-service wire contract, check 10 requires an `E2E…Test` in the same change** ·
`infra/.env{,.mac}.example` · [architecture.md](architecture.md) §LLM strategy ·
[model-strategy.md](model-strategy.md) (a fifth channel + the memory budget) · `.skills/change-map.yaml`
(the new coupling).

### DC-c — the confidence gate primitive in `agent-runtime`
**Requirement:** a surface SHALL be able to ask a typed question and get *decided* or *undecided*,
never an exception on the reply path.

- **Scenario: confident enough**
  - WHEN the answer's confidence is at or above that surface's configured threshold
  - THEN the gate returns the decision (not yet asserted — DC-c not built)
- **Scenario: not confident enough**
  - WHEN the confidence is below the threshold
  - THEN the gate returns *undecided* and the caller takes its existing path
    (not yet asserted — DC-c not built)
- **Scenario: the gate never sinks a reply**
  - WHEN the channel errors, times out, or the engine is absent entirely
  - THEN the gate returns *undecided* rather than throwing, and the flow continues
    (not yet asserted — DC-c not built)
- **Scenario: thresholds are per surface**
  - WHEN two surfaces with different option-set sizes use the gate
  - THEN each reads its own threshold from configuration, and no global default is applied silently
    (not yet asserted — DC-c not built; the spike measured correct answers at 0.11–0.58 on an
    18-option surface against 0.36–0.94 on a 10-option one)

**Propagation:** `libs/agent-runtime` + its README (a new shared primitive, so the "who uses me" surface
too) · this file.

### DC-d — pilot: `SkillClassifier` as a stage split behind the gate
**Requirement:** skill selection SHALL take the fast typed path when the gate is confident and today's
path otherwise, with no behaviour change until a flag is turned on.

- **Scenario: flag off, nothing changes**
  - WHEN the feature flag is unset
  - THEN routing behaviour is identical to today for all three consumers (finance, tasks, notes)
    (not yet asserted — DC-d not built; this is the scenario that makes the slice safe to merge)
- **Scenario: decided, a tool**
  - WHEN the gate decides the action is an MCP tool
  - THEN the generative stage fills that tool's arguments and the tool is dispatched
    (not yet asserted — DC-d not built)
- **Scenario: decided, a flow**
  - WHEN the gate decides the action is one of the agent's registered flows
  - THEN that flow runs, and a flow is never replaced by the raw tool underneath it — the
    `task-capture` / `task-delete` / `task-edit` / `task-status` discriminations hold
    (not yet asserted — DC-d not built; the spike got all four right where the incumbent flaked on one)
- **Scenario: undecided, fall through**
  - WHEN the gate is undecided
  - THEN the existing classifier runs and its result is used, including its lenient prose fallback
    (not yet asserted — DC-d not built)
- **Scenario: the gate it must pass**
  - WHEN the slice is proposed for merge
  - THEN the three consumers' routing goldens are green **and** the incumbent baseline was re-measured
    over several runs in the same session, so a candidate miss is distinguishable from an incumbent
    flake (not yet asserted — DC-d not built; the spike measured the tasks router green only 2 of 3
    runs, which is why this criterion is worded this way)

**Propagation:** `libs/agent-runtime` (the classifier seam) · the three consuming agents' READMEs (flag
+ threshold env) · `infra/.env{,.mac}.example` · [skills-vs-flows.md](skills-vs-flows.md) (#369's gate
weakens if selection stops needing a strong generative model — update the claim, don't restate it) ·
this file.

### DC-e — thresholds calibrated on the deploy host
**Requirement:** every threshold in configuration SHALL be traceable to the run that produced it.

- **Scenario: a threshold has a provenance**
  - WHEN a surface's threshold is set or changed
  - THEN the run it came from is recorded (engine tag, date, host, the frozen-set result file), so the
    number is auditable rather than folklore (not yet asserted — DC-e not built)
- **Scenario: re-measured, not inherited**
  - WHEN the engine tag changes
  - THEN the frozen set is re-run before the thresholds are trusted — the spike's numbers are
    float32-on-CPU from the dev box and bound nothing about the deploy host
    (not yet asserted — DC-e not built)

**Propagation:** `scripts/decision-spike/` README (it becomes the standing calibration harness, not a
one-off) · the consuming modules' READMEs · [model-strategy.md](model-strategy.md) §Rollout step 5.

### DC-f — follow-up surfaces, in order (not in the first pass)
Each is its own slice with its own criteria, authored when it starts:

1. **`LlmIntentClassifier`** (orchestrator) — same `choice` shape, bigger prize (every inbound
   message), bigger blast radius. The spike measured 92% on asserted labels here.
2. **`NoteWorthinessExtractor` pre-gate** — a `noul` question ("does this message contain a durable
   fact?") in front of the generative extractor. Off the reply path, and it retires the Mac-gate on
   [ambient-capture.md](ambient-capture.md) §MQ-3, because a 0.8B model runs where the big one cannot.
3. **The injection guard as defence-in-depth** — today a *fencing doctrine*
   (`UntrustedContent.GUARD`, check 7), not a classifier. Adding one is **new behaviour**, so "the
   goldens stayed green" cannot validate it; it needs its own criteria. Compare the `jevals`
   `IndirectInjection` shape.

## Out of scope / deferred
- **Retiring the `fast` channel.** Plausible once every classifier surface moves, and a direct
  ADR-0006 win — but it is a consequence to *measure*, not a goal to pursue. Not a slice here.
- **Option B (EdgeJev ONNX/INT8).** Recorded in the ADR as the likely successor behind the same wire
  contract; it becomes a slice only if latency on the deploy host says so.
- **Anything that writes.** This channel decides; it never acts. Outbound actions keep going through
  the confirm gate ([ADR-0004](adr/ADR-0004-confirm-act-flow.md)).
