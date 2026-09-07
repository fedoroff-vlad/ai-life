# libs/inbox

Durable inbound **inbox**: persist-before-process + poll-based deferred redrive, so a user message is
never silently dropped when a downstream service (orchestrator / a domain-agent / an MCP / llm-gateway)
is down or the ingress restarts mid-request ([#633](https://github.com/fedoroff-vlad/ai-life/issues/633)).

Mirror of [`libs/event-bus`](../event-bus/README.md) (the transactional **outbox**), **opposite
direction**: the outbox durably records outbound events on their way out; the inbox durably records
inbound user messages on their way in. Same at-least-once guarantee, same Postgres primitive.

## How it works
1. **Persist-before-process.** The ingress calls `InboxWriter.record(dedupKey, payloadJson)` right after
   it has a normalized, replayable message and **before** dispatching it downstream. The row lands
   `PENDING`, scheduled `next_attempt_at = now() + initialDelay` (a grace window).
2. **Idempotency / dedup.** `dedup_key` (the Telegram `update_id`) is `UNIQUE`; `record` inserts
   `ON CONFLICT DO NOTHING`, so a re-delivered update or a redrive re-entry never double-persists.
   `record` returns `true` only for a freshly inserted row.
3. **In-request happy path.** The ingress dispatches synchronously; on success it calls
   `InboxWriter.markProcessed(dedupKey)` — the redriver then never touches the row.
4. **Deferred redrive.** `PostgresInboxRedriver` polls every `poll-interval`, claims due
   `PENDING`/`FAILED` rows (`FOR UPDATE SKIP LOCKED`, so concurrent redrivers are safe), runs the
   ingress-supplied `InboxHandler` (re-dispatch + deliver the reply) inside the claim transaction, and
   marks the row `PROCESSED`. A handler throw reschedules the row with exponential backoff + jitter.
5. **Bounded + dead-letter.** After `max-attempts` a poison row goes terminal `DEAD` and the
   `DeadLetterHandler` fires once (best-effort), so the ingress can tell the user instead of looping.

No `LISTEN/NOTIFY` here (unlike the outbox): the grace window lets the synchronous attempt win the common
case, and inbound redrive after a multi-minute outage does not need sub-second latency — a plain poll
avoids the sync-vs-redrive double-dispatch race entirely.

## Wiring
`@Import(InboxConfig.class)` gives you the `InboxWriter` bean. The redrive side is opt-in — register your
own container with your handlers:

```java
@Bean
InboxRedriverContainer inboxRedriver(DataSource ds, InboxProperties props, GatewayInboxHandler h) {
    return new InboxRedriverContainer(ds, props, h, h::onDead);
}
```

The schema (`bus.inbox`) is applied by the central Liquibase feature
[`016-inbox.yml`](../../infra/liquibase/features/016-inbox.yml) — a consuming service needs only a
`DataSource`, not its own migrations.

## Configuration (`inbox.*`)
| prop | default | meaning |
|------|---------|---------|
| `inbox.enabled` | `true` | gates the **redrive** container only (the writer is always available) |
| `inbox.poll-interval` | `15s` | how long the redrive loop sleeps between drain passes |
| `inbox.initial-delay` | `30s` | grace before a fresh row is redrive-eligible (lets the sync attempt win) |
| `inbox.backoff` | `30s` | base of the exponential backoff between attempts |
| `inbox.max-backoff` | `10m` | ceiling for the backoff |
| `inbox.max-attempts` | `6` | attempts before a row goes terminal `DEAD` |

## Key classes
- `InboxWriter` — `record` (persist `PENDING`, dedup on key) + `markProcessed` (sync-success).
- `PostgresInboxRedriver` — background poll drain: claim due row → handler → `PROCESSED`, or backoff /
  `DEAD` + dead-letter on failure.
- `InboxRedriverContainer` — `SmartLifecycle` wrapper; honors `inbox.enabled`.
- `InboxHandler` / `DeadLetterHandler` — the ingress-supplied redrive + poison hooks.
- `InboxMessage` — one `bus.inbox` row handed to a handler (payload-agnostic JSON string).
- `InboxConfig` / `InboxProperties` — wiring + tuning.

## Consumers
- [`platform/gateway-telegram`](../../platform/gateway-telegram/README.md) — the Telegram ingress:
  persists each normalized message before calling the orchestrator, replies "queued" instead of dropping
  on a downstream outage, and redrives to deliver the answer when the outage clears.
