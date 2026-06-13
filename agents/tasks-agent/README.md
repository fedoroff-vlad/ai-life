# tasks-agent

GTD tasks agent (port **8096**). Owns the inbox → clarify → engage flow for a household's
tasks; tools come from `mcp-tasks` (source of truth: Postgres `tasks.*`). See
[plans/tasks.md](../../plans/tasks.md).

**Skeleton slice** — manifest + intent are real; triggers are a stub (no skills ship yet, so
every trigger kind 404s). Outbound clients (profile/notifier/memory) and MCP-tool-call routing
land with the first skill, mirroring how finance-agent grew.

## Endpoints

| method | path | purpose |
|--------|------|---------|
| GET  | `/agents/tasks/manifest`        | parsed AGENT.md (orchestrator scrapes at startup) |
| POST | `/agents/tasks/intent`          | LLM chat with AGENT.md as the system prompt (fast channel) |
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
