# docs-agent

Personal **document-archive** specialist (port **8117**). Ingests a document photo (receipt / contract
/ warranty / sick-note) into the archive and — from D-d — answers "find my X". Registered in the
orchestrator as `docs`; owns `mcp-docs`; binds the shared `mcp-media-processing` (OCR). Plan:
[plans/docs.md](../../../plans/docs.md).

## Status (D-c)

Scaffold + the **ingest** flow. An inbound message with an `image` attachment →
`doc-archiver`: OCR the photo (`mcp-media-processing` `POST /internal/ocr`) → one llm-gateway turn with
the `doc-archiver` SKILL extracts the metadata (doc_type / title / party / date / amount / currency /
tags) from the OCR text + the user's caption → archive via `mcp-docs` `POST /internal/documents`,
storing the full OCR text as the search corpus → confirm what was filed. A non-photo message falls
through to a chat fallback (search arrives in D-d). Every stage soft-fails to a friendly reply.

## Endpoints

| method | path | purpose |
|--------|------|---------|
| POST | `/agents/docs/intent` | orchestrator entry. Image attachment → `doc-archiver` ingest; else the chat fallback. |
| GET | `/agents/docs/manifest` | the manifest the orchestrator scrapes on startup. |

## Skills

- **`doc-archiver`** (`domains/docs/skills/doc-archiver/SKILL.md`) — strict-JSON extract of the archive
  metadata from a document's OCR text + the user's note.

## Env

| Var | Default | Purpose |
|---|---|---|
| `DOCS_AGENT_PORT` | `8117` | HTTP port. |
| `MCP_DOCS_URL` | `http://mcp-docs:8116` | docs domain-MCP (its data — `/internal/documents`). |
| `MCP_MEDIA_PROCESSING_URL` | `http://mcp-media-processing:8097` | shared media capability (`/internal/ocr`). |
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
  temperature=0) → `saveDocument`; stores the full OCR text; soft-fails per stage.
- `chat/DocsChat` — the open-question fallback (AGENT.md system prompt).
- `web/IntentController` — image attachment → archive, else chat; `web/ManifestController`.
