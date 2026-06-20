# mcp-media-processing

Shared **media-understanding capability-MCP** (`shared/mcp/`, no schema). Turns stored
media bytes into text/structure so any agent can reuse it — a receipt→finance, a
sick-note→docs, an outfit→stylist. Bound by agents over MCP/SSE; it owns no data and
never stores blobs (it reads them from media-service by object id). Plan: [media.md](../../../plans/media.md).

**Status (MP-d1):** `ocr` runs **real OCR** via Tess4J + native tesseract (deployed
default; native-free `StubOcrEngine` via `mediaprocessing.ocr-engine=stub`). `caption`
asks llm-gateway's **vision** channel about an image with a caller-supplied instruction —
the centralised vision call (no agent re-embeds it). Next: MP-c binds the server to
finance-agent and migrates `receipt-parser` off its in-agent vision shortcut onto
`caption`; `transcribe` (STT) lands later. **MP-c1** added the `POST /internal/caption`
HTTP passthrough (below) — the MockWebServer-testable transport finance-agent calls
deterministically (MP-c2 wires it).

## Port: `8097` (`MCP_MEDIA_PROCESSING_PORT`)

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `ocr` | `mediaId` (media-service object id) | `OcrResult{text, lang?, confidence?}` | fetch the image bytes from media-service, run OCR (local Tesseract), return recognised text (empty when none). |
| `caption` | `mediaId`, `instruction` | `CaptionResult{text, model?}` | fetch the image bytes, ask llm-gateway's `vision` channel the `instruction` (free description or structured extraction), return the model's text. Prefer over `ocr` for understanding/structure. |

## HTTP passthrough

| method | path | body | returns | purpose |
|--------|------|------|---------|---------|
| POST | `/internal/caption` | `CaptionInput{mediaId, instruction}` | `CaptionResult{text, model?}` | non-MCP passthrough to the `caption` tool. A capability-MCP is bound over MCP/SSE, but that transport can't be MockWebServer'd, so a caller that already knows it wants a caption (deterministic — it has the media id + instruction) hits this HTTP path instead. Delegates straight to the `caption` tool. Used by finance-agent's `receipt-parser` (MP-c). |

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_MEDIA_PROCESSING_PORT` | `8097` | HTTP port (MCP/SSE + actuator). |
| `MEDIA_SERVICE_URL` | `http://media-service:8088` | media-service base URL — `GET /v1/media/{id}` to fetch blob bytes. |
| `MCP_MEDIA_PROCESSING_OCR_ENGINE` | `tesseract` | `tesseract` (real, needs the native lib) or `stub` (native-free marker). |
| `MCP_MEDIA_PROCESSING_TESS_LANG` | `rus+eng` | Tesseract languages ('+'-joined). |
| `TESSDATA_PREFIX` | `/usr/share/tesseract-ocr/5/tessdata` (image) | tessdata dir; blank → resolved by a path probe. |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | llm-gateway base URL for the `caption` vision call (via `libs/llm-client`). |

No DB / no Liquibase feature (capability-MCP). Binding side: an agent adds a
`spring.ai.mcp.client.sse.connections.mcp-media-processing` block + `MCP_MEDIA_PROCESSING_URL`
(see PATTERNS.md "Recipe: add a capability-MCP", step 4) — happens in MP-c.

## Key classes

- `McpMediaProcessingApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `config/McpMediaProcessingProperties` — `mediaprocessing.media-service-url`.
- `config/HttpConfig` — `mediaWebClient` bean (media-service base URL).
- `http/MediaClient` — `GET /v1/media/{id}` → `FetchedMedia(mimeType, bytes)`. Local copy
  of the per-agent pattern; lift to `libs/media-client` if a third copy appears.
- `engine/OcrEngine` — pluggable OCR backend interface.
- `engine/TesseractOcrEngine` — deployed default (MP-b); Tess4J + native tesseract.
  Decodes bytes via `ImageIO`, runs `doOCR`; `tessdata` resolved from
  `TESSDATA_PREFIX`/property/path-probe. Unreadable bytes → empty text (not an error);
  a genuine native failure → `IllegalStateException`.
- `engine/StubOcrEngine` — native-free marker (`[stub-ocr] <N> bytes`); selected only by
  `mediaprocessing.ocr-engine=stub` (wiring test / degraded boxes).
- `tools/MediaProcessingMcpTools` — `@Tool`s (blocking `.block()`, the MCP `@Tool`
  convention here): `ocr(mediaId)` → `OcrEngine.extract` → `OcrResult`; `caption(mediaId,
  instruction)` → fetch → llm-gateway `vision` channel (`LlmClient`) → `CaptionResult`.
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the `@Tool`s.
- `web/InternalCaptionController` — `POST /internal/caption` passthrough (MP-c1); delegates to
  the `caption` tool on `Schedulers.boundedElastic()` (the tool blocks). The MockWebServer-testable
  transport finance-agent's `receipt-parser` calls instead of the un-mockable MCP/SSE binding.
