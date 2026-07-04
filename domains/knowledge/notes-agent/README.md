# notes-agent

Conversational front of the **second-brain** substrate (epic [#257](https://github.com/fedoroff-vlad/ai-life/issues/257),
slice **SB-4**), port **8118**. Captures the things the owner wants to remember and finds them again:
"запомни …" saves a durable note, "что я думал про …" recalls it with its connected notes. Registered
in the orchestrator as `notes`; **owns no MCP** — the knowledge base is memory-service itself
(`memory.note` + recall + `[[wiki-link]]` graph), reached through the shared `agent-runtime` clients plus
a thin `NoteClient` over the same memory-service base URL. Plan: [plans/second-brain.md](../../../plans/second-brain.md).

## Status (SB-4 + resurfacing R-b)

Scaffold + the **capture**, **recall**, and **proactive-resurfacing** flows.

- **Resurface (R-b)** — a `notes.resurface` scheduler wake (declared in `AGENT.md`) → `NoteResurfacer`
  pulls one stale note the owner hasn't revisited in a while (memory-service
  `GET /v1/notes/resurface`, staleness window `NOTES_RESURFACE_OLDER_THAN_DAYS`) → delivers a gentle
  "🧠 Из твоих заметок: «…»" reminder via notifier-service (to the note's owner if set, else fanned out
  to the household). Best-effort: nothing stale (`204`) is a silent no-op; the wake always returns 202
  so the schedule advances.
- **Cron auto-registration (R-c)** — on a successful capture, `NoteWriter` fires `SchedulerClient`
  `ensureResurfaceSchedule`: it lists the household's schedules and, only if no `notes.resurface` cron
  exists yet, registers one (`POST /v1/schedules`, `NOTES_RESURFACE_CRON`, default weekly). Idempotent,
  best-effort, off the reply path — the same "ensure on first use" shape calendar uses to auto-issue an
  ICS feed. No manual setup: capturing your first note wires up the weekly resurfacing wake.

- **Capture (note-writer)** — a "запомни …" cue → one llm-gateway turn with the `note-writer` SKILL
  distils a structured note (title / type / tags / body, strict JSON, temperature=0) → `POST /v1/notes`
  on memory-service stores it (which auto-seeds recall + `[[wiki-link]]` graph edges, SB-2/SB-3) → reply
  confirming the title. A model that produces no usable title falls back to the user's own words.
- **Recall (note-finder)** — a "что я думал про …" cue → one llm-gateway turn with the `note-finder`
  SKILL distils a search query → a memory-service semantic recall surfaces the matching notes
  (`source=note`, `{kind:note, refId}`) whose `refId` resolves to their rows via `GET /v1/notes/{id}` →
  the reply lists them (title + snippet), and the top hit's **connected notes** are appended from its
  `GET /v1/notes/{id}/backlinks` (SB-3).

- **Ambient-approve (AC-4)** — the resume side of ambient capture. When memory-service notices an
  important-but-inferred fact from ordinary chat it asks the owner "заметил: … — записать?" and route-locks
  the conversation to `notes` with the ready-to-write note in the `pendingAction`. The owner's reply
  resumes at `POST /agents/notes/resume`: an affirmative (`да`/`ок`/…) writes the pre-built note via
  `POST /v1/notes` (`source=ambient`; attribution + `[[wiki-link]]` were resolved at capture time, so this
  is a passthrough), anything else drops it. Either way the reply carries no `pendingAction`, so the
  orchestrator clears the lock. The **capture side that raises the question is memory-service (AC-4b, next).**

Otherwise a message falls through to a chat fallback. Every stage soft-fails to a friendly reply.

## Endpoints

