# platform/memory-service

pgvector-backed long-term recall + single-hop relation graph. `POST /v1/memories`
writes a piece of text + metadata after embedding it via llm-gateway; `POST
/v1/memories/recall` returns top-k cosine-nearest hits within a scope filter;
`DELETE /v1/memories/{id}` forgets. `POST /v1/relations` writes one structured
edge ("Maria likes books"); `GET /v1/graph/person/{id}/relations?householdId=…`
returns the person's outgoing + incoming edges.

## Port: `8087` (`MEMORY_PORT`)

## Endpoints

### Memories (pgvector)
- `POST /v1/memories` — body: [WriteMemoryRequest](../../libs/contracts/src/main/java/dev/fedorov/ailife/contracts/memory/WriteMemoryRequest.java). Returns `MemoryDto`.
- `POST /v1/memories/recall` — body: [RecallMemoryRequest](../../libs/contracts/src/main/java/dev/fedorov/ailife/contracts/memory/RecallMemoryRequest.java) (`householdId` required, `userId`/`personId` narrow, `k` defaults to 5, capped at 50). Returns `RecallMemoryHit[]` ordered by ascending cosine distance.
- `DELETE /v1/memories/{id}` — 204 on success, 404 on unknown.

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

## Key classes
- `MemoryServiceApplication`.
- `config/MemoryServiceProperties` — `memory.{default-k, max-k, dim}`.
- `embed/EmbeddingClient` — wraps `LlmClient.embed`, returns `float[]` for one text.
- `domain/Vectors` — `float[] → "[v1,v2,…]"` literal for the `::vector` cast (pgvector wire format).
- `domain/MemoryRow` — read model; `toDto()` for the API surface.
- `domain/MemoryRepository` — JdbcTemplate over `memory.memories`. **JPA was deliberately skipped** — pgvector mapping in JPA requires a custom Hibernate type and we don't need ORM features here.
- `service/MemoryService` — orchestrates write/recall/forget; clamps `k`, validates non-blank text, asserts embedding dim matches `memory.dim`.
- `web/MemoryController` — REST endpoints from the Memories block above.
- `domain/RelationRepository` — JdbcTemplate over `memory.relations`. `outgoingForPerson` / `incomingForPerson` both filter by `household_id`.
- `service/RelationService` — write/forget/personRelations + field validation.
- `web/RelationController` — `/v1/relations` + `/v1/graph/person/{id}/relations`.

## Schema
- [004-memory.yml](../../infra/liquibase/features/004-memory.yml) — `memory.memories` with `vector(384) embedding` column and HNSW cosine index. Scope = `household_id` required + optional `user_id` and/or `person_id`. NULL `user_id` = household-shared memory; NULL `person_id` = not about a specific person. The recall query treats both NULL-as-broader-scope.
- [005-memory-relations.yml](../../infra/liquibase/features/005-memory-relations.yml) — `memory.relations` (single-hop edges). `subject_type`/`subject_id` + `edge` + `object_type`/`object_id`/`object_label` (the label is always present even when `object_id` is null, so display works for free-text objects like "loose-leaf tea"). Indexes on `(household_id, subject_type, subject_id)` and `(household_id, object_type, object_id)`.

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
- PR17: first cross-agent chain (`calendar.birthday_upcoming → memory recall + person relations → gift-recommender`).
- Future: Apache AGE upgrade (per promotion criteria above).
- Future: bulk re-embed endpoint when we change provider/dim (Stage 5).
