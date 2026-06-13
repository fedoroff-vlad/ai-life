# tasks-agent

GTD tasks agent (port **8096**). Owns the inbox → clarify → engage flow for a household's
tasks; tools come from `mcp-tasks` (source of truth: Postgres `tasks.*`). See
[plans/tasks.md](../../plans/tasks.md).

Manifest + intent are real; triggers are still a stub (no skills ship yet, so every trigger kind
404s). `intent` routes via `IntentRouter` (PR58): when mcp-tasks tools are wired the LLM either
invokes a tool (`add_task`/`clarify_task`/`list_tasks`/…) or replies directly; with the MCP client
disabled it falls back to a plain chat.

The MCP client dials `mcp-tasks` at boot (`spring.ai.mcp.client.enabled`, default true) — a
missing mcp-tasks surfaces at agent startup. Toggle off with `TASKS_AGENT_MCP_CLIENT_ENABLED=false`
in dev/degraded environments.

## Endpoints

| method | path | purpose |
|--------|------|---------|
| GET  | `/agents/tasks/manifest`        | parsed AGENT.md (orchestrator scrapes at startup) |
| POST | `/agents/tasks/intent`          | LLM routes to an mcp-tasks tool call or a chat reply (`IntentRouter`) |
| POST | `/agents/tasks/triggers/{kind}` | scheduler-driven wake — **404 until skills ship** |
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
- `web/IntentController` — `POST /agents/tasks/intent`; plain LLM chat on the fast channel.
- `web/TriggerController` — `POST /agents/tasks/triggers/{kind}`; consults the `SkillRegistry`,
  always 404 in the skeleton.

## Skills

Skills live at the repo root under `skills/tasks/<name>/SKILL.md` (none yet). The first ones
(`inbox-clarify`, `next-action-suggester`, the proactive `weekly-review`) arrive in later PRs.
