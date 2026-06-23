# nutritionist-agent

Nutrition domain agent (port **8105**). Logs meals, breaks grocery baskets down (КБЖУ +
good/watch/cut), keeps per-person diet profiles, and (over the coming slices) plans rations +
shopping lists. Owns the `mcp-nutrition` domain-MCP; binds the shared `mcp-media-processing`
(vision caption for meal/receipt photos) + `mcp-web` (store/availability lookup). Routes via the
orchestrator (registered as `nutritionist`). See [plans/nutrition.md](../../../plans/nutrition.md).

## Status (NU-b scaffold)

Scaffold only: the manifest endpoint + a chat fallback. The real flows replace the fallback
branch-by-branch:
- **NU-c** — food log (typed / photo meal → `mcp-media-processing` caption → write via
  `mcp-nutrition /internal/meal`).
- **NU-d** — multi-person diet profiles (`/internal/diet-profile`).
- **NU-e** — nutrition-analysis HTML board (shared `libs/doc-render`).
- **NU-f** — basket breakdown (direct); **IA-b** — basket breakdown auto-triggered off the
  `basket.captured` bus event (the case-1 fan-out).
- **NU-g** — ration + shopping list (multi-person `Coordinator` flow; invokes `chef-agent`).

## Endpoints

- `POST /agents/nutritionist/intent` (body `NormalizedMessage`) → `IntentResponse` — the
  orchestrator's entry point. Currently always the chat fallback.
- `GET /agents/nutritionist/manifest` → `AgentManifest` — scraped by the orchestrator on startup.

## Env

| Var | Default | Purpose |
|---|---|---|
| `NUTRITIONIST_AGENT_PORT` | `8105` | HTTP port |
| `MCP_NUTRITION_URL` | `http://mcp-nutrition:8104` | nutrition domain-MCP (its data) |
| `MCP_MEDIA_PROCESSING_URL` | `http://mcp-media-processing:8097` | shared vision capability |
| `MCP_WEB_URL` | `http://mcp-web:8098` | shared web/store-lookup capability |
| `NUTRITIONIST_AGENT_MCP_CLIENT_ENABLED` | `true` | toggle the eager MCP-SSE binding off in dev |
| `NUTRITIONIST_AGENT_MEMORY_RECALL_K` | `5` | memory recall fan-out |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | llm-gateway (chat) |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` / `MEMORY_SERVICE_URL` | — | shared agent-runtime clients |

## Key classes

- `NutritionistAgentApplication` — `@Import(AgentRuntimeConfig)` + `@EnableConfigurationProperties`.
- `config/NutritionistAgentProperties` — the outbound base URLs (`nutritionist-agent.*`).
- `config/OutboundHttpConfig` — one `clone()`d `WebClient` per dependency; the
  `profile/notifier/memory` qualified beans back the shared runtime clients.
- `chat/NutritionistChat` — the scaffold chat fallback (one LLM turn, AGENT.md as system prompt).
- `web/IntentController` — `POST /intent` (→ chat for now).
- `web/ManifestController` — `GET /manifest`.

## AGENT.md

`name: nutritionist`, binds `mcp-nutrition` + `mcp-media-processing` + `mcp-web`, `skills: []`
until NU-c. ⚠️ Carries the infant/medical-safety rule (general guidance only + pediatrician
caveat).
