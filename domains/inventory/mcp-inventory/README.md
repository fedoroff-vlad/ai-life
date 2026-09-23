# mcp-inventory

Inventory **domain-MCP** — source-of-truth store over the `inventory.*` schema: storage zones
(кладовка / гараж / дача), QR-labelled containers (boxes / shelves / bins) and the photographed
items inside them. Answers *"what is in this container"* (by id, or by the `qrToken` printed on its
label) and *"where does this thing live"* (free-text search returning the item **with its place**).
Plan: [inventory.md](../../../plans/inventory.md) §IN-a.

**Status (IN-a):** store + search only. The photo capture, the vision caption that names an item,
the QR rendering/decoding and the container card live in `inventory-agent` (IN-c…IN-g) — not here.
No agent binds this yet.

Two invariants this module owns:
- **`qrToken` is the printed identity** — minted once at creation, never derived from `code` or
  `label` and never re-minted. A rename, a zone move or a status change therefore leaves a label
  already stuck on a box valid. (`updatable = false` on the column backs this.)
- **Location is always a container** — an item sits in exactly one container; loose things get a
  per-zone `loose` pseudo-container rather than a nullable `container_id` beside a parallel
  `zone_id`. One location path, one search query.

## Port: `8127` (`MCP_INVENTORY_PORT`)

## MCP tools

| tool | args | returns | purpose |
|------|------|---------|---------|
| `saveZone` | `SaveZoneInput` | `StorageZoneDto` | create/rename a storage zone. **Upserted on `(householdId, lower(name))`** — the agent resolves a zone by the spoken name, so "в кладовку" twice is one кладовка. `labelColour` is the colour of the label *stock* (the thermal printer is black-only). |
| `listZones` | `householdId` | `List<StorageZoneDto>` | a household's zones, by name. |
| `saveContainer` | `SaveContainerInput` | `ContainerDto` | create (null `id`: mints `qrToken`, defaults `code` to the next `B-NN`) or update in place (non-null `id`: `qrToken` preserved). `kind` defaults `box`, `status` `open` (`open\|packed\|in_transit\|unpacked`). Leaving `open` stamps `closedAt`. |
| `getContainer` | `id` | `ContainerViewDto` | the container + its zone + its contents (the card shape). `null` when absent. |
| `getContainerByToken` | `qrToken` | `ContainerViewDto` | **what a scanned label resolves to.** `null` when the token is unknown. |
| `listContainers` | `householdId`, `zoneId?`, `status?`, `limit?` | `List<ContainerDto>` | newest first; default limit 50, max 200. |
| `saveItem` | `SaveItemInput` | `ItemDto` | put one photographed thing in a container (append-only). `containerId` + `mediaId` required — the photo IS the record. `qty` defaults 1. |
| `listItems` | `containerId` | `List<ItemDto>` | contents in insertion order (the order things went into the box). |
| `deleteItem` | `id` | `boolean` | `false` when there was no such item. |
| `searchItems` | `householdId`, `query`, `limit?` | `List<ItemLocationDto>` | "где лежит X": trigram/ILIKE over item title + description, each hit carrying **its container and zone**. Ranked by similarity, then recency. Household scope comes from the join to `container` — items carry no household of their own. |

## HTTP passthrough

Every tool is mirrored under `/internal` for deterministic agent→MCP calls (the MCP/SSE binding
isn't MockWebServer-testable). Base: `/internal`.

| method | path | body / params | returns |
|--------|------|---------------|---------|
| POST | `/zones` | `SaveZoneInput` | `StorageZoneDto` (400 on a missing required field) |
| GET | `/zones` | `householdId` | `List<StorageZoneDto>` |
| POST | `/containers` | `SaveContainerInput` | `ContainerDto` (400 on a missing field / unknown id) |
| GET | `/containers/{id}` | — | `ContainerViewDto` (404 when absent) |
| GET | `/containers/by-token/{qrToken}` | — | `ContainerViewDto` (404 when unknown) |
| GET | `/containers` | `householdId`, `zoneId?`, `status?`, `limit?` | `List<ContainerDto>` |
| POST | `/items` | `SaveItemInput` | `ItemDto` (400 on a missing field / unknown container) |
| GET | `/items` | `containerId` | `List<ItemDto>` |
| DELETE | `/items/{id}` | — | 204, or 404 when there was no such item |
| GET | `/items/search` | `householdId`, `query`, `limit?` | `List<ItemLocationDto>` |

All `/internal/*` traffic is Bearer-guarded centrally by platform-common's shared-secret filter
(`INTERNAL_SHARED_SECRET`, #630 / ADR-0007) — not by this controller.

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_INVENTORY_PORT` | `8127` | HTTP port (MCP/SSE + `/internal` + actuator). |
| `MCP_INVENTORY_DB_URL` | `jdbc:postgresql://localhost:5432/ailife` | Postgres URL. |
| `MCP_INVENTORY_DB_USER` | `ailife` | DB user. |
| `MCP_INVENTORY_DB_PASSWORD` | `ailife` | DB password. |

Schema: `inventory` (created in `infra/postgres/init.sql`), tables from
`infra/liquibase/features/120-inventory.yml`.

## Key classes

- `McpInventoryApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `domain/StorageZoneEntity` + `StorageZoneRepository` — the zone; `findByHouseholdAndName` is the
  case-insensitive upsert key.
- `domain/ContainerEntity` + `ContainerRepository` — the labelled container; `findByQrToken` is the
  scan path, `countByHouseholdId` seeds the next `B-NN` code, `listIn` is the CAST-guarded
  nullable-filter list (pgjdbc can't infer a NULL param's type inside `IS NULL`).
- `domain/ItemEntity` + `ItemRepository` — the photographed thing; `search` joins container to scope
  by household and ranks by `similarity()` over the `gin_trgm_ops` expression index.
- `tools/InventoryMcpTools` — the `@Tool`s above. Mints `qrToken` (16 hex chars — short keeps the
  printed QR sparse, which matters on a small label) and resolves a search hit's place in two
  batched reads rather than per-row lookups.
- `tools/ToolsConfig` — `MethodToolCallbackProvider` exposing the `@Tool`s.
- `web/InternalInventoryController` — the `/internal` passthrough; delegates to the tools so the
  scope/validation rules apply identically.
