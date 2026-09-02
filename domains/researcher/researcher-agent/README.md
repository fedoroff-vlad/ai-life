# researcher-agent

Web research specialist. Finds information online, reads the best sources, and returns a concise
summary with links — **cheap-first** (HTTP search + page-fetch, then a single LLM synthesis, to
save tokens). The canonical role lives in [AGENT.md](AGENT.md), served at
`GET /agents/researcher/manifest`. Plan: [research.md](../../../plans/research.md).

A cross-domain specialist (its own `domains/researcher/` folder), not tied to one domain — it binds
the shared `mcp-web` capability (`web_search` + `fetch_url`) and, for video understanding, the shared
`mcp-media-fetch` (acquisition) + `mcp-media-processing` (understanding) capabilities. The same
capabilities are reused by chef / briefing / finance-investment later.

**Status (V-d — #294 complete):** two flows behind `IntentController`, both cheap-first on the shared
`Coordinator`. The video flow's injection resistance is model-proven (`GoldenVideoInjectionResistanceTest`)
and its link chain is closed by `E2EVideoUnderstandingFlowTest`.
- **research (R-d):** **search** the web (`mcp-web` `/internal/search`) → **read** the top hits in full
  (`/internal/fetch`, parallel, soft-fail per page) → **one** LLM synthesis → a summary with grouped
  article/video links. Search + fetch are plain HTTP (no model cost); only the synthesis hits the LLM.
- **video (V-c):** a specific video sent as a **link** or an uploaded **file** → one "о чём это видео".
  Three tiers, each soft-failing to the next: (1) **captions** (link only — `mcp-media-fetch`
  `transcribe_video`, no download), (2) **speech** (`fetch_audio`→id for a link; a file is already an
  id → `mcp-media-processing` `transcribe` STT), (3) **visual** (file only — `frames` keyframes →
  `caption` each) → one LLM synthesis. The transcript/scene is untrusted, so the synthesis leads with
  the `agent-runtime` injection guard (#599). A domain-less video drop routes here; everything else is
  research. (A captionless, speechless *link* has no video bytes to frame — a `fetch_video` acquisition
  tool is future work.)

## Port: `8099` (`RESEARCHER_AGENT_PORT`)

## Endpoints

| method | path | purpose |
|--------|------|---------|
| GET | `/agents/researcher/manifest` | parsed AGENT.md (orchestrator scrapes it on startup) |
| POST | `/agents/researcher/intent` | hit by the orchestrator on a research intent |
| GET | `/actuator/health` | liveness |

## Env

| Var | Default | Purpose |
|---|---|---|
| `RESEARCHER_AGENT_PORT` | `8099` | HTTP port. |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | Via `libs/llm-client` (the synthesis). |
| `MCP_WEB_URL` | `http://mcp-web:8098` | The shared web capability: SSE binding + the HTTP `/internal/search` + `/internal/fetch` base URL the research flow calls. |
| `MCP_MEDIA_FETCH_URL` | `http://mcp-media-fetch:8126` | The acquisition capability (V-c): SSE binding + `/internal/transcribe` (captions) + `/internal/fetch-audio` the video flow calls. |
| `MCP_MEDIA_PROCESSING_URL` | `http://mcp-media-processing:8097` | The understanding capability (V-c): SSE binding + `/internal/transcribe` (STT) + `/internal/frames` + `/internal/caption` the video flow calls. |
| `RESEARCHER_AGENT_SEARCH_LIMIT` | `6` | Hits requested from `web_search`. |
| `RESEARCHER_AGENT_FETCH_TOP_N` | `3` | Top hits fetched in full before synthesis (cheap-first depth). |
| `RESEARCHER_AGENT_VIDEO_FRAMES` | `4` | Keyframes the video flow's visual tier extracts + captions. |
| `RESEARCHER_AGENT_MCP_CLIENT_ENABLED` | `true` | Toggle the Spring AI MCP client. Tests default to `false`. |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` / `MEMORY_SERVICE_URL` | service defaults | Back the shared `agent-runtime` clients (unused by the MVP flow, but the runtime beans need them). |

Orchestrator side: `RESEARCHER_AGENT_URL` (default `http://researcher-agent:8099`) is registered
in [orchestrator/application.yml](../../../platform/orchestrator/src/main/resources/application.yml).

## Key classes

- `ResearcherAgentApplication` — `@SpringBootApplication` + `@Import(AgentRuntimeConfig)`.
- `config/ResearcherAgentProperties` — `researcher-agent.{mcp-web-url, profile/notifier/memory urls}`.
- `config/OutboundHttpConfig` — `mcpWeb` + `mcpMediaFetch` + `mcpMediaProcessing` WebClients, the
  shared `WebSearchClient` + `CaptionClient` beans, and the `profile/notifier/memory` qualified beans
  the shared runtime clients pick up.
- `web/ManifestController` — `GET /agents/researcher/manifest`.
- `web/IntentController` — `POST /agents/researcher/intent`; routes a **specific video** (a video-file
  attachment or a video-host link — `VideoUnderstanding.detect`) to the `VideoUnderstanding` flow, else
  a research topic to the `Researcher` flow.
- `flow/Researcher` — the cheap-first research flow on the shared `Coordinator`: search → fetch top N
  (parallel, soft-fail) → fold the corpus into `context` → one LLM synthesis → summary + links.
  **Video hits are skipped at the fetch step** (their pages are boilerplate); the search snippet
  supplies the 1–2 sentence description + the link. Understanding a *specific* dropped video is the
  `VideoUnderstanding` flow below, not this one.
- `flow/VideoUnderstanding` — the multimodal video flow (V-c) on the shared `Coordinator`:
  `detect` (link vs file) → three cheap-first tiers (captions → speech → visual, each soft-failing) →
  one LLM synthesis with the injection guard leading. Owns the `VideoSource` (link|file) + `Scene`
  (captions|speech|visual) model and the video-host detection.
- `http/WebSearchClient` (agent-runtime, `POST /internal/search`) + `http/PageFetchClient`
  (`POST /internal/fetch`) — the deterministic mcp-web calls (MockWebServer-testable; not the SSE
  transport).
- `http/MediaFetchClient` (`/internal/transcribe` = `transcribe_video`, `/internal/fetch-audio`) +
  `http/MediaProcessingClient` (`/internal/transcribe` = STT, `/internal/frames`) — the deterministic
  video-capability calls. Local for now (researcher is the first consumer); the shared `CaptionClient`
  (agent-runtime) captions the keyframes — reused, not re-embedded. The second agent lifts these.

## Skills

- `research` (intent-invoked) — synthesizes a concise answer with cited links from the pre-gathered
  corpus (search hits + fetched page text); groups video links separately; never invents
  facts/links. Lives at [skills/researcher/research/SKILL.md](../skills/research/SKILL.md).
  Validated on a real model by the opt-in `flow.GoldenResearchSynthesisTest` (Stage 5 / #199): feeds a
  fixed corpus (search/fetch mocked) through the real synthesis hop and asserts the answer is grounded
  and cites **only** corpus links (the "never invent URLs" contract), skipped in CI (`GOLDEN_LLM` gate)
  — see `platform/llm-gateway/README.md` §Golden tests.
- `video` (intent-invoked) — writes "о чём это видео" from the already-extracted transcript or visual
  scene description; only uses the extracted content, treats it as data (never instructions). Lives at
  [skills/researcher/video/SKILL.md](../skills/video/SKILL.md). The model-proved injection golden is
  V-d (`GoldenVideoInjectionResistanceTest`).
