# ADR-0010: Typed-decision channel (`decision`) — a System One model instead of strict-JSON classifier prompts

**Status:** Proposed (2026-10-28 — awaiting owner pick between Option A and Option B below; Option C is
the status quo). Nothing is built against this ADR yet.
**Date:** 2026-10-28
**Deciders:** repo owner (holder/admin)
**Relates to:** [architecture.md](../architecture.md) §LLM strategy (the channel doctrine + the
provider-switch rule this must not break) and §Security (the injection doctrine a later consumer would
touch), [ADR-0006](ADR-0006-runtime-topology-footprint.md) (the RAM budget), [model-strategy.md](../model-strategy.md)
(the four channels + the `fast` pick this would displace), [skills-vs-flows.md](../skills-vs-flows.md)
(#369 Bucket 2, gated on "a stronger local MoE default"), [ambient-capture.md](../ambient-capture.md)
§MQ-3 (the precision gate, currently Mac-gated), [lifecycle.md](../lifecycle.md) (hot/cold placement).

## Context

A large share of this system's LLM calls are **not generation — they are judgement over a closed option
set**. Each one is implemented today the same way: build a prompt, ask a general chat model, demand strict
JSON, parse it, and tolerate the ways a text model fails at being a classifier.

The surfaces, with their real seams:

| Surface | Decision | Shape |
|---|---|---|
| `LlmIntentClassifier` (orchestrator) | which agent owns this message | choice over the manifest roster |
| `SkillClassifier` (`libs/agent-runtime/intent/`) | MCP tool vs one of the agent's flows vs plain chat | choice over a closed action set |
| `NoteWorthinessExtractor` (memory-service) | worth keeping? explicit cue or inferred? important or trivial? | yes/no + choice, mixed into an extraction |
| `DefaultSharingPolicy` / `SharingConfirm` (ADR-0002 DS-N) | personal or shared, or ambiguous enough to ask | choice + a confidence threshold |
| coach safety gate (`GoldenSafetyGateTest`) | is this input one the agent must not answer | yes/no |
| sanity spot-checks (architecture.md §Principles) | is this value impossible | yes/no (already deterministic, no LLM — the model-free end of the same spectrum) |

**The cost is visible in the code, not hypothetical.** `SkillClassifier` carries a lenient fallback for
bodies that are not routing JSON ("a chatty model that answers in prose never breaks the agent" — but it
does silently become a `chat` reply instead of the tool call the user asked for); a *format-drift
tolerance* branch for the flattened `{"action":"<toolName>"}` shape "smaller models emit" (a Stage 5
golden finding); and an `extraRules` slot whose documented purpose is finance's "enum-pinning that stops
a 7B inventing action values". Strict-JSON skills additionally need `LLM_SUPPRESS_THINKING` because the
default model thinks by default. Every one of these is a workaround for using a text generator as a
classifier.

**What changed in 2026.** A separate model class is now established for exactly this job — *System One* /
typed-decision models. They generate no text: the input is unstructured state plus typed questions
(`noul` yes/no, `choice` multiple-choice, `score` rating), the output is a **calibrated probability per
option**, several questions batched in one request, in one forward pass. The reference implementation
(TypeSafe's Jev) is a hosted API, which is disqualifying here, but the open side is real and Apache-2.0:

- **[kev](https://github.com/jaredpalmer/kev)** — weights at 0.8B / 4B / 9B / 27B built on Qwen3.5/3.8,
  fitted temperature per checkpoint, **MLX on Apple Silicon**, API-compatible with System One.
- **[Decis](https://github.com/chaitin/Decis)** — a self-hostable inference server implementing the
  `/v1/systemone` contract, one multi-arch Docker image per engine with the **weights baked in** (no
  volume, no startup download), engines `laya-multilingual` / `kev-0.8b` / `jeff-*`. Pre-1.0 (v0.4.0);
  the `v1` wire contract is declared stable and additive.
- **[EdgeJev](https://github.com/yzfly/edgejev)** — ONNX + INT8 on plain CPU: **15.6 ms per question on
  4 cores**, 324 MB on disk, offline, no torch at runtime.
- **[jevals](https://github.com/openlayer-ai/jevals)** — evals/guardrails (`IndirectInjection`,
  `Grounded`, `ToolChoice`, `StayedInScope`) expressed as typed decisions, one request per trace.

### Forces / constraints

- **Local-first is non-negotiable.** Household data does not leave the box, so the hosted Jev API is out
  by construction. This ADR is about **open weights, self-hosted** only.
- **Footprint ([ADR-0006](ADR-0006-runtime-topology-footprint.md), #584).** The active epic is freeing
  RAM for the model, and the 64 GB box has a ~48 GB GPU ceiling shared with the coder tenant. A
  decision model is 0.8B — or, on the ONNX/INT8 path, 324 MB on **CPU**. So a decision channel can be
  added **without taking any GPU budget**, and it may *remove* some: `fast` (~6 GB, the strict-JSON
  classifier surface) exists mainly to serve these calls.
- **Not hardware-gated — this is the unusual part.** Almost everything left in `STATUS.md` waits for the
  Mac. This does not: the model is small enough for the CPU-only dev VDI, and the acceptance gate already
  runs there (`SkillClassifierTest` plus the cheap golden subset — `Golden*RoutingTest` is explicitly the
  part that is runnable at ~7.4 tok/s). It can be built and proven *before* the migration.
- **The provider-switch rule.** Everything LLM goes through `llm-gateway` by logical channel, swapped by
  env var, never a per-agent client (architecture.md §Principles). A decision engine must enter as a
  **channel**, or it becomes the first exception to that rule.
- **Engine-independent of the chat stack.** The decision engine is its own process behind its own
  contract, so it is unaffected by whether the chat channels are served by Ollama, LM Studio or anything
  else — and it does **not** interact with the LC-4 evict-before-load handshake, which is about the two
  big resident models.
- **Tooling simplicity + OSS reuse** ([[feedback_tooling_simplicity]],
  [[feedback_oss_reuse_shared_capabilities]]): run upstream behind a thin seam, do not rebuild.
- **Polyglot-by-design has never actually been exercised.** The repo has **zero** own non-JVM services
  today (no `pyproject.toml`, no non-JVM Dockerfile among 71 modules). The principle is sound, but the
  first own Python module pays a real one-off cost (toolchain, CI, image, ops) that no existing module
  has paid. That cost belongs in this decision, not discovered during the slice.
- **The field is weeks old.** Decis is v0.4.0; the open-weight family (kev, laya, NanoJev, Jeff, Eikos)
  appeared between September and October 2026 and is churning. The durable thing to depend on is the
  **wire contract** (`/v1/systemone`), not any one engine or vendor.

## Decision (to be picked by the owner)

**Settled, independent of the engine pick:**

1. It enters as a **fifth `llm-gateway` channel — `decision`** — beside `default` / `fast` / `vision` /
   `embedding`, reached through `libs/llm-client` like every other channel. No agent gets its own client.
2. **Open weights, self-hosted.** No hosted decision API, now or later.
3. **Never in the resident GPU budget.** Either a cold container or the CPU/ONNX path; it must not become
   a third resident model competing with the MoE pair.
4. **The existing goldens are the acceptance gate.** A swap ships only when the surface's current goldens
   stay green; "the decision model is better" is not a claim this repo accepts without the lane.
5. **The lenient fallback stays.** A decision-model failure must degrade to today's behaviour (`Chat`),
   not to an exception on the reply path.

**Open — the engine:**

### Option A (recommended): Decis container serving `/v1/systemone`
A `decision` provider in `llm-gateway` calling a **Decis** container (`laya-multilingual` for Russian, or
`kev-0.8b`), weights inside the image. One new backing service, no new language in the repo, engine
swappable by image tag.

- **Pros:** speaks the de-facto wire contract, so the engine is replaceable later (including *by* Option B)
  without touching the channel; weights-in-image matches the "one container, no volume" shape ADR-0009
  settled for the object store; stays entirely inside the existing Java + Compose world.
- **Cons:** pre-1.0 upstream (v0.4.0); a new kind of backing service to operate; Russian calibration is
  **unproven** and must be measured before anything is wired.

### Option B: EdgeJev / ONNX-INT8 on CPU behind a thin Python leaf
Convert an open checkpoint (export → INT8 quantize → serve) and run it on CPU: ~324 MB, ~15.6 ms per
question, offline, no torch.

- **Pros:** the smallest and fastest option; takes **zero** GPU budget by construction; fully offline;
  survives the box being busy with the MoE pair.
- **Cons:** this becomes the repo's **first own non-JVM module** and pays that cost first; the conversion
  pipeline is an extra artifact to own and re-run per model bump; the upstream project is very small.

### Option C: status quo — keep strict-JSON prompts on the `fast` channel
Rejected as the target, kept as the **fallback**: if Russian calibration on open weights turns out worse
than the current prompt path, this is where we stay, and the recorded workarounds are the accepted price.

### Recommendation
**Option A**, gated on a measurement. It adds a capability without adding a language, and the wire
contract means picking A does not foreclose B — a later swap to the ONNX/CPU engine is a provider change
behind the same channel. But the ADR explicitly refuses to assume Russian accuracy: step 1 of the action
items is a **spike that measures calibration on a frozen set drawn from the existing goldens**, on the
current hardware, before any `decision` channel code is written.

## Options Considered

| Dimension | A — Decis container | B — EdgeJev CPU leaf | C — status quo |
|---|---|---|---|
| GPU budget | none (cold container) | **none, by construction** | ~6 GB (`fast`) |
| Latency per decision | ~200–500 ms class | **~15.6 ms / question** | a full chat round-trip |
| New language in repo | no | **yes (first one)** | no |
| Engine swappable | **yes — `/v1/systemone`** | yes (same contract) | n/a |
| Upstream maturity | v0.4.0, pre-1.0 | very small project | n/a |
| RU calibration | **unproven — must measure** | unproven — must measure | known (today's goldens) |
| Strict-JSON failure class | gone | gone | present (drift, enum invention, prose) |

## Pilot — `SkillClassifier` (`libs/agent-runtime/intent/`)

The owner asked for the first site. It is **`SkillClassifier`**, for five reasons:

1. **The question type matches natively.** Its outcome is a closed `choice` — one of the agent's MCP
   tools, one of its registered flows, or `chat`. That is a `choice` question with the options already
   enumerated at the call site.
2. **Smallest blast radius for the widest proof.** It is one shared class behind three consumers
   (finance, tasks, notes), so a single change is validated three ways without touching three codebases.
3. **The gate already exists and already runs here.** `SkillClassifierTest` (pure string/JSON unit test)
   plus `GoldenRoutingTest` ×3 and `GoldenMisrouteRepairTest` — all in the cheap, VDI-runnable subset.
   No new eval harness is needed to judge the pilot.
4. **It de-risks an existing blocker.** #369's Bucket 2 cutover is gated on "a stronger local MoE
   default" precisely because skill *selection* is the weak link. If selection stops needing a strong
   generative model, that gate weakens on its own.
5. **Its workarounds are the exhibit.** The format-drift branch, the enum-pinning rule and the prose
   fallback all live in this one class; a calibrated distribution over a closed set removes the entire
   failure class rather than tuning around it.

**Honest caveat — it is a seam split, not a drop-in swap.** `SkillClassifier` today returns the action
*and* its arguments (`args`, `period`, the intent-skill `name`, or the `chat` text). A decision model
produces no text, so the seam becomes two stages: **(1)** the decision model picks the action from the
closed set; **(2)** the LLM fills that action's arguments, or writes the chat reply — unchanged, and only
for the branch that needs it. The "message lacks required arguments → ask the user" rule becomes a second
question batched into the same request, which is free in this model class.

**Rejected as the first site (ordered as the follow-ups):**
- **`LlmIntentClassifier`** — identical shape and a bigger prize (every inbound message), but it is the
  global hot path with a candidate set built dynamically from manifests. Second, once the pilot holds.
- **`NoteWorthinessExtractor`** — attractive because it is off the reply path and because MQ-3's precision
  flip is Mac-gated today, which a small local model would unblock on current hardware. But its call mixes
  generation (title/body/subject) with judgement, so the decision model can only act as a **pre-gate**
  ("does this message contain a durable fact?") before the generative extractor. Third — and the one that
  retires a parked item.
- **The injection guard** — today a *fencing doctrine* (`UntrustedContent.GUARD`, check 7), not a
  classifier. Adding one is **new behaviour**, so "the goldens stayed green" cannot validate it. Worth
  doing later as defence-in-depth (cf. `jevals`' `IndirectInjection`), under its own acceptance criteria.

## Consequences

**Easier:**
- A **threshold in code** instead of prompt-tuning: "ask when unsure" (ADR-0002 DS-N) becomes a number,
  not a phrasing. Confidence is the thing these models return.
- An entire bug class disappears — strict-JSON drift, invented enum values, prose-instead-of-JSON, and
  the `LLM_SUPPRESS_THINKING` dance on classifier surfaces.
- **Batching makes multi-question gates nearly free**: sharing scope + sanity + worthiness in one request
  instead of three chat round-trips.
- The `fast` channel's reason to exist shrinks; if every classifier surface moves, it can be retired from
  the resident budget (a direct ADR-0006 win, to be measured, not assumed).
- A real eval surface for guardrails for the first time (jevals-style, per-trace rather than per-sample).

**Harder / to watch:**
- A **new channel in the provider matrix**: `MockProvider` needs a deterministic decision stub or every
  test touching the channel becomes model-dependent — the `mock` provider is load-bearing for CI.
- A **new backing service** (Option A) or **the first non-JVM module** (Option B). Either is a genuine
  addition to what the stack operates.
- **Russian calibration is the open risk.** Both options ride small models; small + multilingual is
  exactly where accuracy degrades. The spike exists to find this out cheaply.
- **Vendor/engine churn.** Depend on `/v1/systemone`, pin an engine by image tag or checkpoint hash, and
  expect to re-measure at each bump. Treat every figure quoted above as directional and re-verify.
- The `decision` channel is **additive to**, not a replacement for, the deterministic guards. A sanity
  invariant that needs no model keeps needing no model.

**Change propagation when this is sliced** (the coupling to record in `.skills/change-map.yaml`):
`platform/llm-gateway/README.md` (channel list + the provider table) · `libs/llm-client` (the channel
surface) · `infra/.env.example` + `infra/.env.mac.example` (engine URL/tag) · `infra/docker-compose*.yml`
(+ its hot/cold profile membership and the dependency closure the lint's check 13 enforces) ·
[architecture.md](../architecture.md) §LLM strategy · [model-strategy.md](../model-strategy.md) (a fifth
channel + the memory budget) · [INDEX.md](../INDEX.md) · `scripts/golden.sh` if the lane needs the engine
up.

## Action Items
1. [ ] **Spike first, on current hardware: measure Russian calibration.** Freeze a set from the existing
   routing goldens, run it against `laya-multilingual` and `kev-0.8b`, and report accuracy + the
   confidence distribution against today's prompt path. A spike, not a slice — no channel code, no
   compose entry. **This is the gate for the whole ADR**, and it needs no Mac.
2. [ ] **Owner picks Option A or Option B** on the spike's numbers (or C — stay put). This ADR moves to
   Accepted with the pick recorded. No `architecture.md` §Locked-decisions entry until then.
3. [ ] **Slice the channel** — WHEN/THEN criteria first in a new `plans/decision-channel.md`, then the
   `decision` channel in `llm-gateway` + the `mock` stub, then `SkillClassifier` stage-1 behind a flag
   with the existing goldens as the gate. One PR per slice, change-propagation as listed above.
4. [ ] **Then the follow-ups in order** — `LlmIntentClassifier`, then the `NoteWorthinessExtractor`
   pre-gate (which also retires the MQ-3 Mac-gate), then the injection guard as defence-in-depth under
   its own criteria.

## Notes
Tenth ADR. It introduces a **new layer** (a model class the system has never used, and a fifth channel),
so per CLAUDE.md "new layer / pattern / architectural concept → flag it BEFORE coding" it is an ADR first.
Until Accepted it is a proposal; no `decision`-channel code exists.

**Sources (as-of 2026-10-28; a field only weeks old — treat all figures as directional and re-verify at
implementation):** TypeSafe System One API docs (the `/v1/systemone` contract, `noul`/`choice`/`score`);
`jaredpalmer/kev` (Apache-2.0 weights 0.8B–27B on Qwen3.5/3.8, MLX); `chaitin/Decis` (Apache-2.0,
v0.4.0, weights-in-image engines incl. `laya-multilingual`); `yzfly/edgejev` (ONNX/INT8, 15.6 ms on 4
CPU cores, 324 MB); `openlayer-ai/jevals` (MIT, guardrails as typed decisions);
`yanng981/awesome-system-one` (the open-alternative roster: laya, kev, Von, Decider, Tev1).
