# Tasks domain (GTD)

Stage-3 domain spec — owner-blessed 2026-06-12 (full GTD shape; inbox routing = explicit-only for now,
see below). Authority the Stage-3 PRs follow, same role finance.md / calendar.md play for their domains.

Source of truth: **Postgres `tasks.*`** (our own schema). Methodology: **GTD** — capture everything to
an Inbox, clarify into projects + next-actions with a context, engage by context/due. The system
never silently drops a capture; an unclarified item just sits in `inbox` until the user (or a skill)
clarifies it.

## GTD model → data
- **Inbox** = `task_item.status = 'inbox'` (default for every fresh capture; no project, no context yet).
- **Next action** = `status = 'next'` + a `context` (`@home`, `@work`, `@errand`, `@calls`, …).
- **Waiting-for** = `status = 'waiting'` (delegated; `note` holds who/what).
- **Scheduled** = `status = 'scheduled'` + `due_at` / `defer_until`; a hard date moves it toward calendar.
- **Someday/maybe** = project-level `status = 'someday'`.
- **Done / dropped** = terminal states (`completed_at` stamped on done).

Contexts are a free-text tag on the item (like finance's `period` varchar), **not** a lookup table —
GTD contexts are a small, user-personal, evolving set; a table buys nothing for an MVP and a re-tag
is one update. Promote to a table only if we need per-context config later.

## Schema `tasks` (030-tasks.yml)
- `task_project` — id, household_id, owner_id? (null = shared), name, status (`active|someday|done|dropped`),
  note, metadata jsonb, created_at.
- `task_item` — id, household_id, owner_id? (null = shared), project_id? (FK → task_project, null = standalone/inbox),
  title, status (`inbox|next|waiting|scheduled|done|dropped`), context? (text tag), priority? (smallint),
  due_at?, defer_until?, note, source (`manual|telegram`), external_ref?,
  **calendar_event_uid?** (set when an item is "turned into an event" — the calendar mirror's `calendar_uid`,
  no cross-schema FK: calendar is a different domain + Radicale owns event lifecycle, same no-FK reasoning as
  `fin_budget.schedule_id`), schedule_id? (scheduler row for a due reminder), metadata jsonb, created_at, completed_at?.
- Indexes: `(household_id, status)`, `(project_id)`, `(due_at)`, `(household_id, context)`.

## mcp-tasks (mcp/, port 8095)
CRUD + GTD transitions over Postgres. Tools (mirrors mcp-finance's shape — terse EN descriptions):
- `add_task(title, note?, source?)` — captures to **inbox** (status=inbox, no project/context). The
  one-line capture GTD lives on.
- `clarify_task(id, status, context?, projectId?, dueAt?, deferUntil?, priority?)` — the inbox→organized
  transition; rejects an unknown project / cross-household project (mirrors finance's cross-household guard).
- `update_task` / `complete_task(id)` (stamps completed_at + status=done) / `delete_task(id)`.
- `list_tasks(status?, context?, projectId?, dueBefore?, limit?)` — native-SQL optional filters with
  `CAST(:p AS …)` (same pgjdbc NULL-bind workaround mcp-finance uses); hard cap like finance's 200.
- `upsert_project` / `list_projects(status?)`.
- `link_task_to_event(id, calendarEventUid)` — stores the uid only. **Creating** the calendar event is the
  agent's job via orchestrator → calendar-agent (MCP servers don't call each other).

## tasks-agent (agents/, port 8096)
AGENT.md (frontmatter + EN body). REST: `POST /agents/tasks/intent`, `.../triggers/{kind}`,
`.../skills/{skill}/invoke` — same surface calendar/finance expose, built on `libs/agent-runtime`.
Tools: mcp-tasks. Cross-cutting: scheduler (due reminders + weekly review), memory, telegram; calls
calendar-agent via orchestrator for task→event.
Principles: capture-first (never lose an input — when in doubt, `add_task` to inbox rather than ask);
respect owner/scope (don't surface another user's private tasks in household scope); clarify ambiguous
due dates; a hard deadline → offer to turn into a calendar event; confirm before delete/bulk.

**Inbox routing (owner decision 2026-06-12 = explicit-only first):** tasks-agent is routed like
calendar/finance — the orchestrator's intent classifier picks it from AGENT.md few-shots when the
message looks task-shaped ("купить…", "позвонить…", "не забыть…"). The roadmap's catch-all
("anything not calendar/finance → tasks-inbox", i.e. making `tasks` the orchestrator **fallback**
instead of `echo`) is **deferred to its own later PR** — it changes global routing for every
unmatched message, so it's a deliberate standalone change, not hidden inside the agent slice. See
Deferred in STATUS.

## Skills (skills/tasks/)
- `inbox-clarify` — given an inbox item, propose a GTD clarification (actionable? project? context? next
  action?) → suggest a `clarify_task` call; confirm before applying.
- `next-action-suggester` — given a context (or "what should I do now"), rank open next-actions by
  due/priority.
- `weekly-review` — proactive via scheduler (weekly cron): surface stale inbox items, waiting-fors, and
  projects with no next action (the GTD weekly review).
- (later) `task-to-event` (hard-deadline item → calendar event via orchestrator), `delegation-tracker`.

## Reminders → scheduler-service
No own tick. On a `due_at`/`defer_until`, tasks-agent registers
`mcp-scheduler.schedule_once(target=tasks, payload={taskId})`; scheduler wakes tasks-agent via
orchestrator on the date → agent formats + notifier→telegram. `weekly-review` is a recurring cron the
agent registers once per household.

## Calendar link (roadmap "turn task into event")
tasks-agent, on a hard-deadline item, asks calendar-agent (via orchestrator) to `create_event`, then
calls mcp-tasks `link_task_to_event(id, returnedUid)`. One direction for the MVP (task → event); a
two-way sync (event done → task done) is a later cross-agent PR.
