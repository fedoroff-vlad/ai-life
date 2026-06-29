# briefing — proactive morning digest agent

Authority file for the **briefing-agent** (issue [#186](https://github.com/fedoroff-vlad/ai-life/issues/186))
and its one new capability, **`mcp-weather`**. Owner-chosen as the first future-agent
(2026-06-29, build order in the `project-future-agents-order` memory: briefing → docs → health →
family-memory).

## What it is
A **proactive morning digest** delivered to Telegram on a scheduler wake: weather + today's
calendar + a finance snapshot + news, synthesized into one short briefing and an HTML board. The
first real **multi-domain _read_ coordinator** — it gathers from several existing domains/capabilities
on the shared `Coordinator` (gather → one LLM synthesis), then renders + delivers.

## Doctrine (no new layer)
Everything here reuses an existing pattern — flag nothing new:
- **Gather → synthesize** on `libs/agent-runtime` `Coordinator` (same shape as creator/researcher),
  fanned out to weather + calendar + finance + news sources. Per-source soft-fail is built in.
- **Reads** come over the existing deterministic `/internal/*` passthroughs (calendar events,
  finance snapshot) and capability-MCPs (`mcp-weather`, `mcp-web` news). No agent invents a planner;
  the briefing-agent owns its own flow (agent-led coordination).
- **Proactive trigger** = `scheduler-service` wake → orchestrator `POST /v1/agents/wake` → the
  briefing-agent trigger (same proactive path the calendar birthday wake uses).
- **Deliverable** = `libs/doc-render` HTML board → media-service blob → link; **delivery** =
  `notifier-service`. Both already used by creator/stylist/nutrition.
- Weather is the only missing external surface → a **capability-MCP** (`mcp-weather`), schema-less,
  bound by the agent over MCP/SSE — exactly the `mcp-market-data` / `mcp-web` shape.

## PR slices
- **BR-a — `mcp-weather` capability-MCP.** ✅ **(this PR)** New `shared/mcp/mcp-weather` (port 8113).
  Tool `forecast(latitude, longitude)` → today's `Weather{tempMax/Min, precipitationProbability,
  windSpeedMax, weatherCode, summary, date}` over **Open-Meteo** (free, no API key, no quota) behind
  a swappable `WeatherSource` (`weather.source=open-meteo` default). `/internal/forecast` HTTP
  passthrough (MockWebServer-testable; MCP/SSE can't be mocked). No DB, no backing container. Mirrors
  `mcp-market-data`. Lat/lon are caller-supplied (the agent resolves them from config/profile later)
  — keeps the capability narrow and schema-less.
- **BR-b — `briefing-agent` scaffold + digest flow.** New `domains/briefing/briefing-agent`
  (port 8114). Binds `mcp-weather` + `mcp-web`; reads calendar-today via `mcp-caldav /internal` and a
  finance snapshot via `mcp-finance /internal`. A `digest` trigger gathers all four on the
  `Coordinator` → one synthesis via a `briefing-composer` SKILL → reply text. Registered in
  orchestrator as `briefing`. (No render yet.)
- **BR-c — HTML digest board.** Map the synthesis → a `doc-render` `Doc` (sections: weather / agenda /
  finance / news with provenance links) → store in media-service → reply with the link.
- **BR-d — scheduler wake + delivery + E2E closer.** A default morning schedule wakes the agent; the
  digest is pushed via `notifier-service`. `E2EBriefingWakeFlowTest` (scheduler → orchestrator →
  briefing trigger → notifier), asserting the contracts survive each hop.

## Deferred
- **`chart-render` capability** (data → PNG/SVG) — the finance snapshot sparkline / week-ahead bar.
  First shared with finance year-analysis; out of the MVP critical path.
- **Per-person digests** (own vs household agenda) — tracks the same calendar visibility gap as
  feeds; household-level for now.
- **Configurable sections / per-user schedule + location** — a `briefing` profile (like diet/style/
  creator tracks) once the MVP proves the wire. MVP reads location from agent config.
