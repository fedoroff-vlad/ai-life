# calendar-agent

Calendar domain agent. Owns calendar events, birthdays, anniversaries, and time-based
reminders for a household. The canonical role description and capabilities live in
[AGENT.md](AGENT.md) — read it at startup, served at `GET /manifest`.

This PR (9b) ships the **skeleton only**: module, AGENT.md spec, manifest endpoint,
and stubs for intent/trigger entry points. Real handlers + skills (birthday-greeter)
land in PR9c.

## REST API

| method | path                                       | purpose                                    |
|--------|--------------------------------------------|--------------------------------------------|
| GET    | `/manifest`                                | parsed AGENT.md (frontmatter + body)       |
| POST   | `/agents/calendar/intent`                  | (stub) hit by orchestrator on user intent  |
| POST   | `/agents/calendar/triggers/{kind}`         | (stub) hit by orchestrator on schedule wake|
| GET    | `/actuator/health`                         | liveness                                   |

`POST /agents/calendar/intent` and `/triggers/{kind}` currently return **202 Accepted**
with no side effects — they exist so the contract is testable end-to-end before
PR9c wires real logic.

## AGENT.md convention

The file lives at `agents/<name>/AGENT.md`:

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

The Maven build copies `AGENT.md` from the module root onto the classpath. The
manifest loader reads it on startup; orchestrator discovers agents by GETting
each agent's `/manifest`.

## Configuration

```
CALENDAR_AGENT_PORT=8086
```

## Tests

`mvn -B -pl agents/calendar-agent -am test` — context boots, AGENT.md parses, GET /manifest returns frontmatter + body.
