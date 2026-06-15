# libs/event-bus

Shared **async event bus** over Postgres `LISTEN/NOTIFY` + a transactional outbox
(`bus.outbox`). Lets a service fan out a domain event without a direct call to the
consumer — the locked inter-agent async transport (architecture.md §Decisions: agents
talk only via the orchestrator (sync) or this bus (async); no new broker infra).

Stage 4, Track B. **B1: the adapter + table. B2: the Spring-bean API + first producer
(scheduler-service emits `schedule.fired`).** The consumer side (a real service running a
listener) lands with the first async chain, Track C2.

## Model

- `bus.outbox` is the **durable, append-only log** — the source of truth.
- A producer INSERTs a PENDING row **inside its business transaction** and fires
  `pg_notify` on the same connection, so the row + wake-up commit atomically.
- A listener `LISTEN`s on one channel and, on every wake (a NOTIFY **or** the poll
  timeout), drains PENDING rows oldest-first, dispatches each to a handler, and marks
  it PUBLISHED — all in one transaction per row, claimed with `FOR UPDATE SKIP LOCKED`.
- **At-least-once:** NOTIFY only wakes the loop early; the periodic poll delivers even
  if a notification is lost (listener was down). A handler that throws rolls back, so
  the row stays PENDING and the next poll retries it.

## Key classes (package `dev.fedorov.ailife.bus`)

| Class | Role |
|---|---|
| `EventBus` | Constants — `DEFAULT_CHANNEL = "ailife_events"` (the single NOTIFY channel). |
| `EventBusMessage` | Envelope record `(id, topic, householdId, payload, occurredAt)`; `payload` is a raw JSON string, `householdId` nullable for system-wide events. |
| `OutboxPublisher` | `publish(topic, householdId, payloadJson)` — INSERT row + `pg_notify`, runs on the caller's `JdbcTemplate` (caller's transaction). |
| `PostgresEventBusListener` | `AutoCloseable` LISTEN/NOTIFY adapter: `start()` spins a daemon thread that drains PENDING rows to a `Consumer<EventBusMessage>` handler; `close()` stops it. |
| `EventBusConfig` | `@Configuration` a service `@Import`s — provides the `OutboxPublisher` bean (producer). |
| `EventBusProperties` | `event-bus.{enabled, channel, poll-millis}`. `enabled` gates the listener only; the producer is always available. |
| `EventBusListenerContainer` | `SmartLifecycle` wrapper a **consumer** service registers as a `@Bean` (with its handler) — starts/stops the listener with the context. |

## Spring wiring

`@Import(EventBusConfig.class)` from the service's application class (the config lives
outside any service's component-scan root). That gives the `OutboxPublisher` producer
bean automatically. A consumer additionally declares an `EventBusListenerContainer`
`@Bean` with its own handler. Reference producer: `scheduler-service` `ScheduleTick`
publishes `schedule.fired` after each agent wake (best-effort — a bus failure never rolls
back the wake).

## Schema

`infra/liquibase/features/007-bus.yml` — `bus` schema + `bus.outbox`
`(id, topic, household_id?, payload jsonb, status, created_at, published_at?)`,
index `ix_outbox_pending (status, created_at)`. `household_id` has **no FK** (the log
must outlive / not block on a household row). Single PENDING/PUBLISHED status — per-
consumer offsets are out of scope for B1.

## Tests

`EventBusIntegrationTest` (Testcontainers `postgres:16`, `test-schema.sql` mirrors the
migration): NOTIFY-driven delivery + mark PUBLISHED; a row published before the listener
starts is still drained (at-least-once via the start/poll drain); a failing handler leaves
the row PENDING for retry.
