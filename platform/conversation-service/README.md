# conversation-service

Owns **short-term conversation control state** — the route-lock + pending-action that make
multi-turn dialog and confirmations possible (Stage 4, Track A; see
[plans/stage4.md](../../plans/stage4.md)). One row per active `(household, user, channel)`
conversation in `core.conversation_state`.

This is **routing/confirmation control**, distinct from `core.conversations` (message history)
and from memory-service (semantic recall). The orchestrator stays stateless and calls this over
HTTP, the same way it calls memory-service.

Typical flow (wired in later Track-A slices): an agent that needs a confirmation `PUT`s a lock
(`routeLock=<agent>`, `pendingAction={…}`); the orchestrator, on the next message, `GET`s the
active state and — if locked — routes the reply straight to that agent's resume path instead of
re-classifying; the agent `DELETE`s the state once resolved. A forgotten confirmation simply ages
out (TTL) and the next message classifies normally.

## REST API

| method | path | purpose |
|--------|------|---------|
| PUT    | `/v1/conversation-state` | upsert the lock + pending action (body `SetConversationStateRequest`); TTL'd. Returns `ConversationStateDto` |
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
  (unique on `(household_id, user_id, channel)`; `pending_action` is `jsonb`).
- `domain/ConversationStateService` — `set` (upsert + TTL), `getActive` (unexpired only),
  `clear`. Expiry checked on read.
- `web/ConversationStateController` — the REST surface above.

## Schema

- [009-conversation-state.yml](../../infra/liquibase/features/009-conversation-state.yml) —
  `core.conversation_state` (route_lock, pending_action jsonb, expires_at TTL) with a unique
  key on `(household_id, user_id, channel)`.
