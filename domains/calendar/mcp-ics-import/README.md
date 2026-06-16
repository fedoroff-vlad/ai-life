# mcp-ics-import

MCP server: pull read-only ICS subscriptions (Apple iCloud, Google public calendars)
into Radicale and the shared `calendar.events_cache`. Also auto-registers an hourly
re-pull cron in scheduler-service so external feeds stay fresh without manual ticks.

## Tools (MCP)

- `add_subscription(householdId, name, url)` — register a feed, pull it immediately, and
  **POST a recurring `ics.pull` schedule to scheduler-service** (`cron=0 0 * * * *`,
  payload `{subscriptionId}`). Scheduler unreachable → `schedule_id` stays null; a
  reconciliation pass can populate it later.
- `pull_calendar(subscriptionId)` — re-sync one feed; upserts new/changed events, removes
  events that vanished from the feed. Captures fetch/parse errors into `last_error`.
- `list_subscriptions(householdId)`.
- `remove_subscription(subscriptionId)` — DELETE the schedule first, then drop the row,
  mirrored events, and the Radicale collection.

## REST (non-MCP, for system callers)

- `POST /internal/pull/{subscriptionId}` — drives the same sync as `pull_calendar` but
  without the MCP / LLM surface. Returns the `PullCalendarResult` JSON. Used by
  calendar-agent's `ics.pull` system trigger so scheduler ticks don't pay the LLM tax.

Each subscription is mirrored into a per-subscription Radicale collection named
`<prefix>-<slug>` (default prefix `external`). The slug is derived from the display
name; non-ASCII names fall back to `feed-<uuid8>`.

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_ICS_IMPORT_PORT` | `8091` | HTTP port |
| `MCP_ICS_IMPORT_DB_URL` | `jdbc:postgresql://localhost:5432/ailife` | Postgres |
| `CALDAV_URL` | `http://localhost:5232` | Radicale base URL |
| `CALDAV_USER` / `CALDAV_PASSWORD` | empty | Optional basic-auth |
| `ICS_IMPORT_COLLECTION_PREFIX` | `external` | Per-subscription Radicale collection prefix |
| `ICS_IMPORT_MAX_BYTES` | `5242880` | Hard cap on a single ICS body |
| `icsimport.scheduler-url` | `http://scheduler-service:8085` | Scheduler base URL (for auto-registered cron) |
| `icsimport.pull-cron` | `0 0 * * * *` | Spring CronExpression (UTC) for re-pull |
| `icsimport.pull-owner-agent` | `calendar` | Logical owner_agent of the registered schedule |
| `icsimport.pull-trigger-kind` | `ics.pull` | Trigger kind the agent handler dispatches on |

## Key classes
- `McpIcsImportApplication`.
- `config/McpIcsImportProperties` — `icsimport.{caldav-url, caldav-user, caldav-password, collection-prefix, max-ics-bytes}`.
- `config/HttpConfig` — separate `WebClient`s for Radicale, the open-internet ICS fetcher, and scheduler-service (each `.clone()`d).
- `scheduler/SchedulerClient` — sync (block) WebClient to scheduler-service. `register(householdId, subscriptionId)` POSTs `/v1/schedules` and returns the new id; `delete(scheduleId)` DELETEs (404 swallowed). Scheduler unreachable → returns null / no-op.
- `web/InternalPullController` — `POST /internal/pull/{id}` REST passthrough (non-MCP).
- `caldav/CalDavWriteClient` — duplicated from mcp-caldav for now; will lift to `libs/caldav-client` when a third consumer appears. Adds `deleteCollection` (used on `remove_subscription`).
- `ics/IcsFetcher` — `webcal://` → `https://` normalisation; body capped at `ICS_IMPORT_MAX_BYTES`; 30 s timeout.
- `ics/IcsParser` — ical4j 4.2.5 → list of `ParsedEvent`. UID preserved verbatim; `getStartDate()`/`getEndDate()` return `Optional<DateProperty>`; `Summary`/`Description`/`Location` are direct (may be null); `RRULE` via `getProperty("RRULE")`.
- `domain/IcsSubscription` + `Repository` — JPA over `calendar.ics_subscriptions`.
- `domain/ExternalEvent` + `Repository` — JPA over `calendar.events_cache` rows where `source_calendar` matches `external-<slug>`. **Schema is owned by mcp-caldav's `010-calendar.yml`**, this entity is a second JPA view.
- `sync/SubscriptionSync` — fetch → parse → diff (incoming UID set vs cached rows) → upsert + delete-missing. Captures fetch/parse errors into `IcsSubscription.last_error` and returns them in `PullCalendarResult.error` rather than throwing.
- `tools/IcsImportMcpTools` — the 4 `@Tool` methods + slug derivation (`Normalizer.NFKD` + ASCII-only; non-ASCII names → `feed-<uuid8>` fallback).
- `tools/ToolsConfig` — `MethodToolCallbackProvider`.

## Schema
[011-ics-subscriptions.yml](../../infra/liquibase/features/011-ics-subscriptions.yml) —
`calendar.ics_subscriptions` (unique `(household_id, slug)`, FK to `core.households`).
[012-ics-subscriptions-schedule-id.yml](../../infra/liquibase/features/012-ics-subscriptions-schedule-id.yml)
adds the nullable `schedule_id uuid` link to the auto-registered cron (no FK — soft
coupling so a manually-deleted scheduler row doesn't break cleanup). Reuses
`calendar.events_cache` from [010-calendar.yml](../../infra/liquibase/features/010-calendar.yml)
for the mirror — `source_calendar = external-<slug>` is the discriminator.
