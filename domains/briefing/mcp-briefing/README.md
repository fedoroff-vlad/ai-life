# mcp-briefing

Briefing domain-MCP (port **8114**). Source-of-truth CRUD over the `briefing.*` schema — the
**per-person briefing preferences** that drive the proactive morning digest. Owns `briefing_profile`
(location + interests + sections + schedule). The gather → synthesize digest flow lives in
`briefing-agent`; this MCP just persists. Mirrors `mcp-creator`. Plan:
[plans/briefing.md](../../../plans/briefing.md).

## Status (BR-b)

The personalization store: `briefing_profile` keyed `(household_id, owner_id)` (null owner =
household-default) + the `set`/`get`/`listScheduled` tools + the `/internal/briefing-profile`
passthrough. **Bound by `briefing-agent` in BR-c** (profiler write) and the **scheduler in BR-f**
(scheduled fan-out read). No caller yet.

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `setBriefingProfile` | `SetBriefingProfileInput` | `BriefingProfileDto` | upsert a person's prefs, keyed `(householdId, ownerId)`. Full set — every field overwrites. `latitude`/`longitude` are the geocoded coords of `locationLabel`; `interests`/`sections` are JSON arrays; `scheduleTime` ("HH:mm") + `scheduleEnabled` drive the wake. |
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

Schema owned: `briefing` (`briefing_profile`). Migration:
[`070-briefing.yml`](../../../infra/liquibase/features/070-briefing.yml). `briefing` schema created in
[`infra/postgres/init.sql`](../../../infra/postgres/init.sql).

## Key classes

- `McpBriefingApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `domain/BriefingProfile` (+ `Repository`) — the `briefing_profile` entity; `findForOwner`
  (null-owner CAST workaround) + `findByScheduleEnabledTrue`.
- `tools/BriefingMcpTools` — `set`/`get`/`listScheduled` `@Tool`s; `(household, owner)` upsert keying.
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the `@Tool`s.
- `web/InternalBriefingProfileController` — `POST`/`GET /internal/briefing-profile` (+ `/scheduled`).
