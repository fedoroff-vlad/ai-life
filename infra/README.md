# infra

Local development infrastructure for ai-life.

## Start

```sh
cd infra
cp .env.example .env   # edit if you want non-default ports/passwords
docker compose -f docker-compose.dev.yml up -d
```

Liquibase runs automatically after Postgres becomes healthy and applies
`liquibase/db.changelog-master.xml`. The `liquibase` container exits with code 0 once
migrations are done — that is expected.

## Stop

```sh
docker compose -f docker-compose.dev.yml down
```

Add `-v` to also remove the volumes (`postgres-data`, `radicale-data`, `minio-data`)
if you want a clean slate.

## Services and default ports

| service  | port           | purpose                                     |
|----------|----------------|---------------------------------------------|
| postgres | 5432           | core DB (pgvector + pg_trgm; AGE later)     |
| minio    | 9000 / 9001    | S3-compatible object store / web console    |
| radicale | 5232           | CalDAV server (calendar source of truth)    |

Default credentials are in `.env.example`. Replace them in your `.env` before
exposing anything to a network.

## DB conventions

Schemas split by **bounded context**, not by service:

| schema   | owner / writers                                            |
|----------|------------------------------------------------------------|
| core     | profile-service, orchestrator, gateway-telegram            |
| memory   | memory-service                                             |
| audit    | every service (writes); observability (reads)              |
| bus      | event-bus library (outbox + LISTEN/NOTIFY)                 |
| media    | gateway-telegram (writes); agents (read)                   |
| calendar | mcp-caldav, calendar-agent, scheduler-service              |
| finance  | mcp-finance, finance-agent, mcp-money-pro-import           |
| tasks    | mcp-tasks, tasks-agent                                     |

All Liquibase changesets are applied by one job; ordering is controlled by
`db.changelog-master.xml`.
