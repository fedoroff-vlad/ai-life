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
- Tools are schema-less and engine-backed: `ocr` (Tesseract), `caption` (vision channel),
  `transcribe` (whisper sidecar). Tool descriptions in English.
- Bound by agents via `spring.ai.mcp.client.sse.connections.mcp-media-processing` (mirror
  finance-agent ↔ mcp-money-pro-import, PR33) + agent-side env `MCP_MEDIA_PROCESSING_URL`.
  **Multiple agents bind the same server — that is the point.**

## Decision — OCR engine integration: **Tess4J in-image** (LOCKED, owner 2026-06-19)
The `ocr` tool reaches Tesseract via the **Tess4J JNI wrapper** with native `tesseract-ocr`
+ language data bundled in the module's Docker image (fewest moving parts, free, fits
"simple to configure/scale"). Behind the `OcrEngine` interface, so the wiring test stays
native-free. The OCR-sidecar alternative (an OSS OCR HTTP service over `WebClient`) was
considered and deferred — revisit only if a second engine (PaddleOCR/docTR) or scaling
pushes us off-JVM. **Operational notes:** `tessdata` is resolved at startup from
`TESSDATA_PREFIX` → a probe of common distro paths (works across tesseract 4/5 layouts);
CI installs `tesseract-ocr` so the real-OCR test runs (else it self-skips on a bare box).

**Backlog candidate — VLM OCR for hard receipts/documents (deferred, needs GPU).**
[`baidu/Unlimited-OCR`](https://github.com/baidu/Unlimited-OCR) (MIT, local inference, a
DeepSeek-OCR-based vision-language model for long/complex-layout docs) is a candidate engine
behind the same `OcrEngine` interface — a drop-in via a `mediaprocessing.ocr-engine=unlimited`
sidecar, no caller change. **Blocked on the GPU line** (NVIDIA + CUDA 12.9 + PyTorch; same
deferred GPU as stylist image-gen). Revisit only if Tesseract quality on real receipts proves
insufficient *and* a GPU host exists — otherwise Tesseract stays. Vet the repo before adopting
(young, high-star).

