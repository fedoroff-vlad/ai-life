# notes-agent

Conversational front of the **second-brain** substrate (epic [#257](https://github.com/fedoroff-vlad/ai-life/issues/257),
slice **SB-4**), port **8118**. Captures the things the owner wants to remember and finds them again:
"запомни …" saves a durable note, "что я думал про …" recalls it with its connected notes. Registered
in the orchestrator as `notes`; **owns no MCP** — the knowledge base is memory-service itself
(`memory.note` + recall + `[[wiki-link]]` graph), reached through the shared `agent-runtime` clients plus
a thin `NoteClient` over the same memory-service base URL. Plan: [plans/second-brain.md](../../../plans/second-brain.md).

## Status (SB-4 + resurfacing R-b + ambient-approve AC-4 + lists LI-a + undo H + note-delete/note-edit H.2)

Scaffold + the **capture**, **recall**, **proactive-resurfacing**, **ambient-approve** (AC-4 resume),
and **lists** (LI-a) flows, plus the **undo** reversal (#486/Track H — "отмени последнее" deletes a
just-captured note) and the user-facing **note-delete** flow (#486/Track H.2 — "удали заметку про X"
behind a confirm gate).

- **Lists (LI-a)** — everyday item checklists (a shopping / to-buy / packing list). A "…список…" /
  "вычеркни …" cue → one llm-gateway turn with the `list-manager` SKILL classifies the message into
  `{op, list, item}` → `ListManager` resolves the list note (find-or-create by title among the
  household's `type=list` notes) and applies the op (`add` / `check` / `clear` / `show`) to the
  checklist body via the pure `MarkdownChecklist` util, persisting with `POST`/`PUT /v1/notes`. A list
  **is** a note (`type=list`, household-shared, body = a `- [ ]`/`- [x]` task list), so it recalls and
  exports (SB-2/SB-7) with everything else — no new store/endpoint/contract. Plan:
  [plans/lists.md](../../../plans/lists.md). Ambient (keyword-free) list capture is LI-b, deferred.

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
| POST | `/agents/notes/intent` | orchestrator entry. `NotesIntentRouter` classifies the message via the shared `SkillClassifier` (#475) into one intent skill — `note-finder` recall / `list-manager` op (LI-a) / `note-writer` capture — or the chat fallback. Routing SSOT is each skill's SKILL.md description (a paraphrase outside the old keyword cues routes correctly); every stage soft-fails to chat. |
| POST | `/agents/notes/resume` | orchestrator resume for an open notes question (route-lock). `pendingAction.flow=ambient-approve` → confirm/drop the ambiently-captured note (AC-4); `note-delete-confirm` → delete/keep the note picked for deletion (#486/Track H.2); `note-edit-confirm` → apply/discard the edit picked for a note (#486/Track H.2); unknown flow → graceful reply. |
| POST | `/agents/notes/actions/{action}` | inter-agent action envelope (Track C1). `undo` (#486/Track H) reverses a just-captured note: `DELETE /v1/notes/{id}` by the stored handle id, confirming with its title; an already-gone note → honest `ok=false`. |
| POST | `/agents/notes/triggers/{kind}` | scheduler wake (via orchestrator). `notes.resurface` → surface one stale note to the household; unbound kind → 404. |
| GET | `/agents/notes/manifest` | the manifest the orchestrator scrapes on startup. |

## Skills

- **`note-writer`** (`domains/knowledge/skills/note-writer/SKILL.md`) — strict-JSON structure of a note
  (title / type / tags / body) from a "remember this" request.
- **`note-finder`** (`domains/knowledge/skills/note-finder/SKILL.md`) — strict-JSON distil of a search
  query from a "what did I think about X" request.
- **`list-manager`** (`domains/knowledge/skills/list-manager/SKILL.md`) — strict-JSON classify of a
  list request into `{op, list, item}` (`op` ∈ `add|check|clear|show`) for LI-a.
- **`note-delete`** (`domains/knowledge/skills/note-delete/SKILL.md`) — strict-JSON pick of which
  saved note to delete from a numbered candidate list (`{"pick":n}`/`{"ambiguous":[…]}`/`{}`), for the
  confirm-gated delete-a-note flow (#486/Track H.2).
- **`note-edit`** (`domains/knowledge/skills/note-edit/SKILL.md`) — strict-JSON pick of which saved note
  to fix/rename **plus** the new title/body the user gave (`{"pick":n,"newTitle"?,"newBody"?}` /
  `{"ambiguous":[…]}` / `{}`), for the confirm-gated edit-a-note flow (#486/Track H.2); a bare `{"pick":n}`
  (note named, no change stated) makes the flow ask what to change.

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
- `http/NoteClient` — `/v1/notes` create / get / **update** / **list** / **delete** / backlinks / **resurface** over the shared `memoryServiceWebClient`. `delete` (#486/Track H) is the undo reversal — `DELETE /v1/notes/{id}` (memory-service also drops the recall seed + wiki-link edges).
- `list/ListManager` — LI-a flow: LLM classify (`list-manager` SKILL, temperature 0) → find-or-create the `type=list` note by title → mutate the checklist body (via the shared `common.list.MarkdownChecklist` in `libs/platform-common`) → `POST`/`PUT /v1/notes`; each stage soft-fails. **Why-trace (#485/G2):** the write ops (add / check-off / clear / create-list) attach a payload-free `IntentResponse.trace` ("wrote: …"); reads (show) and no-ops (duplicate/absent) none.
- `http/SchedulerClient` — R-c: idempotent `ensureResurfaceSchedule(household)` (list → create only if no `notes.resurface` cron yet) over `schedulerWebClient`; best-effort, soft-fails.
- `flow/NoteResurfacer` — the R-b proactive flow: `NoteClient.resurface` → format a reminder → deliver via notifier (owner if set, else household fan-out); best-effort, no-op when nothing is stale.
- `write/NoteWriter` — the capture flow: LLM structure (`note-writer` SKILL, temperature=0) → `NoteClient.create`; soft-fails per stage, falls back to the user's words for the title. On a successful capture it also fires `SchedulerClient.ensureResurfaceSchedule` (R-c, fire-and-forget), attaches a payload-free `IntentResponse.trace` "wrote: saved a note" (why-trace #485/G2), and an `IntentResponse.undo` handle (#486/Track H) carrying the note id + title so "отмени последнее" can delete it.
- `web/ActionController` — the inter-agent action envelope (`AgentActionController`). Registers `undo` (#486/Track H): deletes a just-captured note via `NoteClient.delete` and confirms with its title; an already-gone note → honest `ok=false`.
- `find/NoteFinder` — the recall flow: LLM query distil (`note-finder` SKILL, temperature=0) → `MemoryClient.recall` → resolve `refId` → `NoteClient.get`, top hit enriched with `NoteClient.backlinks`; each stage soft-fails.
- `chat/NotesChat` — the open-question fallback (AGENT.md system prompt).
- `approve/AmbientApprover` — AC-4 resume: parse the `pendingAction.note` (a ready `WriteNoteRequest`), and on an affirmative reply write it (`source=ambient`) via `NoteClient.create`, else drop; both clear the lock. Soft-fails to a friendly reply.
- `intent/NoteDeleter` — the user-facing **delete-a-note** flow (#486/Track H.2 — the notes delete hole), behind a **confirm-before-delete** gate. `delete(msg)`: read the household's recent notes (`NoteClient.list`, `type=list` notes excluded — those are LI-a's job) → LLM pick (`note-delete` SKILL, temperature 0, numbered candidates) returning `{"pick":n}` / `{"ambiguous":[…]}` / `{}` → a single match replies with a `pendingAction` asking to confirm (deletes nothing); ambiguous lists, none/miss asks. `resume(req)`: an affirmative deletes via `NoteClient.delete` (memory-service `DELETE /v1/notes/{id}` — the same reversal the undo primitive uses, which also drops the recall seed + wiki-link edges), anything else leaves it; either reply clears the lock. Mirrors finance's `TransactionDeleter` / tasks' `TaskDeleter`. Every stage soft-fails. The pick→confirm→act loop itself is the shared `agent-runtime` `PickConfirmActRunner` (ADR-0004); this class is the notes adapter (`candidates`/`view`/`act`/`nouns`, `type=list` exclusion in the read).
- `intent/NoteEditor` — the user-facing **edit-a-note** flow (#486/Track H.2 — the notes edit hole), behind a **confirm-before-change** gate. `edit(msg)`: read the household's recent notes (`NoteClient.list`, `type=list` excluded — those are LI-a's job) → LLM pick + extract the change (`note-edit` SKILL, temperature 0) returning `{"pick":n,"newTitle"?,"newBody"?}` / `{"ambiguous":[…]}` / `{}` → a single match with a stated change replies with a `pendingAction` asking to confirm (writes nothing); a `{"pick":n}` with no change re-asks ("Как исправить …?"); ambiguous lists, none/miss asks. `resume(req)`: an affirmative re-reads the note (`NoteClient.get`), applies the new title/body, and PUTs it (`NoteClient.update` — memory-service `PUT /v1/notes/{id}` replaces the mutable fields, so untouched fields are preserved), anything else leaves it; either reply clears the lock. Uses the shared `agent-runtime` `PickConfirmActRunner` (ADR-0004) — the first non-calendar update consumer: the new title/body threads through the `pendingAction` (like a calendar move's new time), `missing()` re-asks when no change is given, `act()` does the update. Every stage soft-fails.
- `intent/NotesIntentRouter` — the in-agent router (#475): one llm-gateway turn via the shared `agent-runtime` `SkillClassifier` offers the five intent skills as one `skill` choice (their SKILL.md descriptions are the routing SSOT) → dispatch to `NoteFinder`/`ListManager`/`NoteWriter`/`NoteDeleter`/`NoteEditor`, or the `NotesChat` fallback (blank / unknown-skill / non-JSON / LLM error all soft-fail to chat). Replaced the old keyword-cue heuristic.
- `web/IntentController` — thin: delegates `/intent` to `NotesIntentRouter`; `web/ManifestController`.
- `web/ResumeController` — `POST /agents/notes/resume`; dispatches on `pendingAction.flow` (`ambient-approve` → `AmbientApprover`, `note-delete-confirm` → `NoteDeleter.resume`, `note-edit-confirm` → `NoteEditor.resume`).
- `web/TriggerController` — `POST /agents/notes/triggers/{kind}`; `notes.resurface` → `NoteResurfacer` (202), unbound kind → 404.
