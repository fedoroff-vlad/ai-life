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
| Multi-agent orchestration (>1 agent per request) | 🚧 **#290** — Slice A shipped a thin `coordinator-agent` (data-driven multi-domain routing → one memory-driven synthesis); Slice B adds live cross-agent gather |

**The memory half is done. Conversation-state (A) + inter-agent chains (C1/D2) + the event-bus (B) are
built; the open work is now the memory-driven multi-domain *coordination* itself — [#290](https://github.com/fedoroff-vlad/ai-life/issues/290), the Jarvis agenda.** (This table is the corrected view — it once marked A/B/C ❌ though they shipped, the stale state #298 tracked.)

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
- **E-B2 (Slice B2, next)** — the coordinator **uses** `brief`: pick relevant specialists (a configured
  roster + a FAST planning step) → invoke each `brief` via the hub → fold their live answers into the
  DEFAULT synthesis alongside memory. Wire a second exposer (calendar) as needed.
- **E-later** — the bounded multi-step loop (plan → gather → maybe-gather-again) wrapping `run`; a
  confidence-aware routing/escalation refinement.

## Out of scope for Stage 4
- Real LLM providers / golden tests on real models — **Stage 5** (blocked on model access).
- New domain agents (chef, researcher, stylist, creator, …) — **Stage 6+**. (creator may be pulled
  earlier only if C2 wants the birthday-greeting chain.)
- Apache AGE upgrade — gated on the promotion criteria in memory-service README.
