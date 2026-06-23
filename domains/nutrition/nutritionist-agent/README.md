# nutritionist-agent

Nutrition domain agent (port **8105**). Logs meals, breaks grocery baskets down (КБЖУ +
good/watch/cut), keeps per-person diet profiles, and (over the coming slices) plans rations +
shopping lists. Owns the `mcp-nutrition` domain-MCP; binds the shared `mcp-media-processing`
(vision caption for meal/receipt photos) + `mcp-web` (store/availability lookup). Routes via the
orchestrator (registered as `nutritionist`). See [plans/nutrition.md](../../../plans/nutrition.md).

## Status (through NU-c)

Manifest endpoint + chat fallback (NU-b) + the **food-log flow** (NU-c). Remaining flows replace
the fallback branch-by-branch:
- **NU-c — food log. DONE.** A meal photo → `mcp-media-processing` caption extract, or a typed meal
  ("съел…", "на обед…", "запиши…") → one LLM extract, both via the `meal-logger` SKILL → write
  **write-immediately** to `mcp-nutrition`'s `/internal/meal` (attributed to the sender). `foodlog/FoodLogger`.
- **NU-d — diet profiles. DONE.** A typed message with a diet-profile cue ("моя цель…", "у меня
  аллергия…") → one LLM extract via the `diet-profiler` SKILL → upsert via `/internal/diet-profile`,
  for the sender (`self`) or the household-default (`household`). `profile/DietProfiler`.
- **NU-e** — nutrition-analysis HTML board (shared `libs/doc-render`).
- **NU-f** — basket breakdown (direct); **IA-b** — basket breakdown auto-triggered off the
  `basket.captured` bus event (the case-1 fan-out).
- **NU-g** — ration + shopping list (multi-person `Coordinator` flow; invokes `chef-agent`).

## Endpoints

- `POST /agents/nutritionist/intent` (body `NormalizedMessage`) → `IntentResponse` — the
  orchestrator's entry point. A photo → food-log; a typed food-log cue → food-log; else chat.
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
- `chat/NutritionistChat` — the chat fallback (one LLM turn, AGENT.md as system prompt).
- `foodlog/FoodLogger` — the food-log flow: photo → caption / typed → LLM extract, both via the
  `meal-logger` SKILL, write-immediately to `/internal/meal` (attributed to the sender).
- `profile/DietProfiler` — the diet-profile flow: typed goals/restrictions → LLM extract via the
  `diet-profiler` SKILL → upsert via `/internal/diet-profile` (self or household-default).
- `http/CaptionClient` — `POST /internal/caption` on mcp-media-processing (vision).
- `http/MealClient` — `POST /internal/meal` on mcp-nutrition (write meal).
- `http/DietProfileClient` — `POST /internal/diet-profile` on mcp-nutrition (upsert profile).
- `web/IntentController` — `POST /intent` (photo → food-log; profile cue → diet-profiler; food-log
  cue → food-log; else chat).
- `web/ManifestController` — `GET /manifest`.

## Skills

- `meal-logger` (`domains/nutrition/skills/meal-logger/SKILL.md`) — strict-JSON meal extraction
  (description, items, best-effort КБЖУ), shared by both the photo (caption instruction) and typed
  (LLM system prompt) paths.
- `diet-profiler` (`domains/nutrition/skills/diet-profiler/SKILL.md`) — strict-JSON diet-profile
  extraction (scope self|household, macro goals, restrictions, tastes) from a typed message.

## AGENT.md

`name: nutritionist`, binds `mcp-nutrition` + `mcp-media-processing` + `mcp-web`, `skills: []`
until NU-c. ⚠️ Carries the infant/medical-safety rule (general guidance only + pediatrician
caveat).
