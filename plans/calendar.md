# Calendar domain

Source of truth: **Radicale** (CalDAV). Postgres `calendar.*` = mirror/cache for fast queries + links. Apple/Google = read-only ICS subscriptions into an `external` calendar.

## Architecture: write-through Radicale, reads-from-cache
```
MCP tool call
  ├── create/update/delete → CalDavClient (WebClient PUT/DELETE) → Radicale → (on success) EventMirror → events_cache
  └── list/search          → EventsCacheRepository (PG read only)
```
Cache = authority for reads (fast, indexed, independent of Radicale uptime). Radicale = authority for writes (source for iOS/macOS CalDAV subscriptions). Full two-way sync is a later PR (external calendars); here we mirror only our own operations.

## Schema `calendar` (010-calendar.yml)
`calendar.events_cache`:
```
id, household_id, source_calendar, calendar_uid, etag,
summary, description, location, dtstart, dtend, rrule,
categories text[], person_id (FK NULL → core.people),
raw_ics, last_synced_at, created_at
```
Unique: `(household_id, source_calendar, calendar_uid)`. Indexes: `(household_id, dtstart)`, `person_id`, GIN `categories`.
`calendar.ics_subscriptions`: `id, household_id, name, url, last_synced_at, last_error` (for read-only external feeds).

## mcp-caldav (mcp/, port 8090)
Spring Boot, Spring AI MCP server (webflux starter), SSE at `/mcp/sse`. Backend: WebClient PUT/DELETE to Radicale (minimal CalDAV subset; no CalDAV4j unless PROPFIND/REPORT needed later). ical4j for ICS convert.
Tools: `create_event`, `update_event`, `delete_event`, `list_events`, `search_events`. Each write mirrors result into events_cache.
Env: `CALDAV_URL`, `CALDAV_USER`, `CALDAV_PASSWORD`, `CALDAV_DEFAULT_CALENDAR`, `MCP_CALDAV_PORT`, `DB_*`.

## mcp-ics-import (mcp/, port 8091)
Tool `pull_calendar(url)` — GET ICS, parse ical4j, store into Radicale `external` calendar (read-only marker in metadata). Scheduler registers an hourly cron per subscription.

## Per-item tenant scope (ADR-0001 slice 4)
Each event lives in **exactly one household = its visibility boundary**. On `create_event`, calendar-agent
resolves the item's `SharingScope` (explicit on `CreateEventInput.sharing`, else the default-sharing
policy: occasion categories → shared, everything else → private) against the acting user's
`GET /v1/users/{id}/household-routing` split (personal vs family) to a concrete `household_id`. A shared
choice with no family household degrades to personal; a request with no `userId` falls back to the
envelope household (pre-membership path). mcp-caldav stays tenant-agnostic — it writes to whatever
household it is handed. Reads honoring the caller's household set (personal ∪ shared) = slice 5 (#295).

## calendar-agent (agents/, port 8086)
AGENT.md (frontmatter + EN prompt). REST: `POST /agents/calendar/intent` (from orchestrator), `POST /agents/calendar/triggers/{kind}` (from scheduler), `POST /agents/calendar/skills/{skill}/invoke`.
Tools: all mcp-caldav. Cross-cutting: web-fetch, media, memory, telegram. May ask finance (gift budget) via orchestrator.
Principles: clarify ambiguous time; store tzid from profile; birthdays/anniversaries are occasions (people + lead_days), not plain events; for video/links extract date first → confirm → create; never write to another user's private scope; confirm before delete/bulk change.

## Skills (skills/calendar/)
- `birthday-greeter` — personalized greeting from profile + memories.
- `gift-recommender` — ideas from `core.people.interests` + past events/notes; may ask finance for budget. Endpoint + scheduler invokes 7 days before birthday.
- `video-link-analyzer` — via mcp-youtube transcript (or web-search fallback): extract date/subject → confirm → create event with source_ref.
- (later) `parse-datetime`, `recurrence-rules`, `reminder-format`.

## Reminders → scheduler-service
No own reminder table/tick. Agent calls `mcp-scheduler.schedule_once/recurring(target=calendar, payload=...)`. Scheduler wakes the agent via orchestrator; agent formats and sends via notifier→telegram. `core.people` holds occasion data (birthdays + lead_days), not schedules.
