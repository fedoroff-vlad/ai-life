# mcp-ics-import

MCP server: pull read-only ICS subscriptions (Apple iCloud, Google public calendars)
into Radicale and the shared `calendar.events_cache`.

## Tools

- `add_subscription(householdId, name, url)` — register a feed and pull it immediately.
- `pull_calendar(subscriptionId)` — re-sync one feed; upserts new/changed events, removes
  events that vanished from the feed. Captures fetch/parse errors into `last_error`.
- `list_subscriptions(householdId)`.
- `remove_subscription(subscriptionId)` — drop the row, mirrored events, and the
  Radicale collection.

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

Scheduler-driven hourly re-pull lands in a follow-up PR (one `pull_calendar` cron per
`calendar.ics_subscriptions` row).

## Key classes
- `McpIcsImportApplication`.
- `config/McpIcsImportProperties` — `icsimport.{caldav-url, caldav-user, caldav-password, collection-prefix, max-ics-bytes}`.
- `config/HttpConfig` — separate `WebClient`s for Radicale and the open-internet ICS fetcher (each `.clone()`d).
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
Reuses `calendar.events_cache` from [010-calendar.yml](../../infra/liquibase/features/010-calendar.yml)
for the mirror — `source_calendar = external-<slug>` is the discriminator.
