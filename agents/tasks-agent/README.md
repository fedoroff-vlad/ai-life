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
| GET  | `/actuator/health`              | liveness |

## Config (env vars)

| Var | Default | Purpose |
|---|---|---|
| `TASKS_AGENT_PORT` | `8096` | HTTP port |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | llm-gateway base URL |
| `TASKS_AGENT_MEMORY_RECALL_K` | `5` | top-k memory recall (used once skills wire memory) |

## Key classes

- `TasksAgentApplication` — `@SpringBootApplication` + `@Import(AgentRuntimeConfig.class)` (the
  runtime supplies the AGENT.md / SKILL.md loaders + `SkillRegistry`).
- `web/ManifestController` — `GET /agents/tasks/manifest`.
- `web/IntentController` — `POST /agents/tasks/intent`; delegates to `IntentRouter`, dispatching to
  an intent skill's flow (e.g. `InboxClarifier`) when the router picks one.
- `web/TriggerController` — `POST /agents/tasks/triggers/{kind}`; resolves a skill from the
  `SkillRegistry`, enriches, runs it, fans out to the household (unknown kinds 404).
- `intent/IntentRouter` — single LLM classifier turn → tool / intent-skill / chat.
- `intent/InboxClarifier` — runs the `inbox-clarify` flow: fetch the inbox via `TaskReviewClient`
  (`/internal/review`) → LLM with AGENT.md + SKILL.md → proposal text. Proposal-only (apply-on-
  confirm deferred with the conversation-state layer).

## Skills

Skills live at the repo root under `skills/tasks/<name>/SKILL.md`.
- `weekly-review` — proactive GTD nudge (inbox/waiting counts + stuck projects), driven by a
  scheduler `weekly.review` cron; enriched via mcp-tasks `/internal/review`. Emits `SKIP` on a
  clean week.
- `inbox-clarify` — reactive (user-invoked, e.g. "разбери инбокс"): the router routes to it, the
  agent fetches the un-clarified inbox and proposes a GTD clarification per item. Proposal-only —
  applying the `clarify_task` calls is deferred (needs the conversation-state confirm layer).

`next-action-suggester` arrives in a later PR.
