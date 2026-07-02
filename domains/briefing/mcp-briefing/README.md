# mcp-briefing

Briefing domain-MCP (port **8114**). Source-of-truth CRUD over the `briefing.*` schema — the
**per-person briefing preferences** that drive the proactive morning digest. Owns `briefing_profile`
(location + interests + sections + schedule). The gather → synthesize digest flow lives in
`briefing-agent`; this MCP just persists. Mirrors `mcp-creator`. Plan:
[plans/briefing.md](../../../plans/briefing.md).

## Status (BR-f2)

The personalization store: `briefing_profile` keyed `(household_id, owner_id)` (null owner =
household-default) + the `set`/`get`/`listScheduled` tools + the `/internal/briefing-profile`
passthrough. **Bound by `briefing-agent`** (BR-c profiler write). **BR-f2**: `setBriefingProfile`
now auto-registers a per-profile `briefing.digest` cron in scheduler-service (and deletes/replaces it
on update/disable), so the per-person morning wake fires without a manually-created schedule.

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `setBriefingProfile` | `SetBriefingProfileInput` | `BriefingProfileDto` | upsert a person's prefs, keyed `(householdId, ownerId)`. Full set — every field overwrites. `latitude`/`longitude` are the geocoded coords of `locationLabel`; `interests`/`sections` are JSON arrays; `scheduleTime` ("HH:mm") + `scheduleEnabled` drive the wake. **Side effect (BR-f2):** when `scheduleEnabled` + `scheduleTime`, registers/replaces a `briefing.digest` cron in scheduler-service (payload `{ownerId}`, cron = the local time+`timezone` converted to UTC); disabling deletes it. Soft-fail — a scheduler outage doesn't block the write. |
| `getBriefingProfile` | `householdId`, `ownerId?` | `BriefingProfileDto` \| null | read a person's prefs (null ownerId = household-default). null when unset. |
| `listScheduledProfiles` | — | `BriefingProfileDto[]` | every profile with `scheduleEnabled=true`, across households — the scheduler's fan-out source (BR-f). |

## HTTP passthrough

| method | path | body / params | returns | purpose |
|--------|------|---------------|---------|---------|
| POST | `/internal/briefing-profile` | `SetBriefingProfileInput` | `BriefingProfileDto` (400 on missing `householdId`) | deterministic upsert (the briefing-profiler flow). |
| GET | `/internal/briefing-profile` | `householdId`, `ownerId?` | `BriefingProfileDto` (404 if unset) | read a person's prefs. |
| GET | `/internal/briefing-profile/scheduled` | — | `BriefingProfileDto[]` | the scheduled fan-out list (BR-f). |

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_BRIEFING_PORT` | `8114` | HTTP port (MCP/SSE + actuator). |
| `MCP_BRIEFING_DB_URL` | `jdbc:postgresql://localhost:5432/ailife` | Postgres (`briefing` schema). |
| `MCP_BRIEFING_DB_USER` / `MCP_BRIEFING_DB_PASSWORD` | `ailife` / `ailife` | DB credentials. |
| `SCHEDULER_URL` (`mcp-briefing.scheduler-url`) | `http://scheduler-service:8085` | scheduler-service base URL for the auto-registered `briefing.digest` cron (BR-f2). |

Schema owned: `briefing` (`briefing_profile`). Migration:
[`070-briefing.yml`](../../../infra/liquibase/features/070-briefing.yml). `briefing` schema created in
[`infra/postgres/init.sql`](../../../infra/postgres/init.sql).

## Key classes

- `McpBriefingApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `domain/BriefingProfile` (+ `Repository`) — the `briefing_profile` entity (incl. internal
  `schedule_id`, not in the DTO); `findForOwner` (null-owner CAST workaround) + `findByScheduleEnabledTrue`.
- `tools/BriefingMcpTools` — `set`/`get`/`listScheduled` `@Tool`s; `(household, owner)` upsert keying;
  the BR-f2 cron register/replace/delete side effect + `toUtcDailyCron` (local HH:mm+zone → UTC cron).
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the `@Tool`s.
- `web/InternalBriefingProfileController` — `POST`/`GET /internal/briefing-profile` (+ `/scheduled`).
- `scheduler/SchedulerClient` — thin blocking client to scheduler-service (`register`/`delete` the
  `briefing.digest` cron), soft-fail. Mirrors mcp-finance.
- `config/McpBriefingProperties` (`mcp-briefing.*`: scheduler URL + owner-agent/trigger-kind) +
  `config/HttpConfig` (`schedulerWebClient` bean).
