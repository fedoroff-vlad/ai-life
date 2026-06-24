# creator-agent

Creator / content-factory domain agent (port **8109**). Monitors trends for a creator's niche and
proposes fresh trends (with links), post ideas, ready drafts (title/text/CTA/hashtags), and
per-platform format recommendations, keeping a per-person content track. Owns the `mcp-creator`
domain-MCP; binds the shared `mcp-web` (web trend search). Routes via the orchestrator (registered as
`creator`). The gather → synthesize shape is the `researcher` agent, fanned out to multiple sources.
See [plans/creator.md](../../../plans/creator.md).

## Status (CR-b — scaffold)

Manifest endpoint + the `chat/CreatorChat` fallback (one LLM turn, AGENT.md as system prompt). Real
flows replace the fallback branch-by-branch as they land:
- **CR-c — creator profile.** A typed profile cue → one LLM extract via a `creator-profiler` SKILL →
  upsert the per-person content track via `mcp-creator`.
- **CR-d — trend → ideas → drafts (the headline).** Gather `{web, youtube, reddit, feeds}` for the
  niche on the shared `Coordinator` (cheap-first — all gather is HTTP/API, one LLM synthesis) → 3–5
  trends + 10 ideas + 2–3 drafts + per-platform format recs → render an HTML board via
  `libs/doc-render` → store in media-service → reply with a link.
- **CR-g — `draft_greeting` action.** Invoked over the orchestrator hub by the calendar birthday
  wake → drafts a greeting → notifier delivers (closes the Stage-4 chain).

## Endpoints

- `POST /agents/creator/intent` (body `NormalizedMessage`) → `IntentResponse` — the orchestrator's
  entry point. CR-b routes everything to the chat fallback.
- `GET /agents/creator/manifest` → `AgentManifest` — scraped by the orchestrator on startup.

## Env

| Var | Default | Purpose |
|---|---|---|
| `CREATOR_AGENT_PORT` | `8109` | HTTP port |
| `MCP_CREATOR_URL` | `http://mcp-creator:8108` | creator domain-MCP (its data) |
| `MCP_WEB_URL` | `http://mcp-web:8098` | shared web/trend-search capability |
| `CREATOR_AGENT_MCP_CLIENT_ENABLED` | `true` | toggle the eager MCP-SSE binding off in dev |
| `CREATOR_AGENT_MEMORY_RECALL_K` | `5` | memory recall fan-out |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | llm-gateway (chat) |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` / `MEMORY_SERVICE_URL` | — | shared agent-runtime clients |

## Key classes

- `CreatorAgentApplication` — `@Import(AgentRuntimeConfig)` + `@EnableConfigurationProperties`.
- `config/CreatorAgentProperties` — the outbound base URLs (`creator-agent.*`).
- `config/OutboundHttpConfig` — one `clone()`d `WebClient` per dependency; the `profile/notifier/memory`
  qualified beans back the shared runtime clients, `mcpCreator` + `mcpWeb` the future flows.
- `chat/CreatorChat` — the chat fallback (one LLM turn, AGENT.md as system prompt).
- `web/IntentController` — `POST /agents/creator/intent` (CR-b → chat fallback).
- `web/ManifestController` — `GET /agents/creator/manifest`.

## AGENT.md

`name: creator`, binds `mcp-creator` + `mcp-web`, `skills: []` (until CR-c). Guardrails: respect
platform rules, no clickbait, friendly-expert tone, never invent a trend or a source link.
