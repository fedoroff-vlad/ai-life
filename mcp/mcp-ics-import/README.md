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
