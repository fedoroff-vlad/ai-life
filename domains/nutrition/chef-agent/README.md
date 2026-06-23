# chef-agent

Recipe specialist (port **8106**). Turns a ration or a food request into concrete recipes —
web-searched links (food.ru etc.) and a formatted HTML recipe card. Reads ration/diet context from
the `mcp-nutrition` domain-MCP; searches recipes via the shared `mcp-web` capability; renders cards
via the shared `libs/doc-render`. Invoked by the nutritionist (ration → recipes) over the
orchestrator hub, and routable directly (registered as `chef`).
See [plans/nutrition.md](../../../plans/nutrition.md).

## Status (CH-a)

Scaffold + orchestrator registration + chat fallback. The recipe flow lands branch-by-branch:
- **CH-a — scaffold. DONE.** Manifest endpoint + `chat/ChefChat` fallback (one LLM turn, AGENT.md as
  system prompt). Binds `mcp-nutrition` + `mcp-web` over SSE (future LLM-driven tool selection; the
  deterministic CH-b flow calls them over `/internal/*` HTTP passthroughs). No skills yet.
- **CH-b — recipe flow.** A recipe request / a ration → `mcp-web` recipe search (food.ru etc.) → an
  HTML recipe card (links + web photos) via the `recipe-finder` SKILL. The nutritionist's NU-g ration
  flow **invokes the chef** via the orchestrator hub (ration → recipes) — the gift-recommender→finance
  shape.

## Endpoints

- `POST /agents/chef/intent` (body `NormalizedMessage`) → `IntentResponse` — the orchestrator's entry
  point. CH-a: always the chat fallback; CH-b adds the recipe-flow branch.
- `GET /agents/chef/manifest` → `AgentManifest` — scraped by the orchestrator on startup.

## Env

| Var | Default | Purpose |
|---|---|---|
| `CHEF_AGENT_PORT` | `8106` | HTTP port |
| `MCP_NUTRITION_URL` | `http://mcp-nutrition:8104` | nutrition domain-MCP (ration/diet context) |
| `MCP_WEB_URL` | `http://mcp-web:8098` | shared web/recipe-search capability |
| `MEDIA_SERVICE_URL` | `http://media-service:8088` | stores the rendered recipe-card HTML |
| `CHEF_PUBLIC_MEDIA_BASE_URL` | `http://media-service:8088` | public base for the deliverable link (`<base>/v1/media/{id}`) |
| `CHEF_AGENT_MCP_CLIENT_ENABLED` | `true` | toggle the eager MCP-SSE binding off in dev |
| `CHEF_AGENT_MEMORY_RECALL_K` | `5` | memory recall fan-out |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | llm-gateway (chat) |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` / `MEMORY_SERVICE_URL` | — | shared agent-runtime clients |

## Key classes

- `ChefAgentApplication` — `@Import(AgentRuntimeConfig)` + `@EnableConfigurationProperties`.
- `config/ChefAgentProperties` — the outbound base URLs (`chef-agent.*`).
- `config/OutboundHttpConfig` — one `clone()`d `WebClient` per dependency; the `profile/notifier/memory`
  qualified beans back the shared runtime clients.
- `config/RenderConfig` — the shared `DocRenderer` bean (lib default `DocTheme`), for the CH-b cards.
- `chat/ChefChat` — the chat fallback (one LLM turn, AGENT.md as system prompt).
- `web/IntentController` — `POST /intent` (CH-a: chat fallback; CH-b: recipe flow).
- `web/ManifestController` — `GET /manifest`.

## AGENT.md

`name: chef`, binds `mcp-nutrition` + `mcp-web`, `skills: []` (until CH-b adds `recipe-finder`).
⚠️ Respects the diet profile's restrictions and the infant/medical-safety posture; generated step
photos are out of scope (deferred to the image-gen line).
