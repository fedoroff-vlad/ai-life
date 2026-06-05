# calendar-agent

Calendar domain agent. Owns calendar events, birthdays, anniversaries, and time-based
reminders for a household. The canonical role description and capabilities live in
[AGENT.md](AGENT.md) — read at startup, served at `GET /agents/calendar/manifest`.

Currently bound MCP servers: `mcp-caldav` (write-through Radicale + cache). Skills
loaded from `skills/calendar/<name>/SKILL.md`: `birthday-greeter`, `gift-recommender`.

## Port: `8086` (`CALENDAR_AGENT_PORT`)

## Endpoints

| method | path                                       | purpose                                              |
|--------|--------------------------------------------|------------------------------------------------------|
| GET    | `/agents/calendar/manifest`                | parsed AGENT.md (frontmatter + body)                 |
| POST   | `/agents/calendar/intent`                  | hit by orchestrator on user intent                   |
| POST   | `/agents/calendar/triggers/{kind}`         | hit by orchestrator on a scheduler wake (`birthday.greet`, `gift.recommend`, …) |
| GET    | `/actuator/health`                         | liveness                                             |

`/triggers/{kind}` looks up the bound skill in `SkillRegistry`, **resolves `personId`
from the wake payload via profile-service** before the LLM call, calls llm-gateway
with `[manifest.body, skill.body]` as system prompts and the wake payload (as JSON
text, augmented with the resolved `person` object) as the user message. The generated
text is then **fanned out to every member of the household** via notifier-service
(per the explicit recipient policy — no role filter). Per-user notifier failures are
logged + swallowed; the trigger still returns 202.

## Env
| Var | Default | Purpose |
|---|---|---|
| `CALENDAR_AGENT_PORT` | `8086` | HTTP port. |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | Via `libs/llm-client`. |
| `PROFILE_SERVICE_URL` | `http://profile-service:8082` | Person + household-members lookup. |
| `NOTIFIER_URL` | `http://notifier-service:8084` | Outbound channel. |

## AGENT.md convention

```
---
name: <agent id used in routing / wake>
description: <one-line for intent classifier few-shot>
version: <semver>
port: <listening port>
mcp:        [<mcp server names this agent uses>]
skills:     [<skills/<domain>/<name>>]
triggers:   [{ kind, description }]    # for scheduler-triggered wake-ups
intents:    [{ example, description }] # for orchestrator's intent classifier
---

<English body = system prompt / agent role>
```

`pom.xml` `<resources>` copies `AGENT.md` from the module root onto the classpath and
`skills/calendar/` from the repo root into `classpath:skills/calendar/`. The manifest
loader reads at startup; the skill loader scans `classpath*:skills/calendar/*/SKILL.md`.

## Key classes
- `CalendarAgentApplication`.
- `config/CalendarAgentProperties` — `calendar-agent.{profile-service-url, notifier-url}`.
- `config/OutboundHttpConfig` — `WebClient` per outbound dependency (LLM, profile, notifier), each via `WebClient.Builder.clone()`.
- `http/ProfileClient` — `personById(uuid) → PersonDto`, `usersByHousehold(uuid) → list`.
- `http/NotifierClient` — `POST /v1/notify` per user (failures logged + swallowed).
- `manifest/ManifestLoader` — SnakeYAML frontmatter + body, exposed as `AgentManifest`.
- `skill/Skill` — `(name, triggers[], body)` record.
- `skill/SkillLoader` — `classpath*:skills/calendar/*/SKILL.md`.
- `skill/SkillRegistry` — trigger kind → `Skill` index.
- `web/ManifestController` — `GET /agents/calendar/manifest`.
- `web/IntentController` — `POST /agents/calendar/intent`. Calls llm-gateway with AGENT.md body as system prompt.
- `web/TriggerController` — `POST /agents/calendar/triggers/{kind}`. Skill dispatch, person resolution, household fan-out via notifier.

## Tests

`mvn -B -pl agents/calendar-agent -am test` — 7 tests:
- Manifest endpoint returns frontmatter + body.
- Skill loader discovers both `birthday-greeter` and `gift-recommender`.
- `TriggerControllerTest` (Dispatcher-driven MockWebServer faking LLM + profile + notifier) covers `birthday.greet` and `gift.recommend` end-to-end, asserting both household members receive the notify POST and the LLM body contains the resolved person's `interests`/`notes`.
- Unknown trigger kind → 200 with a no-op log.

## Gotcha (captured here so we don't relearn it)
In the wake handler, do NOT use `switchIfEmpty` on a `Mono<Void>` to fall back when the
person lookup returns 404 — that fires the skill twice and times out the LLM stub. Use
`defaultIfEmpty(Optional.empty())` instead (see PR11 commit).

## Adding a skill
Create `skills/calendar/<name>/SKILL.md` at the **repo root** (not under this module —
`pom.xml` copies them in). Frontmatter:

```
---
name: <skill id>
description: <short>
version: <semver>
domain: calendar
triggers: [<kind1>, <kind2>]
languages: [en, ru]
inputs: [<json-path>, ...]
---

<English skill body = additional system prompt appended to AGENT.md body>
```

Optional `SKILL.ru.md` for Russian; the loader picks the right one per
[language convention](../../CLAUDE.md) (user-facing text follows the end user's
language).
