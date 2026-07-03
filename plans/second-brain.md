# second brain — unified knowledge substrate

Authority file for the **second-brain** epic (issue [#257](https://github.com/fedoroff-vlad/ai-life/issues/257)):
the owner's own "second memory" — an ai-life-owned, Obsidian-shaped knowledge substrate that the
**whole system reads from and writes to**. Not a leaf agent; a foundational layer. Reframes
[#189](https://github.com/fedoroff-vlad/ai-life/issues/189) (family-memory) as one slice on top of it.

## North-star
A single durable memory the owner deliberately curates and every agent feeds:
- **markdown** as the human form (title + body + frontmatter + tags),
- **vector** for "recall what's related",
- **graph** (`[[wiki-links]]` → backlinks) for "what's connected",
- surfaced through Telegram now (capture + query + proactive resurfacing), with **endpoints/seams left
  open so a UI — Obsidian-like or bespoke — can attach later**. UI itself is out of scope for the epic.

The test of done for the whole epic: the owner can say "запомни …" and later "что я думал про …",
agents automatically enrich their answers from this store, and the store can be exported as a
markdown vault.

## Doctrine — evolve `memory-service`, don't build a second store (decision: variant A)
The engine already exists in [`platform/memory-service`](../platform/memory-service/README.md):
`memory.memories` (pgvector recall) + `memory.relations` (single-hop graph; AGE deferred per its
promotion criteria). We **add an authored-notes tier on top of the same service/schema**, not a rival
knowledge base. Consequences (flag nothing new — it's additive over proven parts):

- **A note is the durable, authored, human-readable unit** — `memory.note` (title, `body_md`,
  `frontmatter` jsonb, `tags[]`, timestamps), editable and forgettable. Distinct from the opaque
  auto-facts (`source = "chat-capture"`) which stay as the associative tier.
- **Notes reuse the recall engine for free.** On write, a note auto-seeds a `memory.memories` row
  (embed the body, `source = "note"`, metadata `{kind:note, refId:noteId}`) — **exactly the pattern
  docs-agent D-e already ships** for documents. Semantic "find related" then works with zero new
  retrieval code.
- **Notes reuse the graph engine for free.** `[[wiki-links]]` in the body are parsed to
  `memory.relations` edges (`subject_type=note` → `object_type=note`), so backlinks + traversal come
  from the existing relation store. This adds a `note` subject/object type — a small additive change to
  the relations surface, not a new graph.
- **markdown is the interchange format, Postgres is the source of truth.** No dependency on Obsidian
  the app; export/import md keeps the data portable and a future vault-sync cheap.
- **Universal seam, not a silo.** Agents that learn a durable thing write a note (or upgrade their
  existing `memory-service` seed to a note); agents that need context recall over the unified store.
  docs-agent D-e is the first brick; the epic generalises it.
- **Coordination stays agent-led.** A thin `notes-agent` is the conversational front (capture / query /
  resurface); the substrate itself is a shared platform capability every agent already binds
  (`memory-service`), so there's no new client to invent — recall/relations clients exist in
  `libs/agent-runtime`.

## Boundaries (so we don't grow a second everything)
- **vs auto-facts:** the note tier is *curated & authored*; `chat-capture` stays *associative & noisy*.
  Same service, distinguished by `source`/`kind`. Recall can span both or filter to notes.
- **vs proactive chains (`GiftRecommender`, creator-greeting):** they remain the owners of the
  proactive act; the second brain only **improves their inputs** (curated person notes instead of
  noisy extracted facts). We must NOT reimplement gift/greeting logic here.
- **vs #189 family-memory:** #189 becomes a slice — people/preferences/anniversaries are just notes
  tagged `#person`, resolved to `core.people` for the graph. Close #189 once that slice lands.

## Architecture sketch
```
Telegram → orchestrator → notes-agent ─┐
                                       ├─(write)→ memory-service  POST /v1/notes
docs/finance/calendar/... agents ──────┘                          │  ├─ note row (memory.note)
                                                                   │  ├─ auto-seed embedding (memory.memories, source=note)
                                                                   │  └─ parse [[links]] → edges (memory.relations, type=note)
any agent ──────────────────(read)────→ memory-service  /v1/memories/recall  (spans notes + facts)
                                                        /v1/notes/{id}/backlinks (relations)
                                                        /v1/notes/export → markdown vault
```

## Note format — the manifest
There is no single industry "second-brain" standard; we adopt the proven de-facto conventions
(**CommonMark** body + **YAML frontmatter** + **`[[wikilinks]]`** + **`#tags`** + Zettelkasten
atomicity/stable-id/backlinks) and pin our own note shape on top. This mirrors the format Claude's own
file-memory uses (frontmatter + `[[links]]` + an index) — a working precedent, not an invention.

A note (rendered form):
```markdown
---
id: 7a3f-…            # stable UUID — the anchor for links/refId (NOT the title)
title: Мама — что любит
type: person           # person | fact | idea | reference | journal | goal | reflection
tags: [person, gift]
source: user           # who authored: user | docs-agent | calendar-agent | …
person: mama-uuid      # optional: core.people id when the note is about a person
created: 2026-07-02
updated: 2026-07-02
---
Любит пионы 🌸. Предпочитает в горшке, не срезку.
Связано: [[Мама]] · #gift
```

Conventions on top of the format:
- **`id` ≠ `title`.** Links / `refId` / graph edges anchor on the stable `id`; the human `title` is free
  to rename.
- **`[[Target]]`** resolves to a note (by title) **or** a person (`core.people`); an unresolved link
  stays a stub edge with a label (dangling `[[link]]`s are allowed, same as Claude's memory).
- **Atomicity** — prefer many small linked notes over one long note.
- **`source`** separates authored (`user`) from agent-written notes; an agent note carries a `ref`
  back-pointer (`{kind, refId}` — the exact shape docs-agent D-e already seeds).
- **`type`** is deliberately pre-stocked with `goal` / `journal` / `reflection` so the future
  `coach-agent` (see Repo layout) inherits a structured corpus with zero migration.
- **Scope** — `household` + `owner` (null owner = household-shared), same as the rest of `memory.*`.

**Storage vs form:** the source of truth is a Postgres row (`memory.note`); markdown is the
form/interchange. Frontmatter maps to columns (`title, type, tags[], source, person_id,
created/updated`) plus a `frontmatter jsonb` for extensibility without migrations. Body `[[wikilinks]]`
project into `memory.relations`. SB-7 export reassembles the row back into `.md` with this header,
round-trippable.

## Repo layout
Variant A ⇒ the substrate is an **extension of `platform/memory-service`**, NOT a new domain-MCP. The
conversational front is a thin agent under its own domain folder (1:1 with `domains/docs/docs-agent`).
```
platform/memory-service/…/memory/          # the substrate (extend the existing service)
  domain/NoteRow.java + NoteRepository.java # JdbcTemplate row model, like MemoryRow/RelationRow (no JPA)
  note/WikiLinkParser.java                  # extract [[Target]] from the body
  service/NoteService.java                  # write → row + seed embedding (MemoryService) + edges (RelationService); delete → forget
  web/NoteController.java                    # /v1/notes CRUD + /v1/notes/{id}/backlinks + /v1/notes/export

libs/contracts/…/contracts/note/           # new contract package
  NoteDto · WriteNoteRequest · NoteBacklinksResponse

domains/notes/                             # new domain — the front (no own MCP; binds memory-service)
  notes-agent/                             # Spring Boot app, port 8118
    …/agents/notes/{NotesAgentApplication, config/*, http/NoteClient,
                    write/NoteWriter, find/NoteFinder, chat/NotesChat, web/*}
    AGENT.md · README.md · pom.xml
  skills/{note-writer, note-finder}/SKILL.md (+ SKILL.ru.md)

infra/liquibase/features/090-memory-note.yml  # memory.note table (existing `memory` schema) + master include
```
Wiring touchpoints (easy to forget): root `pom.xml` module, `infra/compose.yaml` service (8118),
orchestrator agent registry (registered as `notes`), `AGENT.md` binds `memory-service` (via the shared
runtime client), **no `mcp-notes`**.

**Future sibling — `coach-agent` (self-improvement).** Planned later as its **own** domain
(`domains/coach/coach-agent`) that *reads* this substrate (goals / journal / reflections / patterns) and
adds behaviour-change logic — a consumer of the second brain exactly like every agent already consumes
`memory-service`, not a variant of `notes-agent`. The `goal`/`journal`/`reflection` note types above
exist so its corpus is ready when it lands.

## Phased slices (each = one small vertical slice / PR unless noted)
- **SB-1 — `memory.note` store + notes CRUD on memory-service. ✅ DONE (PR #260).** `memory.note` table
  (Liquibase `090-memory-note.yml`, existing `memory` schema) + `NoteDto`/`WriteNoteRequest` contracts +
  `NoteRow`/`NoteRepository`/`NoteService`/`NoteController` = `POST/GET/PUT/DELETE /v1/notes` (+ list).
  Per the manifest: `id`≠`title` anchor, null owner = household-shared, blank `type`→`fact`, null
  `source`→`user`, tags/frontmatter jsonb. No embedding/graph wiring yet — just the durable authored row.
  `NotesIntegrationTest` (6, Testcontainers); full memory-service suite green.
- **SB-2 — auto-seed recall on note write. ✅ DONE (PR #262).** On create/update `NoteService.reseed`
  embeds `title`+`body_md` into `memory.memories` (`source=note`, `{kind:note, refId}`) via
  `MemoryService.write`; on delete `forget` drops it via the new `MemoryService.forgetBySourceRef`
  (`metadata.refId` match) — an update re-seeds (one memory per note). Best-effort: the note row commits
  first, so an embed/llm-gateway outage never fails the write. Notes are now recallable via the existing
  `/v1/memories/recall`. `NoteSeedIntegrationTest` (3, mock embedder): seed+recall, re-seed-once on
  update, forget-on-delete.
- **SB-3 — `[[wiki-links]]` → relations + backlinks. ✅ DONE.** On create/update `NoteService.reseedLinks`
  parses the body via `WikiLinkParser` and re-projects each distinct `[[target]]` into a `memory.relations`
  edge (`subject_type=note`, `subject_id=<note>`, `edge=links_to`, `source=note`): target resolves to a
  note by title (case-insensitive) → `object_type=note`, else a person via profile-service →
  `object_type=person`, else a dangling `object_type=label` stub. An update drops the note's old edges by
  subject and re-seeds; delete forgets them — best-effort (never fails the note write). `GET
  /v1/notes/{id}/backlinks` (`NoteBacklinksResponse`) reads the reverse note→note edges. New:
  `RelationRepository.deleteBySubjectNote`/`backlinkNoteIds`, `RelationService.forgetNoteLinks`/
  `noteBacklinkIds`, `NoteRepository.findIdByTitle`. `WikiLinkParserTest` (5, unit) +
  `NoteLinksIntegrationTest` (6, Testcontainers: note/person/label edges, backlinks, re-seed, forget).
- **SB-4 — `notes-agent` (conversational front). ✅ DONE.** New `domains/knowledge/notes-agent` (port
  **8118**, registered as `notes`; owns no MCP — binds memory-service via the shared runtime clients +
  a thin `NoteClient` over the same `memoryServiceWebClient`). `IntentController` routes a "что я думал
  про …" cue → `NoteFinder`, a "запомни …" cue → `NoteWriter`, else `NotesChat`. **note-writer**
  ("запомни …" → `note-writer` SKILL, strict JSON title/type/tags/body, temp=0 → `POST /v1/notes`;
  falls back to the user's words for the title). **note-finder** ("что я думал про …" → `note-finder`
  SKILL query distil, temp=0 → `MemoryClient.recall` → resolve `{kind:note, refId}` hits via `GET
  /v1/notes/{id}` → list, top hit enriched with `GET /v1/notes/{id}/backlinks`). Wired into root pom,
  orchestrator registry, compose (8118), `.env.example`, infra port table. Tests: `NoteWriterTest`/
  `NoteFinderTest` (MockWebServer), `ManifestControllerTest`, `E2ENotesCaptureRecallFlowTest` (capture→
  recall across real HTTP), golden `GoldenNoteWriterTest`/`GoldenNoteFinderTest` (`@GoldenLlmTest`).
- **SB-5 — universal write seam (agents feed the brain).** Generalise the docs-agent D-e pattern:
  agents that learn durable facts write notes (or upgrade their `memory-service` seed to a note with
  `kind`/`refId`). Start by pointing docs-agent's seed at the note tier; document the seam in
  `libs/agent-runtime` so new agents adopt it. One consumer per PR.
- **SB-6 — family/people slice (closes #189).** People notes (`#person`, resolved to `core.people`),
  `GiftRecommender` reads the curated person notes as a gather source. E2E closer proving a curated
  preference note flows into a gift suggestion. **Closes #189.**
- **SB-7 — markdown export (vault seam for a future UI).** `GET /v1/notes/export` → a zip / folder of
  `.md` files with frontmatter + `[[links]]` intact (round-trippable). The hand-off point for attaching
  any Obsidian-like frontend later.

## Deferred (out of the epic, note when a consumer needs one)
- **Real UI / vault two-way sync.** Endpoints (SB-7 export + SB-1 CRUD) are the seam; a live editor or
  filesystem watcher is later.
- **AGE multi-hop traversal.** Backlinks are single-hop (SQL) per memory-service's existing promotion
  criteria; promote to AGE only when 2+ hop walks or graph algorithms are actually needed.
- **Note versioning / history.** Edits overwrite for the MVP; an append-only revision log is later.
- **Bulk import** of an existing markdown vault (the inverse of SB-7 export).
- **Proactive resurfacing** ("ты полгода назад отметил X") — a scheduler wake over stale/relevant
  notes; the briefing-style proactive path, after the read/write core is proven.
- **`coach-agent` (self-improvement)** — its own future domain that reads this substrate (goals /
  journal / reflections); out of this epic, but the note `type`s above are pre-stocked for it (see Repo
  layout).

## Golden tests — from the start
Real model access is unblocked (local Ollama `qwen2.5:7b` via llm-gateway). Each LLM seam gets an
opt-in `@GoldenLlmTest` (`libs/golden-test-support`, gated by `GOLDEN_LLM`, CI-skipped):
`GoldenNoteWriterTest` (message → structured note JSON) and `GoldenNoteFinderTest` (question → query
distil). Assert **structure, not wording**. Each cross-service slice adds an `E2E…Test` per the
CLAUDE.md end-to-end rule.
