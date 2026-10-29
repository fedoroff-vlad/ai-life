# ADR-0010: Typed-decision channel (`decision`) — a System One model instead of strict-JSON classifier prompts

**Status: ACCEPTED 2026-10-29 — Option A with the `kev-0.8b` engine.** The owner picked it on the
spike's numbers (§Spike result: `laya-multilingual` rejected on measurement, `kev-0.8b` at 83–100% on
asserted labels). Option B (EdgeJev ONNX/INT8) stays recorded as the likely *successor* behind the same
wire contract, not as a rejected alternative — the ~100× CPU latency gap is the reason. Option C
(status quo) is the fallback if a slice's acceptance gate cannot be met.
**Implementation plan:** [decision-channel.md](../decision-channel.md) — slices DC-a…DC-f with
WHEN/THEN criteria. This ADR stays the SSOT for the *decision*; the plan owns the *slicing*.
**Date:** 2026-10-28 · spike appended 2026-10-29 · accepted 2026-10-29
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

## Spike result (2026-10-29) — action item 1, run on the CPU-only dev box

Harness, frozen set and raw runs: [`scripts/decision-spike/`](../../scripts/decision-spike). 45 cases
lifted **verbatim** from four golden tests across four families; the headline metric is accuracy on the
labels a golden **asserts**, with inferred labels reported separately. Engines were `chaitin/decis`
images on this box: `device: cpu`, `dtype: float32` — no quantization attempted.

### laya-multilingual (the Decis default) — rejected on measurement

| Family | Options | Asserted accuracy | Notes |
|---|---|---|---|
| orchestrator (RU) | 10 | **33%** (4/12) | and confidently wrong: "Я потратил 12 евро на кофе" → `chef` @0.94, "Запиши мой обед" → `chef` @0.99 |
| orchestrator (EN control) | 10 | **58%** (7/12) | the control uses the manifests' own few-shot examples — the easiest English input there is |
| finance / tasks | 22 / 18 | **not expressible** | HTTP 422: the question is 324 / 311 tokens against this engine's 256-token per-question limit |

Three disqualifying findings. It is weak at the task in **both** languages, so this is not a Russian
problem. It is **anti-calibrated** on the English control — mean confidence 0.65 on correct answers vs
**0.69 on wrong ones** — which removes the "threshold in code" benefit entirely. And its 256-token
ceiling cannot hold the pilot's real option set; Russian criteria do not fit even the 10-option surface
(272 tokens), because Cyrillic costs more tokens for the same text.

### kev-0.8b — viable

| Family | Options | Asserted | All labelled | conf. correct / wrong | server ms |
|---|---|---|---|---|---|
| tasks-skill | 18 | **100%** (7/7) | 89% | 0.27 / 0.24 | ~2010 |
| orchestrator (RU) | 10 | **92%** (11/12) | 93% | 0.67 / 0.61 | ~1500 |
| orchestrator (EN control) | 10 | **92%** (11/12) | 92% | 0.68 / 0.38 | ~1442 |
| finance-skill | 22 | **83%** (5/6) | 78% | 0.61 / 0.26 | ~2015 |

It gets the **hard discriminations** right — the ones today's prompt needs `extraRules` and the
format-drift branch to survive: `task-capture` not the `add_task` tool, `task-delete` not
`delete_task`, `task-edit` not `update_task`, `task-status` not `clarify_task`, and the
`nutritionist` vs `chef` confusable pair in both languages.

Russian ≈ English (93% vs 92%) **despite kev's own model card saying "English (checkpoints are trained
on English decision datasets)"**. Treat that as an empirical result on 27 Russian cases, not a
supported guarantee — re-measure on every engine bump.

**What the misses say.** Both finance misses are "заведи (новую) карту" → `chat` at **0.24 / 0.29**
confidence against a 0.61 mean on correct answers: a low-confidence miss is one a threshold can catch.
The single orchestrator miss ("напомни мне пожалуйста вечером позвонить маме" → `calendar` @0.61) is
confidently wrong, on the known tasks-vs-calendar boundary. The only tasks miss is on an **inferred**
label where the engine's answer (`complete_task` for "отметь задачу про молоко выполненной") is
defensible — more likely a mislabel in the frozen set than a model error. The #395 calibration case
("какой баланс на карте?", no account named) comes back `get_balance` @0.85: the engine does **not**
reproduce that ambiguity as uncertainty, which is consistent — #395's ambiguity is about *which
account*, an argument-level question, not the routing choice — but it shows confidence will not catch
everything today's path defers on.