## Decision — STT engine integration: **whisper sidecar service** (LOCKED, owner 2026-06-21)
The `transcribe` tool reaches whisper as a **separate self-hosted container** (an OSS whisper
ASR webservice, faster-whisper engine) over plain HTTP, behind the `SttEngine` interface. Chosen
over the in-JVM JNI option (whisper.cpp/whisper-jni in-image, the OCR-style "in-image" shape)
because: (1) `architecture.md` §Principles **polyglot-by-design** — "run each as its own service,
bind a thin capability-MCP"; (2) the roadmap's recorded Whisper verdict is literally "run as a
service"; (3) Java whisper bindings are immature vs a mature OSS HTTP service, and the model
(~150 MB+) would bloat/brittle the JVM image. **Trade-off accepted:** +1 container in compose, but
the JVM image stays slim and the engine is a pure HTTP client → fully MockWebServer-testable in CI
(no native dep at all — strictly better than MP-b's self-skipping native test). **Operational:**
whisper pulls its model on first boot (slow), so mcp-media-processing depends on it `service_started`,
not `service_healthy` — `ocr`/`caption` must not block on the STT sidecar; `transcribe` is lazy +
best-effort. Note this differs from OCR (Tess4J in-image) on purpose: OCR's native lib is small and
JNI-mature; whisper's isn't.

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
- **MP-b — real OCR engine (Tesseract OSS).** ✅ **DONE.** `TesseractOcrEngine` (Tess4J)
  is the deployed default behind the same `OcrEngine` interface; `StubOcrEngine` is selected
  only by `mediaprocessing.ocr-engine=stub` (the wiring test, degraded boxes). Docker image
  installs `tesseract-ocr` + eng/rus + sets `TESSDATA_PREFIX`; CI installs tesseract so the
  real-OCR test (render → extract → assert) runs. `tessdata` path resolved via env-then-probe.
  **RU-4 (#489):** it now also populates `OcrResult.confidence` — mean per-word Tesseract
  confidence (`getWords`) ÷ 100, `0.0` on empty text, `null` when the engine gives no signal —
  the OCR twin of the whisper STT confidence, consumed by docs-agent's unreadable-photo gate.
- **Reorder (owner 2026-06-19):** the `caption` vision tool comes **before** the
  receipt-parser migration — vision-on-the-image is more accurate than parsing noisy OCR
  text, so receipt-parser migrates onto `caption`, not `ocr`. New order: MP-d1 (caption) →
  MP-c (bind + migrate) → MP-d2 (STT).
- **MP-d1 — `caption` vision tool (centralised).** ✅ **DONE.** `caption(mediaId, instruction)`
  fetches bytes from media-service and calls llm-gateway's `vision` channel (via `libs/llm-client`),
  returning the model's text. Centralises the vision call so no agent re-embeds it. Generic:
  the caller supplies the instruction (e.g. "extract amount/currency/merchant/date as JSON")
  and parses the result — reasoning stays in the caller's skill.
- **MP-c — bind to finance-agent + migrate `receipt-parser` off in-agent vision.** ✅ **DONE**
  (split c1/c2, mirrors money-pro PR44→PR45 — the deterministic agent→capability call goes over an
  HTTP `/internal/*` passthrough, NOT MCP/SSE, which can't be MockWebServer'd). **MP-c1 (PR113):**
  mcp-media-processing `POST /internal/caption` passthrough → the `caption` tool (new
  `web/InternalCaptionController` + `contracts/media/CaptionInput`). **MP-c2 (PR114):** finance-agent
  binds the `mcp-media-processing` SSE connection + `MCP_MEDIA_PROCESSING_URL` and a `http/CaptionClient`
  on `/internal/caption`; `receipt-parser` calls `caption` (instruction = the `receipt-parser` SKILL.md
  + the user's caption as a hint) instead of running the `vision` channel inline — `MediaClient`/`LlmClient`
  dropped from the receipt path. **Cleared the STATUS Deferred anti-pattern item.**
- **MP-d2 — `transcribe` (STT, whisper OSS)**, bound by future Stage-6 agents (docs,
  stylist, …). Slice like MP-a/b (stub → real engine). **MP-d2a ✅ DONE:** `transcribe(mediaId)`
  `@Tool` fetches audio/video bytes from media-service → runs a pluggable `engine/SttEngine`
  → new contract `media/TranscriptResult(text, lang?, durationSeconds?)`. Default
  `StubSttEngine` returns a deterministic byte-count marker (`[stub-stt] <N> bytes`), so the
  fetch→engine→tool wiring is provable native-free (`MediaProcessingTranscribeTest`). **MP-d2b
  ✅ DONE — real whisper engine via an ASR sidecar over HTTP.** `WhisperSttEngine`
  (`@ConditionalOnProperty mediaprocessing.stt-engine=whisper`, matchIfMissing) is the deployed
  default behind the same `SttEngine` interface; `StubSttEngine` is now `=stub`-only. It POSTs the
  audio bytes as multipart `audio_file` to the whisper webservice (`POST /asr?output=json`) and
  reads `text`/`language` (+ duration from the last segment). No-speech → empty text; sidecar
  5xx/timeout/unparseable → `IllegalStateException` (mirrors `OcrEngine`). Because the engine is
  now just an HTTP client, it's **fully MockWebServer-testable, no native/model dep** —
  `WhisperSttEngineTest` runs everywhere (incl. CI), unlike MP-b's self-skipping native test; the
  live whisper container is exercised via `docker compose up` (the SearXNG/mcp-web live-verify way).
  compose + dev.yml gain a `whisper` service (`onerahmet/openai-whisper-asr-webservice`, faster_whisper,
  host 9100); mcp-media-processing depends on it `service_started` (not healthy — ocr/caption mustn't
  block on whisper's first-boot model pull) + env `MCP_MEDIA_PROCESSING_STT_ENGINE`/`WHISPER_ASR_URL`.

## PR-sized slices (cont.)
- **MP-e — `frames(mediaId, n)` video keyframe extraction (ffmpeg).** ✅ **DONE.** The consumer this
  tool was waiting for has arrived — the researcher's **video-understanding** flow
  ([research.md](research.md) §Video understanding, [#294](https://github.com/fedoroff-vlad/ai-life/issues/294))
  needs a visual channel for speechless video (ASMR / landscape) where `transcribe` returns empty.
  `frames(mediaId, n, householdId, ownerId?)` fetches the video bytes from media-service → extracts `n`
  evenly-spaced keyframes via **ffmpeg** → stores each as a media-service image (module-local write-side
  `MediaStoreClient`, mirror of `mcp-media-fetch`'s) → returns `FramesResult(frameMediaIds)`; the caller
  then runs the existing `caption` (MP-d1) per frame. **stub → real** behind a `FrameExtractor` seam
  (`StubFrameExtractor` = deterministic placeholder frames, native-free CI; `FfmpegFrameExtractor` =
  `@ConditionalOnProperty mediaprocessing.frame-extractor=ffmpeg`, matchIfMissing — ffprobe duration
  then seek to `duration*i/(n+1)` per frame, per-frame soft-fail). `/internal/frames` passthrough (the
  deterministic path researcher calls in V-c). ffmpeg (+ffprobe) installed in the module's Docker image
  (same in-image posture as Tess4J's tesseract; ffmpeg is a small mature native tool, unlike whisper's
  model). New contracts `media/{FramesInput, FramesResult}`; `n` clamped to `frame-max-count` (20).
  Test = `MediaProcessingFramesTest` (MockWebServer for media-service GET + N uploads, stub extractor).
  - **Scenario (extract+store):** WHEN `frames(videoId, 3, household)` is called on a stored video,
    THEN 3 evenly-spaced keyframes are extracted, each uploaded to media-service, and their ids are
    returned in temporal order.
  - **Scenario (no scope):** WHEN `householdId` is missing, THEN it returns an empty result and never
    fetches or uploads (frames must be storable under a scope).
  - **Scenario (nothing produced):** WHEN the extractor yields no frame (unreadable bytes / ffmpeg
    error), THEN it returns an empty `frameMediaIds` — the visual tier's "nothing" signal, not a 500.

## Out of scope (here)
- Real LLM providers for `caption` — uses the existing `vision` channel; quality is Stage 5.
- New domain agents that consume the capability (docs / stylist / health) — **Stage 6+**.
- **Media acquisition** (downloading a platform link / a raw video into media-service) — a *separate*
  capability by doctrine (this MCP takes ids, never fetches external URLs); it lives in
  **`mcp-media-fetch`** ([research.md](research.md) §Video understanding).
