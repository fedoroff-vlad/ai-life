# Stage 4 — memory + inter-agent

Stage-4 plan. Same role calendar.md / finance.md / tasks.md play for their domains: the authority
Stage-4 PRs follow. Owner direction (2026-06-14): the system must grow beyond one-way orchestration
(orchestrator → one agent) toward **conversation-state + inter-agent interaction** — this is the
"home Jarvis" north-star. See `[[project-inter-agent-orchestration]]` in memory.

## Reality check — what "Stage 4 closed" actually meant

Roadmap §Stage 4 is **two halves**. Only the *memory* half shipped:

| Component | State |
|---|---|
| memory-service: store / recall / delete (pgvector, scope) | ✅ PR14 |
| Relations graph: SQL `memory.relations` + `GET /v1/graph/person/{id}/relations` | ✅ PR16 |
| Agents enrich (recall + relations before the LLM call) | ✅ PR17 (calendar), PR24c (finance) |
| Apache AGE graph | ⏸️ **deliberately deferred** — SQL relations table suffices; AGE promotion criteria in `platform/memory-service/README.md` (multi-hop traversal / graph algos / ~100k+ rows) |
| Event-bus: Postgres LISTEN/NOTIFY + `bus.outbox` | ✅ B1/B2 — `libs/event-bus` implemented + a reference producer/consumer wired (see HISTORY) |
| Real agent→agent chains | ✅ C1 sync hub (`/v1/agents/invoke`) + D2 gift flow (calendar→finance→memory via the Coordinator) |
| Conversation-state (dialog + confirmations) | ✅ A1–A4 — `conversation-service` (`core.conversation_state`) + orchestrator route-lock/`/resume`; AC-4 reused it |
| Multi-agent orchestration (>1 agent per request) | ✅ **#290** — Slice A (`coordinator-agent` data-driven routing → memory-driven synthesis) + B1 (`brief` primitive) + B2 (coordinator plans + gathers live specialist `brief`s) + B2-followup (calendar the 2nd exposer → planner picks among ≥2 specialists) + E-later (bounded confidence loop: FAST self-check gates a re-gather within `max-rounds`) all shipped |

**The memory half is done. Conversation-state (A) + inter-agent chains (C1/D2) + the event-bus (B) are
built; the memory-driven multi-domain *coordination* — [#290](https://github.com/fedoroff-vlad/ai-life/issues/290), the Jarvis agenda — is now ✅ shipped too (Track E below: coordinator + `brief` exposers + bounded confidence loop).** (This table is the corrected view — it once marked A/B/C ❌ though they shipped, the stale state #298 tracked.) The Stage-4 north-star is complete; further multi-agent depth is future feature work, not open Stage-4 debt.

## Locked constraints (do NOT relitigate — from architecture.md §Decisions)
- Agents **never** call each other directly — only **via orchestrator (sync)** or the **event bus
  (Postgres LISTEN/NOTIFY, async)**.
- Inter-agent transport at start = Postgres LISTEN/NOTIFY (no new infra); outbox in `bus` schema.
- Everything still runs on the **mock** LLM provider; real models are Stage 5 (blocked on access).

## Tracks & PR-sized slices

### Track A — Conversation-state (dialog + confirmations) ★ recommended first
Unblocks the most deferred debt (receipt-parser confirm-before-write, inbox-clarify apply-on-confirm)
and is the foundation of "assistant-ness": today agents are stateless, so a follow-up "да" has nowhere
to go.

- **A1** — `core.conversation_state` schema + a state service: per-(household, user, channel) short
  context holding `pending_action` (JSON) + `route_lock` (which agent owns the open question) + TTL.
  Decide: live in orchestrator vs a small conversation-service.
- **A2** — orchestrator checks the route-lock **before** classifying: an open question + a reply →
  route straight to the locked agent's `resume` path, bypassing the intent classifier.
- **A3** — extend `IntentResponse` with a pending-action marker; add `POST /agents/<name>/resume`;
  tasks-agent / finance-agent learn to set a pending action and resolve it on the next turn.
- **A4** — turn on confirm flows: receipt-parser (draft → "да" → write) and inbox-clarify (apply the
  proposed `clarify_task` on confirm). Clears two STATUS Deferred items.

### Track B — Event-bus (async foundation)
- **B1** — implement `libs/event-bus`: Postgres `LISTEN/NOTIFY` adapter + `bus.outbox` table
  (at-least-once; migration in the 001-009 range).
- **B2** — producer/consumer API + wire one service as the reference (e.g. scheduler or notifier).

### Track C — Inter-agent chains
- **C1** — **task-to-event** (first real chain; both agents already exist): tasks-agent → orchestrator
  → calendar-agent `create_event` → mcp-tasks `link_task_to_event(id, uid)`. **Sync via orchestrator**
  (locked decision). One-direction MVP (task→event); event-done→task-done is later. Clears the
  STATUS Deferred `task-to-event` item.
- **C2** — async chain over the bus (needs B): e.g. `calendar.birthday_upcoming → notifier`, or the
  roadmap's `→ creator.draft_greeting → notifier` (creator agent doesn't exist yet — either simplify
  to notifier now or bring creator in Stage 6).