### Three measurements that change this ADR

1. **Batching is not free.** Three questions in one request cost ~2.6–2.8× a single question
   (kev 1.48 s → 3.91 s; laya 0.28 s → 0.82 s). The "nearly free multi-question gate" claim in
   §Consequences was wrong and is corrected there.
2. **Confidence is not comparable across surfaces.** tasks (18 options) answers *correctly* at
   0.11–0.58 while finance (22) is correct at 0.15–0.88 and orchestrator (10) at 0.36–0.94. More
   options spread the mass thinner, so a threshold must be **per surface**, calibrated from that
   surface's own run — never one global number.
3. **"Weights in the image" costs ~10 GB of disk per engine** (laya-multilingual 7.39 GB, kev-0.8b
   9.67 GB, both carrying a CUDA torch this box cannot use). Still the right trade for a 24/7 host —
   no download, no volume, no startup fetch — but it is not free, and it is per engine.

### Latency, and why this number is not the deploy number

1.4–2.0 s per decision is **float32 on a CPU with no GPU**. It bounds nothing about the M4 Max (MPS),
and it sits ~100× above EdgeJev's INT8/ONNX claim of 15.6 ms per question on 4 CPU cores. That gap is
now the biggest open question this spike produced, and it argues for taking **Option B seriously**
rather than treating it as the exotic one.

Against today's path on the same box, same day: three runs of `GoldenRoutingTest` in tasks-agent took
119.6 s (behaviour test, 8 model calls — cold), 142.2 s and 78.52 s (both tests, 14 model calls). That
is **≈5.6 s per classifier call warm and ≈15 s cold**, against kev's 1.4–2.0 s on the same cases: a
**3–7× latency win** even in the unquantized float32-on-CPU worst case. Both numbers are
wall-clock-per-call derived from totals, not instrumented per call — treat them as the right order of
magnitude, not as precise figures.

### The baseline is flaky, which is its own finding

The tasks routing golden was run three times on this box. **It failed once and passed twice.** The
failure: «переведи задачу про врача в ожидание» routed to the `clarify_task` **tool** instead of the
`task-status` **flow** — precisely the discrimination the test's own comment calls out (#486/Track
H.2), and precisely the case kev-0.8b answered correctly (at low confidence, 0.13).

So this is **not** a standing regression — one run in three, i.e. model nondeterminism on a borderline
case, the same class of flake #395 recorded for finance's account-less balance request. Two things
follow, and they are worth more than the single red run:

- **`@GoldenLlmTest` is opt-in** (`GOLDEN_LLM` unset in CI, so the suite stays green on the mock
  provider), so **nothing runs this gate automatically** and a 1-in-3 flake can sit unnoticed. "The
  goldens are green" was an assumption; measured, it is "green about two times in three on this box".
- Therefore the acceptance criterion in this ADR — "a swap ships only when the surface's current
  goldens stay green" — must be restated as: **the baseline is re-measured over several runs in the
  same session as the candidate.** A single comparison run cannot distinguish a candidate's miss from
  the incumbent's flake.

A borderline case the incumbent gets right two times in three and the candidate gets right at 0.13
confidence is, in the end, an argument for the hybrid: neither path is reliable alone, and the
confidence number is what lets the system know which one to trust.

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
A `decision` provider in `llm-gateway` calling a **Decis** container, weights inside the image. One new
backing service, no new language in the repo, engine swappable by image tag. **Engine pinned by the
spike: `kev-0.8b`.** The default `laya-multilingual` is rejected on measurement (33% RU / 58% EN,
anti-calibrated, and its 256-token per-question limit cannot express the pilot's option set).

- **Pros:** speaks the de-facto wire contract, so the engine is replaceable later (including *by* Option B)
  without touching the channel; weights-in-image matches the "one container, no volume" shape ADR-0009
  settled for the object store; stays entirely inside the existing Java + Compose world. Measured 83–100%
  on asserted labels and ~7× faster than today's path even unquantized on CPU.
- **Cons:** pre-1.0 upstream (v0.4.0); a new kind of backing service to operate; **~10 GB of disk per
  engine image**; the engine serves float32 on CPU with no quantization path of its own, so the Mac's MPS
  is doing the heavy lifting for latency; and kev's Russian competence is empirical (its card says
  English-only), so it must be re-measured on every bump.

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

