# conversation-service

Owns **short-term conversation control state** — the route-lock + pending-action that make
multi-turn dialog and confirmations possible (Stage 4, Track A; see
[plans/stage4.md](../../plans/stage4.md)), plus the **last-route** memory that backs
misroute-repair (road-test [#484](https://github.com/fedoroff-vlad/ai-life/issues/484)). One row per
active `(household, user, channel)` conversation in `core.conversation_state`.

This is **routing/confirmation control**, distinct from `core.conversations` (message history)
and from memory-service (semantic recall). The orchestrator stays stateless and calls this over
HTTP, the same way it calls memory-service.

Typical flow: an agent that needs a confirmation `PUT`s a lock (`routeLock=<agent>`,
`pendingAction={…}`); the orchestrator, on the next message, `GET`s the active state and — if locked —
routes the reply straight to that agent's resume path instead of re-classifying; the agent `DELETE`s the
state once resolved. A forgotten confirmation simply ages out (TTL) and the next message classifies
normally.

**last-route (misroute-repair #484):** a separate, lock-independent pair of fields —
`lastRouteAgent` + `lastRouteText` — records the agent a *fresh* (non-locked) message was routed to and
that message's text. The orchestrator `PUT`s them (with a short TTL, `routeLock=null`) after a resolved
reply, then reads them while classifying the next turn: if it is a correction ("не то, я про задачи")
the classifier re-routes with the prior route as context. `routeLock` takes priority — when a lock is
active the reply resumes and last-route is not consulted. Both fields are `NON_NULL`-omitted, so a
lock-only or last-route-only row carries just the fields it set.

**Consumers:** the **orchestrator** reads the lock before classifying and clears it after a resolved
resume (`IntentRouter`); agents (**tasks-agent** inbox-clarify, **notes-agent** ambient-approve) set a
`pendingAction` on their reply so the orchestrator locks. **memory-service also sets a lock out-of-band**
(ambient capture AC-4): off the reply path it notices an important inferred fact, `PUT`s a lock to
`notes` with the ready-to-write note in the `pendingAction`, and pushes "заметил: … — записать?" — the
owner's reply is then resolved by notes-agent's `/resume`. The channel it keys on is
`MessageReceivedEvent.source`, the same string the orchestrator reads a lock by, so the two sides meet.

## REST API

| method | path | purpose |
|--------|------|---------|
| PUT    | `/v1/conversation-state` | upsert the lock + pending action **and/or** last-route (`lastRouteAgent`/`lastRouteText`) (body `SetConversationStateRequest`); TTL'd. A field left null clears it. Returns `ConversationStateDto` |
| GET    | `/v1/conversation-state?householdId=&userId=&channel=` | active (unexpired) state → 200 `ConversationStateDto`, else 204 |
| DELETE | `/v1/conversation-state?householdId=&userId=&channel=` | clear after resolve → 204 |
| GET    | `/actuator/health` | liveness |

Expiry is enforced on read (a stale lock reads as absent); no sweeper needed for correctness.
`ttlSeconds` omitted → default 1800 s (30 min).

## Env

| Var | Default | Purpose |
|---|---|---|
| `CONVERSATION_PORT` | `8089` | HTTP port |
| `CONVERSATION_DB_URL` | `jdbc:postgresql://localhost:5432/ailife` | Postgres |
| `CONVERSATION_DB_USER` / `CONVERSATION_DB_PASSWORD` | `ailife` | DB credentials |

## Key classes

- `ConversationServiceApplication`.
- `domain/ConversationState` + `ConversationStateRepository` — JPA over `core.conversation_state`
  (unique on `(household_id, user_id, channel)`; `pending_action` is `jsonb`; `last_route_agent` /
  `last_route_text` back misroute-repair).
- `domain/ConversationStateService` — `set` (upsert + TTL), `getActive` (unexpired only),
  `clear`. Expiry checked on read.
- `web/ConversationStateController` — the REST surface above.

## Schema

- [009-conversation-state.yml](../../infra/liquibase/features/009-conversation-state.yml) —
  `core.conversation_state` (route_lock, pending_action jsonb, expires_at TTL) with a unique
  key on `(household_id, user_id, channel)`.
- [010-conversation-last-route.yml](../../infra/liquibase/features/010-conversation-last-route.yml) —
  adds `last_route_agent` / `last_route_text` (misroute-repair #484).
