# tasks-agent

GTD tasks agent (port **8096**). Owns the inbox → clarify → engage flow for a household's
tasks; tools come from `mcp-tasks` (source of truth: Postgres `tasks.*`). See
[plans/tasks.md](../../plans/tasks.md).

Manifest, intent and the first trigger (`weekly.review`) are real. `intent` routes via
`IntentRouter` (PR58): when mcp-tasks tools are wired the LLM either invokes a tool
(`add_task`/`clarify_task`/`list_tasks`/…), runs an **intent skill**, or replies directly; with the
MCP client disabled it falls back to a plain chat. Intent skills are skills with no `triggers`
(user-invoked, not scheduler-fired) — `inbox-clarify` is the first; when the router picks one,
`IntentController` runs that skill's flow. `triggers/{kind}` resolves a skill from the
`SkillRegistry`, enriches the wake payload, runs the skill (LLM with AGENT.md + SKILL.md), and fans
the result out to the household — unknown kinds still 404.

The MCP client dials `mcp-tasks` at boot (`spring.ai.mcp.client.enabled`, default true) — a
missing mcp-tasks surfaces at agent startup. Toggle off with `TASKS_AGENT_MCP_CLIENT_ENABLED=false`
in dev/degraded environments.

## Endpoints

| method | path | purpose |
|--------|------|---------|
| GET  | `/agents/tasks/manifest`        | parsed AGENT.md (orchestrator scrapes at startup) |
| POST | `/agents/tasks/intent`          | LLM routes to an mcp-tasks tool call, an intent skill (`inbox-clarify`), or a chat reply (`IntentRouter`) |
| POST | `/agents/tasks/triggers/{kind}` | scheduler-driven wake → skill + notifier fan-out (`weekly.review` live; unknown kinds 404) |
| POST | `/agents/tasks/internal/task-to-event` | turn a hard-deadline task into a calendar event (orchestrator → calendar `create_event` → link); internal/admin |
| GET  | `/actuator/health`              | liveness |

## Config (env vars)

| Var | Default | Purpose |
|---|---|---|
| `TASKS_AGENT_PORT` | `8096` | HTTP port |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | llm-gateway base URL |
| `TASKS_AGENT_MEMORY_RECALL_K` | `5` | top-k memory recall (used once skills wire memory) |
| `ORCHESTRATOR_URL` | `http://orchestrator:8083` | orchestrator sync hub for inter-agent calls (task-to-event) |

## Key classes

- `TasksAgentApplication` — `@SpringBootApplication` + `@Import(AgentRuntimeConfig.class)` (the
  runtime supplies the AGENT.md / SKILL.md loaders + `SkillRegistry`).
- `web/ManifestController` — `GET /agents/tasks/manifest`.
- `web/IntentController` — `POST /agents/tasks/intent`; delegates to `IntentRouter`, dispatching to
  an intent skill's flow (e.g. `InboxClarifier`) when the router picks one.
- `web/TriggerController` — `POST /agents/tasks/triggers/{kind}`; resolves a skill from the
  `SkillRegistry`, enriches, runs it, fans out to the household (unknown kinds 404).
- `web/ResumeController` — `POST /agents/tasks/resume`; hit when the user replies to an open tasks
  question (conversation route-locked to tasks). Dispatches on `pendingAction.flow`; today only
  `inbox-clarify-apply` → `InboxClarifier.resume`.
- `intent/IntentRouter` — single LLM classifier turn → tool / intent-skill / chat.
- `intent/InboxClarifier` — runs the `inbox-clarify` flow **apply-on-confirm**: fetch the inbox via
  `TaskReviewClient` (`/internal/review`) → LLM returns structured proposals → render a confirm +
  stash them as a `pendingAction`. On `resume` an affirmative reply applies each via `ClarifyClient`
  (`/internal/clarify`); anything else cancels.
- `intent/NextActionSuggester` — runs the `next-action-suggester` flow: fetch open next-actions via
  `NextActionClient` (`/internal/tasks?status=next`) → LLM ranks by due/priority/context. Read-only
  (suggests, doesn't change tasks).
- `flow/TaskToEventService` — the task-to-event chain (Stage 4 / C1): orchestrator `/v1/agents/invoke`
  (calendar `create_event`) via `OrchestratorInvokeClient` → records the `eventUid` via mcp-tasks
  `/internal/link-event` via `LinkEventClient`. Always returns an `AgentActionResult`; calendar
  errors propagate (`ok=false`, no link).
- `web/InternalTaskToEventController` — `POST /agents/tasks/internal/task-to-event` (body
  `TaskToEventRequest`); drives `TaskToEventService`. Internal/admin; the user-facing trigger
  (auto-offer on a hard-deadline clarify) is a follow-up.

## Skills

Skills live beside the agent under `domains/tasks/skills/<name>/SKILL.md`.
- `weekly-review` — proactive GTD nudge (inbox/waiting counts + stuck projects), driven by a
  scheduler `weekly.review` cron; enriched via mcp-tasks `/internal/review`. Emits `SKIP` on a
  clean week.
- `inbox-clarify` — reactive (user-invoked, e.g. "разбери инбокс"): fetches the un-clarified inbox,
  the LLM returns structured proposals, the agent shows a confirm and **applies the `clarify_task`
  calls only after the user says "да"** (via the conversation route-lock / resume mechanism).
- `next-action-suggester` — reactive (user-invoked, e.g. "что мне сейчас сделать"): fetches the open
  next-actions and ranks them by due date / priority / context. Read-only suggestion.
