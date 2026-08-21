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
household it is handed.

**Per-member reads (slice 5, #295 — epic closer):** `mcp-caldav` `GET /internal/events` takes a
**repeatable** `householdId` and returns the union over the set. `calendar-web` resolves a per-person
feed's `ownerId` → the member's household set (personal ∪ shared) via profile-service
`GET /v1/users/{id}/households` and reads that union, so each member's ICS feed serves their own events
plus the shared family ones and nothing private to another member. An owner-less feed (static env feed /
legacy whole-household) serves its single household; a profile lookup failure falls back to the feed's
own household. mcp-caldav stays tenant-agnostic — the member→set resolution lives in calendar-web.

## calendar-agent (agents/, port 8086)
AGENT.md (frontmatter + EN prompt). REST: `POST /agents/calendar/intent` (from orchestrator), `POST /agents/calendar/triggers/{kind}` (from scheduler), `POST /agents/calendar/skills/{skill}/invoke`.
Tools: all mcp-caldav. Cross-cutting: web-fetch, media, memory, telegram. May ask finance (gift budget) via orchestrator.
Principles: clarify ambiguous time; store tzid from profile; birthdays/anniversaries are occasions (people + lead_days), not plain events; for video/links extract date first → confirm → create; never write to another user's private scope; confirm before delete/bulk change.

## Skills (skills/calendar/)
- `birthday-greeter` — personalized greeting from profile + memories.
- `gift-recommender` — ideas from `core.people.interests` + past events/notes; may ask finance for budget. Endpoint + scheduler invokes 7 days before birthday.
- `video-link-analyzer` — via mcp-youtube transcript (or web-search fallback): extract date/subject → confirm → create event with source_ref.
- (later) `parse-datetime`, `recurrence-rules`, `reminder-format`.

## H.2 — user-facing event CRUD via chat (road-test [#486](https://github.com/fedoroff-vlad/ai-life/issues/486))
Until now calendar's `/intent` was **chat-only** — events were created solely by the inter-agent
`create_event` action (tasks→calendar). Daily use needs the owner to **create / move / cancel** events by
just chatting ("запиши встречу завтра в 15", "перенеси на 16", "отмени встречу с врачом"). This also gives
the cross-cutting **undo primitive** its calendar producer (a user-turn create records `last_mutation`, so
"отмени последнее" cancels it) — the reason "calendar rollout" landed here rather than in the bare undo
rollout (calendar had no user-facing write to hang an undo handle on).

Built on the shared in-agent `SkillRouter` (#475, same as notes): a `CalendarIntentRouter` classifies the
message via each intent skill's SKILL.md description into one flow, or the chat fallback (`CalendarChat`,
which keeps the #195 ICS-feed nudge). mcp-caldav stays tenant-agnostic; the household is resolved by the
shared `SharingResolver` exactly as `create_event` already does.

**Subslices (≤5 files each):**
- **HC-1 — event capture (create via chat).** `event-capture` SKILL (strict-JSON `{summary, dtstart,
  dtend?}`, "now" passed for relative-date resolution) → `EventCapturer` resolves the household + creates via
  mcp-caldav `/internal/event` → confirms. Router + `CalendarChat` extraction. No undo yet.
- **HC-2 — undo a just-created event.** mcp-caldav `DELETE /internal/event/{id}` passthrough (delegates to
  the existing `delete_event` tool) + `CaldavEventClient.delete` + calendar `/actions/undo` + `EventCapturer`
  attaches `withUndo` (event id + summary). Closes the original "cancel the just-created event" undo goal.
- **HC-3 — cancel an event via chat + destructive-delete confirm gate.** `event-cancel` SKILL → resolve the
  target by description / "последнюю" (mcp-caldav search/list) → the pending-action confirm gate (Track A
  `/resume`) → delete. Confirm-before-delete is a standing calendar principle (above).
- **HC-4 — move / reschedule via chat.** `event-move` SKILL → resolve target → `UpdateEventInput` via a new
  `/internal/event/{id}` PUT passthrough (`update_event` tool).

**Acceptance criteria (WHEN/THEN):**
- Scenario: **create by chat.** WHEN the owner says "запиши встречу с врачом завтра в 15:00" → THEN calendar
  parses the time, creates the event in the resolved household, and confirms naming it (no id, no inter-agent
  hop).
- Scenario: **undo the just-created event.** WHEN the owner then says "отмени последнее" → THEN the event is
  cancelled and it confirms (undo primitive, HC-2).
- Scenario: **not an event.** WHEN the message isn't an event op ("когда у Маши др?") → THEN it falls through
  to the chat reply (and the first-message ICS-feed nudge still fires), never a spurious create.
- Scenario: **cancel by description confirms first.** WHEN the owner says "отмени встречу с врачом" → THEN
  calendar resolves the target and asks to confirm before deleting (destructive-delete gate, HC-3).
- Scenario: **ambiguous time is clarified.** WHEN the time can't be resolved → THEN calendar asks rather than
  filing a wrong-time event (standing "clarify ambiguous time" principle).

## Reminders → scheduler-service
No own reminder table/tick. Agent calls `mcp-scheduler.schedule_once/recurring(target=calendar, payload=...)`. Scheduler wakes the agent via orchestrator; agent formats and sends via notifier→telegram. `core.people` holds occasion data (birthdays + lead_days), not schedules.
