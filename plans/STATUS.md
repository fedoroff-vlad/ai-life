# STATUS — current in-flight work (MUTABLE: update at the end of each PR)

## Now
- **Stage:** 1 (calendar). **Branch:** `stage-1-pr9a-people-and-wake` (about to merge). PR9 was split into 9a/9b/9c.
- **Next goal (PR9b):** `calendar-agent` module skeleton + `AGENT.md` / `SKILL.md` convention (frontmatter + EN body) + manifest registration. No skills yet.
- **Then (PR9c):** birthday-greeter skill + intent routing in orchestrator (`LlmIntentClassifier` with few-shot from agent manifests) end-to-end with scheduler.

## Done (Stage 0, merged)
End-to-end echo path: gateway-telegram → orchestrator → llm-gateway (mock) → reply. PRs 1–5 merged.

## Done (Stage 1)
- **PR9a — `core.people` + People CRUD + orchestrator `/v1/agents/wake` stub (this PR):** New `core.people` table (`infra/liquibase/features/003-people.yml`) — household-scoped contacts for birthdays/gifts/greetings, distinct from `core.users`. `pg_trgm` enabled; GIN(interests jsonb_path_ops) + GIN(display_name gin_trgm_ops) + btree(household_id). Profile-service gains Person entity + repo, `PeopleController` (POST / GET / GET by-household / PATCH partial), `PersonDto` shared contract. Orchestrator gains `POST /v1/agents/wake` — looks up agent by name in the existing router registry, returns 202 if registered, 404 otherwise. Closes the dispatch gap for [[PR8]]: scheduler now hits a real endpoint instead of 404. Actual wake dispatch (calling an agent's handler with the payload) lands when calendar-agent in PR9b implements a wake handler. 3 profile-service tests + 2 orchestrator wake tests green; full reactor green.
- **PR8 — scheduler-service + `core.schedules` / `core.shedlock`:** `platform/scheduler-service` (8085). `@EnableScheduling` + ShedLock (JDBC `core.shedlock`). Fixed-delay tick (default 30s, `scheduler.tick-millis`) wrapped in `@SchedulerLock("scheduler-tick")` reads due rows from `core.schedules` and POSTs orchestrator `/v1/agents/wake` (`AgentWakeRequest{scheduleId, householdId, agent, kind, payload}`). Cron recurring vs one-shot `runAt`; cron uses Spring's `CronExpression` (UTC). Per-row tx so a failed wake leaves the row at its current `next_run_ts` for retry. `TickRunner` is the production fixed-delay loop; `ScheduleTick` is the testable unit (tests invoke it directly with `scheduler.tick-millis=3600000`). REST API: create / list-by-household / pause / resume / delete. New feature changelog `infra/liquibase/features/002-scheduling.yml`. ShedLock 5.16.0. Integration test: pgvector PG + OkHttp MockWebServer as orchestrator, 4 tests green. Note: orchestrator's `/v1/agents/wake` endpoint is implemented in PR9 — until then dev wake-ups 404 and the row sits in due state, which is the intended retry behaviour.
- **PR7 — notifier-service + gateway-telegram `/internal/send`:** `platform/notifier-service` (8084) resolves `userId → telegram_user_id` via profile-service and forwards to gateway-telegram's `POST /internal/send` (Bearer `${GATEWAY_INTERNAL_API_TOKEN}`). Bot token stays in gateway. New contracts `NotifyRequest` + `InternalSendRequest`. `TelegramClient` lifted to a conditional Spring bean so both `BotRegistration` and `InternalSendController` share it via `ObjectProvider`. 12 modules, 21 tests green.
- **PR6 — calendar schema + `mcp-caldav`:** Spring AI 1.0.8 webflux MCP starter, ical4j 4.2.5. 5 tools (create/update/delete/list/search) over Radicale, write-through with PG mirror in `calendar.events_cache`. Testcontainers integration test (pgvector PG + tomsquest/docker-radicale).
  - **Radicale gotchas captured here so we don't relearn them:**
    1. Principal `/{household}/` must be MKCOL'd before MKCALENDAR `/{household}/{calendar}/`, otherwise PUT events get 409 "Conflict in the request". `CalDavClient.ensureCollection` does both, idempotent (treats 405/409 as "already exists").
    2. `tomsquest/docker-radicale` image has no bash, so Testcontainers' default port listening check no-ops and the wait returns instantly. Use `Wait.forHttp("/.web/").forStatusCodeMatching(c -> c >= 200 && c < 500)` instead — the `/.web/` UI endpoint returns 200 only after the Python server has bound.
    3. Custom Radicale config mounts to `/etc/radicale/config` (not `/config/config`). Tests mount a copy with `[rights] type = none` so anonymous (auth=none) requests can create/write collections.

## Next PRs (Stage 1)
- PR9b: calendar-agent module skeleton + AGENT.md/SKILL.md convention + manifest registration.
- PR9c: birthday-greeter skill + intent routing in orchestrator + scheduler→orchestrator→agent end-to-end wake.
- PR10: gift-recommender skill.
- PR11: mcp-ics-import (read-only Apple/Google subscriptions).
- PR12: proactive birthday flow end-to-end (closing test of Stage 1).

## Workflow reminder
Run only the relevant test class while iterating; full suite once before PR (CI is the authority). Don't paste full logs — extract failing assertion + ~3 lines. Auto-merge squash on green, delete branch. Start a fresh Claude Code session after each merged PR.
