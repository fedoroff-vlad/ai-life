# mcp-image-gen

Shared **image-generation capability-MCP**: render an image from a prompt (+ optional reference
photos), store it in media-service, return the media id. No DB schema (a capability-MCP owns no
data). The backend engine is **swappable by config** — a free placeholder **stub** now, a
self-hosted **local** GPU model later — with no caller change. Sibling of `mcp-web` /
`mcp-media-processing`. See [plans/stylist.md](../../../plans/stylist.md) §Phase 2 (the deferred
image-gen line).

**Status:** infra laid, **stub engine** by default (placeholder PNG, no cost). Flip
`IMAGE_GEN_ENGINE=local` + point `IMAGE_GEN_LOCAL_URL` at a model server (e.g. a Mac Studio GPU
model) to go live — config only. Try-on (reference photos) is carried by the contract but not yet
resolved (lands with a real try-on engine).

## Port: `8103` (`MCP_IMAGE_GEN_PORT`)

## Tools (MCP)

- `generate_image(householdId, ownerId?, prompt, refMediaIds?)` → `ImageGenResult{mediaId, model}` —
  render via the configured engine, store in media-service, return the stored media id + the engine.
  `refMediaIds` (try-on inputs) are accepted but not yet resolved by the stub.

## Internal REST passthrough

- `POST /internal/generate` (body `ImageGenInput`) → `ImageGenResult` — the deterministic,
  MockWebServer-testable path (MCP/SSE can't be mocked). Delegates to `generate_image`. Mirrors
  mcp-market-data's `/internal/quote`.

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_IMAGE_GEN_PORT` | `8103` | HTTP port. |
| `IMAGE_GEN_ENGINE` | `stub` | Engine: `stub` (placeholder PNG) or `local` (self-hosted model). |
| `IMAGE_GEN_LOCAL_URL` | `http://localhost:9200` | Model server base URL (used when `engine=local`). |
| `MEDIA_SERVICE_URL` | `http://media-service:8088` | Generated images are stored here. |

## Key classes

- `McpImageGenApplication`.
- `config/McpImageGenProperties` — `image-gen.{engine, local-url, media-service-url}`.
- `config/HttpConfig` — `mediaServiceWebClient` + `localModelWebClient` beans.
- `engine/ImageEngine` (interface) — `generate(prompt, refs) → GeneratedImage`; the swappable seam.
- `engine/StubImageEngine` — default placeholder PNG (`@ConditionalOnProperty image-gen.engine=stub`).
- `engine/LocalImageEngine` — posts the prompt to the self-hosted model server
  (`@ConditionalOnProperty image-gen.engine=local`); the seam to adapt to the chosen server's API.
- `media/MediaUploader` — multipart `POST /v1/media` upload to media-service.
- `tools/ImageGenMcpTools` (`generate_image`) + `tools/ToolsConfig` — the MCP tool.
- `web/InternalGenerateController` — `POST /internal/generate`.
