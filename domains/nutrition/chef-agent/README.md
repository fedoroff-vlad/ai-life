# chef-agent

Recipe specialist (port **8106**). Turns a ration or a food request into concrete recipes —
web-searched links (food.ru etc.) and a formatted HTML recipe card. Reads ration/diet context from
the `mcp-nutrition` domain-MCP; searches recipes via the shared `mcp-web` capability; renders cards
via the shared `libs/doc-render`. Invoked by the nutritionist (ration → recipes) over the
orchestrator hub, and routable directly (registered as `chef`).
See [plans/nutrition.md](../../../plans/nutrition.md).

## Status (CH-b)

Scaffold + orchestrator registration + chat fallback (CH-a) + the **recipe flow** (CH-b1, direct) +
the **ration → recipes hub action** (CH-b2):
- **CH-a — scaffold. DONE.** Manifest endpoint + `chat/ChefChat` fallback (one LLM turn, AGENT.md as
  system prompt). Binds `mcp-nutrition` + `mcp-web` over SSE (future LLM-driven tool selection; the
  deterministic flow calls them over `/internal/*` HTTP passthroughs).
- **CH-b1 — recipe flow (direct). DONE.** A recipe-cue message ("рецепт", "что приготовить") →
  `flow/RecipeFinder`: search recipes via `mcp-web` (`/internal/search`, biased to recipe sources) →
  one LLM synthesis via the `recipe-finder` SKILL → render an HTML **recipe card** (the synthesized
  text as sections + the **real recipe links from the search hits**, never LLM-invented URLs) via the
  shared `libs/doc-render` → store in media-service → reply with a link. Empty search → the skill
  falls back to a couple of simple dishes (no links). Token economy is structural (search = HTTP,
  only the synthesis hits the LLM).
- **CH-b2 — ration → recipes over the hub. DONE.** `web/ActionController` exposes
  `POST /agents/chef/actions/recommend_recipes` — the orchestrator forwards it when the nutritionist's
  NU-g ration flow **invokes the chef** (ration → recipes, the gift-recommender→finance shape). It
  reads `args.request` (the ration text), runs the shared `RecipeFinder.recommend` core, and returns
  `{link, summary}`. Always an `AgentActionResult` (never an HTTP error).

## Endpoints

- `POST /agents/chef/intent` (body `NormalizedMessage`) → `IntentResponse` — the orchestrator's entry
  point. A recipe-cue message → the recipe flow; otherwise the chat fallback.
- `POST /agents/chef/actions/{action}` (body `AgentActionRequest`) → `AgentActionResult` — the
  inter-agent hub entry. `recommend_recipes` (args `{request}`) → a recipe card `{link, summary}`;
  invoked by the nutritionist's NU-g over the orchestrator (ration → recipes).
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
- `flow/RecipeFinder` — the recipe flow: `mcp-web` recipe search → one LLM synthesis via the
  `recipe-finder` SKILL → render an HTML recipe card (text + the real recipe links) via
  `libs/doc-render` → store in media-service. The `recommend(household, user, request)` core serves
  both the direct intent path and the inter-agent action; `findRecipes` wraps it for the intent path.
- `web/ActionController` — `POST /agents/chef/actions/recommend_recipes` (the hub action the
  nutritionist invokes): `args.request` → `RecipeFinder.recommend` → `AgentActionResult{link, summary}`.
- `http/WebSearchClient` — `POST /internal/search` on mcp-web (recipe search).
- `MediaStoreClient` (shared, `libs/agent-runtime`) — multipart `POST /v1/media` (store the rendered card); `@Bean` (source `chef`) wired in `config/OutboundHttpConfig`.
- `web/IntentController` — `POST /intent` (recipe cue → recipe flow; else chat).
- `web/ManifestController` — `GET /manifest`.

## Skills

- `recipe-finder` (`domains/nutrition/skills/recipe-finder/SKILL.md`) — turns a recipe request + the
  web search hits into a short recipe card (which dishes to cook + how), grounded in the hits, never
  inventing URLs (the agent renders the real links).

## AGENT.md

`name: chef`, binds `mcp-nutrition` + `mcp-web`, `skills: recipe-finder`.
⚠️ Respects the diet profile's restrictions and the infant/medical-safety posture; generated step
photos are out of scope (deferred to the image-gen line).
