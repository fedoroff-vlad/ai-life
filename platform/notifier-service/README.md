# notifier-service

Resolves an internal `userId` to a `telegram_user_id` (via profile-service) and forwards
the payload to gateway-telegram's `/internal/send`. The Telegram bot token never leaves
gateway-telegram. Delivery is driven two ways:

- **synchronous** — `POST /v1/notify` (callers that already hold the request thread);
- **asynchronous** — a `notify.requested` event on the event bus (`libs/event-bus`),
  the decoupled fan-out path. notifier is the **first bus consumer** in the system.

## Proactive-UX gate (#487 PX-1)

notifier is the single seam every proactive push flows through (a reactive reply the user just asked
for goes straight through gateway-telegram, not here), so it hosts the send-time gate that makes
proactive UX controllable. Both the REST `NotifyRequest` and the bus `NotifyRequestedEvent` carry a
`proactive` flag (default `false` → reactive, never gated; back-compat via a secondary ctor so existing
callers don't ripple). `NotificationGate.evaluate` reads the user's preference and returns one of
**SUPPRESSED** (the send's `source`/**stream** is in the user's `core.notification_stream_optout` set — a
muted stream, PX-3 — or today already hit `daily_cap`, PX-2 → the push is dropped, not queued), **HELD**
(inside quiet hours → parked in `core.notification_held` with `deliver_after` = the window's next end, PX-1),
or **PASS** (deliver now; `NotifySender` then calls `recordSent` to log the delivery in
`core.notification_sent` so it counts toward the cap). The stream opt-out is checked **first** — a muted
stream is never held or counted. HELD/SUPPRESSED both report `202 ACCEPTED` without touching gateway. Absent
preference row, reactive send, or no `DataSource` → the gate is **inert** (fail-open — a send is never blocked
by the pref store). Deterministic (`QuietHours` pure logic, tz- and midnight-wrap-aware), never an LLM decision.

A held row is redelivered by the **redrain tick** (`HeldRedrain`, `@Scheduled` every
`notifier.held-redrain-millis`, default 60s): each pass drops rows older than `notifier.held-stale-hours`
(default 12 — a message parked too long is never delivered late), then delivers every remaining row whose
`deliver_after` has passed (as a plain non-gated send) and removes it. The tick is gated by
`notifier.held-redrain-enabled` (default on; the notifier tests turn it off to drive `drain()` directly).
Frequency caps, per-stream opt-out and snooze buttons are PX-2…PX-4 — see
[plans/platform.md](../../plans/platform.md).

## Endpoints

| method | path        | purpose                                          |
|--------|-------------|--------------------------------------------------|
| POST   | `/v1/notify`| `{ userId: UUID, text: string }` → 202/404/422   |

## Bus consumer

`notify.requested` (`NotifyRequestedEvent{userId, text, source?}`) is consumed off
`bus.outbox` by an `EventBusListenerContainer` bean (`EventBusListenerConfig`) and handled
by `NotifyEventHandler`, which delivers via the same `NotifySender` the REST path uses.
Single recipient by design — notifier stays domain-agnostic (text only); a producer that
wants a whole household emits one event per member.

**Retry policy** (the bus is at-least-once): a transient failure (profile/gateway 5xx,
timeout, network) makes the handler throw, so the outbox row stays `PENDING` for the next
drain. A permanent failure (404 unknown user / 422 no telegram link / unparsable payload)
is logged and accepted — re-throwing would head-of-line-block the single-consumer drain
(a known B1 limitation). notifier currently drains **all** topics; events other than
`notify.requested` are ignored (and thereby marked `PUBLISHED`).

## Configuration (env vars)

```
NOTIFIER_PORT=8084
NOTIFIER_DB_URL=jdbc:postgresql://postgres:5432/ailife   # consumes bus.outbox
NOTIFIER_DB_USER=ailife
NOTIFIER_DB_PASSWORD=ailife
PROFILE_SERVICE_URL=http://profile-service:8082
GATEWAY_TELEGRAM_URL=http://gateway-telegram:8080
INTERNAL_API_TOKEN=<shared with gateway-telegram>
EVENT_BUS_CHANNEL=ailife_events
```

## Status codes

- `202` — accepted and forwarded successfully
- `400` — bad request (missing fields)
- `404` — user not found
- `422` — user has no telegram_user_id linked yet

## Key classes
- `NotifierApplication` — `@Import(EventBusConfig.class)` for the bus producer/consumer wiring.
- `config/NotifierProperties` — `notifier.{profile-base-url, gateway-base-url, internal-api-token}`.
- `config/HttpConfig` — separate WebClients for profile + gateway (each via `.clone()` to avoid shared-builder leakage).
- `config/EventBusListenerConfig` — registers the `EventBusListenerContainer` consumer bean.
- `notify/NotifySender` — apply the proactive-UX gate, then resolve user → forward to gateway; shared by the REST and bus paths. `send(userId, text)` stays the reactive default; `send(userId, text, proactive, source)` is the gated overload.
- `notify/QuietHours` — pure value logic for the quiet-hours window (tz-aware, midnight-wrap, `nextWindowEnd`). Unit-tested.
- `notify/NotificationGate` — `evaluate` → HELD (park in `core.notification_held`) / SUPPRESSED (over `daily_cap`, counted via `core.notification_sent`) / PASS; `recordSent` logs a delivered proactive push (JDBC; inert when no `DataSource`).
- `notify/HeldRedrain` — redelivers held rows once their window opens and drops stale ones (JDBC; inert when no `DataSource`); `notify/HeldRedrainRunner` is its `@Scheduled` driver (conditional on `notifier.held-redrain-enabled`).
- `web/NotifyController` — thin `POST /v1/notify` wrapper over `NotifySender` (passes `NotifyRequest.proactive`).
- `bus/NotifyEventHandler` — consumes `notify.requested`, applies the transient/permanent retry policy (passes `proactive`/`source`).

## Failure mode
Per-user notifier failures are caller-swallowed by upstream fan-outs (one bad user
doesn't block the rest). See `TriggerController` in [calendar-agent](../../domains/calendar/calendar-agent)
for the loop that exploits this — it logs + continues when a single member's notify
POST fails.
