# mcp-tasks

MCP server: source-of-truth GTD CRUD over the `tasks.*` schema (projects + items),
including the GTD transitions (clarify / update / complete / delete / link-to-event).
See [plans/tasks.md](../../plans/tasks.md).

## Tools (MCP)

- `upsert_project(id?, householdId, ownerId?, name, status?, note?)` — create or
  update a GTD project (a multi-step outcome). `status` ∈ `active|someday|done|dropped`
  (default `active`). `ownerId` null = household-shared; non-null = private.
- `list_projects(householdId, status?)` — ordered by name; `status` filters, omit for all.
- `add_task(householdId, ownerId?, title, note?, source?)` — capture to the **inbox**
  (status=inbox, no project/context). The one-line capture GTD lives on. `source`
  defaults to `manual`.
- `list_tasks(householdId, status?, context?, projectId?, dueBefore?, limit?)` — all
  filters optional; ordered by due date soonest-first (undated last). Hard cap 200,
  default 50.
- `clarify_task(id, status, context?, projectId?, dueAt?, deferUntil?, priority?)` — the
  inbox→organized GTD move. `status` ∈ inbox|next|waiting|scheduled|done|dropped;
  organizing fields applied when non-null. `projectId` must belong to the task's
  household. status=done stamps `completedAt`; leaving done clears it.
- `update_task(id, title?, note?, context?, projectId?, priority?, dueAt?, deferUntil?)` —
  partial content edit (non-null fields only); status changes go through clarify/complete.
  `projectId` cross-household guarded. household/source/externalRef/createdAt immutable.
- `complete_task(id)` — status=done + `completedAt` stamp. Throws on unknown id.
- `delete_task(id)` — delete and return the deleted row (so the agent can confirm/undo).
  Throws on unknown id. Confirming the destructive action is the agent layer's job.
- `link_task_to_event(id, calendarEventUid)` — store the linked calendar event UID
  ("turn task into event"); creating the event itself is the agent's job (via
  orchestrator → calendar-agent).
- `enable_weekly_review(householdId, cron?)` — register the per-household GTD
  weekly-review recurring schedule in scheduler-service (wakes tasks-agent with
  `kind=weekly.review`). `cron` is an optional Spring cron (UTC); omit for the
  default weekly Monday 09:00. Idempotent: same cron → no-op, different cron →
  replace. Returns the active `ScheduleDto`.
- `disable_weekly_review(householdId)` — delete the household's weekly-review
  schedule. Returns the removed `ScheduleDto`, or null if none was active.

Scope rule: every tool takes a `householdId` and reads/writes only within that
household. Per-user privacy (private items filtered by `owner_id`) is the agent
layer's job — this MCP is intentionally low-level.

## Internal REST passthroughs

Non-MCP, no LLM tax — for system callers driven by scheduler-service.

- `GET /internal/review?householdId=<uuid>` → `WeeklyReviewResult` — the GTD
  weekly-review aggregate: inbox/waiting counts + capped samples + "stuck"
  active projects (no next-action). Used by tasks-agent's `weekly-review` skill
  to enrich its scheduler-driven wake payload. Mirrors mcp-finance's
  `/internal/budget-status`.

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_TASKS_PORT` | `8095` | HTTP port |
| `MCP_TASKS_DB_URL` | `jdbc:postgresql://localhost:5432/ailife` | Postgres |
| `MCP_TASKS_DB_USER` / `MCP_TASKS_DB_PASSWORD` | `ailife` | DB credentials |
| `SCHEDULER_URL` | `http://scheduler-service:8085` | scheduler-service for `enable_weekly_review` |
| `MCP_TASKS_REVIEW_CRON` | `0 0 9 * * MON` | default weekly-review cron (UTC) |
| `MCP_TASKS_REVIEW_OWNER_AGENT` | `tasks` | agent woken by the review schedule |
| `MCP_TASKS_REVIEW_TRIGGER_KIND` | `weekly.review` | trigger kind for the review schedule |

## Key classes

- `McpTasksApplication`.
- `domain/TaskProject` + `TaskProjectRepository` — JPA over `tasks.task_project`.
- `domain/TaskItem` + `TaskItemRepository` — JPA over `tasks.task_item`; `filter()` is
  the parameterised list query (all filters optional, due soonest-first, native-SQL
  `CAST` for NULL-safe binds — same pgjdbc workaround as mcp-finance).
- `tools/TasksMcpTools` — eleven `@Tool` methods: CRUD (`upsert_project`, `list_projects`,
  `add_task`→inbox, `list_tasks`) + GTD transitions (`clarify_task`, `update_task`,
  `complete_task`, `delete_task`, `link_task_to_event`) + weekly-review scheduling
  (`enable_weekly_review`, `disable_weekly_review`). The only invariants enforced
  here are the household scope, the `projectId` cross-household guard (clarify/update),
  and the status whitelist; everything else relies on DB constraints. `clarify_task` and
  `complete_task` keep `completed_at` consistent with the `done` state.
- `tools/ToolsConfig` — `MethodToolCallbackProvider`.
- `scheduler/SchedulerClient` — synchronous client to scheduler-service for the
  `weekly.review` cron. Reconciles by `(householdId, kind=weekly.review)` against the
  list endpoint (no local schedule-id column — the review is one-per-household).
- `config/McpTasksProperties` — `mcp-tasks.{scheduler-url, review.*}` config.
- `config/HttpConfig` — `schedulerWebClient` bean.
- `review/ReviewService` — read-only GTD weekly-review aggregation (inbox/waiting
  counts + samples + stuck active projects via `findActiveWithoutNextAction`).
- `web/InternalReviewController` — `GET /internal/review` over `ReviewService`.

## Schema

- [030-tasks.yml](../../infra/liquibase/features/030-tasks.yml) — `tasks.task_project`
  + `tasks.task_item` (status, context tag, due/defer, `calendar_event_uid` link,
  `schedule_id`) with indices on `(household_id, status)`, `project_id`, `due_at`,
  `(household_id, context)`. `calendar_event_uid` carries no cross-schema FK
  (calendar/Radicale own event lifecycle), mirroring `fin_budget.schedule_id`.
