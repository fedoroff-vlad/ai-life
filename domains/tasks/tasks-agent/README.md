# tasks-agent

GTD tasks agent (port **8096**). Owns the inbox → clarify → engage flow for a household's
tasks; tools come from `mcp-tasks` (source of truth: Postgres `tasks.*`). See
[plans/tasks.md](../../plans/tasks.md).

Manifest, intent and the first trigger (`weekly.review`) are real. `intent` routes via
`IntentRouter` (PR58): when mcp-tasks tools are wired the LLM either invokes a tool
(`add_task`/`clarify_task`/`list_tasks`/…), runs an **intent skill**, or replies directly; with the
MCP client disabled it falls back to a plain chat. Intent skills are skills with no `triggers`
(user-invoked, not scheduler-fired) — `inbox-clarify`, `next-action-suggester` and `task-capture`
(the sharing write path, ADR-0002 slice 5); when the router picks one, `IntentController` runs that
skill's flow. `triggers/{kind}` resolves a skill from the
`SkillRegistry`, enriches the wake payload, runs the skill (LLM with AGENT.md + SKILL.md), and fans
the result out to the household — unknown kinds still 404.

The MCP client dials `mcp-tasks` at boot (`spring.ai.mcp.client.enabled`, default true) — a
missing mcp-tasks surfaces at agent startup. Toggle off with `TASKS_AGENT_MCP_CLIENT_ENABLED=false`
in dev/degraded environments.

## Endpoints

| method | path | purpose |
|--------|------|---------|
| GET  | `/agents/tasks/manifest`        | parsed AGENT.md (orchestrator scrapes at startup) |
| POST | `/agents/tasks/intent`          | LLM routes to an mcp-tasks tool call, an intent skill (`inbox-clarify` / `next-action-suggester` / `task-capture`), or a chat reply (`IntentRouter`) |
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
- `intent/IntentRouter` (on the shared `SkillClassifier` since #358 Bucket 1 slice 3) — single LLM
  classifier turn → tool / intent-skill / chat. Delegates prompt-build + strict-JSON parse to
  `SkillClassifier` (`libs/agent-runtime`); the agent keeps its own LLM round-trip, the `ToolSpec`s it
  maps from the wired mcp-tasks tools, and the one **`skill` choice** (the intent skills collapsed into
  one action — the LLM names the skill in `node.name`; an unknown name falls back to chat). Intent
  skills are the trigger-less skills (user-invoked, not scheduler-fired). Empty intent-skill set → only
  tool/chat offered; both tools and skills empty → the pre-routing plain chat path.
- `intent/InboxClarifier` — runs the `inbox-clarify` flow **apply-on-confirm**: fetch the inbox via
  `TaskReviewClient` (`/internal/review`) → LLM returns structured proposals → render a confirm +
  stash them as a `pendingAction`. On `resume` an affirmative reply applies each via `ClarifyClient`
  (`/internal/clarify`); anything else cancels.
- `intent/NextActionSuggester` — runs the `next-action-suggester` flow: fetch open next-actions via the
  sharing-aware `read/TaskReads` → LLM ranks by due/priority/context. Read-only (suggests, doesn't change
  tasks). **Sharing (ADR-0002 slice 5b):** default reads the member's **own** tasks (envelope household,
  mirroring finance); when the router tags `scope:"shared"` (family/shared tasks — "наши дела") it unions
  across the member's personal ∪ shared households.
- `read/TaskReads` — the sharing-aware **read** helper (sibling of finance's `read/SpendingReads`):
  `households(envelope, userId, shared)` (own = envelope; shared = personal ∪ shared via
  `ProfileSharingClient.households`, degrading to the envelope) + `nextActionsUnion(households, limit)`
  (fan-out across the set + flatten). The own-vs-shared cut lives here, in one place.
- `capture/TaskCapturer` — the sharing **write path** (ADR-0002 slice 5): runs the `task-capture`
  flow. The LLM plans `{title, note?, shared?}`, the shared `SharingResolver` (wired with
  `sharing/TasksSharingPolicy`) routes it to the personal or the shared household, then `AddTaskClient`
  (`POST /internal/task`) captures it to the inbox. The deterministic capture an LLM-driven `add_task`
  tool call can't take (the classifier never sees the household id). Mirrors finance's `AccountManager`.
- `sharing/TasksSharingPolicy` — tasks' `DefaultSharingPolicy`: a household/shared-list task (chore,
  shared shopping, involves another member) defaults to the shared household, a personal todo to
  private. The only "what is shared here" logic tasks owns; the routing mechanism lives in `libs/sharing`.
  **Memory-driven default (item 8, DS-4):** `config/OutboundHttpConfig` wraps `TasksSharingPolicy` in
  `libs/sharing`'s `LearnedSharingPolicy` and builds the `SharingResolver` with its learning-enabled
  constructor (+ a `SharingLearningClient` bean over the shared `memoryServiceWebClient`), so a captured
  task with no explicit household/personal signal defaults to the owner's learned choice for the same
  signal profile once the tally is deep + decisive, else the static household-task rule; explicit choices
  are recorded. Both best-effort — routing mechanism unchanged. Mirrors calendar / finance.
- `http/AddTaskClient` — `POST /internal/task` passthrough (capture under a resolved household).
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
  Validated on a real model by the opt-in `intent.GoldenInboxClarifyTest` (Stage 5 / #199): asserts
  the LLM emits strict `{"proposals":[…]}` JSON with verbatim task ids + valid GTD statuses, skipped
  in CI (`GOLDEN_LLM` gate) — see `platform/llm-gateway/README.md` §Golden tests.
- `next-action-suggester` — reactive (user-invoked, e.g. "что мне сейчас сделать"): fetches the open
  next-actions and ranks them by due date / priority / context. Read-only suggestion. Defaults to the
  member's own tasks; "наши дела / семейные задачи" (`scope:"shared"`) unions personal ∪ shared
  households (ADR-0002 slice 5b).
- `task-capture` — reactive (user-invoked, e.g. "напомни купить молоко"): plans the task and marks
  whether it belongs on the household/shared list; the agent routes it to the personal or shared
  household (ADR-0002 slice 5 — the sharing write path).
