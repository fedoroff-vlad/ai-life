# mcp-media-processing

Shared **media-understanding capability-MCP** (`shared/mcp/`, no schema). Turns stored
media bytes into text/structure so any agent can reuse it — a receipt→finance, a
sick-note→docs, an outfit→stylist. Bound by agents over MCP/SSE; it owns no data and
never stores blobs (it reads them from media-service by object id). Plan: [media.md](../../../plans/media.md).

**Status (MP-b):** the `ocr` tool runs **real OCR** via Tess4J + native tesseract
(deployed default); the native-free `StubOcrEngine` remains for the wiring test and
degraded boxes (`mediaprocessing.ocr-engine=stub`). Next: MP-c binds it to finance-agent
and migrates `receipt-parser` off its in-agent vision shortcut; MP-d adds `caption`
(vision) + `transcribe` (STT).

## Port: `8097` (`MCP_MEDIA_PROCESSING_PORT`)

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `ocr` | `mediaId` (media-service object id) | `OcrResult{text, lang?, confidence?}` | fetch the image bytes from media-service, run OCR, return recognised text (empty when none). Interpreting the text is the caller's job. |

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_MEDIA_PROCESSING_PORT` | `8097` | HTTP port (MCP/SSE + actuator). |
| `MEDIA_SERVICE_URL` | `http://media-service:8088` | media-service base URL — `GET /v1/media/{id}` to fetch blob bytes. |
| `MCP_MEDIA_PROCESSING_OCR_ENGINE` | `tesseract` | `tesseract` (real, needs the native lib) or `stub` (native-free marker). |
| `MCP_MEDIA_PROCESSING_TESS_LANG` | `rus+eng` | Tesseract languages ('+'-joined). |
| `TESSDATA_PREFIX` | `/usr/share/tesseract-ocr/5/tessdata` (image) | tessdata dir; blank → resolved by a path probe. |

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
- `tools/MediaProcessingMcpTools` — `@Tool ocr(mediaId)`: fetch (blocking `.block()`, the
  MCP `@Tool` convention here) → `OcrEngine.extract` → `OcrResult`.
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the `@Tool`s.
