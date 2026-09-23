# inventory-agent

Physical-storage specialist (port **8128**) — the household's answer to "где что лежит". Runs a
**packing session**: open a container, send photos, each becomes a named item inside it, close it.
Registered in the orchestrator as `inventory`; owns `mcp-inventory`; binds the shared
`mcp-media-processing` (vision caption). Plan: [plans/inventory.md](../../../plans/inventory.md).

## Status (IN-c + IN-e)

Scaffold + the **packing session** (IN-c) + **"где лежит X"** (IN-e). The QR label and the container
card (IN-d), the scan path (IN-f) and semantic recall for the finder (IN-e2) are later slices; a
message that is neither a packing move nor a lookup falls through to a chat reply.

**The session is the shipped route-lock, not a new mechanism.** Opening a container returns a
`pendingAction`, so the orchestrator routes every following message straight back to this agent's
`/resume` until the box closes. That is what makes a batch of photos work without asking "which box?"
per photo — packing is a batch activity, not a form per item.

- **Open** — "открой коробку «кухня — посуда» в кладовку" → the `box-packer` SKILL extracts
  `action/label/zone/kind/destination` (strict JSON, temperature 0) → the zone is upserted by name
  (so saying "в кладовку" twice is one кладовка) → the container is created with its code + QR token
  → reply + the session envelope. A zone is optional: a nameless or failed zone still opens the box
  (it can be placed later) rather than blocking the session.
- **Add** — while open, every photo goes into that container. The thing is named by the shared vision
  capability (`caption`) so the owner types nothing with their hands full; **a caption the user wrote
  themselves wins over the model**. Captioning soft-fails — an unnamed item is still recorded with its
  photo, because the photo *is* the record and losing it would be the real failure. A failed save
  keeps the session open (the owner is mid-batch; losing the lock costs more than the item).
- **Close** — "закрой коробку" → the container becomes `packed`, the reply states the item count, and
  the `pendingAction` goes null, clearing the lock.
- **A photo with no open session** never guesses a container — it asks. A misfiled thing is found in
  the wrong box months later, so one extra question is the cheaper error. This is a deterministic
  pre-check in `IntentController`: a photo is unambiguous, so it costs no LLM turn.
- **Find (IN-e)** — "где лежат ёлочные игрушки" → the `item-finder` SKILL distils the *thing* out of
  the question (the stored names came from photo captions, so the interrogatives would only dilute
  the match) → `searchItems` → the reply names the **place**: container code, its label, its zone.
  A container with no zone yet is still named ("зона не указана") rather than failing. Nothing found
  says so plainly and never invents a location. Scope is the envelope household; semantic recall
  (IN-e2) and the personal ∪ shared widening come later.

## Endpoints

| method | path | purpose |
|--------|------|---------|
| POST | `/agents/inventory/intent` | orchestrator entry. Photo → "which container?" (deterministic pre-check); otherwise `InventoryIntentRouter` classifies the text → the packing flow, the finder, or a chat reply. |
| POST | `/agents/inventory/resume` | the route-locked turn while a box is open: a photo becomes an item, "закрой коробку" ends the session. Dispatches on `pendingAction.flow` = `box-packing`. |
| GET | `/agents/inventory/manifest` | the manifest the orchestrator scrapes on startup. |

## Skills

- **`box-packer`** (`domains/inventory/skills/box-packer/SKILL.md`) — strict-JSON extract of the
  packing move (`open`/`close`) plus the container's label, zone, kind and move destination.
- **`item-finder`** (`domains/inventory/skills/item-finder/SKILL.md`) — strict-JSON distil of the
  search phrase out of a "где лежит X" question.

## Env

| Var | Default | Purpose |
|---|---|---|
| `INVENTORY_AGENT_PORT` | `8128` | HTTP port. |
| `MCP_INVENTORY_URL` | `http://mcp-inventory:8127` | inventory domain-MCP (its data — `/internal/{zones,containers,items}`). |
| `MCP_MEDIA_PROCESSING_URL` | `http://mcp-media-processing:8097` | shared media capability (`/internal/caption`). |
| `INVENTORY_AGENT_MCP_CLIENT_ENABLED` | `true` | bind mcp-inventory + mcp-media-processing over MCP/SSE (toggle off in degraded envs). |
| `INVENTORY_AGENT_MEMORY_RECALL_K` | `5` | memory-recall fan-in (shared agent-runtime). |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | llm-gateway for the packing-move extract. |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` / `MEMORY_SERVICE_URL` | internal | shared agent-runtime clients. |

## Key classes

- `InventoryAgentApplication` — `@SpringBootApplication` + `@Import(AgentRuntimeConfig)`.
- `config/InventoryAgentProperties` (`inventory-agent.*` base URLs) + `config/OutboundHttpConfig`
  (`mcpInventoryWebClient` + `mcpMediaProcessingWebClient` + the opt-in shared `CaptionClient` bean).
- `http/InventoryClient` — the `mcp-inventory` `/internal` passthroughs (`saveZone` / `saveContainer`
  / `getContainer` / `saveItem` / `searchItems`). Mirrors docs-agent's `DocumentClient`.
- `find/ItemFinder` — "где лежит X" (IN-e): query distil (`item-finder` SKILL, temperature 0, falling
  back to the raw text when the model returns nothing usable) → `searchItems` → a reply that names the
  place. Shows the best hit plus up to four more.
- `pack/BoxPacker` — the session: `start` (open) · `resume` (photo → caption → `saveItem`, or close) ·
  `photoWithoutSession` (ask). Owns the `box-packing` pendingAction envelope
  (`{flow, containerId, code, label, count}`) — re-issued each turn to keep the lock, null to end it.
  Attaches a payload-free `IntentResponse.trace` on each write (#485 / G2).
- `intent/InventoryIntentRouter` — a thin binding over the shared `agent-runtime` `SkillRouter` (#475);
  the dispatch map holds `box-packer` + `item-finder`, and each SKILL.md `description` is the routing
  SSOT. Photos never reach it (locked → `/resume`, unlocked → the controller's pre-check).
- `chat/InventoryChat` — the open-question fallback (AGENT.md system prompt).
- `web/IntentController` · `web/ResumeController` · `web/ManifestController`.
