# mcp-chart-render

Shared **chart-render capability-MCP** (`shared/mcp/`, no schema). Turns a data-only chart spec
(bar / line / pie) into a PNG, stores it in media-service, and returns the media id — the caller
embeds it like any other media object (e.g. inline in a Telegram report). Sibling of `mcp-image-gen`
(both render an image → media-service → media id). Bound by agents over MCP/SSE; it owns no data.
Plan: [finance.md](../../../plans/finance.md) §MVP boundary (the deferred "chart" capability),
issue [#292](https://github.com/fedoroff-vlad/ai-life/issues/292).

**Presentation only.** The caller supplies the numbers (a finance report, a briefing digest); this
capability just draws them. No LLM, no data source.

**Status (#292, unbound):** infra laid, **Java2D engine** by default — a pure `Graphics2D` +
`ImageIO` renderer (no external charting dependency, no cost, deterministic; mirrors
`mcp-image-gen`'s `StubImageEngine`). The engine sits behind a swappable `ChartEngine` interface so a
library-backed renderer could replace it later via config with no caller change. **Not yet bound** to
an agent — the first consumer is finance year-analysis charts (#291), which wires it into
`finance-agent` and adds the reporting skill (same "capability-MCP has no caller until an agent binds
it" pattern `mcp-image-gen` started with).

## Port: `8120` (`MCP_CHART_RENDER_PORT`)

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `render_chart` | `ChartInput{householdId, ownerId?, spec}` | `ChartResult{mediaId, engine}` | render `spec` → PNG, store in media-service, return the media id + the engine. |

`spec` = `ChartSpec{type ('bar'|'line'|'pie'), title, categories[], series[{name, values[]}], unit?}`.
`values` align 1:1 with `categories`; multi-series bar/line draw a legend; pie uses the first series.
Unknown/blank `type` falls back to bar; empty data renders a "No data" placeholder.

## HTTP passthrough

| method | path | body | returns | purpose |
|--------|------|------|---------|---------|
| POST | `/internal/render` | `ChartInput` | `ChartResult` | non-MCP passthrough to `render_chart`. The MockWebServer-testable, deterministic path an agent calls (MCP/SSE can't be mocked). Delegates straight to the tool. |

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_CHART_RENDER_PORT` | `8120` | HTTP port (MCP/SSE + actuator). |
| `CHART_RENDER_ENGINE` | `java2d` | Which `ChartEngine` to wire. Swappable later (library-backed) via env with no caller change. |
| `MEDIA_SERVICE_URL` | `http://media-service:8088` | Rendered charts are stored here. |

No DB / no Liquibase feature (capability-MCP). No backing container (rendering is in-process). Binding
side: an agent adds a `spring.ai.mcp.client.sse.connections.mcp-chart-render` block + `CHART_RENDER_URL`
(happens with the first consumer, #291).

## Key classes

- `McpChartRenderApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `config/McpChartRenderProperties` — `chart-render.{engine, media-service-url}`.
- `config/HttpConfig` — `mediaServiceWebClient` bean.
- `engine/ChartEngine` (interface) — `render(ChartSpec) → RenderedChart`; the swappable seam (mirrors
  `mcp-image-gen`'s `ImageEngine`).
- `engine/Java2dChartEngine` — default (`chart-render.engine=java2d`); plots bar/line/pie with
  `Graphics2D` (antialiased, nice-rounded y-axis, palette, legend) → PNG via `ImageIO`.
- `media/MediaUploader` — multipart `POST /v1/media` upload to media-service.
- `tools/ChartRenderMcpTools` (`render_chart`) + `tools/ToolsConfig` — the MCP tool.
- `web/InternalRenderController` — `POST /internal/render` passthrough (delegates on
  `Schedulers.boundedElastic()`).
