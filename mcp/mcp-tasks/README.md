# mcp-tasks

MCP server: source-of-truth GTD CRUD over the `tasks.*` schema (projects + items).
Stage-3 opener — the starter slice covers capture / read / projects; the GTD
transitions (clarify, complete, link-to-event) land in the next PR. See
[plans/tasks.md](../../plans/tasks.md).

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

Scope rule: every tool takes a `householdId` and reads/writes only within that
household. Per-user privacy (private items filtered by `owner_id`) is the agent
layer's job — this MCP is intentionally low-level.

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_TASKS_PORT` | `8095` | HTTP port |
| `MCP_TASKS_DB_URL` | `jdbc:postgresql://localhost:5432/ailife` | Postgres |
| `MCP_TASKS_DB_USER` / `MCP_TASKS_DB_PASSWORD` | `ailife` | DB credentials |

## Key classes

- `McpTasksApplication`.
- `domain/TaskProject` + `TaskProjectRepository` — JPA over `tasks.task_project`.
- `domain/TaskItem` + `TaskItemRepository` — JPA over `tasks.task_item`; `filter()` is
  the parameterised list query (all filters optional, due soonest-first, native-SQL
  `CAST` for NULL-safe binds — same pgjdbc workaround as mcp-finance).
- `tools/TasksMcpTools` — four `@Tool` methods (`upsert_project`, `list_projects`,
  `add_task`→inbox, `list_tasks`). No cross-entity invariants beyond the household
  scope; everything else relies on DB constraints.
- `tools/ToolsConfig` — `MethodToolCallbackProvider`.

## Schema

- [030-tasks.yml](../../infra/liquibase/features/030-tasks.yml) — `tasks.task_project`
  + `tasks.task_item` (status, context tag, due/defer, `calendar_event_uid` link,
  `schedule_id`) with indices on `(household_id, status)`, `project_id`, `due_at`,
  `(household_id, context)`. `calendar_event_uid` carries no cross-schema FK
  (calendar/Radicale own event lifecycle), mirroring `fin_budget.schedule_id`.
