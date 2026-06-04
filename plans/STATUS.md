# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

## Now
- **Stage:** 1 (calendar). **Branch:** `stage-1-pr7-notifier` (about to merge). **Renumber note:** original PR7 (notifier+scheduler) was split — scheduler now lives in PR8, calendar-agent and the rest shift +1.
- **Next goal (new PR8):** `scheduler-service` (8085) + `infra/liquibase/features/002-scheduling.yml` (`core.shedlock` + `core.schedules`). Per `plans/platform.md`: `@EnableScheduling` + ShedLock (JDBC) — **not Quartz**. Tick reads due jobs and POSTs orchestrator to wake target agent. Does not think or format.

## Done (Stage 0, merged)
End-to-end echo path: gateway-telegram → orchestrator → llm-gateway (mock) → reply. PRs 1–5 merged.

## Done (Stage 1)
- **PR7 — notifier-service + gateway-telegram `/internal/send` (this PR):** `platform/notifier-service` (8084) resolves `userId → telegram_user_id` via profile-service and forwards to gateway-telegram's `POST /internal/send` (Bearer `${GATEWAY_INTERNAL_API_TOKEN}`). Bot token stays in gateway. New contracts `NotifyRequest` + `InternalSendRequest`. `TelegramClient` lifted to a conditional Spring bean so both `BotRegistration` and `InternalSendController` share it via `ObjectProvider`. 12 modules, 21 tests green.
- **PR6 — calendar schema + `mcp-caldav`:** Spring AI 1.0.8 webflux MCP starter, ical4j 4.2.5. 5 tools (create/update/delete/list/search) over Radicale, write-through with PG mirror in `calendar.events_cache`. Testcontainers integration test (pgvector PG + tomsquest/docker-radicale).
  - **Radicale gotchas captured here so we don't relearn them:**
    1. Principal `/{household}/` must be MKCOL'd before MKCALENDAR `/{household}/{calendar}/`, otherwise PUT events get 409 "Conflict in the request". `CalDavClient.ensureCollection` does both, idempotent (treats 405/409 as "already exists").
    2. `tomsquest/docker-radicale` image has no bash, so Testcontainers' default port listening check no-ops and the wait returns instantly. Use `Wait.forHttp("/.web/").forStatusCodeMatching(c -> c >= 200 && c < 500)` instead — the `/.web/` UI endpoint returns 200 only after the Python server has bound.
    3. Custom Radicale config mounts to `/etc/radicale/config` (not `/config/config`). Tests mount a copy with `[rights] type = none` so anonymous (auth=none) requests can create/write collections.

## Next PRs (Stage 1)
- PR8: scheduler-service (ShedLock + `core.schedules`).
- PR9: calendar-agent + AGENT.md/SKILL.md convention + intent routing + `core.people` + birthday-greeter.
- PR10: gift-recommender skill.
- PR11: mcp-ics-import (read-only Apple/Google subscriptions).
- PR12: proactive birthday flow end-to-end (closing test of Stage 1).

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR.
