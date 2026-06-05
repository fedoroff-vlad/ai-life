# scheduler-service

Owns `core.schedules`. Reads due rows on a fixed-delay tick (ShedLock-coordinated so
only one instance fires per tick) and POSTs `POST /v1/agents/wake` on orchestrator with
the schedule's payload. Does **not** think or format — strictly a trigger.

## REST API

| method | path                          | purpose                                |
|--------|-------------------------------|----------------------------------------|
| POST   | `/v1/schedules`               | create (one of `cron` / `runAt`)       |
| GET    | `/v1/schedules?householdId=`  | list for a household, ordered by next  |
| POST   | `/v1/schedules/{id}/pause`    | disable without deleting               |
| POST   | `/v1/schedules/{id}/resume`   | re-enable                              |
| DELETE | `/v1/schedules/{id}`          | drop                                   |
| GET    | `/actuator/health`            | liveness                               |

Bad input (missing fields, both cron+runAt, invalid cron) → 400.

## Tick

`@Scheduled(fixedDelay = SCHEDULER_TICK_MILLIS)`, default 30s. Wrapped in
`@SchedulerLock("scheduler-tick")`. Each due row processed independently — a failed
wake leaves the row at its current `next_run_ts` for retry on the next tick.

## Configuration

```
SCHEDULER_PORT=8085
SCHEDULER_DB_URL=jdbc:postgresql://localhost:5432/ailife
SCHEDULER_DB_USER=ailife
SCHEDULER_DB_PASSWORD=ailife
ORCHESTRATOR_URL=http://orchestrator:8083
SCHEDULER_TICK_MILLIS=30000
SCHEDULER_BATCH_SIZE=50
```

## Tests

`mvn -B -pl platform/scheduler-service -am test` — Testcontainers pgvector PG
(no AGE needed) + OkHttp MockWebServer as orchestrator. Tick is invoked directly
(not via `@Scheduled`) so tests are deterministic.

## Key classes
- `SchedulerApplication`.
- `config/SchedulerProperties` — `scheduler.{orchestrator-base-url, tick-millis, batch-size}`.
- `config/HttpConfig` — WebClient bean for orchestrator.
- `config/ShedLockConfig` — JDBC lock provider over `core.shedlock`.
- `domain/Schedule` + `domain/ScheduleRepository` — JPA over `core.schedules`.
- `domain/NextRunCalculator` — cron → next `Instant` (Spring `CronExpression`, UTC). One-shot rows clear `cron` and have `run_at` only.
- `domain/ScheduleService` — CRUD + lifecycle (pause/resume/delete).
- `orchestrator/OrchestratorClient` — POST `/v1/agents/wake`.
- `tick/ScheduleTick` — pure tickable: read due rows in a per-row tx, POST, recompute next or `enabled=false` for one-shots. **Test-friendly: invoke directly.**
- `tick/TickRunner` — `@Scheduled(fixedDelay)` wrapper, `@SchedulerLock("scheduler-tick")`.
- `web/ScheduleController` — REST endpoints from the table above.

## Schema
[002-scheduling.yml](../../infra/liquibase/features/002-scheduling.yml) — `core.schedules` + `core.shedlock` (standard ShedLock 4.x schema).
