# mcp-caldav

MCP server that exposes calendar CRUD as tools, backed by Radicale (CalDAV) with a
Postgres mirror cache for fast reads.

## Tools

| name           | summary                                                            |
|----------------|--------------------------------------------------------------------|
| `createEvent`  | Create a new event. Writes to Radicale, mirrors to cache.          |
| `updateEvent`  | Update an event by internal id. Idempotent PUT on Radicale.        |
| `deleteEvent`  | Delete an event by internal id.                                    |
| `listEvents`   | Events in a `[from, to)` range. Reads from cache only.             |
| `searchEvents` | Fuzzy match on summary (PostgreSQL `pg_trgm`).                     |

All tool method descriptions are in English (token economy).

## Internal REST (non-MCP)

| method | path              | purpose                                                            |
|--------|-------------------|--------------------------------------------------------------------|
| POST   | `/internal/event` | Create an event deterministically (body `CreateEventInput` → `CalendarEventDto`, 400 on bad input). |

Passthrough straight to the `createEvent` tool for callers that already have the
concrete fields and don't need an LLM to pick the tool — first consumer is
calendar-agent's `create_event` action (Stage 4 / C1 task-to-event chain). Mirrors
mcp-finance's `POST /internal/transaction`.

## Architecture

- **Write-through Radicale:** every create/update/delete is sent to Radicale first, then
  mirrored to `calendar.events_cache`.
- **Read from cache:** `listEvents` / `searchEvents` go straight to Postgres. They do not
  touch Radicale. Full two-way sync with external calendars is in `mcp-ics-import` (PR10).

## Configuration

| env var                    | default                                | purpose                            |
|----------------------------|----------------------------------------|------------------------------------|
| `MCP_CALDAV_PORT`          | `8090`                                 | HTTP port                          |
| `MCP_CALDAV_DB_URL`        | `jdbc:postgresql://localhost:5432/ailife` | datasource              |
| `MCP_CALDAV_DB_USER`       | `ailife`                               |                                    |
| `MCP_CALDAV_DB_PASSWORD`   | `ailife`                               |                                    |
| `CALDAV_URL`               | `http://localhost:5232`                | Radicale base URL                  |
| `CALDAV_USER`              | (empty)                                | basic-auth user (dev: none)        |
| `CALDAV_PASSWORD`          | (empty)                                | basic-auth password (dev: none)    |
| `CALDAV_DEFAULT_CALENDAR`  | `ours`                                 | collection name per household      |

## MCP transport

Spring AI's `spring-ai-starter-mcp-server-webflux` starter exposes the MCP protocol
over Server-Sent Events at `/sse` (clients also POST to `/mcp/message`). Clients should
use the official MCP SDK or Spring AI's MCP client wrapper rather than calling endpoints
by hand.

## Run locally

```sh
docker compose -f infra/docker-compose.dev.yml up -d   # Postgres + Radicale
mvn -B -pl domains/calendar/mcp-caldav -am spring-boot:run
```

## Tests

```sh
mvn -B -pl domains/calendar/mcp-caldav -am verify
```

Spins up two Testcontainers (Postgres `pgvector/pgvector:pg16` + `tomsquest/docker-radicale`)
and runs an end-to-end CRUD flow asserting both the Radicale upstream and the cache row.

## Key classes
- `McpCaldavApplication`.
- `config/McpCaldavProperties` — `caldav.{url, user, password, default-calendar}`.
- `config/HttpConfig` — `WebClient` for Radicale (basic-auth filter if creds present).
- `caldav/CalDavClient` — write-only CalDAV: idempotent `MKCOL` (principal) → `MKCALENDAR` → `PUT`/`DELETE`. 405/409 are swallowed (already-exists).
- `caldav/IcsConverter` — render single-VEVENT VCALENDAR for `PUT`.
- `domain/CalendarEvent` — JPA over `calendar.events_cache`.
- `domain/EventsCacheRepository` — `findInRange` (indexed scan) + `searchBySimilarity` (`pg_trgm`).
- `domain/EventMirror` — applies upstream CalDAV op result to the cache row.
- `tools/CalendarMcpTools` — the 5 `@Tool` methods.
- `tools/ToolsConfig` — exposes them via `MethodToolCallbackProvider`.
- `web/InternalEventController` — `POST /internal/event` passthrough to `createEvent` (non-MCP; for deterministic agent callers).

## Schema
[010-calendar.yml](../../infra/liquibase/features/010-calendar.yml) — `calendar.events_cache`
(unique `(household_id, source_calendar, calendar_uid)`, indexes on `(household_id, dtstart)`,
`person_id`, GIN on `categories`).

## Radicale gotchas (captured so we don't relearn them)
1. Principal `/{household}/` must be `MKCOL`'d before `MKCALENDAR /{household}/{calendar}/`, otherwise `PUT` events return 409 "Conflict in the request". `CalDavClient.ensureCollection` does both, idempotent.
2. `tomsquest/docker-radicale` has no bash, so Testcontainers' default port-listening check no-ops and the wait returns instantly. Use `Wait.forHttp("/.web/")` — the web UI endpoint returns 200 only once Python has bound.
3. Custom Radicale config mounts at `/etc/radicale/config` (not `/config/config`). Tests mount with `[rights] type = none` so anonymous (auth=none) can MKCALENDAR/PUT.
