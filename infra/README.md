# infra

Local development infrastructure + full-system compose for ai-life.

## Two compose files — pick the right one

| file | covers | when |
|---|---|---|
| `docker-compose.dev.yml` | postgres + liquibase + radicale + minio (one-shot liquibase, no app services) | IDE-driven development. Run JVMs from IntelliJ pointed at host ports. |
| `docker-compose.yml` | everything in `dev.yml` (minus MinIO) **plus** all 7 platform services + 2 agents + 4 MCP servers | End-to-end smoke testing, Mac Studio deployment, validating a release. |

Both files share `.env` and the same Postgres volume — they don't conflict, but
don't run them at the same time either (port collisions on 5432 / 5232).

## Start (IDE-driven dev)

```sh
cd infra
cp .env.example .env   # edit if you want non-default ports/passwords
docker compose -f docker-compose.dev.yml up -d
```

Liquibase runs automatically after Postgres becomes healthy and applies
`liquibase/db.changelog-master.xml`. The `liquibase` container exits with code 0 once
migrations are done — that is expected.

## Start (full system)

```sh
cd infra
cp .env.example .env
docker compose -f docker-compose.yml up -d --build   # first run builds 13 images
docker compose -f docker-compose.yml logs -f gateway-telegram
```

First build is ~5–10 minutes (one Maven build per Dockerfile; `--mount=type=cache`
keeps `~/.m2` warm between subsequent builds). Subsequent `up` calls are fast.

Only `gateway-telegram` exposes a host port (default 8080) — everything else
talks via the internal `ai-life` docker network. `postgres` / `radicale` host
ports are still exposed for psql / Radicale UI access.

## Stop

```sh
docker compose -f docker-compose.dev.yml down       # or docker-compose.yml
```

Add `-v` to also remove the volumes (`postgres-data`, `radicale-data`, `minio-data`
in dev; `postgres-data`, `radicale-data` in full) if you want a clean slate.

## Services and default ports

| service              | port | purpose                                                       |
|----------------------|------|---------------------------------------------------------------|
| postgres             | 5432 | core DB (pgvector + pg_trgm; AGE later)                       |
| radicale             | 5232 | CalDAV server (calendar source of truth)                      |
| minio                | 9000 / 9001 | S3-compatible object store / web console               |
| gateway-telegram     | 8080 | Telegram webhook receiver — **the only externally-exposed app** |
| llm-gateway          | 8081 | Provider-agnostic LLM channel                                 |
| profile-service      | 8082 | Identity / households / people                                |
| orchestrator         | 8083 | Intent routing                                                |
| notifier-service     | 8084 | Outbound push (back through gateway-telegram)                 |
| scheduler-service    | 8085 | Cron / one-shot triggers                                      |
| calendar-agent       | 8086 | Calendar domain agent                                         |
| memory-service       | 8087 | pgvector recall + single-hop relations                        |
| media-service        | 8088 | media catalogue (MinIO blobs + metadata)                      |
| conversation-service | 8089 | short-term conversation control state (route-lock + pending)  |
| mcp-caldav           | 8090 | CalDAV CRUD MCP                                               |
| mcp-ics-import       | 8091 | ICS subscription puller                                       |
| mcp-finance          | 8092 | Finance CRUD MCP                                              |
| finance-agent        | 8093 | Finance domain agent                                          |
| mcp-money-pro-import | 8094 | Money Pro CSV importer                                        |
| mcp-tasks            | 8095 | Tasks/GTD CRUD MCP                                            |
| tasks-agent          | 8096 | Tasks/GTD domain agent                                        |
| mcp-media-processing | 8097 | Media-understanding capability-MCP (OCR + vision caption; STT later) |
| mcp-web              | 8098 | Web capability-MCP (web_search + fetch_url) over SearXNG         |
| researcher-agent     | 8099 | Web research specialist (binds mcp-web)                          |
| searxng              | 8888 | Self-hosted meta-search (backing service for mcp-web; JSON API)  |

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
| media    | media-service (writes); gateway-telegram + agents (via media-service) |
| calendar | mcp-caldav, calendar-agent, scheduler-service              |
| finance  | mcp-finance, finance-agent, mcp-money-pro-import           |
| tasks    | mcp-tasks, tasks-agent                                     |

All Liquibase changesets are applied by one job; ordering is controlled by
`db.changelog-master.xml`.
