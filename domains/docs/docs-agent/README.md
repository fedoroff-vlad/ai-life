# docs-agent

Personal **document-archive** specialist (port **8117**). Ingests a document photo (receipt / contract
/ warranty / sick-note) into the archive and answers "find my X". Registered in the orchestrator as
`docs`; owns `mcp-docs`; binds the shared `mcp-media-processing` (OCR). Plan:
[plans/docs.md](../../../plans/docs.md).

## Status (SB-5)

Scaffold + the **ingest** and **search** flows, with **semantic recall** layered onto both. As of the
second-brain **SB-5** (epic #257) the semantic seed is an authored **note** in the shared substrate, not
a raw `memory.memories` row — docs-agent is the first consumer of the universal note-write seam
(`MemoryClient.note`/`getNote` in `libs/agent-runtime`).

- **Ingest (D-c, +SB-5 note seed)** — an inbound message with an `image` attachment → `doc-archiver`:
  OCR the photo (`mcp-media-processing` `POST /internal/ocr`) → one llm-gateway turn with the
  `doc-archiver` SKILL extracts the metadata (doc_type / title / party / date / amount / currency / tags)
  from the OCR text + the user's caption → archive via `mcp-docs` `POST /internal/documents`, storing the
  full OCR text as the search corpus → **seed the document into the second-brain as a note**
  (`MemoryClient.note` → `POST /v1/notes`, `source=docs-agent`, `type=reference`, body = OCR text,
  `frontmatter={kind:document, refId}`) so it lands in the one store every agent reads and memory-service
  auto-seeds it into recall (SB-2) → confirm what was filed. The note seed soft-fails: the document is
  already saved + text-searchable.
- **Search (D-d, +SB-5 recall)** — a "find my X" cue → `doc-finder`: one llm-gateway turn with the
  `doc-finder` SKILL distils a search query + optional docType filter → **two searches in parallel** —
  `mcp-docs` `GET /internal/documents/search` (household-scoped trigram) **and** a memory-service
  semantic recall over the second-brain: a `{kind:note, refId}` recall hit is fetched
  (`MemoryClient.getNote` → `GET /v1/notes/{id}`) and its `frontmatter` `{kind:document, refId}`
  back-pointer resolves to the document row (`GET /internal/documents/{id}`) — merged + de-duplicated →
  the reply lists the hits (title / type / date / party) each with an open link to the stored blob. Each
  source soft-fails independently.

Otherwise a message falls through to a chat fallback. Every stage soft-fails to a friendly reply.

## Endpoints

| method | path | purpose |
|--------|------|---------|
| POST | `/agents/docs/intent` | orchestrator entry. Image attachment → `doc-archiver` ingest; "find my X" cue → `doc-finder` search; else the chat fallback. |
| GET | `/agents/docs/manifest` | the manifest the orchestrator scrapes on startup. |

## Skills

- **`doc-archiver`** (`domains/docs/skills/doc-archiver/SKILL.md`) — strict-JSON extract of the archive
  metadata from a document's OCR text + the user's note.
- **`doc-finder`** (`domains/docs/skills/doc-finder/SKILL.md`) — strict-JSON distil of a search query +
  optional docType filter from a "find my X" request.

## Env

| Var | Default | Purpose |
|---|---|---|
| `DOCS_AGENT_PORT` | `8117` | HTTP port. |
| `MCP_DOCS_URL` | `http://mcp-docs:8116` | docs domain-MCP (its data — `/internal/documents`). |
| `MCP_MEDIA_PROCESSING_URL` | `http://mcp-media-processing:8097` | shared media capability (`/internal/ocr`). |
| `DOCS_PUBLIC_MEDIA_BASE_URL` | `http://media-service:8088` | public base for a search hit's open link (`<base>/v1/media/{mediaId}`). |
| `DOCS_AGENT_MCP_CLIENT_ENABLED` | `true` | bind mcp-docs + mcp-media-processing over MCP/SSE (toggle off in degraded envs). |
| `DOCS_AGENT_MEMORY_RECALL_K` | `5` | memory-recall fan-in (shared agent-runtime). |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | llm-gateway for the metadata extract. |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` / `MEMORY_SERVICE_URL` | internal | shared agent-runtime clients. |

## Key classes

- `DocsAgentApplication` — `@SpringBootApplication` + `@Import(AgentRuntimeConfig)`.
- `config/DocsAgentProperties` (`docs-agent.*` base URLs) + `config/OutboundHttpConfig`
  (`mcpDocsWebClient` + `mcpMediaProcessingWebClient`).
- `http/OcrClient` — `POST /internal/ocr` → `OcrResult` (mirrors finance `CaptionClient`).
- `http/DocumentClient` — `POST /internal/documents` → `DocumentDto` (mirrors `BriefingProfileClient`).
- `archive/DocArchiver` — the ingest flow: OCR → LLM metadata extract (`doc-archiver` SKILL,
  temperature=0) → `saveDocument` (stores the full OCR text) → second-brain note seed
  (`MemoryClient.note`, `source=docs-agent`, body = OCR text, `frontmatter={kind:document, refId}`);
  soft-fails per stage.
- `find/DocFinder` — the search flow: LLM query distil (`doc-finder` SKILL, temperature=0) → parallel
  trigram `searchDocuments` + memory-service `recall` (SB-5; a `{kind:note, refId}` hit →
  `MemoryClient.getNote` → its `frontmatter` `{kind:document, refId}` → `DocumentClient.get`), merged +
  de-duplicated → a hit list with open links; each source soft-fails independently.
- `chat/DocsChat` — the open-question fallback (AGENT.md system prompt).
- `web/IntentController` — image attachment → archive, "find my X" cue → search, else chat;
  `web/ManifestController`.
