# mcp-docs

Docs domain-MCP (port **8116**). Source-of-truth store + text search over the `docs.*` schema — the
**personal document archive** (receipts, contracts, warranties, notes). Owns `document`
(media-service blob id + extracted metadata + full OCR text). The OCR call and the metadata
extraction live in `docs-agent`; this MCP just persists and searches. Mirrors `mcp-briefing`. Plan:
[plans/docs.md](../../../plans/docs.md).

## Status (D-a)

The archive store: `docs.document` (append-only, scoped `(household_id, owner_id)`, null owner =
household-shared) + the `save`/`get`/`list`/`search` tools + the `/internal/documents` passthrough.
Search is a `pg_trgm` `ILIKE` over `title + party + ocr_text`, index-accelerated + similarity-ranked.
**No agent binding yet** — `docs-agent` (D-c) will bind it.

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `saveDocument` | `SaveDocumentInput` | `DocumentDto` | archive a NEW document (append-only). `householdId` + `mediaId` required; a null `ownerId` is household-shared. `docType` (receipt\|contract\|warranty\|note\|other), `title`/`party`/`docDate` metadata, `amount`+`currency` for money docs, `ocrText` (search corpus), `tags` (JSON array). |
| `getDocument` | `id` | `DocumentDto` \| null | one document by id; null when absent. |
| `listDocuments` | `householdId`, `docType?`, `limit?` | `DocumentDto[]` | most-recent documents in a household, optionally one `docType` (default 20, max 100). |
| `searchDocuments` | `householdId`, `query`, `docType?`, `limit?` | `DocumentDto[]` | free-text search over title + party + OCR text (case-insensitive), ranked by relevance then recency. |

## HTTP passthrough

| method | path | body / params | returns | purpose |
|--------|------|---------------|---------|---------|
| POST | `/internal/documents` | `SaveDocumentInput` | `DocumentDto` (400 on missing `householdId`/`mediaId`) | deterministic archive (docs-agent ingest, D-c). |
| GET | `/internal/documents/{id}` | — | `DocumentDto` (404 if absent) | one document by id. |
| GET | `/internal/documents` | `householdId`, `docType?`, `limit?` | `DocumentDto[]` | most-recent list. |
| GET | `/internal/documents/search` | `householdId`, `query`, `docType?`, `limit?` | `DocumentDto[]` | free-text search (docs-agent search, D-d). |

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_DOCS_PORT` | `8116` | HTTP port (MCP/SSE + actuator). |
| `MCP_DOCS_DB_URL` | `jdbc:postgresql://localhost:5432/ailife` | Postgres (`docs` schema). |
| `MCP_DOCS_DB_USER` / `MCP_DOCS_DB_PASSWORD` | `ailife` / `ailife` | DB credentials. |

Schema owned: `docs` (`document`). Migration:
[`080-docs.yml`](../../../infra/liquibase/features/080-docs.yml). `docs` schema created in
[`infra/postgres/init.sql`](../../../infra/postgres/init.sql).

## Key classes

- `McpDocsApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `domain/DocumentEntity` (+ `Repository`) — the append-only `document` entity; `listRecent`
  (null-`docType` CAST workaround) + `search` (`ILIKE` + `similarity()` ranking over the concatenated
  title/party/OCR text, `gin_trgm_ops`-indexed).
- `tools/DocsMcpTools` — `save`/`get`/`list`/`search` `@Tool`s; per-household scope; limit clamp.
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the `@Tool`s.
- `web/InternalDocumentsController` — `POST`/`GET /internal/documents` (+ `/{id}`, `/search`).
