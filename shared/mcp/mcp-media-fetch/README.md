# mcp-media-fetch

Shared **media-acquisition capability-MCP** (`shared/mcp/`, no schema). Pulls media content out of a
URL via **yt-dlp** — a concern distinct from web *retrieval* (`mcp-web`) and from media
*understanding* (`mcp-media-processing`), so it gets its own capability. **Bound by:** the
`researcher` (video-understanding flow, V-c). Reached through the `/internal/*` passthroughs. Bound
over MCP/SSE; it owns no data. Plan: [research.md](../../../plans/research.md) §Video understanding.

**Status:** `transcribe_video` pulls a video's subtitles/auto-captions via **yt-dlp** (behind
`VideoTranscriptEngine`) → plain transcript text — the cheapest way to read video content, which
`mcp-web`'s `fetch_url` can't (JS-rendered pages return only boilerplate). **Cheap retrieval, no LLM**
— the calling agent does the synthesis. `fetch_audio` (audio → media-service id, for the no-captions
STT path) lands in V-b.

## Port: `8126` (`MCP_MEDIA_FETCH_PORT`)

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `transcribe_video` | `url`, `lang?` | `VideoTranscript{url, title?, text, lang?, truncated}` | yt-dlp pulls the video's subtitles/captions → plain transcript text. The fast-path for video links when captions exist. Empty text when no transcript. |

## HTTP passthroughs

| method | path | body | returns | purpose |
|--------|------|------|---------|---------|
| POST | `/internal/transcribe` | `TranscribeInput{url, lang?}` | `VideoTranscript` | non-MCP passthrough to `transcribe_video` (blocking yt-dlp on `boundedElastic`). The MockWebServer-testable, deterministic path an agent calls (MCP/SSE can't be mocked). |

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_MEDIA_FETCH_PORT` | `8126` | HTTP port (MCP/SSE + actuator). |
| `MCP_MEDIA_FETCH_TRANSCRIPT_ENGINE` | `yt-dlp` | `transcribe_video` engine: `yt-dlp` (binary in image) or `stub`. |
| `MCP_MEDIA_FETCH_TRANSCRIPT_LANGS` | `en.*,ru.*` | yt-dlp `--sub-langs` (comma-separated, regex ok). |
| `MCP_MEDIA_FETCH_TRANSCRIPT_TIMEOUT_SEC` | `60` | yt-dlp subprocess timeout (s). |
| `MCP_MEDIA_FETCH_TRANSCRIPT_MAX_CHARS` | `12000` | `transcribe_video` max chars; longer → truncated. |

No DB / no Liquibase feature (capability-MCP). The image bundles the **yt-dlp** standalone linux
binary (PyInstaller, no system Python). Binding side: an agent adds a
`spring.ai.mcp.client.sse.connections.mcp-media-fetch` block + `MCP_MEDIA_FETCH_URL` (happens in V-c).

## Key classes

- `McpMediaFetchApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `config/McpMediaFetchProperties` — `media-fetch.{transcript-engine, yt-dlp-bin, transcript-langs,
  transcript-timeout-sec, transcript-max-chars}`.
- `engine/VideoTranscriptEngine` — pluggable transcript backend interface (mirrors `OcrEngine`).
- `engine/YtDlpTranscriptEngine` — default (`transcript-engine=yt-dlp`); shells out to the bundled
  yt-dlp binary (`--skip-download --write-(auto-)subs`), parses the WebVTT with `SubtitleParser`,
  caps length. Best-effort: no subs / error / timeout → empty text.
- `engine/StubTranscriptEngine` — native-free marker (`transcript-engine=stub`; wiring test).
- `engine/SubtitleParser` — WebVTT → plain text (drops header/timings/cue-ids/inline-tags, collapses
  auto-caption repeats). Pure function, unit-tested.
- `tools/MediaFetchMcpTools` — `transcribe_video` `@Tool` (blocking per the MCP convention) →
  `VideoTranscript`.
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the `@Tool`s.
- `web/InternalTranscribeController` — the `POST /internal/transcribe` passthrough (delegates on
  `Schedulers.boundedElastic()`).
