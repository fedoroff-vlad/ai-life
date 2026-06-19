# media — shared media-understanding capability

Authority file for the **media-understanding capability-MCP** (`mcp-media-processing`):
the shared toolbox that turns stored media bytes into text/structure (OCR → STT →
vision-caption → video-frames). Owner-prioritised **after D3** (2026-06-19) — see
`roadmap.md` line 21 and STATUS "Deferred work".

## Why a shared capability (the doctrine, not relitigated)
Media (photo / audio / video) can belong to **any** domain — a receipt→finance, a
sick-note→docs/health, an outfit→stylist. So *understanding* it must be a **capability-MCP
any agent binds** (`shared/mcp/mcp-media-processing`, **no schema**) — NOT embedded per
agent. This pays down the interim anti-pattern: `receipt-parser` today runs the `vision`
channel **inside** finance-agent (the canonical "stuffed into one place" shortcut). See
`architecture.md` §"Where each kind of thing lives" (capability-MCP row) and PATTERNS.md
"Recipe: add a capability-MCP". Reuse **free OSS engines** (Tesseract/PaddleOCR/docTR for
OCR, whisper for STT) — do not rebuild, do not add a paid API. Reasoning over the extracted
text stays in the calling agent's skill (the capability returns raw text/structure, not a
domain decision).

## Where it lives / shape
- `shared/mcp/mcp-media-processing` — Spring Boot MCP server, **port 8097**, **no DB**
  (capability-MCP: no JPA / datasource / Liquibase feature). Reads blob bytes from
  **media-service** (`GET /v1/media/{id}` → bytes + content-type, port 8088, PR37) by media
  id — callers pass an id, never raw bytes.
- Tools are schema-less and engine-backed: `ocr` first; `transcribe` (STT) + `caption`
  (vision) later. Tool descriptions in English.
- Bound by agents via `spring.ai.mcp.client.sse.connections.mcp-media-processing` (mirror
  finance-agent ↔ mcp-money-pro-import, PR33) + agent-side env `MCP_MEDIA_PROCESSING_URL`.
  **Multiple agents bind the same server — that is the point.**

## Open decision — OCR engine integration (LOCK before MP-b, not needed for MP-a)
How the `ocr` tool reaches Tesseract. Behind an `OcrEngine` interface either way, so MP-a's
tests don't care.
- **(rec) Tess4J in-image** — bundle `tesseract-ocr` + lang data in the module's Docker
  image, call via the Tess4J JNI wrapper. Lean (no extra container), free, fits "simple to
  configure/scale". Cost: a native dep in one image; dev on Windows runs it via the
  container, not bare-metal.
- **(alt) OCR sidecar** — a small OSS OCR HTTP service (e.g. a PaddleOCR/docTR container) the
  MCP calls over `WebClient` (the textbook capability-MCP shape — wraps an HTTP surface).
  Cleaner JVM, cross-platform; cost: one more container to run/maintain.

Recommendation: **Tess4J in-image** for the first engine (fewest moving parts); revisit the
sidecar only if a second engine (Paddle/docTR) or scaling pushes us off-JVM. Confirm with
owner at MP-b.

## PR-sized slices
- **MP-a — scaffold + `ocr` tool on a stub engine (end-to-end, no native dep).** New
  `shared/mcp/mcp-media-processing` module: Spring Boot app + Spring AI MCP server starter,
  `config/HttpConfig` `mediaWebClient` + `http/MediaClient.fetch(mediaId) → (bytes, mime)`
  (mirror finance-agent's `MediaClient`), an `engine/OcrEngine` interface + a deterministic
  `StubOcrEngine` (default bean), `tools/MediaProcessingMcpTools.ocr(mediaId)` →
  fetch bytes → `engine.extract` → new contract `media/OcrResult(text, lang?, confidence?)`.
  Register in root `pom.xml` `<modules>`, compose service block, `.env.example`,
  infra/README port row (8097), README. Test = `MockWebServer` for media-service + stub
  engine (assert id → media fetch → OcrResult). **No native dep, no DB, no agent binding
  yet** — the capability stands alone and is fully testable.
- **MP-b — real OCR engine (Tesseract OSS).** Swap `StubOcrEngine` for the chosen
  integration (per the decision above) behind the same `OcrEngine` interface, so MP-a's
  tests stay green. Docker image gains the engine (in-image) or compose gains a sidecar.
  Test the mapping bytes → extracted text against a tiny fixture image.
- **MP-c — bind to finance-agent + migrate `receipt-parser` off in-agent vision.** Add the
  `mcp-media-processing` SSE connection + `MCP_MEDIA_PROCESSING_URL` to finance-agent;
  `receipt-parser` calls the capability instead of running the `vision` channel inline, then
  parses the returned text/structure with its existing skill. **Clears the STATUS Deferred
  anti-pattern item.** (Decision to lock here: receipt parsing on **OCR text + skill** vs a
  `caption` vision tool — see MP-d.)
- **MP-d (later) — expand the toolbox.** `caption` (vision-caption via llm-gateway's
  `vision` channel, centralised here so no agent re-embeds it) and `transcribe` (STT, whisper
  OSS) tools, bound by future Stage-6 agents (docs, stylist, …). Slice each like MP-a/b
  (stub → real engine).

## Out of scope (here)
- Real LLM providers for `caption` — uses the existing `vision` channel; quality is Stage 5.
- New domain agents that consume the capability (docs / stylist / health) — **Stage 6+**.
- Video-frames extraction (`ffmpeg`) — a later tool once a consumer needs it.
