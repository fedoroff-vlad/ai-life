# calendar-agent

Calendar domain agent. Owns calendar events, birthdays, anniversaries, and time-based
reminders for a household. The canonical role description and capabilities live in
[AGENT.md](AGENT.md) — read at startup, served at `GET /agents/calendar/manifest`.

Currently bound MCP servers: `mcp-caldav` (write-through Radicale + cache). Skills
loaded from `skills/calendar/<name>/SKILL.md`: `birthday-greeter`, `gift-recommender`.
Also handles `ics.pull` as a **system trigger** (no LLM) forwarded directly to
mcp-ics-import's `POST /internal/pull/{id}`.

**Cross-agent recall (PR17)**: before every skill LLM call, `TriggerController`
zips two memory-service calls — `POST /v1/memories/recall` (top-k by
householdId/personId) and `GET /v1/graph/person/{id}/relations` — and injects
their results as `memories[]` and `relations` fields in the user-message JSON.
Both calls are soft-fail (500 ms timeout, errors collapse to empty) — the skill
always runs, just without that enrichment when memory-service is down.

## Port: `8086` (`CALENDAR_AGENT_PORT`)

## Endpoints

| method | path                                       | purpose                                              |
|--------|--------------------------------------------|------------------------------------------------------|
| GET    | `/agents/calendar/manifest`                | parsed AGENT.md (frontmatter + body)                 |
| POST   | `/agents/calendar/intent`                  | hit by orchestrator on user intent                   |
| POST   | `/agents/calendar/triggers/{kind}`         | hit by orchestrator on a scheduler wake (`birthday.greet`, `gift.recommend`, …) |
| POST   | `/agents/calendar/actions/{action}`        | inter-agent action (Stage 4 / C1); `create_event` → mcp-caldav `/internal/event`, returns `{eventUid}` |
| GET    | `/actuator/health`                         | liveness                                             |

`/triggers/{kind}` first consults `SystemTriggerRegistry`. If a `SystemTriggerHandler`
claims the kind (e.g. `ics.pull`), it runs without the LLM and without notifier fan-out —
just whatever the handler does (for `ics.pull`: forward to mcp-ics-import). Otherwise it
falls through to `SkillRegistry`, **resolves `personId`
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
| `calendar-agent.ics-import-url` / `MCP_ICS_IMPORT_URL` | `http://mcp-ics-import:8091` | Target for `ics.pull` system trigger. |
| `calendar-agent.memory-service-url` / `MEMORY_SERVICE_URL` | `http://memory-service:8087` | Pre-skill recall + relations enrichment. |
| `calendar-agent.mcp-caldav-url` / `MCP_CALDAV_URL` | `http://mcp-caldav:8090` | Target for the `create_event` action (`POST /internal/event`). |
| `calendar-agent.memory-recall-k` / `CALENDAR_AGENT_MEMORY_RECALL_K` | `5` | Top-k for the recall call. |

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
`../skills` (i.e. `domains/calendar/skills/`) into `classpath:skills/calendar/`. The manifest
loader reads at startup; the skill loader scans `classpath*:skills/calendar/*/SKILL.md`.

## Key classes
- `CalendarAgentApplication`.
- `config/CalendarAgentProperties` — `calendar-agent.{profile-service-url, notifier-url}`.
- `config/OutboundHttpConfig` — `WebClient` per outbound dependency (LLM, profile, notifier), each via `WebClient.Builder.clone()`.
- `ProfileClient` / `NotifierClient` / `MemoryClient` live in shared `libs/agent-runtime` as of PR25b — `AgentRuntimeConfig` registers them with `@Qualifier`-driven `WebClient` injection, so the per-agent `OutboundHttpConfig` only owns the URL binding.
- `http/IcsImportClient` — `pull(subscriptionId)` POSTs `/internal/pull/{id}` on mcp-ics-import. Calendar-only; stays here until a second consumer appears.
- `http/CaldavEventClient` — `createEvent(CreateEventInput)` POSTs mcp-caldav `/internal/event`. Used by the `create_event` action.
- `web/ActionController` — `POST /agents/calendar/actions/{action}`; inter-agent action endpoint. `create_event` maps the invoke `args` → `CreateEventInput`, calls mcp-caldav, returns `{eventUid}`. Always replies an `AgentActionResult` (structured `ok=false` on bad input, never an HTTP error).
- `system/SystemTriggerHandler` — interface for non-LLM triggers (`kind()` + `handle(req)`).
- `system/SystemTriggerRegistry` — indexes all `SystemTriggerHandler` beans by kind.
- `system/IcsPullTriggerHandler` — first implementation; extracts `subscriptionId` from payload, forwards via `IcsImportClient`. Downstream errors are logged + swallowed (scheduler advances regardless).
- AGENT.md / SKILL.md loaders + `SkillRegistry` live in shared `libs/agent-runtime` as of PR25a. Agent opts in with `@Import(AgentRuntimeConfig.class)` on `CalendarAgentApplication`; scan paths come from `agent.{manifest-classpath, skills-classpath}` in `application.yml`.
- `web/ManifestController` — `GET /agents/calendar/manifest`.
- `web/IntentController` — `POST /agents/calendar/intent`. Calls llm-gateway with AGENT.md body as system prompt.
- `web/TriggerController` — `POST /agents/calendar/triggers/{kind}`. **System-trigger check first**, then skill dispatch + person resolution + **memory enrichment (recall + relations zipped)** + household fan-out via notifier. `buildRecallQuery` anchors the recall query on the person's display name when available.

## Tests

`mvn -B -pl domains/calendar/calendar-agent -am test` — 10 tests:
- Manifest endpoint returns frontmatter + body.
- Skill loader discovers both `birthday-greeter` and `gift-recommender`.
- `TriggerControllerTest` (Dispatcher-driven MockWebServer faking LLM + profile + notifier + ics-import + memory-service) covers `birthday.greet` and `gift.recommend` end-to-end, asserting (a) both household members receive the notify POST, (b) the LLM body contains the resolved person's `interests`/`notes`, **(c) the recalled memory text + relation `object_label` are present**.
- New: memory-service returns 500 → skill still runs, LLM body has **no** `memories` or `relations` block (soft-fail).
- `ics.pull` system trigger forwards to mcp-ics-import without touching LLM or notifier; downstream 500 still returns 202.
- Unknown trigger kind → 404 (no skill, no system handler).

## Gotcha (captured here so we don't relearn it)
In the wake handler, do NOT use `switchIfEmpty` on a `Mono<Void>` to fall back when the
person lookup returns 404 — that fires the skill twice and times out the LLM stub. Use
`defaultIfEmpty(Optional.empty())` instead (see PR11 commit).

## Adding a skill
Create `domains/calendar/skills/<name>/SKILL.md` (beside the agent, not inside the agent
module — `pom.xml` copies them onto the classpath). Frontmatter:

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
