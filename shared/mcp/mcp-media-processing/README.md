# mcp-media-processing

Shared **media-understanding capability-MCP** (`shared/mcp/`, no schema). Turns stored
media bytes into text/structure so any agent can reuse it — a receipt→finance, a
sick-note→docs, an outfit→stylist. Bound by agents over MCP/SSE; it owns no data and
never stores blobs (it reads them from media-service by object id). Plan: [media.md](../../../plans/media.md).

**Status (MP-d2b):** `ocr` runs **real OCR** via Tess4J + native tesseract (deployed
default; native-free `StubOcrEngine` via `mediaprocessing.ocr-engine=stub`). `caption`
asks llm-gateway's **vision** channel about an image with a caller-supplied instruction —
the centralised vision call (no agent re-embeds it). `transcribe` runs **real STT** via a
whisper ASR **sidecar over HTTP** (`WhisperSttEngine`, deployed default; native-free
`StubSttEngine` via `mediaprocessing.stt-engine=stub`) — engine decision LOCKED = sidecar
service (polyglot-by-design: the model runs in its own container, the JVM image stays slim).
The `POST /internal/caption` HTTP passthrough (below) is the MockWebServer-testable transport
callers use deterministically. **`caption` is now bound by** finance (`receipt-parser`, MP-c), the
stylist (garment / self-photo), and the nutritionist (meal / basket photo) — the centralised vision
call, reused not re-embedded.

## Port: `8097` (`MCP_MEDIA_PROCESSING_PORT`)

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `ocr` | `mediaId` (media-service object id) | `OcrResult{text, lang?, confidence?}` | fetch the image bytes from media-service, run OCR (local Tesseract), return recognised text (empty when none). |
| `caption` | `mediaId`, `instruction` | `CaptionResult{text, model?}` | fetch the image bytes, ask llm-gateway's `vision` channel the `instruction` (free description or structured extraction), return the model's text. Prefer over `ocr` for understanding/structure. |
| `transcribe` | `mediaId` (media-service object id) | `TranscriptResult{text, lang?, durationSeconds?}` | fetch the audio/video bytes from media-service, run STT (local engine), return recognised speech (empty when none). For voice notes / dictated messages. |

## HTTP passthrough

| method | path | body | returns | purpose |
|--------|------|------|---------|---------|
| POST | `/internal/caption` | `CaptionInput{mediaId, instruction}` | `CaptionResult{text, model?}` | non-MCP passthrough to the `caption` tool. A capability-MCP is bound over MCP/SSE, but that transport can't be MockWebServer'd, so a caller that already knows it wants a caption (deterministic — it has the media id + instruction) hits this HTTP path instead. Delegates straight to the `caption` tool. Used by finance-agent's `receipt-parser` (MP-c). |
| POST | `/internal/ocr` | `OcrInput{mediaId}` | `OcrResult{text, lang?, confidence?}` | non-MCP passthrough to the `ocr` tool (the OCR twin of `/internal/caption`). Same rationale — a caller that deterministically wants OCR text hits this HTTP path rather than the un-mockable MCP/SSE binding. Used by docs-agent's `doc-archiver` (D-c) to turn a document photo into the full text it archives + indexes. |

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_MEDIA_PROCESSING_PORT` | `8097` | HTTP port (MCP/SSE + actuator). |
| `MEDIA_SERVICE_URL` | `http://media-service:8088` | media-service base URL — `GET /v1/media/{id}` to fetch blob bytes. |
| `MCP_MEDIA_PROCESSING_OCR_ENGINE` | `tesseract` | `tesseract` (real, needs the native lib) or `stub` (native-free marker). |
| `MCP_MEDIA_PROCESSING_TESS_LANG` | `rus+eng` | Tesseract languages ('+'-joined). |
| `MCP_MEDIA_PROCESSING_STT_ENGINE` | `whisper` | `whisper` (real, calls the ASR sidecar) or `stub` (native-free marker). |
| `WHISPER_ASR_URL` | `http://whisper:9000` | whisper ASR sidecar base URL — `transcribe` POSTs audio to `/asr`. |
| `TESSDATA_PREFIX` | `/usr/share/tesseract-ocr/5/tessdata` (image) | tessdata dir; blank → resolved by a path probe. |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | llm-gateway base URL for the `caption` vision call (via `libs/llm-client`). |

No DB / no Liquibase feature (capability-MCP). Binding side: an agent adds a
`spring.ai.mcp.client.sse.connections.mcp-media-processing` block + `MCP_MEDIA_PROCESSING_URL`
(see PATTERNS.md "Recipe: add a capability-MCP", step 4) — happens in MP-c.

## Key classes

- `McpMediaProcessingApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `config/McpMediaProcessingProperties` — `mediaprocessing.media-service-url`.
- `config/HttpConfig` — `mediaWebClient` (media-service) + `whisperWebClient` (ASR sidecar) beans.
- `http/MediaClient` — `GET /v1/media/{id}` → `FetchedMedia(mimeType, bytes)`. Local copy
  of the per-agent pattern; lift to `libs/media-client` if a third copy appears.
- `engine/OcrEngine` — pluggable OCR backend interface.
- `engine/TesseractOcrEngine` — deployed default (MP-b); Tess4J + native tesseract.
  Decodes bytes via `ImageIO`, runs `doOCR`; `tessdata` resolved from
  `TESSDATA_PREFIX`/property/path-probe. Unreadable bytes → empty text (not an error);
  a genuine native failure → `IllegalStateException`.
- `engine/StubOcrEngine` — native-free marker (`[stub-ocr] <N> bytes`); selected only by
  `mediaprocessing.ocr-engine=stub` (wiring test / degraded boxes).
- `engine/SttEngine` — pluggable speech-to-text backend interface (audio path mirror of
  `OcrEngine`).
- `engine/WhisperSttEngine` — deployed default (MP-d2b); POSTs the audio bytes as multipart
  `audio_file` to the whisper ASR sidecar (`POST /asr?output=json`) and reads `text`/`language`.
  No-speech → empty text; a genuine sidecar failure (5xx/timeout) → `IllegalStateException`.
- `engine/StubSttEngine` — native-free marker (`[stub-stt] <N> bytes`); selected only by
  `mediaprocessing.stt-engine=stub` (wiring test / degraded boxes).
- `tools/MediaProcessingMcpTools` — `@Tool`s (blocking `.block()`, the MCP `@Tool`
  convention here): `ocr(mediaId)` → `OcrEngine.extract` → `OcrResult`; `caption(mediaId,
  instruction)` → fetch → llm-gateway `vision` channel (`LlmClient`) → `CaptionResult`;
  `transcribe(mediaId)` → `SttEngine.transcribe` → `TranscriptResult`.
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the `@Tool`s.
- `web/InternalCaptionController` — `POST /internal/caption` passthrough (MP-c1); delegates to
  the `caption` tool on `Schedulers.boundedElastic()` (the tool blocks). The MockWebServer-testable
  transport finance-agent's `receipt-parser` calls instead of the un-mockable MCP/SSE binding.
- `web/InternalOcrController` — `POST /internal/ocr` passthrough (D-b), the OCR twin of the caption
  one; delegates to the `ocr` tool on `Schedulers.boundedElastic()`. Called by docs-agent (D-c).