### Track D — Coordinator substrate (agent-led multi-source flows)
**Doctrine update (2026-06-16, owner-aligned):** coordination is **agent-led**, not
orchestrator-planned. A domain agent owns a flow and reaches other specialists through the
hub (`/v1/agents/invoke`, C1) or the bus; the orchestrator stays a thin router
(architecture.md §routing doctrine). So the original "orchestrator routes to many agents"
framing is **replaced** — the smarts live in a reusable agent-side coordinator + per-flow
gather steps. This is the **reusable infrastructure** the owner asked to build before
features; every scenario (gift / picnic-menu / outfit / 3D-print) is the same gather→synthesize
shape, so we build the substrate once on the cheapest vertical, then "new domain = add a specialist".

- **D1 — Coordinator scaffold** ✅ **DONE (PR91).** `libs/agent-runtime` `Coordinator.coordinate(
  systemPrompts, payload, gather, channel)`: named async gather steps (memory recall, inter-agent
  `/v1/agents/invoke`, tool call) run in parallel → folded into a `context` object → LLM synthesis
  from `[systemPrompts] + {payload, context}` → `CoordinationResult(text, gathered, llmModel)`.
  Per-step soft-fail. Registered as a bean every agent gets on `@Import(AgentRuntimeConfig)`.
- **D2 — first real flow: budget-aware `gift-recommender`** (calendar→finance). Proves the
  scaffold end-to-end on the cheapest vertical (both agents exist; needs no new infra beyond the
  finance action). Slices mirror C1's a→e shape:
  - **D2a** — mcp-finance deterministic **gift-budget read passthrough**: `GET /internal/gift-budget?
    householdId=…` → `{amount, currency, remaining?}` (reuse the existing `get_budget`/budget-status
    read; the "gifts" category budget is the envelope for the MVP). Mirror C1b (PR73).
  - **D2b** — finance-agent **`POST /agents/finance/actions/get_gift_budget`** — first finance
    `/actions/*` endpoint (consumer side of the invoke primitive, like calendar's `create_event`).
    Forces `householdId` from the envelope, calls D2a, returns an `AgentActionResult` with the
    budget (never an HTTP error). Mirror C1c (PR74).
  - **D2c** ✅ **DONE (PR95)** — calendar-agent **`gift.recommend` flow rebuilt on `Coordinator`**:
    `flow/GiftRecommender` gathers `{ budget: finance invoke get_gift_budget via the orchestrator
    hub, memories: recall(person), relations }` in parallel → synthesizes budget-aware gift ideas →
    fans out to the household. Per-step soft-fail. `gift-recommender` SKILL.md upgraded to consume
    `context.budget` — **the "finance integration deferred" note is cleared.** First real Coordinator
    flow; mirrored C1e (PR76).
- **D3 (later)** — relationship-tiered budget **rules as editable preferences** (structured store,
  set from chat: "родителям 20к на НГ" — NOT skill prose, per the routing-doctrine "editable rules =
  data" rule) + the birthday **"reminder + gift" two-notification** wiring (one trigger → two outputs).
  Stacks on D2.

## Parallel foundation — memory-from-chat (the fuel)
Every coordinator flow is empty without facts ("Маша=друг, любит мемы"; "у меня диета"). The memory
half shipped **recall** (PR14–17); **auto-capture of facts about people/self from ordinary dialogue**
into memory-service (+ relations) is the missing fuel. Foundational, runnable in parallel with D2.
Owner-chosen next after D2 proves the coordinator end-to-end (don't build more on an unproven scaffold).

## Dependency order & recommendation
A and B are independent and foundational. C1 needs nothing new (sync, no bus). C2 needs B.
D1 (scaffold) needs nothing new; D2 needs C1's invoke primitive (have it). 

**Order so far: A ✅ → C1 ✅ → B ✅ → C2a ✅ → D1 ✅ → D2 ✅ → memory-from-chat (now) → D3.**
A/B/C1/C2a/D1/**D2** are merged (D2a PR93 + D2b PR94 + D2c PR95). D2 validated the coordinator
substrate end-to-end on the cheapest vertical (calendar → finance → memory). **The active line is
now memory-from-chat** (the fuel — see §"Parallel foundation" above), then D3.

## Track E — memory-driven multi-domain orchestration (#290) — the coordinator line
The *output* half of the north-star (the D-tracks proved the agent-led coordinator substrate on a
single named flow; this generalises it to a routable, memory-driven engine). Reuses everything above:
the hub (C1), the `Coordinator` (D1), conversation-state (A). Slices:

- **E-A (Slice A) ✅** — a thin **`coordinator-agent`** (`domains/assistant`, port 8119): the
  cross-cutting synthesis engine. A multi-domain message is **routed to it purely by manifest**
  (data-driven — no orchestrator code), it reads the second brain and synthesizes ONE grounded answer.
  **Dual-triggered**: reactive `/intent` + proactive `/triggers/coordinator.surface` (relevance-gated —
  precision over volume). One-shot but **loop-ready** (`gatherFor → synthesize` seam a future bounded
  plan→gather→re-gather loop wraps). Model-agnostic (mock/7b → Claude by env); golden-verified on
  `qwen2.5:7b`. Detail → [coordinator-agent README](../domains/assistant/coordinator-agent/README.md).
- **E-B1 (Slice B1) ✅** — the generic read-only **`brief`** cross-agent query: a reusable
  `BriefResponder` in `libs/agent-runtime` (second-brain recall → one FAST synthesis under a read-only
  instruction → `{agent, answer}`), wired into **finance-agent** as the first exposer via
  `register("brief", …)`. The seam the coordinator gathers *live* specialist answers through the hub.
- **E-B2 (Slice B2) ✅** — the coordinator **uses** `brief`. `SpecialistBriefs` (coordinator-agent) runs
  a FAST **planning** step over the configured roster (`coordinator-agent.specialists[]`: each agent's
  `name` + one-line `expertise`) to pick the specialists that bear on the request (precision over volume),
  invokes each picked specialist's read-only `brief` in parallel through the hub
  (`OrchestratorInvokeClient`), and folds the live answers into `context.briefs`. Wired into
  `MultiDomainCoordinator.gatherFor` alongside the memory recall; both sources soft-fail per-step, and an
  empty roster keeps the agent memory-only. finance is the wired exposer; golden green on qwen2.5:7b.
  Detail → [coordinator-agent README](../domains/assistant/coordinator-agent/README.md).
- **E-B2-followup (Slice B2-followup) ✅** — **calendar-agent** wired as the second `brief` exposer
  (`register("brief", briefResponder::answer)` on its `ActionController`) + `calendar` added to the
  `coordinator-agent.specialists[]` roster, so the FAST planning step now chooses among ≥2 real
  specialists (finance + calendar). More exposers join the same way. `BriefActionTest` proves the calendar
  `brief` hop (recall → FAST synthesis → `{agent, answer}`).
- **E-later (bounded confidence loop) ✅** — `MultiDomainCoordinator.run` now wraps the
  `gatherFor → synthesize` seam in a bounded `gather → synthesize → self-check → maybe-re-gather` loop.
  A cheap FAST `SufficiencyAssessor` judges each draft `{sufficient, missing}`; an under-confident verdict
  (within the `coordinator-agent.max-rounds` budget, default 2) sharpens the focus with the `missing` hint,
  re-gathers (memory re-recall + specialist re-plan) and re-synthesizes, folding the prior draft into the
  payload so the model refines rather than restarts. `max-rounds: 1` = today's one-shot exactly (self-check
  never called); the assessor soft-fails toward "sufficient" so a broken judge never inflates rounds. This
  is the confidence-aware escalation for the read/synthesis path. Detail →
  [coordinator-agent README](../domains/assistant/coordinator-agent/README.md).

## Track F — misroute-repair (road-test [#484](https://github.com/fedoroff-vlad/ai-life/issues/484))
Daily-use reliability: when the classifier picks the wrong intent, the owner should fix it in **one short
turn** ("не то, я про задачи") without re-typing the request. Extends the conversation-state substrate
(Track A) with a **last-route** memory: after a fresh (non-locked) reply the orchestrator records the
agent it routed to + the original text (short TTL); on the next turn, if that last-route is fresh, the
classifier re-decides **with the prior route as context** (approach chosen 2026-08-18: correction-aware
classifier, *not* a separate detector hop or `*_CUES` keywords — one classifier turn, paraphrase-robust).
`routeLock` still takes priority (an open question resumes; last-route is only consulted while classifying).

**Slices (≤5 files each):**
- **F1 ✅** — conversation-service remembers last-route: `last_route_{agent,text}` columns + `set()` writes
  them + contract DTOs (storage only; orchestrator untouched, still green).
- **F2 ✅** — orchestrator records last-route after a fresh specialist dispatch + `LlmIntentClassifier` takes
  an optional `PriorRoute` context so a correction re-routes; `GoldenMisrouteRepairTest` proves a real model
  honours the correction (and leaves an unrelated message alone). **F3 folded in** — the routing-quality
  signal is a one-line `routing-correction` log in `IntentRouter`, no separate slice needed.

**Acceptance criteria (WHEN/THEN):**
- Scenario: **misroute corrected in one turn.** WHEN the owner replies "не то, я имел в виду … / я про
  задачи" right after a reply → THEN the orchestrator re-classifies that turn with the prior route as
  context and routes to the corrected intent, instead of treating it as a fresh unrelated message
  (asserted by `IntentRouterLockTest` + model-proven by `GoldenMisrouteRepairTest`).
- Scenario: **last-route ages out.** WHEN the correction window (short TTL) has elapsed since the last
  routing → THEN the next message classifies normally (no stale prior-route context leaks in) (asserted
  by `GoldenMisrouteRepairTest`).
- Scenario: **lock still wins.** WHEN an agent has an open question (active `routeLock`) → THEN the reply
  resumes that agent as today; last-route is not consulted (asserted by `IntentRouterLockTest`).
- Scenario: **correction logged.** WHEN a correction re-routes to a different agent → THEN it is at least
  logged as a routing-quality signal (agent_from → agent_to + phrasing) (asserted by `IntentRouterLockTest`).

## Track G — "why did you do that" trace (road-test [#485](https://github.com/fedoroff-vlad/ai-life/issues/485))
Daily-use trust: right after a reply the owner should be able to ask **why the assistant handled it that
way** and get one honest sentence — not silence, not a black box. Piggybacks on the same conversation-state
substrate as Track F: the last fresh routing is already remembered (`last_route`), so a follow-up "почему ты
так сделал / как ты это понял" is a **meta-query about the prior route**. The classifier — which already
receives that route as `PriorRoute` context (#484) — gains one more reserved outcome `explain`, offered
**only when a `PriorRoute` is present**; the orchestrator answers it deterministically from the remembered
route instead of dispatching to a domain agent. Keyword-free (classifier-detected, paraphrase-robust), same
doctrine as Track F. This is the user-facing half of the #485 transparency doctrine (the degraded-notice half
lives in [architecture.md](architecture.md) §Principles "Soft-fail, but never silently").

**Scope of the trace.** G1 explains the **routing** decision the orchestrator genuinely owns — which agent
handled the prior turn and the request that steered there — phrased in the user's language via the LLM, with
**no internal payloads leaked** (it names the agent + paraphrases the request, nothing more). Enriching it
with each agent's own **sources read / action written** (brief/recall/write) is a follow-on (**G2**) where
agents contribute a short trace line via `IntentResponse`, rolled out per agent (reference consumer first,
like #485's board-store rollout); not G1, which would otherwise touch every agent.

**Slices (≤5 files each):**
- **G1** — orchestrator-only, no schema/contract change (reuses the remembered `last_route`): `explain`
  reserved classifier outcome (only offered inside the `PriorRoute` block) + an `ExplainResponder` that
  phrases the routing trace in the user's language via the LLM + an `IntentRouter` branch that returns it
  without dispatching to a domain agent or overwriting `last_route`.
- **G2a** — storage half (orchestrator behaviour untouched, like #484 F1): conversation-service remembers a
  `last_route_trace` alongside `last_route` (migration `011` + `SetConversationStateRequest`/
  `ConversationStateDto` gain a nullable field + entity/service). The trace is a short, payload-free line of
  what the handling agent read/wrote.
- **G2b** — orchestrator wiring (primitive-then-rollout, like #485's `DegradedNotice`): `IntentResponse`
  gains an optional `trace` (+ `withTrace`); `IntentRouter` records `resp.trace()` into `last_route_trace`;
  `PriorRoute` carries it; `ExplainResponder` folds it into the explain answer, falling back to the G1
  routing-only answer when null. No agent produces a trace yet — proven by orchestrator unit tests.
- **G2c** — first real producer: `tasks-agent`'s `task-capture` attaches a payload-free
  `withTrace("wrote: captured a task to the personal/shared list")` on a successful capture (deferred/failed
  turns leave it null). Proves the seam end-to-end.
- **G2-rollout** — `withTrace` extended to every user-facing write agent, one small PR each (same cadence as
  the #485 board-store `DegradedNotice` rollout): tasks (capture), finance (`add_transaction`/account/
  category), notes (`NoteWriter` + `ListManager`), docs (`DocArchiver`), nutrition (`FoodLogger` +
  `DietProfiler`). Each attaches a payload-free "wrote: …" line on its terminal write path; reads / no-ops /
  deferrals / failures set none. Calendar user-intent is chat-only (event CRUD is inter-agent, not a "почему"
  turn); the deliverable/read agents (stylist/travel/creator/chef/briefing) have no reply-path write to trace.

**Acceptance criteria (WHEN/THEN):**
- Scenario: **owner asks why.** WHEN the owner replies "почему ты так сделал / как ты это понял" right after
  a fresh specialist reply → THEN the assistant returns a one-sentence trace naming the agent that handled
  the prior turn and why it was chosen, without routing that meta-query to a domain agent (asserted by
  `IntentRouterLockTest` + model-proven by `GoldenExplainTraceTest`).
- Scenario: **nothing to explain.** WHEN there is no fresh prior route (correction window elapsed, or the
  first message of a conversation) → THEN "почему …" classifies normally — no stale `explain` — degrading to
  echo or its real intent (asserted by `IntentRouterLockTest` + model-proven by `GoldenExplainTraceTest`).
- Scenario: **explain doesn't disturb state.** WHEN an explain turn is answered → THEN it does not overwrite
  `last_route` (a subsequent correction still repairs the *original* route) and leaks no payload from the
  prior turn (asserted by `IntentRouterLockTest`).
- Scenario: **lock still wins.** WHEN an agent has an open question (active `routeLock`) → THEN the reply
  resumes that agent; explain is not consulted (same precedence as Track F) (asserted by `IntentRouterLockTest`).

## Track H — CRUD/undo (road-test [#486](https://github.com/fedoroff-vlad/ai-life/issues/486))
Daily-use completeness: the MVP domains are strong on *create/read* but daily use immediately needs
**edit / delete / correct / undo** — and a wrong capture with no easy fix is worse than no capture. #486
has **two halves**, shipped independently, smallest holes first:

1. **The cross-cutting "отмени последнее / undo" primitive** (highest-leverage single item) — the last
   *mutating* action per conversation is reversible with one phrase.
2. **Per-domain edit/delete/correct holes** — each domain gains the missing lifecycle ops on its own
   intent path (not the cross-cutting primitive).

### H.1 — the undo primitive (mirror of Track F/G)
The primitive is a **near-exact mirror of the misroute-repair (F) / why-trace (G) machinery** — same
conversation-state substrate, same "reserved classifier outcome answered by the orchestrator, not
dispatched" shape, same agent-led doctrine. It reuses the existing **C1 inter-agent `invoke` primitive**
(`Agent.invoke` → `RemoteAgent` → the agent's `/actions/<action>`) for the reversal — **no new `Agent`
interface method**.

**Substrate:**
- **conversation-state** already remembers `last_route` + `last_route_trace`. Add a sibling
  **`last_mutation`** = `{ agent, undo_payload (JSONB, opaque), description }`. Unlike `last_route` (which
  every fresh specialist turn overwrites), `last_mutation` is written **only by a terminal mutating write**
  and cleared when undone — so a plain read/echo turn between the write and "отмени последнее" leaves it
  intact. A short TTL guards staleness. The `undo_payload` is **internal** (holds the ids/inverse-op the
  recording agent needs) and never surfaced; only `description` is user-facing.
- an agent's **`IntentResponse` gains an optional undo handle** — mirror of `withTrace`: `withUndo(UndoHandle)`
  where `UndoHandle = { description, action (JsonNode the agent reverses with) }`. An agent opts in on its
  **terminal write path** only (reads / no-ops / deferrals / failures attach none → nothing to undo).
- the **classifier gains a reserved `undo` outcome**, offered **only when a `last_mutation` exists** (a new
  `Undoable` context passed into `classify`, mirroring `PriorRoute` — it carries the `description` so the
  model recognises "отмени последнее / верни как было / нет, убери это" as an undo of *that* action).
- the **orchestrator** (`IntentRouter`) records `resp.undo()` into `last_mutation` on a fresh terminal write,
  and on the `undo` outcome dispatches the recording agent's `invoke("undo", payload)`, surfaces the agent's
  confirmation (or its honest "это нельзя отменить"), then clears `last_mutation`. When nothing is recorded
  it answers a deterministic "нечего отменять" instead of silently no-op'ing. `routeLock` still wins (an open
  question resumes); an undo turn does **not** overwrite `last_route`.

**Slices (≤5 files each):**
- **H1** — storage half (orchestrator behaviour untouched, like #484 F1 / #485 G2a): conversation-service
  remembers `last_mutation` (migration `012`: `last_mutation_{agent,payload,desc}` on
  `core.conversation_state`) + `SetConversationStateRequest`/`ConversationStateDto` gain the nullable fields
  + entity/service `recordLastMutation`/`clearMutation`. Proven by the conversation-service repository test.
- **H2** — orchestrator wiring + primitive (primitive-then-rollout, like #485 G2b): `IntentResponse` gains
  the `UndoHandle` + `withUndo`; `LlmIntentClassifier` gains the reserved `undo` outcome inside a new
  `Undoable` block (offered only when a `last_mutation` is present); `IntentRouter` records the handle on a
  fresh write, and on `undo` dispatches `invoke("undo", payload)` + surfaces the result + clears the handle,
  with a deterministic "nothing to undo" reply. No agent produces a handle yet — proven by orchestrator unit
  tests with a stub agent.
- **H3** — first real producer + reversal: `tasks-agent`'s `task-capture` attaches `withUndo` on a
  successful capture, and tasks-agent implements `/actions/undo` reversing via `mcp-tasks` (soft-delete the
  just-captured task; add the `mcp-tasks` delete passthrough if absent). E2E: capture → "отмени последнее" →
  task gone; a golden proves a real model emits the `undo` outcome. (Mirror #485 G2c.)
- **H-rollout** — `withUndo` + `/actions/undo` extended to every user-facing write agent, one small PR each
  (same cadence as the #485 G2-rollout): finance (delete the added transaction / restore the prior amount),
  notes (delete the note / remove the just-added list item), calendar (cancel the just-created event). Each
  reverses its own terminal write; agents with no reversible reply-path write attach none.

### H.2 — per-domain edit/delete/correct holes (parallel line)
Independent of the undo substrate: each domain fills its lifecycle holes on **its own** in-agent
`SkillRouter` path (a new edit/delete skill + the `mcp-*` update/delete passthrough), gated by the
**destructive-delete confirm** (reuses Track A's pending-action/`/resume` gate) — **not** the cross-cutting
`undo` classifier outcome. Smallest / highest-daily-use holes first:
- **finance** — edit/delete a logged expense; fix a mis-categorised or wrong-amount transaction
  ("поменяй сумму последней траты на 1500", "удали трату про X").
- **tasks** — delete/rename a task; move its state back; edit due/project.
- **notes** — fix/delete a wrong note; rename/remove one list item (LI-a already has add/check/clear).
- **calendar** — move/cancel an event via chat.
Target resolution is by context ("последней", or by description) — **no id required**. A destructive delete
confirms first and is itself undoable where feasible (soft-delete + restore).

**Acceptance criteria (WHEN/THEN):**
- Scenario: **undo the last mutation.** WHEN the owner says "отмени последнее / верни как было" right after a
  mutating write whose agent attached an undo handle → THEN the orchestrator dispatches the inverse to the
  recording agent, the write is reversed, and it confirms — no id needed (asserted by `IntentRouterLockTest`
  + model-proven by `GoldenUndoTest`).
- Scenario: **nothing to undo.** WHEN no fresh undoable mutation exists (none recorded, TTL elapsed, or the
  last turn was a read) → THEN the assistant says there is nothing to undo (or classifies "отмени …" as its
  real intent) — never a silent no-op (asserted by `IntentRouterLockTest` + model-proven by `GoldenUndoTest`).
- Scenario: **irreversible action.** WHEN the last action is truly irreversible → THEN the assistant says so
  honestly instead of pretending it undid something (asserted by `IntentRouterLockTest`).
- Scenario: **undo doesn't disturb routing state.** WHEN an undo turn is answered → THEN it does not overwrite
  `last_route` (a later correction still repairs the original route) and clears only the consumed
  `last_mutation` (asserted by `IntentRouterLockTest`).
- Scenario: **lock still wins.** WHEN an agent has an open question (active `routeLock`) → THEN the reply
  resumes that agent; undo is not consulted (same precedence as F/G) (asserted by `IntentRouterLockTest`).
- Scenario: **edit without an id.** WHEN the owner says "поменяй сумму последней траты на 1500 / удали задачу
  про X / исправь заметку …" → THEN the domain resolves the target from context and applies the edit/delete,
  confirming (asserted by `PickConfirmActRunnerTest`).
- Scenario: **destructive delete confirms.** WHEN a delete is destructive → THEN it confirms first (reuses the
  pending-action confirm gate) and is itself undoable where feasible (soft-delete + restore) (asserted by
  `PickConfirmActRunnerTest`).

## Track I — real agent-led multi-domain coordination ([#477](https://github.com/fedoroff-vlad/ai-life/issues/477))
The coordinator substrate (Track E / #290) is built, but in practice almost every flow is
**single-domain** gather→synthesize — the genuine cross-domain path is thin and under-exercised (only
`finance` + `calendar` expose `brief`, so the FAST planner rarely picks >1). #477 lands **1–2 real
cross-domain scenarios** end-to-end through the hub, proving the architecture invariants hold on a real
multi-agent path (not just the coordinator demo). Canonical scenario: **"спланируй выходные"** →
calendar free dates + finance budget + tasks to-dos (weather later, via a briefing exposer). Reuses
everything above: the hub (C1), the `Coordinator` (D1), the `brief` primitive (E-B1), conversation-state
(A). Doctrine unchanged — coordination is **agent-led via the hub**; no agent calls another directly.

**Slices (≤5 files each):**
- **I1 ✅** — **tasks-agent as the 3rd `brief` exposer** (mirror of E-B2-followup, calendar-as-second):
  `register("brief", briefResponder::answer)` on tasks' `ActionController` + `tasks` in the
  `coordinator-agent.specialists[]` roster, so the FAST planner now chooses among ≥3 real specialists.
  Memory-only recall, exactly like the finance/calendar exposers (live domain reads via the
  `answer(request, extraGather)` overload is a separate, all-exposer concern — see I3). A `BriefActionTest`
  proves the tasks `brief` hop (recall → FAST synthesis → `{agent, answer}`).
- **I2 ✅ (stage-closer)** — the mandatory multi-domain **E2E**: `E2ECoordinateMultiDomainTest` in
  coordinator-agent — one real coordinator context, MockWebServers **forwarding** the hub
  `/v1/agents/invoke` to the stub specialists, asserting all three invariants on a real multi-agent path:
  a "спланируй выходные" ask fans out to finance+calendar+tasks and returns **one** synthesis grounded in
  every specialist's brief; per-source **soft-fail** (one specialist's hub invoke 500s → the flow still
  returns one grounded reply from the survivors, never a 500); and the architecture invariant that
  coordination reaches specialists **only through the hub** (the only specialist transport wired is
  `/v1/agents/invoke`; every recorded hub call is a `brief` for a rostered specialist — no direct
  agent-to-agent call). **This is the #477 closer** — the genuine cross-domain path is now proven, not
  just the coordinator demo.
- **I3 (later, optional)** — enrich the specialist briefs with **live domain reads** (the
  `answer(request, extraGather)` overload) so a brief carries real data (tasks' open next-actions,
  finance's spend snapshot) rather than memory-only recall. Applies equally to every exposer; not needed
  to prove #477, so it trails the E2E closer.

**Acceptance criteria (WHEN/THEN)** — SSOT for the scenarios (mirrors [#477](https://github.com/fedoroff-vlad/ai-life/issues/477)):
- Scenario: **a multi-domain ask fans out to one synthesized answer.** WHEN the owner asks something
  spanning ≥2 domains ("спланируй выходные") → THEN the lead agent gathers each relevant specialist's
  read-only `brief` via the hub and returns **one** reply grounded in all of them, with per-source
  soft-fail (a missing source degrades, never 500s) (asserted by `E2ECoordinateMultiDomainTest`).
- Scenario: **no direct agent-to-agent calls (invariant).** WHEN coordination happens → THEN it flows
  only through the orchestrator hub (or the bus) (asserted by `E2ECoordinateMultiDomainTest`).
- Scenario: **outbound stays behind confirm.** WHEN a coordinated flow reaches an outbound external
  action → THEN it still hits the conversation-state confirm gate (propose-freely / act-on-confirm holds
  across the coordinated path). _(No coordinated-path outbound flow exists yet — the coordinator is
  read-only `brief` gather; asserted when the first coordinated outbound action lands.)_
- Scenario: **recall drives agent selection.** WHEN the classifier/planner has memory-service recall as
  context → THEN a well-curated brain lets it pull the relevant specialists from the data (asserted by
  `E2ECoordinateFlowTest`, with planner-selection unit coverage in `SpecialistBriefsTest`).

## Out of scope for Stage 4
- Real LLM providers / golden tests on real models — **Stage 5** (blocked on model access).
- New domain agents (chef, researcher, stylist, creator, …) — **Stage 6+**. (creator may be pulled
  earlier only if C2 wants the birthday-greeting chain.)
- Apache AGE upgrade — gated on the promotion criteria in memory-service README.
