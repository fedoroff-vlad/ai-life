# platform/memory-service

pgvector-backed long-term recall. `POST /v1/memories` writes a piece of text +
metadata after embedding it via llm-gateway; `POST /v1/memories/recall` returns
top-k cosine-nearest hits within a scope filter; `DELETE /v1/memories/{id}` forgets.
Apache AGE graph + `/v1/graph/person/{id}/relations` lands in PR16.

## Port: `8087` (`MEMORY_PORT`)

## Endpoints
- `POST /v1/memories` — body: [WriteMemoryRequest](../../libs/contracts/src/main/java/dev/fedorov/ailife/contracts/memory/WriteMemoryRequest.java). Returns `MemoryDto`.
- `POST /v1/memories/recall` — body: [RecallMemoryRequest](../../libs/contracts/src/main/java/dev/fedorov/ailife/contracts/memory/RecallMemoryRequest.java) (`householdId` required, `userId`/`personId` narrow, `k` defaults to 5, capped at 50). Returns `RecallMemoryHit[]` ordered by ascending cosine distance.
- `DELETE /v1/memories/{id}` — 204 on success, 404 on unknown.

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
- `web/MemoryController` — REST endpoints from the table above.

## Schema
[040-memory.yml](../../infra/liquibase/features/040-memory.yml) — `memory.memories`
with `vector(384) embedding` column and HNSW cosine index. Scope = `household_id`
required + optional `user_id` and/or `person_id`. NULL `user_id` = household-shared
memory; NULL `person_id` = not about a specific person. The recall query treats both
NULL-as-broader-scope.

## Dim mismatch (the one gotcha)
The column is `vector(384)` and the mock provider emits 384-dim. When Stage 5
swaps in a real provider (bge-m3 = 1024-dim), the column needs to be widened AND
all rows re-embedded. `MemoryService` fails fast with a clear error if the
returned embedding's length differs from `memory.dim` — this is the first thing
to check when you see `embedding dim mismatch` in logs.

## Roadmap (deferred)
- PR16: Apache AGE graph (Person/Place/Item nodes; likes/owns/related_to edges) + `/v1/graph/person/{id}/relations`.
- PR17: orchestrator pre-routing recall — top-3 memories injected into the fast-channel intent classifier prompt.
- Bulk re-embed endpoint when we change provider/dim.
