# finance-agent

Finance domain agent. Owns transactions, accounts, categories, budgets and
recurring payments for a household. The canonical role description and capabilities
live in [AGENT.md](AGENT.md) — read at startup, served at `GET /agents/finance/manifest`.

PR22 ships the **skeleton only** — manifest + intent endpoints are real (intent goes
through llm-gateway with AGENT.md as the system prompt); triggers always 404 until
the first SKILL.md ships under `skills/finance/<name>/`. mcp-finance tool-calls,
outbound clients (profile-service, notifier, memory-service) and skills land in
subsequent PRs as they're needed — mirrors the calendar-agent build-out
(PR9b → PR9c1 → PR9c2 → PR10 → PR11 → PR17).

## Port: `8093` (`FINANCE_AGENT_PORT`)

## Endpoints

| method | path                                       | purpose                                              |
|--------|--------------------------------------------|------------------------------------------------------|
| GET    | `/agents/finance/manifest`                 | parsed AGENT.md (frontmatter + body)                 |
| POST   | `/agents/finance/intent`                   | hit by orchestrator on user intent                   |
| POST   | `/agents/finance/triggers/{kind}`          | currently 404s for every kind (no skills yet)        |
| GET    | `/actuator/health`                         | liveness                                             |

## Env

| Var | Default | Purpose |
|---|---|---|
| `FINANCE_AGENT_PORT` | `8093` | HTTP port. |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | Via `libs/llm-client`. |

Orchestrator side: `FINANCE_AGENT_URL` (default `http://finance-agent:8093`)
is registered alongside calendar-agent in
[orchestrator/application.yml](../../platform/orchestrator/src/main/resources/application.yml).

## Key classes

- `FinanceAgentApplication`.
- `manifest/ManifestLoader` — SnakeYAML frontmatter + body, exposed as `AgentManifest`.
  Parser shape duplicates calendar-agent's; lift to a shared lib if a third agent ships.
- `skill/Skill` — `(name, triggers[], body)` record.
- `skill/SkillLoader` — scans `classpath*:skills/finance/*/SKILL.md`. Empty registry is valid.
- `skill/SkillRegistry` — trigger kind → `Skill` index.
- `web/ManifestController` — `GET /agents/finance/manifest`.
- `web/IntentController` — `POST /agents/finance/intent`. Calls llm-gateway with AGENT.md body as system prompt.
- `web/TriggerController` — `POST /agents/finance/triggers/{kind}`. 404 until the first skill ships.

## Adding a skill

Create `skills/finance/<name>/SKILL.md` at the **repo root** (not under this module —
`pom.xml` copies them in). Frontmatter shape matches calendar-agent's; see
[plans/PATTERNS.md](../../plans/PATTERNS.md) §"Recipe: add a new agent".

The first SKILL.md will also need TriggerController to grow the real LLM call and the
outbound clients (profile/notifier/memory) — at that point the layout follows
calendar-agent's, and a shared `libs/agent-runtime` becomes worth extracting.