### Recommendation (updated by the spike)
**Option A with the `kev-0.8b` engine.** The measurement that this recommendation was gated on has run:
Russian accuracy is 83–100% on asserted labels, the hard tool-vs-flow discriminations hold, and
confidence separates correct from wrong answers on three of four surfaces. Picking A does not foreclose
B — a later swap to an INT8/ONNX engine is a provider change behind the same channel, and the 100×
latency gap between Decis-on-CPU and EdgeJev's claim means B is now a *likely* follow-up rather than an
exotic one.

**But not as a straight replacement.** 83% on finance against a gate that asserts 6/6 means a bare
stage-1 swap would turn that gate red. The shippable shape the spike found is a **confidence-gated
hybrid**: the decision model answers when its confidence clears that surface's threshold, and anything
below falls through to today's LLM classifier. On finance the two misses came back at 0.24/0.29 against
a 0.61 mean on correct answers, so a threshold near 0.4 keeps the gate green while taking the fast path
for most traffic. This is strictly better than either path alone and it is measurable per surface.

## Options Considered

Figures marked **measured** come from §Spike result on the CPU-only dev box; the rest are upstream
claims.

| Dimension | A — Decis container (`kev-0.8b`) | B — EdgeJev CPU leaf | C — status quo |
|---|---|---|---|
| GPU budget | none (cold container) | **none, by construction** | ~6 GB (`fast`) |
| Latency per decision | **measured 1.4–2.0 s** (float32, CPU, no GPU) | ~15.6 ms / question (claim, INT8) | **measured ≈5.6 s warm, ≈15 s cold** |
| Disk | **measured 9.67 GB** (image, weights inside) | ~324 MB (claim) | none extra |
| New language in repo | no | **yes (first one)** | no |
| Engine swappable | **yes — `/v1/systemone`** | yes (same contract) | n/a |
| Upstream maturity | v0.4.0, pre-1.0 | very small project | n/a |
| RU accuracy, asserted labels | **measured 83–100%** | unproven — must measure | **measured: tasks gate green 2 of 3 runs** |
| Confidence separates right from wrong | **measured: yes on 3 of 4 surfaces** | unproven | n/a — no confidence at all |
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

**The spike confirmed the site and changed its shape.** `kev-0.8b` scored 100% (7/7) on the tasks
half of this surface and 83% (5/6) on the finance half, with both finance misses arriving at low
confidence — so the site is right, but the *mechanism* is the confidence-gated hybrid from
§Recommendation, not a bare replacement. Each surface gets its own threshold, calibrated from its own
run; the fall-through target is the existing classifier, which is also the safety net that keeps the
gate green.

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
  not a phrasing. Confidence is the thing these models return — and the spike measured that it
  separates correct from wrong answers on three of four surfaces, **per surface, not globally**.
- An entire bug class disappears — strict-JSON drift, invented enum values, prose-instead-of-JSON, and
  the `LLM_SUPPRESS_THINKING` dance on classifier surfaces.
- **Batching is cheaper than separate chat round-trips, but it is not free** (corrected by the spike:
  3 questions ≈ 2.6–2.8× the cost of 1, not ≈1×). Sharing scope + sanity + worthiness in one request
  still beats three chat calls by a wide margin; just do not design as if extra questions were free.
- The `fast` channel's reason to exist shrinks; if every classifier surface moves, it can be retired from
  the resident budget (a direct ADR-0006 win, to be measured, not assumed).
- A real eval surface for guardrails for the first time (jevals-style, per-trace rather than per-sample).

