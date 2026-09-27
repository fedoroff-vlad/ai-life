# inventory-agent

Physical-storage specialist (port **8128**) — the household's answer to "где что лежит". Runs a
**packing session**: open a container, send photos, each becomes a named item inside it, close it.
Registered in the orchestrator as `inventory`; owns `mcp-inventory`; binds the shared
`mcp-media-processing` (vision caption) and stores its deliverables in `media-service`. Plan:
[plans/inventory.md](../../../plans/inventory.md).

## Status (IN-c + IN-d + IN-e + IN-f + IN-g1 + IN-g2 + IN-g3 + goldens)

Scaffold + the **packing session** (IN-c) + the **QR label and container card** (IN-d) +
**"где лежит X"** (IN-e) + the **scan path** (IN-f: deep-link + photographed label, the latter read at
the gateway's front door) + **correcting a container** (IN-g1: zone move / status / rename) + **taking a
thing out of its box** (IN-g2) + **appending to a closed box** (IN-g3: a captioned photo filed without a
session). Semantic recall for the finder (IN-e2) is a later slice; a message that is none of the above
falls through to a chat reply.

All LLM seams are covered by opt-in goldens against a real model — `GoldenInventoryRoutingTest`
(the six trigger-less skills, including the close `box-card`/`item-finder` pair and removal vs search) +
`GoldenBoxPackerTest` (open/close extract) + `GoldenItemFinderTest` (query distil) +
`GoldenBoxLabelerTest` (container distil) + `GoldenContainerEditorTest` (the edit's pick + fields) +
`GoldenBoxAppendTest` (the caption split: the box vs the thing). See [plans/inventory.md](../../../plans/inventory.md)
§Golden tests for what each one catches; run with
`scripts/golden.sh -pl domains/inventory/inventory-agent -Dtest=<class>`.

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
  the `pendingAction` goes null, clearing the lock. Closing is also when the **two deliverables**
  (IN-d) are issued, because that is the moment the owner has a taped-up box in front of them: the
  **QR label** to print and stick on it, and the box's **card** — what a scan of that label will show.
  Both soft-fail: the container is already `packed` in the store, so a media hiccup costs a link, not
  the close.
- **Label (IN-d)** — "распечатай этикетку на B-07" → a QR PNG of the
  `t.me/<bot>?start=box_<qr_token>` deep link, stored in media-service and handed over as a link. It
  encodes **only an id**, so repacking, renaming or moving a box never invalidates a sticker already
  on it; the same token always renders byte-identical PNG. Rendering lives in the agent
  (`BoxLabelImage`) because encoding is a pure function — the *decode* half is the capability tool
  (`mcp-media-processing.decode_qr`), since that one reads bytes out of media-service.
- **Card (IN-d)** — "что в коробке B-07" → a `libs/doc-render` board (code · label · zone · status ·
  the photo gallery · the item list) published through the shared `DeliverablePublisher` seam. The
  label and the card stay separate artifacts on purpose: the sticker's whole job is to carry an id,
  and the card is the page a scan opens.
- **Append to a closed box (IN-g3)** — "добавь в B-07 ёлочную гирлянду" + a photo → the `box-append`
  SKILL splits the caption into **which** container and **what** the thing is, the shared
  `ContainerResolver` matches the box (code first, label second), and the item is saved. Deliberately
  **stateless**: no session is opened for one thing, so the conversation is never route-locked by a
  charger found behind the sofa. The owner's words name the thing; a caption that only points at a box
  ("это в B-07") lets vision name it, soft-failing to an unnamed item — the photo *is* the record.
- **A photo with no open session** never guesses a container. With a caption it takes the append path
  above; **bare**, or when the named box matches nothing, it asks. A misfiled thing is found in the wrong
  box months later, so one extra question is the cheaper error. The split is a deterministic pre-check in
  `IntentController`: a bare photo costs no LLM turn at all.
- **Scan (IN-f1)** — a QR label opened with a phone camera lands on the gateway as
  `/start box_<token>`, which dispatches it **deterministically through the hub** (`/v1/agents/invoke`
  → `show_container`) instead of routing it as a message: a sticker carries no sentence, and a
  classifier guess between the camera and "что в этой коробке" is exactly the error this domain
  exists to prevent. The lookup is **by token alone** — no household match — so whoever is holding the
  box gets an answer (prior art: scanning must work for a non-user); the token is unguessable and
  printed on a physical box, and that possession *is* the authorization. First contact is still
  allowlist-gated (#627): a sticker authorizes seeing *that container*, never creating an account. An
  unknown token is answered, never resolved to a nearby box, and a render hiccup still names the
  container and its contents count.
- **Correct a box (IN-g1)** — "коробка B-07 теперь на даче" · "распаковал B-07" · "переименуй B-12 в
  «зимние вещи»" → the `box-editor` SKILL picks the box + what changed, the agent asks to confirm
  (да/нет buttons), and only an affirmative patches it. Zone moves upsert the zone **by name** under the
  *container's* household. Only the named fields are sent, so **`code` and `qrToken` never change** — a
  sticker already on the box keeps resolving after a rename, a move or an unpack. A `status` the model
  invented is dropped, not written. Rides the shared ADR-0004 `PickConfirmActRunner`.
- **Remove a thing (IN-g2)** — "убери из коробки старый чайник" → the `item-finder` phrase distil finds
  the candidates (the **search**, not the whole store — a household after a move holds hundreds of
  things), the `item-remover` SKILL picks which one, and the confirm names the thing *and its box* before
  anything is deleted. A declined removal keeps it; an item already gone is not reported as a failure.
  Deletion is the domain's one irreversible act — the row carries the only reference to the photo.
- **Find (IN-e)** — "где лежат ёлочные игрушки" → the `item-finder` SKILL distils the *thing* out of
  the question (the stored names came from photo captions, so the interrogatives would only dilute
  the match) → `searchItems` → the reply names the **place**: container code, its label, its zone.
  A container with no zone yet is still named ("зона не указана") rather than failing. Nothing found
  says so plainly and never invents a location. Scope is the envelope household; semantic recall
  (IN-e2) and the personal ∪ shared widening come later.

## Endpoints

| method | path | purpose |
|--------|------|---------|
| POST | `/agents/inventory/intent` | orchestrator entry. Captioned photo → filed into the box the caption names (IN-g3); bare photo → "which container?" — both a deterministic pre-check. Otherwise `InventoryIntentRouter` classifies the text → the packing flow, the finder, a container's label/card, a container correction, a removal, or a chat reply. |
| POST | `/agents/inventory/resume` | the route-locked turn. Dispatches on `pendingAction.flow`: `box-packing` (a photo becomes an item, "закрой коробку" ends the session), `box-edit-confirm` (да applies a container correction, IN-g1) and `item-remove-confirm` (да deletes a thing, IN-g2). |
| POST | `/agents/inventory/actions/{action}` | inter-agent action envelope (Stage 4 / C1). `show_container` (args `{qrToken}`) is the **scan** path (IN-f1): a printed label's token → its card. An unknown token → `ok=false` carrying the user-facing text, relayed verbatim. |
| GET | `/agents/inventory/manifest` | the manifest the orchestrator scrapes on startup. |

## Skills

- **`box-packer`** (`domains/inventory/skills/box-packer/SKILL.md`) — strict-JSON extract of the
  packing move (`open`/`close`) plus the container's label, zone, kind and move destination.
- **`item-finder`** (`domains/inventory/skills/item-finder/SKILL.md`) — strict-JSON distil of the
  search phrase out of a "где лежит X" question.
- **`box-label`** (`domains/inventory/skills/box-label/SKILL.md`) — strict-JSON distil of *which*
  container a printable label is wanted for.
- **`box-card`** (`domains/inventory/skills/box-card/SKILL.md`) — strict-JSON distil of *which*
  container's contents to show. A question about a **thing** rather than a box is `item-finder`.
- **`box-editor`** (`domains/inventory/skills/box-editor/SKILL.md`) — strict-JSON pick of a container
  plus what changed about it (`zone` / `status` / `newLabel`). A *correction to the box*, not a question
  about its contents (`box-card`) and not a new box (`box-packer`).
- **`item-remover`** (`domains/inventory/skills/item-remover/SKILL.md`) — strict-JSON pick of *which
  packed thing* to take out, from the candidates the search returned.
- **`box-append`** (`domains/inventory/skills/box-append/SKILL.md`) — strict-JSON split of a photo's
  caption into the **container** it names and the **thing** itself. Never routed from text (it needs the
  photo): the controller's pre-check invokes it directly, so it is absent from the router's dispatch map.

## Env

| Var | Default | Purpose |
|---|---|---|
| `INVENTORY_AGENT_PORT` | `8128` | HTTP port. |
| `MCP_INVENTORY_URL` | `http://mcp-inventory:8127` | inventory domain-MCP (its data — `/internal/{zones,containers,items}`). |
| `MCP_MEDIA_PROCESSING_URL` | `http://mcp-media-processing:8097` | shared media capability (`/internal/caption`). |
| `MEDIA_SERVICE_URL` | `http://media-service:8088` | stores the QR label PNG + the rendered container card (IN-d). |
| `INVENTORY_PUBLIC_MEDIA_BASE_URL` | `MEDIA_SERVICE_URL` | externally-reachable base the label/card links are built from. |
| `GATEWAY_TELEGRAM_BOT_USERNAME` | `ai_life_bot` | the bot the printed label's `?start=box_<token>` deep link points at. |
| `INVENTORY_AGENT_MCP_CLIENT_ENABLED` | `true` | bind mcp-inventory + mcp-media-processing over MCP/SSE (toggle off in degraded envs). |
| `INVENTORY_AGENT_MEMORY_RECALL_K` | `5` | memory-recall fan-in (shared agent-runtime). |
| `LLM_GATEWAY_URL` | `http://llm-gateway:8081` | llm-gateway for the packing-move extract. |
| `PROFILE_SERVICE_URL` / `NOTIFIER_URL` / `MEMORY_SERVICE_URL` | internal | shared agent-runtime clients. |

## Key classes

- `InventoryAgentApplication` — `@SpringBootApplication` + `@Import(AgentRuntimeConfig)`.
- `config/InventoryAgentProperties` (`inventory-agent.*` base URLs) + `config/OutboundHttpConfig`
  (`mcpInventoryWebClient` + `mcpMediaProcessingWebClient` + `mediaServiceWebClient` + the opt-in
  shared `CaptionClient` / `MediaStoreClient` / `DeliverablePublisher` beans).
- `http/InventoryClient` — the `mcp-inventory` `/internal` passthroughs (`saveZone` / `saveContainer`
  / `getContainer` / `getContainerByToken` / `listContainers` / `saveItem` / `searchItems`). Mirrors
  docs-agent's `DocumentClient`. `getContainerByToken` is empty (not an error) on an unknown token —
  a sticker outlives the row it points at.
- `label/BoxLabelImage` — pure: the `t.me/<bot>?start=box_<token>` payload + its QR PNG (ZXing,
  deterministic, 464 px = 58 mm at 203 dpi). Lifts to `libs/qr` on a second consumer.
- `container/ContainerResolver` — "which box did the owner mean": the household's containers matched
  against a phrase, **code first, label second** (the code is the printed identity a person reads off the
  box; a nickname must not outrank it). Lifted here on its second consumer (the deliverables + the
  append). No fuzzy fallback — an unresolved box is answered, never guessed.
- `label/BoxLabeler` — the two deliverables (IN-d): `deliver` (both, on close, each soft-failing),
  `label` / `card` (the on-demand skills). Resolves "коробка B-07" through `ContainerResolver`; an
  unresolvable ask is answered, never guessed.
- `scan/BoxScanner` — the scan path (IN-f1): a label's token → the container view → the same card the
  chat flow renders (reusing `BoxLabeler.cardUrl`, which takes ids because a scan has no message behind
  it). Empty = no such container (the caller answers it); a render failure still answers in text.
- `edit/ContainerEditor` — container corrections (IN-g1): a `TargetedActionFlow` + `CandidateView` +
  `Phrasing` adapter on the shared `PickConfirmActRunner` (ADR-0004). `missing`/`readyToAct` refuse an
  edit with nothing to change; `act` patches only the named fields and resolves a new zone by name from
  the container's own household. Flow discriminator `box-edit-confirm`.
- `find/ItemQuery` — "the thing out of the sentence": the `item-finder` distil, shared by the finder and
  the remover (lifted here on its second consumer). Never fails — a useless model reply degrades to the
  raw text, because a diluted search still beats no search.
- `find/ItemFinder` — "где лежит X" (IN-e): `ItemQuery` → `searchItems` → a reply that names the place.
  Shows the best hit plus up to four more.
- `edit/ItemRemover` — removing a packed thing (IN-g2): a delete flow on the shared
  `PickConfirmActRunner` (so the wording comes from `NounPhrasing` via `nouns()`); candidates are the
  search over the distilled phrase, the label carries the thing *and* its box code, `act` deletes by id.
  Flow discriminator `item-remove-confirm`.
- `pack/BoxPacker` — the session: `start` (open) · `resume` (photo → caption → `saveItem`, or close) ·
  `photoWithoutSession` (ask). Owns the `box-packing` pendingAction envelope
  (`{flow, containerId, code, label, count}`) — re-issued each turn to keep the lock, null to end it.
  Attaches a payload-free `IntentResponse.trace` on each write (#485 / G2).
- `pack/BoxAppender` — appending to a closed box (IN-g3): the `box-append` split → `ContainerResolver` →
  `saveItem`, stateless (no `pendingAction`, so a single append never route-locks the conversation). A
  caption that names no box, or one that matches nothing, is answered — never filed somewhere plausible.
- `intent/InventoryIntentRouter` — a thin binding over the shared `agent-runtime` `SkillRouter` (#475);
  the dispatch map holds `box-packer` + `item-finder` + `box-label` + `box-card` + `box-editor` +
  `item-remover`, and each SKILL.md `description` is the routing SSOT. `box-append` is deliberately absent
  (it needs a photo, so the map's key set excludes it from the route set). Photos never reach the router
  (locked → `/resume`, unlocked → the controller's pre-check).
- `chat/InventoryChat` — the open-question fallback (AGENT.md system prompt).
- `web/IntentController` · `web/ResumeController` · `web/ManifestController` · `web/ActionController`
  (the C1 envelope on the shared `AgentActionController` base; registers `show_container`).
