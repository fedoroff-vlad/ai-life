# mcp-wardrobe

MCP server: source-of-truth wardrobe CRUD over the `wardrobe.*` schema (catalogued
garments + the owner's per-person style profile). The catalogue / "analyse me" / capsule
flows live in `stylist-agent`; this MCP just persists what the agent extracts. See
[plans/stylist.md](../../../plans/stylist.md).

## Tools (MCP)

- `add_item(householdId, ownerId?, name, category?, colour?, material?, pattern?, season?,
  formality?, imageMediaId?)` — add a garment to the catalogue. Only `householdId` + `name`
  are required; the descriptive fields are filled by the catalogue flow's vision extract
  when present. `category` ∈ top|bottom|outerwear|shoes|accessory|… (free text, not a lookup).
  `imageMediaId` links the media-service photo object. `ownerId` null = household-shared.
- `list_items(householdId, category?)` — garments in a household, newest first; `category`
  filters, omit for the whole wardrobe.
- `update_item(id, name?, category?, colour?, material?, pattern?, season?, formality?,
  imageMediaId?)` — partial content edit (non-null fields only); corrects a misclassified
  garment but cannot clear a set field. household/createdAt immutable.
- `delete_item(id)` — delete and return the deleted row (so the agent can confirm/undo).
  Throws on unknown id. Confirming the destructive action is the agent layer's job.
- `set_style_profile(householdId, ownerId?, personType?, bodyShape?, colourType?,
  suitableFabrics?, heightCm?, weightKg?, measurements?, notes?, imageMediaId?)` — upsert the
  "analyse me" profile, keyed on (householdId, ownerId); null ownerId = household-default.
  Full set (every field overwrites — the analyse-me flow recomputes the whole profile).
  `suitableFabrics` / `measurements` are free-form JSON.
- `get_style_profile(householdId, ownerId?)` — the person's profile, or null if unset.

Scope rule: every tool takes a `householdId` and reads/writes only within that household.
Per-user privacy (private items filtered by `owner_id`) is the agent layer's job — this MCP
is intentionally low-level.

## Internal REST passthroughs

Non-MCP, no LLM tax — for an agent that already has a concrete input and just needs to persist
it (the MCP/SSE transport can't be MockWebServer'd, so deterministic calls use HTTP).

- `POST /internal/item` (body `AddItemInput`) → `WardrobeItemDto` | 400 — adds a garment,
  delegating to the `add_item` tool (required-field guards apply). Used by stylist-agent's
  wardrobe-catalogue flow after it extracts the garment from a photo caption (ST-c). Mirrors
  mcp-finance's `/internal/transaction`.
- `POST /internal/profile` (body `SetStyleProfileInput`) → `StyleProfileDto` | 400 — upserts the
  person's style profile, delegating to the `set_style_profile` tool ((household,owner) keying
  applies). Used by stylist-agent's "analyse me" flow (ST-d).

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_WARDROBE_PORT` | `8101` | HTTP port |
| `MCP_WARDROBE_DB_URL` | `jdbc:postgresql://localhost:5432/ailife` | Postgres |
| `MCP_WARDROBE_DB_USER` / `MCP_WARDROBE_DB_PASSWORD` | `ailife` | DB credentials |

## Key classes

- `McpWardrobeApplication`.
- `domain/WardrobeItem` + `WardrobeItemRepository` — JPA over `wardrobe.wardrobe_item`
  (list by household, optionally filtered by category, newest first).
- `domain/StyleProfile` + `StyleProfileRepository` — JPA over `wardrobe.style_profile`;
  `findForOwner` resolves the (household, owner) profile (null owner = household-default,
  native-SQL `CAST` for the NULL-safe bind — same pgjdbc workaround as mcp-tasks). The
  `suitable_fabrics` / `measurements` jsonb columns map to `JsonNode`.
- `tools/WardrobeMcpTools` — six `@Tool` methods: garment CRUD (`add_item`, `list_items`,
  `update_item`, `delete_item`) + style profile upsert/read (`set_style_profile`,
  `get_style_profile`). The only invariants enforced here are the household scope and
  required-field checks; everything else relies on DB constraints.
- `tools/ToolsConfig` — `MethodToolCallbackProvider`.
- `web/InternalItemController` — `POST /internal/item`, delegates to `add_item` (400 on bad input).
- `web/InternalProfileController` — `POST /internal/profile`, delegates to `set_style_profile` (400 on bad input).

## Schema

- [040-wardrobe.yml](../../../infra/liquibase/features/040-wardrobe.yml) —
  `wardrobe.wardrobe_item` (garment: name + category/colour/material/pattern/season/
  formality + `image_media_id`) + `wardrobe.style_profile` (one per person via the unique
  `(household_id, owner_id)` index: person/colour type, body shape, suitable fabrics,
  height/weight, measurements, `image_media_id`). `image_media_id` carries no cross-schema
  FK (media-service owns blob lifecycle), mirroring `task_item.calendar_event_uid`.
