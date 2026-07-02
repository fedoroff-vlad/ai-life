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
- **D-a — `mcp-docs` domain-MCP + `docs` schema.** New `domains/docs/mcp-docs` (port **8116**).
  `docs.document` keyed `id`, scoped `(household, owner)`: `media_id`, `doc_type`
  (receipt|contract|warranty|note|other), `title`, `party` (merchant / counterparty), `doc_date`,
  `amount` + `currency` (nullable — only receipts/invoices), `ocr_text`, `tags text[]`, `created_at`.
  Tools: `saveDocument` / `getDocument` / `listDocuments(docType?, limit)` /
  `searchDocuments(query, docType?, limit)` (pg_trgm over title+party+ocr_text). `/internal/*`
  passthroughs for each (deterministic agent→MCP). Liquibase `080-docs.yml` (`docs` schema + the
  table + a `gin_trgm_ops` index on the searchable text). Mirrors `mcp-creator`. No agent binding yet.
- **D-b — `/internal/ocr` passthrough on `mcp-media-processing`.** Small twin of `/internal/caption`:
  `POST /internal/ocr` `{mediaId}` → the `ocr` tool → `OcrResult`. The MockWebServer-testable
  transport docs-agent calls deterministically (MCP/SSE can't be mocked). Update the media README.
- **D-c — `docs-agent` scaffold + `doc-archiver` ingest skill.** New `domains/docs/docs-agent`
  (port **8117**). Binds `mcp-docs` + `mcp-media-processing`. An inbound message carrying a document
  photo → `ocr` via `/internal/ocr` → `doc-archiver` LLM extract (doc_type/title/party/date/amount
  from the OCR text + the user's caption hint, strict JSON) → `saveDocument` via
  `/internal/documents`. Reply confirms what was filed + the stored-blob link. Registered in
  orchestrator as `docs`. Tests: `DocArchiverTest` (MockWebServer) + `ManifestControllerTest` +
  `GoldenDocArchiverTest` (opt-in).
- **D-d — `doc-finder` search skill.** A "find my X" cue → `doc-finder` extracts a query + optional
  doc_type filter (strict JSON) → `searchDocuments` via `/internal/search` (+ a memory-service recall
  for semantic hits, soft-fail) → reply lists the matches (title, date, party) each with its open
  link. Tests: `DocFinderTest` (MockWebServer) + `GoldenDocFinderTest` (opt-in).
- **D-e — semantic index via memory-service (closer).** On `saveDocument` the agent writes the OCR
  text to memory-service scoped `docs` (`ownerId`, `kind=document`, `refId=documentId`), so
  `doc-finder` recall returns fuzzy matches the trigram search misses. Soft-fail on the memory write
  (the document is still saved + text-searchable). Mandatory **E2E closer**
  `E2EDocsIngestSearchFlowTest`: inbound photo → agent → ocr passthrough (MockWebServer forward) →
  save → search returns it, asserting the `libs/contracts` DTOs survive each hop. **Closes #188.**

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
