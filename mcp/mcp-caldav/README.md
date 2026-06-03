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
mvn -B -pl mcp/mcp-caldav -am spring-boot:run
```

## Tests

```sh
mvn -B -pl mcp/mcp-caldav -am verify
```

Spins up two Testcontainers (Postgres `pgvector/pgvector:pg16` + `tomsquest/docker-radicale`)
and runs an end-to-end CRUD flow asserting both the Radicale upstream and the cache row.
