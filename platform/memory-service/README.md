# platform/memory-service

pgvector-backed long-term recall + single-hop relation graph + the authored-notes
tier of the **second-brain substrate** (epic [#257](https://github.com/fedoroff-vlad/ai-life/issues/257)).
`POST /v1/memories` writes a piece of text + metadata after embedding it via
llm-gateway; `POST /v1/memories/recall` returns top-k cosine-nearest hits within a
scope filter; `DELETE /v1/memories/{id}` forgets. `POST /v1/relations` writes one
structured edge ("Maria likes books"); `GET /v1/graph/person/{id}/relations?householdId=…`
returns the person's outgoing + incoming edges. `POST /v1/capture` is the
memory-from-chat path: it runs the LLM over a message to extract durable facts
and stores them as memories. `/v1/notes` is CRUD over `memory.note`, the durable,
human-authored, markdown unit of the knowledge base — the substrate we **evolve**
into a "second brain": on write a note's body auto-seeds recall (SB-2) and its
`[[wiki-links]]` project into the relation graph (SB-3); `GET /v1/notes/{id}/backlinks`
reads the notes that link to it.

## Port: `8087` (`MEMORY_PORT`)

## Endpoints

### Memories (pgvector)
- `POST /v1/memories` — body: [WriteMemoryRequest](../../libs/contracts/src/main/java/dev/fedorov/ailife/contracts/memory/WriteMemoryRequest.java). Returns `MemoryDto`.
- `POST /v1/memories/recall` — body: [RecallMemoryRequest](../../libs/contracts/src/main/java/dev/fedorov/ailife/contracts/memory/RecallMemoryRequest.java) (`householdId` required, `userId`/`personId` narrow, `k` defaults to 5, capped at 50). Returns `RecallMemoryHit[]` ordered by ascending cosine distance.
- `DELETE /v1/memories/{id}` — 204 on success, 404 on unknown.

### Capture (memory-from-chat)
- `POST /v1/capture` — body [CaptureRequest](../../libs/contracts/src/main/java/dev/fedorov/ailife/contracts/memory/CaptureRequest.java) (`householdId` + `text` required; `userId`/`personId` scope the stored memories). **Synchronous:** runs the LLM `FactExtractor` over the message, writes each extracted durable fact as a memory (`source = "chat-capture"`), and returns the written `MemoryDto[]` (empty list when nothing durable was found). 400 on missing householdId / blank text. **Best-effort:** a malformed LLM reply or parse failure yields zero facts, never an error. For direct/manual/debug use. **Relation capture (MFC-c):** the same call also runs `RelationExtractor` and writes resolved edges to `memory.relations` as a **best-effort side effect** — it never throws and is not reflected in the returned `MemoryDto[]` (which stays memories-only). A `"self"` edge anchors on the `userId` (`subjectType = "user"`); a named subject is resolved to a `core.people` UUID via profile-service (`subjectType = "person"`). An edge whose subject cannot be anchored (no `userId` for a self-edge, or an unresolved name) is **dropped** — we do not auto-create people from chat. Objects are stored free-text (`objectType = "label"`, `objectLabel` = the phrase).
- `POST /v1/observations` — body [MessageReceivedEvent](../../libs/contracts/src/main/java/dev/fedorov/ailife/contracts/message/MessageReceivedEvent.java) (`householdId` + `text` required). The durable async **drop-point** for DB-less producers (orchestrator, agents): publishes the event onto the bus (`bus.outbox`) and returns **202** immediately — the expensive LLM extraction runs later on the consumer (`MessageCaptureHandler`) with at-least-once retry. One HTTP call, no producer-side DB; durable + structured (status-tracked outbox, not a cache that turns into a dump). 400 on missing householdId / blank text. This is the "easy push" any component uses to feed memory-from-chat.
- **Bus consumer (`message.received`, MFC-b):** memory-service also listens on the event bus and runs the same capture off the user's request path. A producer (orchestrator) publishes a [MessageReceivedEvent](../../libs/contracts/src/main/java/dev/fedorov/ailife/contracts/message/MessageReceivedEvent.java) (`message.received`) to `bus.outbox`; `MessageCaptureHandler` drains it → `CaptureService`. Retry policy mirrors notifier's consumer: a parse/validation failure is permanent (logged, row accepted); a transient write failure (embed 5xx / DB blip) re-throws so the row stays `PENDING` for the next poll. Extraction itself never throws, so an llm-gateway outage during extraction just yields zero facts (row settles `PUBLISHED`). Single-consumer bus → foreign topics are drained but ignored. **The orchestrator producer side is the next slice** — until then the topic is only exercised by direct outbox publish (and the synchronous `POST /v1/capture`).

### Notes (second-brain substrate, SB-1/SB-2/SB-3)
Authored, durable, markdown notes — the knowledge-base tier the epic evolves memory-service into.
The source of truth is the `memory.note` row; markdown is the interchange form (frontmatter → columns
+ a `frontmatter` jsonb bag; `id`, not `title`, is the stable link/`refId` anchor). **SB-2 recall seed:**
on create/update the note's `title`+`body_md` is embedded into `memory.memories` (`source=note`,
`metadata {kind:note, refId}`) so the note is recallable via `/v1/memories/recall`; on delete the seed is
forgotten; an update re-seeds (one memory per note). **SB-3 graph seed:** the body's `[[wiki-links]]` are
parsed and projected into `memory.relations` as edges with `subject_type=note`, `subject_id=<note>`,
`edge=links_to`, `source=note` — each target resolves to a note (by title, case-insensitive) →
`object_type=note`, else to a person (profile-service) → `object_type=person`, else a dangling
`object_type=label` stub. An update re-seeds the edges (old dropped by subject); delete forgets them.
Both seeds are **best-effort** — the note row is committed first, so an llm-gateway/profile-service outage
leaves the note un-indexed/un-linked but never fails the write.
- `POST /v1/notes` — body [WriteNoteRequest](../../libs/contracts/src/main/java/dev/fedorov/ailife/contracts/note/WriteNoteRequest.java) (`householdId` + `title` required; null `ownerId` = household-shared; blank `type` → `fact`, null `source` → `user`). Returns `NoteDto`.
- `GET /v1/notes/{id}` — `NoteDto` (404 if absent).
- `GET /v1/notes/{id}/backlinks` — [NoteBacklinksResponse](../../libs/contracts/src/main/java/dev/fedorov/ailife/contracts/note/NoteBacklinksResponse.java): the notes whose `[[wiki-links]]` point at this one (note→note edges, newest first). 404 if the note is absent.
- `GET /v1/notes?householdId=<uuid>&limit=<n>` — most-recent notes in a household (by `updated_at`; default 20, max 100). Returns `NoteDto[]`.
- `GET /v1/notes/resurface?householdId=<uuid>&olderThanDays=<n>` — **proactive-resurfacing candidate**: one *random* note in the household untouched for at least `olderThanDays` (default 7) — the "полгода назад ты отмечал …" wake source. `200` with the `NoteDto`, `204` when nothing is that stale. Random so repeated wakes vary.
- `GET /v1/notes/export?householdId=<uuid>` — **SB-7 markdown-vault export** (epic closer): every note in the household as a `application/zip` of `.md` files — YAML frontmatter (manifest fields from the columns + the open `frontmatter` jsonb bag; `id` is the durable round-trip anchor) followed by the body verbatim (`[[wiki-links]]` + `#tags` intact). Filenames are the (sanitised) titles so an Obsidian `[[Title]]` link resolves; duplicates get a ` (n)` suffix. The hand-off seam for attaching a UI/vault-sync later. `Content-Disposition: attachment; filename="notes-vault.zip"`.
- `PUT /v1/notes/{id}` — replace the mutable fields (title/type/tags/source/personId/bodyMd/frontmatter), bump `updated_at`. `NoteDto` (404 if absent).
- `DELETE /v1/notes/{id}` — 204 / 404.

### Relations (single-hop graph)
- `POST /v1/relations` — body: [WriteRelationRequest](../../libs/contracts/src/main/java/dev/fedorov/ailife/contracts/memory/WriteRelationRequest.java) (`householdId`, `subjectType` + `subjectId`, `edge`, `objectType` + optional `objectId` + `objectLabel`, optional `confidence`, `source`, `metadata`). Returns `RelationDto`.
- `DELETE /v1/relations/{id}` — 204 / 404.
- `GET /v1/graph/person/{personId}/relations?householdId=<uuid>` — returns [PersonRelationsResponse](../../libs/contracts/src/main/java/dev/fedorov/ailife/contracts/memory/PersonRelationsResponse.java) with `outgoing` (this person → other) and `incoming` (other → this person), both newest first, scoped to the household.

## Env
| Var | Default | Purpose |
|---|---|---|
| `MEMORY_PORT` | `8087` | HTTP port. |
| `MEMORY_DB_URL` | `jdbc:postgresql://localhost:5432/ailife` | Postgres (pgvector required). |
| `MEMORY_DB_USER` / `MEMORY_DB_PASSWORD` | `ailife` / `ailife` | DB creds. |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | Via `libs/llm-client`. |
| `MEMORY_DEFAULT_K` | `5` | Top-k when request omits it. |
| `MEMORY_MAX_K` | `50` | Hard cap on top-k. |
| `MEMORY_EMBED_DIM` | `384` | Must match both the `vector(N)` column and the active embedding provider. |
| `PROFILE_SERVICE_URL` | `http://profile-service:8082` | profile-service base URL — relation capture resolves person names to `core.people` UUIDs. |
| `CONVERSATION_BASE_URL` | `http://conversation-service:8089` | conversation-service base URL — AC-4 sets the ambient-approval route-lock here. |
| `NOTIFIER_BASE_URL` | `http://notifier-service:8084` | notifier-service base URL — AC-4 pushes the "заметил: … — записать?" question here. |
| `MEMORY_AMBIENT_CAPTURE_ENABLED` | `false` | Ambient note capture (AC-2+): when true, `CaptureService` also promotes explicit-fixation chat into curated `memory.note`s and raises approval for inferred facts (AC-4). Opt-in. |
| `MEMORY_AMBIENT_CAPTURE_DEDUP_DISTANCE` | `0.15` | AC-3 dedup threshold: skip writing an ambient note when its nearest `source=note` neighbour is within this cosine distance. Smaller = stricter. |

## Key classes
- `MemoryServiceApplication`.
- `config/MemoryServiceProperties` — `memory.{default-k, max-k, dim, profile-base-url, conversation-base-url, notifier-base-url, ambient-capture.{enabled, dedup-distance}}`.
- `embed/EmbeddingClient` — wraps `LlmClient.embed`, returns `float[]` for one text.
- `capture/FactExtractor` — wraps `LlmClient.chat` (DEFAULT channel) to pull durable facts out of a message; lenient JSON parsing (strips markdown fences / leading prose) and best-effort (bad reply → empty list, never throws).
- `capture/RelationExtractor` — relation counterpart of `FactExtractor`: pulls structured edges (`subject —edge→ object`) for the person graph. Same DEFAULT channel + lenient/best-effort parsing; subject `"self"` marks a statement about the speaker. Returns `capture/ExtractedRelation` triples.
- `capture/NoteWorthinessExtractor` — **ambient-capture decision engine** (AC-1): the curated-note counterpart of `FactExtractor`. From an ordinary message (no "запомни" keyword required) it decides what is worth a note and about whom, emitting `capture/NoteCandidate`s. Same DEFAULT channel + lenient/best-effort parsing (bad reply → empty list, never throws). Extract + classify only; a later phase (AC-2) persists the survivors via `NoteService`.
- `capture/NoteCandidate` — one note-worthy candidate `{title, type, body, subject, importance, explicitFixation}`; `subject` = `"self"` | a person name | null. `outcome()` derives the three-way `capture/CaptureOutcome` — `EXPLICIT_FIXATION` (fixation cue → auto-save), `IMPORTANT_INFERRED` (system-noticed → approve first), `TRIVIAL` (ignore).
- `capture/NoteReconciler` — **ambient-capture merge engine** (AC-5a): given an existing note + a near-duplicate incoming one, an LLM decides `capture/ReconcileAction` (`ENRICH`/`SUPERSEDE`/`SKIP`) and returns the merged body as a `capture/NoteReconciliation`. Same DEFAULT channel + lenient/best-effort parsing; **fail-safe → SKIP** on any uncertainty (never blanks a note). Decide-only; AC-5b wires it into the write path.
- `http/ProfileClient` — resolves a chat-derived person label → `core.people` UUID via profile-service `GET /v1/people/by-household/{id}` (case-insensitive `displayName` match). Best-effort: `null` on no match / lookup failure. Never auto-creates a person.
- `service/CaptureService` — memory-from-chat, **three outputs from one message**: (1) facts via `FactExtractor` → `MemoryService` (`source = "chat-capture"`); (2) relations via `RelationExtractor` → subject resolved (`"self"` → `userId`, else `ProfileClient`) → `RelationService`; (3) **ambient notes (AC-2, flag-gated)** via `NoteWorthinessExtractor` → for each **explicit-fixation** candidate a curated note via `NoteService.create` (`source = "user"`) — `"self"` → owner-scoped, a name → `ProfileClient.resolvePersonId` sets `personId` + appends `[[name]]` (unresolved name stays a dangling link, note still saved). Outputs (2) and (3) are best-effort side effects — never throw, never block the memory write. Note capture is off unless `memory.ambient-capture.enabled`. **AC-3/AC-5 dedup + reconcile:** before each explicit-fixation write, `nearestDuplicateNoteId` recalls the `source=note` neighbours (query = `title+body`, scoped by owner/person) and, if the nearest is within `memory.ambient-capture.dedup-distance` (~0.15), resolves its `refId` to the note id and **reconciles** instead of duplicating — `NoteReconciler` decides `ENRICH`/`SUPERSEDE` (→ `NoteService.update` the body, re-seeding recall + graph) or `SKIP` (leave it); a vanished note (race) just creates the new one. Fail-open: a recall blip → treat as new, write it. **AC-4 approval of inferred:** an `IMPORTANT_INFERRED` candidate is not written silently — `askApproval` builds the `ambient` note (attribution shared via `buildNote`), route-locks the conversation to notes-agent (`ConversationStateClient`, `pendingAction={flow:ambient-approve, note}`) and pushes "заметил: … — записать?" (`NotifierClient`); notes-agent writes it on the owner's "да". Needs `CaptureRequest.channel` (from `MessageReceivedEvent.source`) + a `userId` — a channel-less direct `/v1/capture` skips approval. We only ask if the lock persisted.
- `config/HttpConfig` — `profileWebClient` / `conversationWebClient` / `notifierWebClient` beans (base URLs from `memory.{profile,conversation,notifier}-base-url`).
- `http/ConversationStateClient` — AC-4: `PUT /v1/conversation-state` to route-lock the conversation for approval; best-effort, returns whether the lock persisted (only then do we ask).
- `http/NotifierClient` — AC-4: `POST /v1/notify` to push the "заметил: … — записать?" question to the owner; best-effort.
- `web/CaptureController` — `POST /v1/capture` (sync extract+write) and `POST /v1/observations` (durable async drop-point → publishes `message.received` to the bus via `OutboxPublisher`, 202).
- `bus/MessageCaptureHandler` — bus consumer for `message.received` → `CaptureService` (MFC-b); transient-failure-throws / permanent-failure-accepts retry policy.
- `config/EventBusListenerConfig` — registers the `EventBusListenerContainer` bean wiring the handler to the bus. Producer (`OutboxPublisher`) comes from `EventBusConfig`, `@Import`ed in `MemoryServiceApplication`.
- `domain/Vectors` — `float[] → "[v1,v2,…]"` literal for the `::vector` cast (pgvector wire format).
- `domain/MemoryRow` — read model; `toDto()` for the API surface.
- `domain/MemoryRepository` — JdbcTemplate over `memory.memories`. **JPA was deliberately skipped** — pgvector mapping in JPA requires a custom Hibernate type and we don't need ORM features here.
- `service/MemoryService` — orchestrates write/recall/forget; clamps `k`, validates non-blank text, asserts embedding dim matches `memory.dim`.
- `web/MemoryController` — REST endpoints from the Memories block above.
- `domain/RelationRepository` — JdbcTemplate over `memory.relations`. `outgoingForPerson` / `incomingForPerson` both filter by `household_id`. SB-3: `deleteBySubjectNote` (drop a note's link edges for re-seed/cleanup) + `backlinkNoteIds` (note ids linking to a given note).
- `service/RelationService` — write/forget/personRelations + field validation. SB-3: `forgetNoteLinks` / `noteBacklinkIds` delegate to the repo.
- `web/RelationController` — `/v1/relations` + `/v1/graph/person/{id}/relations`.
- `domain/NoteRow` (+ `NoteRepository`) — JdbcTemplate over `memory.note` (SB-1); `tags`/`frontmatter` as jsonb; `insert`/`update`/`findById`/`listByHousehold`/`deleteById`. SB-3: `findIdByTitle` resolves a `[[wiki-link]]` target to a note id (case-insensitive, most-recent wins). SB-7: `listAllByHousehold` (uncapped, title-ordered) backs the vault export. Resurfacing: `resurfaceCandidate(household, olderThan)` picks a random note untouched since a cutoff.
- `note/WikiLinkParser` — SB-3: extracts `[[target]]` tokens from a body (alias `[[target|display]]` keeps the target; blanks dropped, deduped case-insensitively preserving order). Pure/stateless.
- `note/NoteMarkdown` — SB-7: renders a `NoteDto` to its markdown interchange form (YAML frontmatter via snakeyaml + body verbatim) and derives the title-based filename stem. Pure/stateless; `id` in the header is the round-trip anchor.
- `service/NoteExporter` — SB-7: `exportVault(householdId)` zips the household's notes (`NoteMarkdown` per row, duplicate title stems disambiguated). The vault seam for a future UI.
- `service/NoteService` — notes CRUD; blank `type` → `fact`, null `source` → `user`, limit clamp (default 20, max 100); requires `householdId` + non-blank `title`. SB-2: `reseed` embeds `title`+`body` into `memory.memories` via `MemoryService.write` (`source=note`, `{kind,refId}`) on create/update and `forget` drops it on delete. SB-3: `reseedLinks` re-projects the body's `[[wiki-links]]` into `memory.relations` (note→note/person/label edges) on create/update, `forget` drops them, and `backlinks` reads the reverse — all best-effort, never fails the note write.
- `MemoryRepository.deleteBySourceRef(source, refId)` / `MemoryService.forgetBySourceRef` — delete the recall seed a source row (a note) owns, by its `metadata.refId`; used to re-seed on update and clean up on delete.
- `web/NoteController` — `/v1/notes` create/get/list/update/delete + `GET /{id}/backlinks` + `GET /resurface` (proactive-resurfacing candidate) + `GET /export` (SB-7 markdown-vault zip).

## Schema
- [004-memory.yml](../../infra/liquibase/features/004-memory.yml) — `memory.memories` with `vector(384) embedding` column and HNSW cosine index. Scope = `household_id` required + optional `user_id` and/or `person_id`. NULL `user_id` = household-shared memory; NULL `person_id` = not about a specific person. The recall query treats both NULL-as-broader-scope.
- [005-memory-relations.yml](../../infra/liquibase/features/005-memory-relations.yml) — `memory.relations` (single-hop edges). `subject_type`/`subject_id` + `edge` + `object_type`/`object_id`/`object_label` (the label is always present even when `object_id` is null, so display works for free-text objects like "loose-leaf tea"). Indexes on `(household_id, subject_type, subject_id)` and `(household_id, object_type, object_id)`.
- [090-memory-note.yml](../../infra/liquibase/features/090-memory-note.yml) — `memory.note` (second-brain SB-1): the authored notes tier. Scope `household_id` + nullable `owner_id`; `title`, `type` (default `fact`), `tags`/`frontmatter` jsonb, `source` (default `user`), nullable `person_id`, `body_md`, `created_at`/`updated_at`. Indexes on `household_id` and `person_id`. Test mirror kept in `src/test/resources/test-schema.sql`.

## Dim mismatch (the one gotcha)
The column is `vector(384)` and the mock provider emits 384-dim. When Stage 5
swaps in a real provider (bge-m3 = 1024-dim), the column needs to be widened AND
all rows re-embedded. `MemoryService` fails fast with a clear error if the
returned embedding's length differs from `memory.dim` — this is the first thing
to check when you see `embedding dim mismatch` in logs.

## Why a SQL table instead of Apache AGE
[005](../../infra/liquibase/features/005-memory-relations.yml) is plain SQL on
purpose. The original plan (`plans/architecture.md`, `roadmap.md`) was AGE — and
that's still where we end up — but:
1. The roadmap §Risks calls the AGE container image unstable, plan B = Neo4j.
2. PR17 (first cross-agent chain `birthday_upcoming → recall + relations →
   gift-recommender`) only needs single-hop lookup: "what does Maria like?". A
   plain indexed query answers it in milliseconds.
3. AGE pays off on multi-hop walks (friends-of-friends, transitive
   recommendations). Promote then.

**Promotion criteria** — when ANY of these become true, do the AGE upgrade in
its own PR: (a) we need 2+ hop traversal in production; (b) we need graph
algorithms (centrality, shortest path); (c) row count exceeds ~100k and SQL
joins start to hurt.

## Roadmap (deferred)
- **Ambient / intuitive capture (in progress):** the memory-from-chat path (`CaptureService`) now has a **third output** — curated **ambient notes** — so the note tier fills itself without the "запомни" keyword. Phased plan (AC-1..5) in [plans/ambient-capture.md](../../plans/ambient-capture.md). **AC-1..AC-5 all landed, flag-gated:** classify every message → auto-save explicit fixation / approve-first important-inferred (push + notes-agent `/resume`) / ignore trivial; on a near-duplicate, reconcile (`NoteReconciler` enrich/supersede/skip) instead of blindly writing. The ambient/intuitive-capture feature is complete.
- PR17: first cross-agent chain (`calendar.birthday_upcoming → memory recall + person relations → gift-recommender`).
- Future: Apache AGE upgrade (per promotion criteria above).
- Future: bulk re-embed endpoint when we change provider/dim (Stage 5).