**Harder / to watch:**
- A **new channel in the provider matrix**: `MockProvider` needs a deterministic decision stub or every
  test touching the channel becomes model-dependent — the `mock` provider is load-bearing for CI.
- A **new backing service** (Option A) or **the first non-JVM module** (Option B). Either is a genuine
  addition to what the stack operates.
- **Russian calibration — measured for `kev-0.8b`, still the risk for every other engine.** 92–100% on
  asserted labels, and ≈ its English score. But kev's card claims English only, so this is empirical;
  and the engine that *advertises* multilingual support (laya) scored 33%. Never adopt an engine here on
  its card — re-run the frozen set.
- **~10 GB of disk per engine image**, and a second engine for comparison doubles it. Budget it on the
  deploy host; prune old engine tags.
- **The golden lane is opt-in, so it is not a standing gate.** `GOLDEN_LLM` is unset in CI by design,
  and the spike measured the tasks routing golden as green **2 of 3 runs** — a 1-in-3 flake that
  nothing automatic would ever notice. Two consequences: the "existing goldens stay green" acceptance
  criterion only means something when the baseline is **re-measured over several runs in the same
  session** as the candidate, and the flaky tasks-vs-flow case is a live question about today's path
  that this ADR surfaced but does not own.
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
1. [x] **Spike first, on current hardware: measure Russian calibration.** Done 2026-10-29 →
   §Spike result; harness in [`scripts/decision-spike/`](../../scripts/decision-spike). Outcome:
   `laya-multilingual` rejected, `kev-0.8b` viable, pilot shape changed to a confidence-gated hybrid.
2. [x] **Owner picks Option A or Option B.** Done 2026-10-29 — **Option A, engine `kev-0.8b`.**
   Recorded in the Status header above and as a locked decision in
   [architecture.md](../architecture.md) §Locked decisions.
3. [x] **Slice the channel** — done as [decision-channel.md](../decision-channel.md): DC-a…DC-f with
   WHEN/THEN criteria, one PR per slice, change-propagation as listed above. **No code yet** — the plan
   is the spec, and DC-a is the first slice to build.
4. [ ] **Then the follow-ups in order** — `LlmIntentClassifier`, then the `NoteWorthinessExtractor`
   pre-gate (which also retires the MQ-3 Mac-gate), then the injection guard as defence-in-depth under
   its own criteria.
5. [ ] **Re-measure on the Mac**, as part of the model-strategy rollout: MPS instead of float32-on-CPU
   is where the latency question gets its real answer, and it decides whether Option B's INT8/ONNX path
   is needed at all.
6. [ ] **Separately, not this ADR's scope:** the baseline flake the spike surfaced — «переведи задачу
   про врача в ожидание» routing to the `clarify_task` tool instead of the `task-status` flow on
   `qwen3:8b`, in 1 of 3 runs. It belongs to the tasks domain and to whatever decides whether the
   golden lane should be more than opt-in.

## Notes
Tenth ADR. It introduces a **new layer** (a model class the system has never used, and a fifth channel),
so per CLAUDE.md "new layer / pattern / architectural concept → flag it BEFORE coding" it was an ADR
first — flagged 2026-10-28, measured 2026-10-29, accepted the same day. **No `decision`-channel code
exists yet**; the slicing lives in [decision-channel.md](../decision-channel.md) and DC-a is next.

**Sources (as-of 2026-10-28; a field only weeks old — treat all figures as directional and re-verify at
implementation):** TypeSafe System One API docs (the `/v1/systemone` contract, `noul`/`choice`/`score`);
`jaredpalmer/kev` (Apache-2.0 weights 0.8B–27B on Qwen3.5/3.8, MLX); `chaitin/Decis` (Apache-2.0,
v0.4.0, weights-in-image engines incl. `laya-multilingual`); `yzfly/edgejev` (ONNX/INT8, 15.6 ms on 4
CPU cores, 324 MB); `openlayer-ai/jevals` (MIT, guardrails as typed decisions);
`yanng981/awesome-system-one` (the open-alternative roster: laya, kev, Von, Decider, Tev1).
