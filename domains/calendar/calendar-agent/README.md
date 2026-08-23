# calendar-agent

Calendar domain agent. Owns calendar events, birthdays, anniversaries, and time-based
reminders for a household. The canonical role description and capabilities live in
[AGENT.md](AGENT.md) — read at startup, served at `GET /agents/calendar/manifest`.

Currently bound MCP servers: `mcp-caldav` (write-through Radicale + cache). Skills
loaded from `skills/calendar/<name>/SKILL.md`: `birthday-greeter`, `gift-recommender`,
`event-capture` (user-facing "запиши встречу …" create, #486/Track H.2 HC-1),
`event-cancel` (user-facing "отмени встречу …" delete, #486/Track H.2 HC-3),
`event-move` (user-facing "перенеси встречу …" reschedule, #486/Track H.2 HC-4).
Also handles `ics.pull` as a **system trigger** (no LLM) forwarded directly to
mcp-ics-import's `POST /internal/pull/{id}`.

**User-facing event CRUD via chat (#486/Track H.2):** `/intent` is no longer a single chat call — a
`CalendarIntentRouter` (shared `SkillClassifier`, #475) classifies the message by SKILL.md description into
one event flow (HC-1: `event-capture` → `EventCapturer` creates the event via mcp-caldav; HC-3:
`event-cancel` → `EventCanceller` deletes one; HC-4: `event-move` → `EventMover` reschedules one) or the
chat fallback (`CalendarChat`, which keeps the #195 ICS-feed nudge). A chat-created event is **undoable**
(HC-2): `EventCapturer` attaches an `IntentResponse.undo` handle and the `undo` action cancels it via
mcp-caldav's `DELETE /internal/event/{id}`, so "отмени последнее" drops it. **Cancel via chat (HC-3)** and
**move via chat (HC-4)** both go behind a confirm gate: the flow reads the owner's upcoming events
(personal ∪ shared), lets the LLM pick which one they mean (move also resolves the new time), then asks to
confirm (a `pendingAction` route-locks to calendar); the follow-up "да" hits `POST /resume` and only then
deletes (HC-3) / PUTs the new time (HC-4). Plan: [plans/calendar.md](../../../plans/calendar.md) §H.2.

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
| POST   | `/agents/calendar/intent`                  | hit by orchestrator on user intent → `CalendarIntentRouter` classifies into an event flow (`event-capture`) or chat (`CalendarChat` + #195 feed nudge) |
| POST   | `/agents/calendar/resume`                  | hit by orchestrator when the user replies to an open calendar question (route-locked). Dispatches on `pendingAction.flow`: `event-cancel-confirm` → `EventCanceller.resume` (confirm-before-delete, #486/HC-3), `event-move-confirm` → `EventMover.resume` (confirm-before-move, #486/HC-4). A null `pendingAction` on the reply clears the lock |
| POST   | `/agents/calendar/triggers/{kind}`         | hit by orchestrator on a scheduler wake (`birthday.greet`, `gift.recommend`, …) |
| POST   | `/agents/calendar/actions/{action}`        | inter-agent action (Stage 4); `create_event` → resolves the item's private/shared scope to a personal/family household (ADR-0001 slice 4) then mcp-caldav `/internal/event`, returns `{eventUid}`; `undo` → cancel a just-created event by its stored id via mcp-caldav `DELETE /internal/event/{id}` (#486/HC-2), honest `ok=false` when gone; `brief` → read-only sub-question answer (#290 Slice B2-followup) |
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

**`gift.recommend` is the exception (Stage 4 / Track D, D2c):** instead of the generic
recall→LLM path it runs the budget-aware `GiftRecommender`, which uses the shared
`Coordinator` (`libs/agent-runtime`) to gather, in parallel, the household's gift
**budget** (finance-agent's `get_gift_budget` via the orchestrator invoke hub — the
person's `relationship`, when set, is forwarded so finance returns the
relationship-tiered rule, else the "Gifts" envelope, D3d),
**memories** (recall), **relations**, and **personNotes** — the owner's *curated*
second-brain notes about the person (second-brain SB-6): notes whose `[[wiki-link]]`
resolved to this person (`note→person` edge, SB-3) are read back (SB-5 `getNote`) and
trimmed to their title/body, so curated preferences beat the noisy chat-capture facts.
Then it synthesizes budget-aware ideas from `[AGENT.md, SKILL.md]` +
`{payload(person), context}`. Each gather step soft-fails independently — a finance
outage just drops the budget constraint, no curated notes just falls back to the
memories/relations gather. **Delivery is two notifications per member (D3e): a
deterministic birthday reminder, then the gift ideas.**

**`birthday.greet` delegates the greeting to the creator agent (CR-g2):** instead of the
generic recall→LLM path it runs `BirthdayGreeter`, which invokes `creator.draft_greeting`
over the orchestrator hub (content authoring is the creator's specialty; the calendar owns the
occasion) and fans the returned greeting out to the household — closing the Stage-4 chain
`calendar.birthday_upcoming → creator.draft_greeting → notifier.send`. **Best-effort with a
fallback:** if the creator can't help (no person to name, the hub/LLM errors, an `ok=false`
result, or an empty draft) the wake falls back to the local `birthday-greeter` skill, so it
always greets.

## Env
| Var | Default | Purpose |
|---|---|---|
| `CALENDAR_AGENT_PORT` | `8086` | HTTP port. |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | Via `libs/llm-client`. |
| `PROFILE_SERVICE_URL` | `http://profile-service:8082` | Person + household-members lookup. |
| `NOTIFIER_URL` | `http://notifier-service:8084` | Outbound channel. |
| `calendar-agent.ics-import-url` / `MCP_ICS_IMPORT_URL` | `http://mcp-ics-import:8091` | Target for `ics.pull` system trigger. |
| `calendar-agent.memory-service-url` / `MEMORY_SERVICE_URL` | `http://memory-service:8087` | Pre-skill recall + relations enrichment. |
| `calendar-agent.mcp-caldav-url` / `MCP_CALDAV_URL` | `http://mcp-caldav:8090` | Target for the `create_event` action (`POST /internal/event`) and the feed auto-issue (`/internal/feeds`). |
| `calendar-agent.public-feed-base-url` / `CALENDAR_PUBLIC_BASE_URL` | (empty) | Public calendar-web URL (the Tailscale Funnel host, #195). When set, the agent auto-issues a read-only ICS feed on a user's first calendar message and appends the subscribe link. Empty disables the nudge. |
| `calendar-agent.orchestrator-url` / `ORCHESTRATOR_URL` | `http://orchestrator:8083` | Inter-agent invoke hub (`POST /v1/agents/invoke`) — used by `GiftRecommender` to call finance's `get_gift_budget` and by `BirthdayGreeter` to call creator's `draft_greeting`. |
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
- `ProfileClient` / `NotifierClient` / `MemoryClient` live in shared `libs/agent-runtime` as of PR25b — `AgentRuntimeConfig` registers them, and as of #200 also builds their `profile/notifier/memory` `WebClient` beans (from `SharedClientProperties`, which `CalendarAgentProperties` implements), so the per-agent `OutboundHttpConfig` only owns the agent-specific clients + the URL *values*. `ProfileClient.householdRouting(userId)` (ADR-0001 slice 4) is consumed by `ActionController`'s `create_event` tenant routing.
- `http/IcsImportClient` — `pull(subscriptionId)` POSTs `/internal/pull/{id}` on mcp-ics-import. Calendar-only; stays here until a second consumer appears.
- `http/CaldavEventClient` — `createEvent(CreateEventInput)` POSTs mcp-caldav `/internal/event`; `eventsInWindow(household, from, to)` GETs `/internal/events` (backs the `create_event` double-booking sanity check, #485), plus an overload over a **household set** (personal ∪ shared, repeatable `householdId`) that backs the `event-cancel`/`event-move` target read (#486/HC-3, HC-4); `updateEvent(id, UpdateEventInput)` PUTs `/internal/event/{id}` (patches supplied fields — the `event-move` reschedule, #486/HC-4); `deleteEvent(id)` DELETEs `/internal/event/{id}` (the undo reversal #486/HC-2 + the `event-cancel` delete #486/HC-3). Used by the `create_event` action, the `event-capture`/`event-cancel`/`event-move` flows, and the `undo` action.
- `http/CaldavFeedClient` — `ensureFeed(household, owner, label)` over mcp-caldav `/internal/feeds` (list-or-mint). Backs the #195 feed auto-issue.
- `OrchestratorInvokeClient` (shared, `libs/agent-runtime`) — `invoke(req[, timeout])` POSTs the orchestrator's `/v1/agents/invoke` hub. The locked inter-agent path (agents never call each other directly); the `orchestratorWebClient` + the `@Bean` wiring live in `config/OutboundHttpConfig`. Used by `GiftRecommender` (budget) + `BirthdayGreeter` (creator greeting, 30s).
- `flow/GiftRecommender` — the first real `Coordinator` flow (D2c). On `gift.recommend` it gathers `{budget: finance get_gift_budget via the hub, memories: recall, relations}` in parallel, synthesizes budget-aware gift ideas, and fans the result out to the household. Per-step soft-fail (a dropped budget just removes the price constraint). The budget gather forwards the person's `relationship` (when set) so finance can return the relationship-tiered rule (D3d). **Two outputs (D3e):** the single wake delivers each member a short deterministic birthday **reminder** (names the person; `payload.daysUntil` adds "через N дн." when present) followed by the **gift ideas** — reminder skipped when no person resolved, gift message skipped when synthesis is empty.
- `web/ActionController` — `POST /agents/calendar/actions/{action}`; inter-agent action endpoint, extends the shared `AgentActionController` (`libs/agent-runtime`) for the unknown-action + uniform error envelope. `create_event` maps the invoke `args` → `CreateEventInput`, **resolves tenant routing via the shared sharing capability** (ADR-0002 — calendar is its reference impl): it builds a `SharingContext` from the item's categories and hands off to `libs/sharing`'s `SharingResolver` (the explicit `SharingScope` on args wins, else `CalendarSharingPolicy` decides the default: occasion categories → shared, everything else → private), which resolves the acting user's `household-routing` split to a concrete personal/family `household_id`; with no `userId` (or profile 404) it falls back to the envelope household. The routing/fallback rules live once in `libs/sharing`, no longer inline here. Then calls mcp-caldav and returns `{eventUid}`. **Sanity spot-checks (#485):** rejects an impossible event whose `dtend` is at-or-before `dtstart` (`ok=false`), and — for an event with a real span — flags a **double-booking** by reading existing events in its window (`eventsInWindow`) and returning a discreet `warning` next to the `eventUid` (the overlap read soft-fails, never blocking the create). Also registers the generic read-only **`brief`** action (#290 Slice B2-followup) → shared `BriefResponder` (calendar is the second `brief` exposer after finance): recalls the second brain for the coordinator's sub-question and returns `{agent, answer}`. Also registers the reserved **`undo`** action (#486/HC-2): cancels a just-created event by its stored id via `CaldavEventClient.deleteEvent` (mcp-caldav `DELETE /internal/event/{id}`), confirming with the summary; an already-gone event → honest `ok=false`. Always replies an `AgentActionResult` (structured `ok=false` on bad input, never an HTTP error).
- `sharing/CalendarSharingPolicy` — calendar's `DefaultSharingPolicy` (`libs/sharing`): the one "what is shared here" rule, occasion categories (`birthday`/`anniversary`/`occasion`) → shared, else private. The `SharingResolver` + `ProfileSharingClient` beans that consume it are wired in `config/OutboundHttpConfig`. The canonical example the PATTERNS "add sharing to a domain" recipe points at. **Memory-driven default (ADR-0002 item 8, DS-3):** `OutboundHttpConfig` wraps this static policy in `libs/sharing`'s `LearnedSharingPolicy` and builds the `SharingResolver` with its learning-enabled constructor (+ a `SharingLearningClient` bean over the shared `memoryServiceWebClient`), so an unscoped event defaults to what the owner has repeatedly chosen for the same signal profile once the `memory.sharing_decision` tally is deep + decisive, and to the static occasion rule otherwise; explicit choices are recorded as the learn signal. Both best-effort — the routing/fallback mechanism is unchanged. Calendar is the reference wiring for item 8.
- `system/SystemTriggerHandler` — interface for non-LLM triggers (`kind()` + `handle(req)`).
- `system/SystemTriggerRegistry` — indexes all `SystemTriggerHandler` beans by kind.
- `system/IcsPullTriggerHandler` — first implementation; extracts `subscriptionId` from payload, forwards via `IcsImportClient`. Downstream errors are logged + swallowed (scheduler advances regardless).
- AGENT.md / SKILL.md loaders + `SkillRegistry` live in shared `libs/agent-runtime` as of PR25a. Agent opts in with `@Import(AgentRuntimeConfig.class)` on `CalendarAgentApplication`; scan paths come from `agent.{manifest-classpath, skills-classpath}` in `application.yml`.
- `web/ManifestController` — `GET /agents/calendar/manifest`.
- `web/IntentController` — `POST /agents/calendar/intent`. Thin: delegates to `CalendarIntentRouter` (was a single chat call before #486/HC-1; the chat + feed logic moved to `CalendarChat`).
- `intent/CalendarIntentRouter` — the in-agent router (#475): binds the shared `agent-runtime` `SkillRouter` with the calendar dispatch map (`event-capture` → `EventCapturer`, `event-cancel` → `EventCanceller`, `event-move` → `EventMover`) + the `CalendarChat` fallback. SKILL.md descriptions are the routing SSOT; birthday/gift skills stay off the map (wake-driven, not user-routable).
- `flow/EventCapturer` — the user-facing capture flow (HC-1): LLM parse (`event-capture` SKILL, temperature 0, `now` passed for relative-date resolution) → resolve the household via the shared `SharingResolver` (private default, same path `create_event` takes) → mcp-caldav `/internal/event` → confirm. Empty plan (no resolvable time) asks instead of filing a wrong-time event; each stage soft-fails. On a successful create it attaches an `IntentResponse.undo` handle (event id + summary) so "отмени последнее" cancels it (#486/HC-2).
- `flow/EventCanceller` — the user-facing cancel flow (HC-3), behind the **confirm-before-delete** gate. `cancel(msg)`: resolve the read set (personal ∪ shared via `ProfileClient.householdRouting`, else the envelope household) → read upcoming events (`CaldavEventClient.eventsInWindow` over the set, −1d…+180d) → LLM pick (`event-cancel` SKILL, temperature 0, numbered candidates) returning `{"pick":n}` / `{"ambiguous":[…]}` / `{}` → a single match replies with a `pendingAction` asking to confirm (nothing deleted); ambiguous lists the matches; none/miss asks. `resume(req)`: an affirmative deletes the stashed id via `CaldavEventClient.deleteEvent`, anything else leaves it; either reply clears the lock. Every stage soft-fails. The pick→confirm→act loop itself is the shared `agent-runtime` `PickConfirmActRunner` (ADR-0004); this class is the calendar-cancel adapter (`candidates`/`view`/`act` + its own `Phrasing`, `requiresHousehold=false`, `now` via `decorateUserMessage`).
- `flow/EventMover` — the user-facing reschedule flow (HC-4), behind the **confirm-before-move** gate (same fuzzy-target risk as cancel). `move(msg)`: resolve the read set → read upcoming events → LLM pick + new time (`event-move` SKILL, temperature 0) returning `{"pick":n,"dtstart":…,"dtend"?}` / `{"pick":n}` (no time → ask) / `{"ambiguous":[…]}` / `{}` → a single match with a new time replies with a `pendingAction` asking to confirm (nothing changed); ambiguous lists; none/miss asks. `resume(req)`: an affirmative patches only the time via `CaldavEventClient.updateEvent` (mcp-caldav `PUT /internal/event/{id}`), anything else leaves it; either reply clears the lock. Every stage soft-fails. The pick→confirm→act loop itself is the shared `agent-runtime` `PickConfirmActRunner` (ADR-0004); this class is the calendar-move adapter and the runner's first non-delete consumer — the new time is threaded through the `pendingAction`, `missing()` re-asks when no time is given, `readyToAct()` gates the resume on the stashed time, and `act()` PUTs it.
- `web/ResumeController` — `POST /agents/calendar/resume`; dispatches on `pendingAction.flow` (`event-cancel-confirm` → `EventCanceller.resume` #486/HC-3, `event-move-confirm` → `EventMover.resume` #486/HC-4). Mirrors finance's `ResumeController`.
- `chat/CalendarChat` — the chat fallback (lifted out of `IntentController`): AGENT.md-prompted reply + the #195 first-message ICS-feed nudge (best-effort, soft-fail).
- `flow/BirthdayGreeter` — closes the inter-agent chain (CR-g2). On `birthday.greet` it invokes `creator.draft_greeting` over the hub (args `{person: displayName, occasion: "birthday"}`, longer timeout) and fans the returned greeting out to the household. Resolves to `false` (caller falls back to the local skill) on no person / hub error / `ok=false` / empty draft — best-effort, the wake always greets.
- `web/TriggerController` — `POST /agents/calendar/triggers/{kind}`. **System-trigger check first**, then skill dispatch + person resolution. `gift.recommend` is routed to `GiftRecommender` (Coordinator flow); `birthday.greet` to `BirthdayGreeter` (creator hub, local skill on fallback); every other kind takes the generic **memory enrichment (recall + relations zipped)** + LLM + household fan-out path. `buildRecallQuery` anchors the recall query on the person's display name when available.

## Tests

`mvn -B -pl domains/calendar/calendar-agent -am test` — 36 tests:
- `ActionControllerTest` — the `create_event` action, incl. **tenant routing** (ADR-0001 slice 4): an explicit `SHARED` choice routes to the family household, a `birthday` category defaults to shared, a plain event defaults to the personal household, and a `SHARED` choice with no family household degrades to personal; a request without `userId` falls back to the envelope household. Also the **sanity spot-checks** (#485): an end-before-start event is rejected, and an overlapping event is flagged as a double-booking `warning`. And the **`undo`** action (#486/HC-2): a stored id cancels the event (DELETE `/internal/event/{id}`) and confirms; an already-gone event → honest `ok=false`; a missing id → error.
- `EventCapturerTest` — the user-facing **event-capture** flow (#486/HC-1): "запиши встречу …" routes to `event-capture`, the LLM parse creates the event via mcp-caldav and confirms with an undo handle; an unresolvable time asks instead of creating.
- `EventCancellerTest` — the user-facing **event-cancel** flow (#486/HC-3): "отмени встречу с врачом" routes to `event-cancel`, reads the upcoming events, the LLM picks the target, and the reply **asks to confirm** with a `pendingAction` — deleting nothing yet; the follow-up `/resume` "да" then DELETEs `/internal/event/{id}`, while a decline leaves the event untouched.
- `EventMoverTest` — the user-facing **event-move** flow (#486/HC-4): "перенеси встречу с врачом на 16:00" routes to `event-move`, reads the upcoming events, the LLM picks the target + new time, and the reply **asks to confirm** with a `pendingAction` — changing nothing yet; the follow-up `/resume` "да" then PUTs the new `dtstart` to `/internal/event/{id}`, a picked target with no new time asks for it, and a decline leaves the event untouched.
- `IntentControllerTest` / `IntentFeedAutoIssueTest` — the router now classifies first (a non-event message falls through to `CalendarChat` + the #195 feed nudge).
- `BriefActionTest` — the read-only `brief` action (#290 Slice B2-followup): a hub-forwarded `brief` request recalls from memory-service, synthesizes one FAST answer, and returns `{agent:"calendar", answer}` grounded in the recalled fact.
- Manifest endpoint returns frontmatter + body.
- Skill loader discovers both `birthday-greeter` and `gift-recommender`.
- `TriggerControllerTest` (Dispatcher-driven MockWebServer faking LLM + profile + notifier + ics-import + memory-service + orchestrator; the orchestrator dispatcher routes by action so gift/birthday paths don't share an enqueue order) covers `birthday.greet` (creator hub + local-skill fallback) and `gift.recommend` (Coordinator path) end-to-end, asserting (a) household members receive the notify POST, (b) for `birthday.greet`, the hub invoke is `creator.draft_greeting` and the drafted greeting fans out with no local LLM call — and on an `ok=false` the local skill runs as fallback, (c) for `gift.recommend`, the coordinator calls finance's `get_gift_budget` through `/v1/agents/invoke`, the gathered budget envelope reaches the LLM, and the LLM body carries the recalled memory + relation `object_label`.
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
