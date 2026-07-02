# docs — personal document archive agent

Authority file for the **docs-agent** (issue [#188](https://github.com/fedoroff-vlad/ai-life/issues/188)),
the second future-agent (build order in the `project-future-agents-order` memory: briefing →
**docs** → health → family-memory).

## What it is
A **personal document archive**: the user sends a photo/PDF of a receipt, contract, warranty,
sick-note, etc. → OCR turns it into searchable text → we store the blob (media-service) + its
metadata (a `docs` schema) + a semantic index → later "найди мой договор аренды / гарантию на
холодильник" retrieves it. Two flows: **ingest** (photo → archived document) and **search**
("find my X" → matching documents + links).

## Doctrine (no new layer)
Everything reuses an existing pattern — flag nothing new:
- **OCR is the shared media capability** — `mcp-media-processing.ocr` over a deterministic
  `/internal/ocr` passthrough (new — MP only exposes `/internal/caption` today; D-a adds the OCR
  twin, mirroring it). Full text for the search index comes from `ocr`; **structured metadata**
  (doc_type/title/party/date/amount) comes from a `caption` structured-extract **or** an LLM pass
  over the OCR text — the reasoning stays in the agent's skill, the capability returns raw text.
- **`mcp-docs` domain-MCP owns the `docs` schema** — the metadata store + text search, exactly the
  `mcp-creator` / `mcp-briefing` shape (tools + `/internal/*` passthroughs).
- **Blobs stay in media-service** — the agent already receives a `mediaId` on an inbound photo; the
  document row references it, we never re-store bytes.
- **Semantic recall reuses memory-service** — on save the OCR text is embedded into memory-service
  (pgvector) scoped to `docs`; search combines the MCP's cheap text match (pg_trgm) with a
  memory-service recall for fuzzy/semantic "find my X".
- **Gather/extract on `libs/agent-runtime`** — ingest is a single skill (`doc-archiver`), search is a
  single skill (`doc-finder`); no agent invents a planner (agent-led coordination).
- Deliverables are chat replies + media links (the stored blob's open-link); no HTML board needed for
  MVP (a "document list" board is a possible later nicety, not the critical path).

## Golden tests — from the start
Real model access is unblocked (local Ollama `qwen2.5:7b` via llm-gateway). Each LLM seam gets an
**opt-in `@GoldenLlmTest`** (`libs/golden-test-support`, gated by `GOLDEN_LLM` — NOT in the default
fast CI): `GoldenDocArchiverTest` (metadata-extract JSON structure) and `GoldenDocFinderTest`
(query→filter structure), plus a `docs` routing golden in orchestrator. Assert **structure, not
wording**.

## PR slices
- **D-a — `mcp-docs` domain-MCP + `docs` schema.** ✅ **DONE (PR #249).** New `domains/docs/mcp-docs` (port **8116**).
  `docs.document` keyed `id`, scoped `(household, owner)`: `media_id`, `doc_type`
  (receipt|contract|warranty|note|other), `title`, `party` (merchant / counterparty), `doc_date`,
  `amount` + `currency` (nullable — only receipts/invoices), `ocr_text`, `tags text[]`, `created_at`.
  Tools: `saveDocument` / `getDocument` / `listDocuments(docType?, limit)` /
  `searchDocuments(query, docType?, limit)` (pg_trgm over title+party+ocr_text). `/internal/*`
  passthroughs for each (deterministic agent→MCP). Liquibase `080-docs.yml` (`docs` schema + the
  table + a `gin_trgm_ops` index on the searchable text). Mirrors `mcp-creator`. No agent binding yet.
- **D-b — `/internal/ocr` passthrough on `mcp-media-processing`.** ✅ **DONE (PR #250).** Small twin of `/internal/caption`:
  `POST /internal/ocr` `{mediaId}` → the `ocr` tool → `OcrResult` (`contracts/media/OcrInput`). The
  MockWebServer-testable transport docs-agent calls deterministically (MCP/SSE can't be mocked). Media
  README updated. `InternalOcrControllerTest` (stub OCR engine, native-free).
- **D-c — `docs-agent` scaffold + `doc-archiver` ingest skill.** ✅ **DONE (PR #254).** New `domains/docs/docs-agent`
  (port **8117**). Binds `mcp-docs` + `mcp-media-processing`. An inbound message carrying a document
  photo (`image` attachment) → `ocr` via `/internal/ocr` → `doc-archiver` LLM extract (doc_type/title/
  party/date/amount/currency/tags from the OCR text + the user's caption hint, strict JSON, temp=0) →
  `saveDocument` via `/internal/documents` (full OCR text stored as the search corpus). Reply confirms
  what was filed. Non-photo → chat fallback. Registered in orchestrator as `docs`. Tests:
  `DocArchiverTest` (3, MockWebServer) + `ManifestControllerTest` + `GoldenDocArchiverTest` (opt-in).
- **D-d — `doc-finder` search skill.** ✅ **DONE (PR #255).** A "find my X" cue → `doc-finder` distils a query +
  optional doc_type filter (strict JSON, temp=0) → `searchDocuments` via `/internal/documents/search`
  → reply lists the matches (title, type, date, party) each with an open link
  (`<public-media-base>/v1/media/{mediaId}`). Trigram search only here; the memory-service semantic
  recall lands in D-e. Tests: `DocFinderTest` (2, MockWebServer) + `GoldenDocFinderTest` (opt-in).
- **D-e — semantic index via memory-service (closer). ✅ DONE (PR #256).** On `saveDocument` the agent
  seeds the OCR text to memory-service scoped `docs` (`ownerId`, metadata `{kind:document,
  refId:documentId}`) via the new shared `MemoryClient.remember` (`POST /v1/memories`), so `doc-finder`
  runs the trigram search **and** a memory-service recall in parallel — recall hits resolve their
  `refId` to rows via `GET /internal/documents/{id}`, merged + de-duplicated by id — returning fuzzy
  matches the literal trigram search misses. Both the seed and each search source soft-fail
  independently (the document is still saved + text-searchable if memory is down). E2E closer
  `E2EDocsIngestSearchFlowTest`: inbound photo → ocr → save → seed, then a search where the trigram
  returns nothing and the document is recovered purely by the semantic path, asserting the
  `libs/contracts` DTOs survive each hop. **Closes #188.**

## Deferred
- **PDF / multi-page documents.** `mcp-media-processing.ocr` decodes a single image via `ImageIO`;
  PDFs need a page-render step first. MVP = image documents; PDF support is a later
  `pdf-render`-style tool (note it in the media plan when a consumer needs it).
- **Proactive warranty / contract-expiry reminders.** `doc_date` + a duration → a scheduler wake
  ("your fridge warranty expires next month") — the briefing-style proactive path, out of the MVP.
- **Receipt fan-out from finance.** A recognised receipt already reaches finance + nutrition over the
  `basket.captured` bus; a `document.captured` twin could auto-archive it here. Later, once the
  ingest flow is proven standalone.
- **Document-list HTML board** (a `doc-render` "your archive" deliverable) — chat replies + links
  suffice for MVP.
