# libs/event-bus

**Placeholder.** Reserved for the shared Postgres `LISTEN/NOTIFY` async-event helper so
agents can fan out wake-ups without each writing its own JDBC `LISTEN` loop. Currently
only contains `package-info.java`.

Per `plans/architecture.md`: inter-service comms are HTTP/SSE for sync and
LISTEN/NOTIFY for async. The async side has no consumers yet, so this lib is empty.

## When to fill this in
The first time an agent needs to react to a domain event from another service (e.g.
finance-agent reacting to `calendar.events_cache` inserts to recompute budget). Goes
here, not into the consumer module.
