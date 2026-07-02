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

## Phased slices (each = one small vertical slice / PR unless noted)
- **SB-1 — `memory.note` store + notes CRUD on memory-service.** New `memory.note` table (Liquibase
  `NNN-memory-note.yml`, `memory` schema). `POST/GET/PUT/DELETE /v1/notes` + `NoteDto` in
  `libs/contracts`. No embedding/graph wiring yet — just the durable authored row. Slice-tested.
- **SB-2 — auto-seed recall on note write.** On create/update, embed `body_md` into `memory.memories`
  (`source=note`, `{kind:note, refId}`); on delete, forget it. Reuses `EmbeddingClient` + `MemoryService`.
  This makes notes recallable via the existing `/v1/memories/recall`. Mirrors docs-agent D-e's seed,
  now server-side and authoritative. Repository/integration-tested.
- **SB-3 — `[[wiki-links]]` → relations + backlinks.** Parse `[[target]]` tokens on write → upsert
  `memory.relations` edges (`subject_type=note`); add `note` as a relation subject/object type;
  `GET /v1/notes/{id}/backlinks`. Reuses `RelationService`. Link a `[[Person Name]]` to `core.people`
  when it resolves (else keep a `label` edge).
- **SB-4 — `notes-agent` (conversational front).** New `domains/knowledge/notes-agent` registered as
  `notes` in orchestrator. Skills: `note-writer` ("запомни …" → structured note: title/tags/body, strict
  JSON, temp=0 → `POST /v1/notes`) and `note-finder` ("что я думал про …" → recall + backlinks →
  reply). Golden-tested from the start (`@GoldenLlmTest`).
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

## Golden tests — from the start
Real model access is unblocked (local Ollama `qwen2.5:7b` via llm-gateway). Each LLM seam gets an
opt-in `@GoldenLlmTest` (`libs/golden-test-support`, gated by `GOLDEN_LLM`, CI-skipped):
`GoldenNoteWriterTest` (message → structured note JSON) and `GoldenNoteFinderTest` (question → query
distil). Assert **structure, not wording**. Each cross-service slice adds an `E2E…Test` per the
CLAUDE.md end-to-end rule.