| method | path | purpose |
|--------|------|---------|
| POST | `/agents/notes/intent` | orchestrator entry. "запомни …" cue → `note-writer` capture; "что я думал про …" cue → `note-finder` recall; else the chat fallback. |
| POST | `/agents/notes/resume` | orchestrator resume for an open notes question (route-lock). `pendingAction.flow=ambient-approve` → confirm/drop the ambiently-captured note (AC-4); unknown flow → graceful reply. |
| POST | `/agents/notes/triggers/{kind}` | scheduler wake (via orchestrator). `notes.resurface` → surface one stale note to the household; unbound kind → 404. |
| GET | `/agents/notes/manifest` | the manifest the orchestrator scrapes on startup. |

## Skills

- **`note-writer`** (`domains/knowledge/skills/note-writer/SKILL.md`) — strict-JSON structure of a note
  (title / type / tags / body) from a "remember this" request.
- **`note-finder`** (`domains/knowledge/skills/note-finder/SKILL.md`) — strict-JSON distil of a search
  query from a "what did I think about X" request.

## Env

| Var | Default | Purpose |
|---|---|---|
| `NOTES_AGENT_PORT` | `8118` | HTTP port. |
| `NOTES_AGENT_MEMORY_RECALL_K` | `5` | memory-recall fan-in (shared agent-runtime). |
| `NOTES_RESURFACE_OLDER_THAN_DAYS` | `30` | a resurface wake surfaces a note untouched for at least this many days. |
| `NOTES_RESURFACE_CRON` | `0 0 10 * * MON` | Spring 6-field cron for the auto-registered household resurface wake (weekly, Mon 10:00). |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | llm-gateway for the note structure / query distil. |
| `MEMORY_SERVICE_URL` | `http://memory-service:8087` | the knowledge base — `/v1/notes` (store, get, backlinks, resurface) + `/v1/memories/recall`. |
| `SCHEDULER_URL` | `http://scheduler-service:8085` | scheduler-service — auto-register the household resurface cron (R-c). |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` | internal | shared agent-runtime clients. |

## Key classes

- `NotesAgentApplication` — `@SpringBootApplication` + `@Import(AgentRuntimeConfig)`.
- `config/NotesAgentProperties` — `notes-agent.*` base URLs (implements `SharedClientProperties`).
- `config/OutboundHttpConfig` — the `schedulerWebClient` bean (its own base URL; the profile/notifier/memory clients come from `agent-runtime`).
- `http/NoteClient` — `/v1/notes` create / get / backlinks / **resurface** over the shared `memoryServiceWebClient`.
- `http/SchedulerClient` — R-c: idempotent `ensureResurfaceSchedule(household)` (list → create only if no `notes.resurface` cron yet) over `schedulerWebClient`; best-effort, soft-fails.
- `flow/NoteResurfacer` — the R-b proactive flow: `NoteClient.resurface` → format a reminder → deliver via notifier (owner if set, else household fan-out); best-effort, no-op when nothing is stale.
- `write/NoteWriter` — the capture flow: LLM structure (`note-writer` SKILL, temperature=0) → `NoteClient.create`; soft-fails per stage, falls back to the user's words for the title. On a successful capture it also fires `SchedulerClient.ensureResurfaceSchedule` (R-c, fire-and-forget).
- `find/NoteFinder` — the recall flow: LLM query distil (`note-finder` SKILL, temperature=0) → `MemoryClient.recall` → resolve `refId` → `NoteClient.get`, top hit enriched with `NoteClient.backlinks`; each stage soft-fails.
- `chat/NotesChat` — the open-question fallback (AGENT.md system prompt).
- `approve/AmbientApprover` — AC-4 resume: parse the `pendingAction.note` (a ready `WriteNoteRequest`), and on an affirmative reply write it (`source=ambient`) via `NoteClient.create`, else drop; both clear the lock. Soft-fails to a friendly reply.
- `web/IntentController` — recall cue → find, capture cue → write, else chat; `web/ManifestController`.
- `web/ResumeController` — `POST /agents/notes/resume`; dispatches on `pendingAction.flow` (`ambient-approve` → `AmbientApprover`).
- `web/TriggerController` — `POST /agents/notes/triggers/{kind}`; `notes.resurface` → `NoteResurfacer` (202), unbound kind → 404.
