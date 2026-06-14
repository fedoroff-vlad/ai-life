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
| Event-bus: Postgres LISTEN/NOTIFY + `bus.outbox` | ❌ `libs/event-bus` is a stub (package-info only), unused |
| Real agent→agent chains | ❌ none (PR17 was labelled "first cross-agent chain" but it's agent→memory-service enrichment, not inter-agent) |
| Conversation-state (dialog + confirmations) | ❌ `core.conversations` in schema, no impl |
| Multi-agent orchestration (>1 agent per request) | ❌ orchestrator routes to exactly one agent (`getOrDefault`) |

**The memory half is done. The inter-agent half + dialog state are the open Stage-4 work — the Jarvis agenda.**

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

### Track D — Multi-agent orchestration
- **D1** — orchestrator can route to / sequence **more than one** agent per request (classifier →
  a small multi-step plan). The "smartest" piece; do after A + C, once both dialog state and a proven
  agent→agent path exist.

## Dependency order & recommendation
A and B are independent and foundational. C1 needs nothing new (sync, no bus). C2 needs B. D needs A + C.

**Recommended order: A → C1 → B → C2 → D.** A delivers the most assistant-ness and clears two debts;
C1 is the first visible inter-agent feature on agents that already exist.

## Out of scope for Stage 4
- Real LLM providers / golden tests on real models — **Stage 5** (blocked on model access).
- New domain agents (chef, researcher, stylist, creator, …) — **Stage 6+**. (creator may be pulled
  earlier only if C2 wants the birthday-greeting chain.)
- Apache AGE upgrade — gated on the promotion criteria in memory-service README.
