# notes-agent

Conversational front of the **second-brain** substrate (epic [#257](https://github.com/fedoroff-vlad/ai-life/issues/257),
slice **SB-4**), port **8118**. Captures the things the owner wants to remember and finds them again:
"запомни …" saves a durable note, "что я думал про …" recalls it with its connected notes. Registered
in the orchestrator as `notes`; **owns no MCP** — the knowledge base is memory-service itself
(`memory.note` + recall + `[[wiki-link]]` graph), reached through the shared `agent-runtime` clients plus
a thin `NoteClient` over the same memory-service base URL. Plan: [plans/second-brain.md](../../../plans/second-brain.md).

## Status (SB-4)

Scaffold + the **capture** and **recall** flows.

- **Capture (note-writer)** — a "запомни …" cue → one llm-gateway turn with the `note-writer` SKILL
  distils a structured note (title / type / tags / body, strict JSON, temperature=0) → `POST /v1/notes`
  on memory-service stores it (which auto-seeds recall + `[[wiki-link]]` graph edges, SB-2/SB-3) → reply
  confirming the title. A model that produces no usable title falls back to the user's own words.
- **Recall (note-finder)** — a "что я думал про …" cue → one llm-gateway turn with the `note-finder`
  SKILL distils a search query → a memory-service semantic recall surfaces the matching notes
  (`source=note`, `{kind:note, refId}`) whose `refId` resolves to their rows via `GET /v1/notes/{id}` →
  the reply lists them (title + snippet), and the top hit's **connected notes** are appended from its
  `GET /v1/notes/{id}/backlinks` (SB-3).

Otherwise a message falls through to a chat fallback. Every stage soft-fails to a friendly reply.

## Endpoints

| method | path | purpose |
|--------|------|---------|
| POST | `/agents/notes/intent` | orchestrator entry. "запомни …" cue → `note-writer` capture; "что я думал про …" cue → `note-finder` recall; else the chat fallback. |
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
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | llm-gateway for the note structure / query distil. |
| `MEMORY_SERVICE_URL` | `http://memory-service:8087` | the knowledge base — `/v1/notes` (store, get, backlinks) + `/v1/memories/recall`. |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` | internal | shared agent-runtime clients. |

## Key classes

- `NotesAgentApplication` — `@SpringBootApplication` + `@Import(AgentRuntimeConfig)`.
- `config/NotesAgentProperties` — `notes-agent.*` base URLs (implements `SharedClientProperties`).
- `http/NoteClient` — `/v1/notes` create / get / backlinks over the shared `memoryServiceWebClient`.
- `write/NoteWriter` — the capture flow: LLM structure (`note-writer` SKILL, temperature=0) → `NoteClient.create`; soft-fails per stage, falls back to the user's words for the title.
- `find/NoteFinder` — the recall flow: LLM query distil (`note-finder` SKILL, temperature=0) → `MemoryClient.recall` → resolve `refId` → `NoteClient.get`, top hit enriched with `NoteClient.backlinks`; each stage soft-fails.
- `chat/NotesChat` — the open-question fallback (AGENT.md system prompt).
- `web/IntentController` — recall cue → find, capture cue → write, else chat; `web/ManifestController`.
