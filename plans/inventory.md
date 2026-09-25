# inventory — physical storage & belongings agent

Authority file for the **inventory-agent** + **mcp-inventory** domain (owner idea, 2026-09-22).
Spec **and** the shipped record of each slice — per-slice state lives in the `## PR slices` headings
below (in-flight status: [STATUS.md](STATUS.md)). The domain was flagged before any code per
[CLAUDE.md](../CLAUDE.md) §Work style ("new layer → flag BEFORE coding"): it is a **new domain** (14th)
+ **one new capability tool**.

## What it is
"Где что лежит" for physical things. The owner photographs items as they go into a box / onto a
shelf; each **container** keeps its photo inventory and gets a **QR label**; scanning (or
photographing) that label returns a rendered card — *what is inside* + *where it stands*. Later,
"где лежат ёлочные игрушки" answers with the container, the zone, and the card.

Driver: an imminent **move** (packing boxes — the classic "забыл, что куда положил"). Scope is
deliberately wider: the move is the first use case of a **permanent storage organiser** (кладовка /
гараж / дача / балкон / антресоль), which is what the owner actually asked for. Move-specific state
(`status`, `destination`) is two nullable columns on the container, not a separate feature.

## Why a new domain (and not docs / stylist / lists)
| Candidate | Why it is not the home |
|---|---|
| **docs-agent** | Archives *paper*: the record is the OCR **text**, the query is textual ("найди договор"). Here the photo **is** the record (nothing to OCR), the query is **spatial** ("где"), and the unit is a physical container with a printed identity. |
| **stylist / mcp-wardrobe** | A clothes catalogue with style semantics (capsules, style profile), not generic belongings. |
| **notes-agent / lists** | `type=list` notes are text checklists on `memory.note` — no per-item media, no zone→container hierarchy, no stable label identity. |

It **owns a schema** (zone → container → item), so by the repo's own rule (`architecture.md`:
domain-MCP owns a schema, capability-MCP owns none) it is a **domain-MCP + agent**, not a capability.

**Rejected alternative — `mcp-inventory` + skills hung on docs-agent.** Saves one container, but
fuses two unrelated personas into one `AGENT.md` and buries a "where is my stuff" router inside a
document archive; the eventual split would cost more than the saving. Under
[ADR-0006](adr/ADR-0006-runtime-topology-footprint.md) an extra **cold** agent is nearly free — it
joins a cold host-unit (its own `storage-host`, or a slot beside Docs) rather than a standing JVM.

## Doctrine (what is reused — one new tool, flagged)
- **Store:** `mcp-inventory` owns the `inventory` schema — the `mcp-docs` / `mcp-creator` shape
  (`@Tool`s + `/internal/*` passthroughs), tenant-agnostic (writes whatever household it is handed).
- **Blobs stay in media-service** — the agent already receives a `mediaId` per inbound photo; item
  rows reference it, bytes are never re-stored.
- **Item naming is the shared vision capability** — `mcp-media-processing.caption` over
  `/internal/caption` ("что за предмет на фото, 3–5 слов + тип") gives each photo a searchable
  title without the owner typing anything. Bound, not re-embedded (finance / stylist / nutrition
  precedent).
- **QR *decode* is the one new capability tool** — `decode_qr` + `POST /internal/qr` on
  `mcp-media-processing`, an exact twin of the D-b OCR passthrough. Image → structure is that
  capability's remit, so this is a new *tool*, not a new layer.
- **QR *encode* is a pure function → a lib, not an MCP** — ZXing inside the agent renders the PNG,
  stored via the existing `MediaStoreClient`. Same reasoning as
  [`libs/doc-render`](../libs/doc-render/README.md) §"Why a lib (not a capability-MCP)": no external
  resource, no schema → no container, no HTTP hop. Lift to `libs/qr` only on a **second consumer**.
- **The label encodes an id, never the contents** — payload is the existing Telegram deep-link
  shape `https://t.me/<bot>?start=box_<qr_token>` (the literal lives in `libs/contracts`
  `BoxDeepLink`, shared by the renderer and the front door). Consequence (the rule every analogue app
  converged on): **editing a container never invalidates a printed label.** The gateway already
  parses `/start <token>` for family invites (ADR-0001 slice 4b-i), so scanning needs a **prefix
  dispatch** on that existing path, not new plumbing.
- **Packing is a route-lock, not a new dialog engine** — "открой коробку «кухня»" locks the route in
  `conversation-service` (the shipped route-lock / `pendingAction` primitive); every following photo
  lands in that container until "закрой коробку". A batch of photos is therefore N ordinary inbound
  messages, no batching protocol.
- **Edit / delete ride [ADR-0004](adr/ADR-0004-confirm-act-flow.md)** `PickConfirmActRunner` — the
  repo's generic `read candidates → LLM picks → confirm → act` loop; ~30-line adapter, no new flow.
- **Sharing:** [ADR-0002](adr/ADR-0002-sharing-shared-capability.md) — an `InventorySharingPolicy`
  defaults household storage to **shared** (a box in the кладовка is a family asset); personal on an
  explicit cue. Read path unions personal ∪ shared.
- **Semantic search reuses memory-service** — each item seeds an authored note (SB-5 shape,
  `frontmatter={kind:item, refId}`) so "где эта штука для гриля" survives a vocabulary mismatch the
  trigram search misses.
- **The card is a `libs/doc-render` board** — the "красивый шаблон": container header (code + label +
  zone + status), the item photo `gallery`, the item list. No new renderer.

### Modelling decision — location is always a container
A zone holds **containers**, an item lives in **exactly one container**. Loose things (a bike in the
garage) get a per-zone pseudo-container (`kind=loose`, "открытое хранение") rather than a nullable
`container_id` + a parallel `zone_id` on the item. One location path → one search query, one label
story, no "which field wins" ambiguity.

## Data model (`inventory` schema, Liquibase range `120-129`)
- **`inventory.storage_zone`** — `id`, `household_id`, `owner_id`, `name` (кладовка / гараж / дача),
  `kind` (`room|garage|dacha|balcony|closet|other`), `label_colour`, `note`, `created_at`. Upserted
  on `(household_id, lower(name))` — the agent resolves a zone by the spoken name. `label_colour` is
  the colour of the label **stock** the zone's containers print on: a direct-thermal printer prints
  black only, so colour coding is a matter of which roll is loaded, and coloured thermal rolls cost
  about what white ones do. It makes a zone recognisable across a room without scanning anything.
- **`inventory.container`** — `id`, `household_id`, `owner_id`, `zone_id` (FK), `code` (human
  "B-07", unique per household), `label` (the owner's name for it), `kind`
  (`box|shelf|bin|loose`), `qr_token` (opaque, stable, indexed — the printed identity),
  `status` (`open|packed|in_transit|unpacked`), `destination` (target room after the move,
  nullable), `note`, `created_at`, `closed_at`.
- **`inventory.item`** — `id`, `container_id` (FK), `media_id` (media-service), `title`,
  `description`, `tags text[]`, `qty`, `created_at`. GIN `gin_trgm_ops` index over
  `title + description`.

`qr_token` is **never** derived from `code` or the label — it must survive a rename.

## Golden tests — written and green on a real model (IN-goldens)
Per the docs/travel convention each LLM seam has an opt-in `@GoldenLlmTest` (`GOLDEN_LLM`-gated, not in
fast CI) asserting **structure, not wording**. Until this slice every inventory test was a MockWebServer
one — proving the wiring while feeding the parser a JSON answer *written by hand*, so no test could tell
whether a real model routes or extracts correctly. Five classes now cover the five LLM seams, each named
for the defect it catches:

| Class | Seam | The defect it catches |
|---|---|---|
| `GoldenInventoryRoutingTest` | the router over **five** trigger-less skills | the closest pair blurring: "что в коробке B-07" is `box-card` while "где лежит дрель" is `item-finder` — a box the user can name vs a thing they cannot place; and a *correction* to a box ("теперь на даче" → `box-editor`) being mistaken for a question about it |
| `GoldenBoxPackerTest` | `box-packer` extract (`open` / `close`) | the zone leaking into the label (every box named after its shelf), and a missed `close` leaving the conversation route-locked to a taped-up box |
| `GoldenItemFinderTest` | `item-finder` query distil | searching the *question* — item names come from photo captions, so "где"/"лежат" dilute a trigram match against names containing neither |
| `GoldenBoxLabelerTest` | `box-label`/`box-card` container distil | printing a sticker for the **wrong box** — asserted by the resolved container's identity, with two candidates in the store so a mismatch can't pass by luck |
| `GoldenContainerEditorTest` | `box-editor` pick + changed fields (IN-g1) | a correction landing on the **wrong box**, a Russian state the store would drop ("распакована" → nothing changes), or a rename written into `label` instead of `newLabel` |

**Cost + result:** 10 tests, all green on `qwen3:8b` on the CPU-only dev box, each run twice for stability —
~1.5–2 min for the routing class (its prompt carries five SKILL descriptions, so the first prefill needs a
warm-up call) and ~20–45 s each for the four extract/distil classes. These are routing/distil shaped, which is
why they fit the dev box at all; anything generation-heavy waits for the deploy model (that lane is
throughput-gated — [`platform/llm-gateway/README.md`](../platform/llm-gateway/README.md) §Golden tests).
Run with `scripts/golden.sh -pl domains/inventory/inventory-agent -Dtest=<class>`.

**What the lane already caught (why it was worth writing).** `GoldenContainerEditorTest` failed on its
first run, twice in a row — the model picked the *wrong container* for "распаковал коробку с посудой". The
cause was in the SKILL, not the model: its examples showed `{"pick": 2, …}` for that very sentence, so the
model copied the **index from the example** instead of matching the list it was handed. Few-shot index
leakage is invisible to a MockWebServer test (which supplies the JSON the parser reads) and would have
shipped as "the bot unpacks the wrong box". Fixed by giving every example its own candidate list plus an
explicit "the number comes from the list in this message" rule, and by moving the test's fixture off the
examples' own literals — a fixture that reuses them can be passed by copying them.

## PR slices

### IN-a — `mcp-inventory` domain-MCP + `inventory` schema ✅ DONE
**Requirement:** the system SHALL persist zones, containers and photographed items, tenant-agnostically.

Port **8127**. Tools + `/internal/*` twins: `saveZone` / `listZones` / `saveContainer` /
`getContainer` / `getContainerByToken` / `listContainers(zoneId?, status?)` / `saveItem` /
`listItems(containerId)` / `deleteItem` / `searchItems(query, limit)` (pg_trgm over title +
description). Liquibase `120-inventory.yml` (+ the `120-129` row in
[PATTERNS.md](PATTERNS.md) §Numbering). Mirrors `mcp-docs`. `searchItems` returns an
`ItemLocationDto` (item + container + zone), because the question this domain answers is *where*.

- **Scenario: container by token**
  - WHEN `getContainerByToken` is called with a known `qr_token`
  - THEN it returns that container with its zone and its items in insertion order
    (asserted by `McpInventoryIntegrationTest`)
- **Scenario: rename keeps the printed identity**
  - WHEN a container is renamed, moved to another zone and closed
  - THEN its `qr_token` and `code` are unchanged and `getContainerByToken` still resolves
    (asserted by `McpInventoryIntegrationTest`)
- **Scenario: item search returns the place**
  - WHEN `searchItems("гирлянда")` runs over items titled "ёлочная гирлянда"
  - THEN the hit carries its container code/label and its zone, and another household's identical
    item is not in the result (asserted by `McpInventoryIntegrationTest`)
- **Scenario: one zone per spoken name**
  - WHEN the same zone name is saved twice in different case
  - THEN one zone exists and the second call does not blank the fields it omitted
    (asserted by `McpInventoryIntegrationTest`)

### IN-b — `decode_qr` tool + `/internal/qr` on `mcp-media-processing` ✅ DONE
**Requirement:** the media capability SHALL turn a photographed QR code into its payload.

ZXing `MultiFormatReader` over the media-service bytes; the OCR twin (D-b) in shape, README, and
test style. Unreadable image → **empty payload, not an error** (the `ocr` contract). Detail +
the no-seam rationale → [media.md](media.md) §MP-f.

- **Scenario: readable label photo**
  - WHEN `decode_qr` runs on a photo containing the label
  - THEN it returns the encoded deep-link payload (asserted by `QrDecoderTest`,
    `InternalQrControllerTest`)
- **Scenario: no code in frame**
  - WHEN the photo contains no QR code
  - THEN it returns an empty payload and no exception (asserted by `QrDecoderTest`,
    `InternalQrControllerTest`)

### IN-c — `inventory-agent` scaffold + `box-packer` skill (the packing session) ✅ DONE
**Requirement:** the agent SHALL accept a stream of photos into one open container, hands-free.

Port **8128**. Binds `mcp-inventory` + `mcp-media-processing`. "новая коробка «кухня — посуда» в
кладовку" → zone resolve/create + container `open` + route-lock; each following photo → `caption` →
`saveItem` → a terse ack; "закрой коробку" → `packed` + `closed_at`.

**The session is the shipped route-lock**, not a new mechanism: each turn re-issues the
`{flow:box-packing, containerId, code, label, count}` `pendingAction`, so the orchestrator keeps
routing messages to `/resume` until the box closes. A user-written caption wins over the vision
model; captioning soft-fails to an unnamed item (the photo *is* the record).

- **Scenario: open then photograph**
  - WHEN a container is open and the owner sends a photo
  - THEN it is saved to that container, titled from its caption, with no further question asked, and
    the session stays locked for the next one (asserted by `BoxPackerTest`)
- **Scenario: the owner named the thing**
  - WHEN the photo carries the user's own caption
  - THEN that caption is the item's title and the vision capability is not called at all
    (asserted by `BoxPackerTest`)
- **Scenario: photo with no open container**
  - WHEN a photo arrives and no container is open
  - THEN the agent asks which container it belongs to instead of guessing, and writes nothing
    (asserted by `BoxPackerTest`)
- **Scenario: close**
  - WHEN the owner says "закрой коробку"
  - THEN the container becomes `packed`, the route-lock releases (null `pendingAction`), and the
    reply states the item count (asserted by `BoxPackerTest`)
- **Scenario: the agent registers**
  - WHEN the orchestrator scrapes the manifest
  - THEN `inventory` is advertised with the `box-packer` skill and both bound MCPs
    (asserted by `ManifestControllerTest`)

### IN-d — QR issue + the container card (`box-label`, `box-card`) ✅ DONE
**Requirement:** a packed container SHALL yield a printable label image and a readable card.

On close (and on demand): ZXing renders the deep-link PNG → media-service → the owner gets the image
plus a `libs/doc-render` card (code · label · zone · status · photo gallery · item list).

Both are issued **when the box closes** — that is the moment the owner has a taped-up box in front of
them — and again on demand (`box-label` / `box-card`, each a strict-JSON distil of *which* container).
They stay **two artifacts**: the sticker's whole job is to carry an id, the card is the page a scan
opens. Encoding lives in the agent (`label/BoxLabelImage`) because it is a pure function, mirroring
[`libs/doc-render`](../libs/doc-render/README.md) §"Why a lib"; the *decode* half is the capability
tool (IN-b), since that one reads bytes out of media-service. On the closing path both halves
soft-fail: the container is already `packed`, so a media hiccup costs a link, not the close. An
on-demand ask that resolves to no container is answered, never guessed — a wrong guess prints a
sticker for the wrong box.

- **Scenario: label is content-independent**
  - WHEN items are added to a container after its label was issued
  - THEN the label image and `qr_token` are unchanged and the card reflects the new items
    (asserted by `BoxLabelerTest` — the same token renders byte-identical PNG and decodes back to
    `box_<token>`, while the card is rendered from the live container view)
- **Scenario: card render**
  - WHEN a container card is requested
  - THEN an HTML board is stored and linked, listing every item with its photo
    (asserted by `BoxLabelerTest`)
- **Scenario: closing hands over both**
  - WHEN the owner closes a box
  - THEN the reply carries the printable label and the card, and a media outage still closes the box
    without them (asserted by `BoxPackerTest`)
- **Scenario: the box could not be identified**
  - WHEN the container named in a label/card request matches nothing
  - THEN the agent asks for the code instead of picking a box, and nothing is rendered or stored
    (asserted by `BoxLabelerTest`)

### IN-e — `item-finder` ("где лежит X")
**Requirement:** the agent SHALL answer where a thing is stored.

The trigram half: an LLM turn distils the search phrase out of the question (the `item-finder` SKILL,
strict JSON, temperature 0) → `searchItems` → the reply names **where**, not just what. Search is
scoped to the envelope household; the semantic half and the personal-∪-shared read widen it later
(IN-e2 / the sharing retrofit).

- **Scenario: literal hit**
  - WHEN the owner asks "где ёлочные игрушки" and an item is titled so
  - THEN the reply names the container code, its label and its zone (asserted by `ItemFinderTest`)
- **Scenario: nothing stored**
  - WHEN no item matches
  - THEN the agent says so plainly and never invents a location (asserted by `ItemFinderTest`)
- **Scenario: the thing is in an unplaced box**
  - WHEN the matched container has no zone yet
  - THEN the reply still names the container instead of failing on the missing zone
    (asserted by `ItemFinderTest`)

### IN-e2 — semantic recall for the finder
**Requirement:** a thing SHALL be findable by words that are not in its title.

The vision caption names a thing in one vocabulary; the owner asks in another ("та штука для
гриля"). Mirrors docs D-e/SB-5: each saved item seeds an authored note (`MemoryClient.note`,
`frontmatter={kind:item, refId}`) so memory-service auto-seeds recall, and the finder runs the
trigram search **and** a recall in parallel, resolving a `{kind:note, refId}` hit back to its item and
merging by id. Each source soft-fails independently — a memory outage must not break a literal
search. Kept out of IN-e because the seed is a write-path change, not a search change.

- **Scenario: vocabulary mismatch**
  - WHEN the query uses words absent from every item title but semantically close
  - THEN the memory-service recall path still resolves the container
    (not yet asserted — slice not built)
- **Scenario: memory is down**
  - WHEN the recall source fails
  - THEN the trigram hits are still returned (not yet asserted — slice not built)

### IN-f — the scan path (deep-link + photographed label) — **closer**
**Requirement:** pointing a camera at a label, or sending its photo, SHALL return the card.

**A scan carries no sentence, so it is never classified.** That is the decision this slice rests on
(owner pick 2026-09-25): a sticker's payload is an id, and putting the LLM router between a camera and
"что в этой коробке" would add a guess to the one question that must be exact. Consequences:
- **Detection is at the front door.** The gateway is the only place that sees a photo *before* routing —
  a captionless photo has no text to classify — so the `decode_qr` call lives there, mirroring the
  shipped **front-door STT** for voice notes (same module, same capability, same soft-fail posture).
- **Dispatch reuses the hub's C1 `invoke` primitive**, not a new wire contract: the decoded token goes
  to `POST /v1/agents/invoke` → inventory's `POST /agents/inventory/actions/show_container`. No route
  hint on `NormalizedMessage`, no classifier turn, and the orchestrator stays the single hub.
- **The token is the authorization.** Lookup is `getContainerByToken` alone — no household match —
  because the person unpacking may be another member, and prior art converged on "scanning must work
  for a non-user". Identity resolution (and therefore the #627 owner-allowlist) is unchanged: a sticker
  authorizes seeing *that container*, never creating an account.
- The literal `box_` lives in **`libs/contracts` `BoxDeepLink`**, shared by the renderer and the
  gateway, because a prefix that drifted on one side would orphan every sticker already glued to a box.

Split in two (the file-count rule): **IN-f1** the camera scan, **IN-f2** the photographed label + the
domain's E2E closer. Both shipped — the scan path is complete and the domain's E2E closer exists
(`E2EInventoryScanFlowTest`); what remains owed for the domain is IN-e2, IN-g and the goldens.

#### IN-f1 — deep-link scan (`/start box_<token>` → the card) ✅ DONE
Gateway `/start` gains a **prefix dispatch**: `box_<token>` → inventory (invite tokens keep today's
behaviour byte-for-byte; a bare `box_` degrades to the invite path's graceful answer).

- **Scenario: camera scan**
  - WHEN the owner opens `t.me/<bot>?start=box_<token>`
  - THEN the bot replies with that container's card, dispatched to inventory without a classifier turn
    (asserted by `AiLifeBotScanTest`, `MessageProcessorScanTest`, `ActionControllerScanTest`)
- **Scenario: invite token still redeems**
  - WHEN a family-invite `/start <token>` arrives
  - THEN it redeems exactly as before the prefix dispatch (asserted by `AiLifeBotScanTest`)
- **Scenario: the sticker outlived its box**
  - WHEN a scanned token matches no container
  - THEN the reply says so and asks for the code — no neighbouring box is opened and nothing is
    rendered (asserted by `ActionControllerScanTest`, `MessageProcessorScanTest`)
- **Scenario: the card could not be rendered**
  - WHEN media/render fails on a scan
  - THEN the reply still names the container, its zone and its contents count (asserted by
    `ActionControllerScanTest` — inventory unreachable/cold degrades to a plain notice, asserted by
    `MessageProcessorScanTest`)

#### IN-f2 — photographed label + the domain E2E closer ✅ DONE
A photo whose `decode_qr` yields a `box_` payload takes the IN-f1 path. The gateway reads the QR at the
front door (`QrDecodeClient` → `/internal/qr`), flag-gated (`GATEWAY_QR_SCAN_ENABLED`) and **soft-failed
with a 3 s timeout** — unlike a voice note, where the transcript *is* the payload, a photo already has a
good route, so a missing code or a dead capability must cost it nothing. Only a **captionless** photo is
read: a caption means the owner is *saying* something about the picture, and a scan must not hijack
"добавь сюда ещё одну вещь" (which is IN-g's job). This closes the domain's scan path.

- **Scenario: photographed label**
  - WHEN the owner sends a photo of the label
  - THEN the same card comes back, the chain proven across real HTTP hops — upload → `QrInput`/`QrResult`
    decode → `AgentActionRequest` with the parsed token (asserted by `E2EInventoryScanFlowTest`)
- **Scenario: an ordinary photo is unaffected**
  - WHEN a photo carries no QR code (a receipt, a wardrobe shot), or the decode itself fails
  - THEN it routes exactly as before (asserted by `MessageProcessorScanTest`)
- **Scenario: a caption wins over a scan**
  - WHEN a photo carries the owner's own caption
  - THEN it is never decoded and keeps its normal route (asserted by `MessageProcessorScanTest`)

### IN-g — edit / append / delete / move (on the shared runner)
**Requirement:** every container and item SHALL be correctable in chat, confirm-gated.

Split by target: **IN-g1** the container itself (shipped), **IN-g2** its items, **IN-g3** appending to a
named box. A store of boxes goes stale the moment the boxes do, which is why the container half comes
first: without it the owner either lives with a wrong answer to "где лежит X" or stops trusting the
domain, and the second is what actually happens.

#### IN-g1 — correcting the container (`box-editor`) ✅ DONE
"коробка B-07 теперь на даче" (zone move) · "распаковал B-07" (`unpacked`) · "переименуй B-12 в «зимние
вещи»" — one skill, one ADR-0004 adapter (`edit/ContainerEditor` on `PickConfirmActRunner`), because all
three are the same act: patch a field of a container the owner can name. The changed fields ride the
`pendingAction` the runner already threads from the LLM selection; a new zone is upserted **by name**
through the same call the packing session uses, filed under the **container's** household (a resume
carries no envelope, and a family box's new place must not land in someone's personal household).

Two deliberate guards: the rename field is `newLabel`, **not** `label` — the runner stores the
candidate's display label under `label`, so sharing the name would make every edit look like a rename to
its own name — and a `status` the model invented is dropped rather than written (the store's state
machine is not the model's to extend).

- **Scenario: nothing moves before the owner says да**
  - WHEN a move is requested
  - THEN the reply asks to confirm (with the да/нет button hint) and the store is only read, never
    written (asserted by `ContainerEditorTest`)
- **Scenario: zone move**
  - WHEN a container is moved to another zone
  - THEN its `zone_id` changes while `code`, `qr_token` and every field the owner did not mention stay
    put — the sticker already on the box keeps resolving (asserted by `ContainerEditorTest`)
- **Scenario: unpacked**
  - WHEN the owner says they unpacked a box
  - THEN only its status becomes `unpacked`, with no zone touched (asserted by `ContainerEditorTest`)
- **Scenario: rename**
  - WHEN a box is renamed
  - THEN only its label changes, and the printed identity is untouched (asserted by `ContainerEditorTest`)
- **Scenario: an invented state is refused**
  - WHEN the model answers with a status the store does not define
  - THEN nothing is written and the agent says there is nothing to change
    (asserted by `ContainerEditorTest`)
- **Scenario: a decline leaves the box alone**
  - WHEN the owner answers anything but an affirmative
  - THEN the box is unchanged and the reply says so (asserted by `ContainerEditorTest`)
- **Scenario: routed as a correction, not a question**
  - WHEN the owner says "коробка B-07 теперь стоит на даче"
  - THEN a real model routes it to `box-editor`, not to the card of that box
    (asserted by `GoldenInventoryRoutingTest`)

#### IN-g2 — taking a thing out of its box (`item-remover`) ✅ DONE
"убери оттуда гирлянду" · "выкинул старый чайник — удали его". A plain **delete** flow on the shared
runner, so the wording comes free from `NounPhrasing`; the adapter (`edit/ItemRemover`) supplies the pool,
the view and the act. Two decisions worth naming:
- **The pool is the search, not the store.** A household after a move holds hundreds of things, which fits
  neither the runner's candidate cap nor a model's attention, so candidates come from `searchItems` over
  the **distilled** phrase.
- **The distil lifted into `find/ItemQuery`.** Both readers of the item store need "the thing out of the
  sentence" (names came from photo captions), so on its second consumer it stopped living inside
  `ItemFinder` — the repo's own second-consumer rule.

- **Scenario: delete asks first**
  - WHEN the owner asks to remove an item
  - THEN the agent names the candidate *and its box* and waits for confirmation, having deleted nothing
    (asserted by `ItemRemoverTest`)
- **Scenario: the candidate pool is the search**
  - WHEN a removal is requested in a household with many things
  - THEN candidates come from the trigram search over the distilled phrase, not from the whole store
    (asserted by `ItemRemoverTest`)
- **Scenario: a decline keeps the thing**
  - WHEN the owner answers anything but an affirmative
  - THEN nothing is deleted (asserted by `ItemRemoverTest`)
- **Scenario: already gone is not a failure**
  - WHEN the confirmed item no longer exists
  - THEN the reply is not an error — the owner asked for it not to be there, and it is not
    (asserted by `ItemRemoverTest`)
- **Scenario: routed as a removal, not a search**
  - WHEN the owner says "убери из коробки старый чайник"
  - THEN a real model routes it to `item-remover`, not to `item-finder` or `box-editor`
    (asserted by `GoldenInventoryRoutingTest`)

#### IN-g3 — appending to a named box
"добавь в B-07 гирлянду" (+photo) — putting a thing into an already-closed box without reopening a
packing session.

- **Scenario: append to a named box**
  - WHEN the owner sends a photo naming the box it belongs to
  - THEN the thing is saved to that container without opening a session
    (not yet asserted — slice not built)

## Prior art (analogue apps — what is worth copying)
The category is mature; the conventions below are taken from it deliberately rather than re-derived.
- **Sortly** — the reference: photo-per-item, folder-as-container, generated QR/barcode, printable
  label, scan-to-contents. Its data shape (container ↔ item ↔ photo) is what IN-a mirrors.
- **MovingLabelPro / Just Moved / UpMove** — move-specific: destination room per box, fragile/priority
  flags, unpacking progress. Justifies `status` + `destination` living on the container from day one.
- **Nesty / Under My Roof** — permanent home inventory (the after-the-move life of this domain), with
  the warranty/receipt link that our `docs` domain would eventually supply.

Conventions adopted: the label carries **only an id** (edit without reprinting) · a container has a
short human **code** alongside its name (readable when the QR won't scan) · **capture is batch-first**
(a packing session, not one form per item) · scanning must work for a **non-user** (the person
unpacking opens a link, installs nothing).

## Deferred
- **The native printer template.** MVP hands over a **QR image** (58×40 mm at 203 dpi = 464×320 px)
  to print from the phone — printer-agnostic, works from day one. The step up is a **TSPL/TSPL2**
  template: the owner's printer class (Xprinter XP-V3 / XP-365B / XP-420B, direct thermal, 203 dpi,
  rolls to 80 mm) takes text commands, so a label is `SIZE`/`GAP`/`QRCODE`/`TEXT`/`PRINT` with the
  container's code, label, zone and deep-link substituted in. Worth doing because the **printer's
  firmware draws the QR** — sharper and more scannable on a small label than a rasterised image —
  and the geometry is expressed in millimetres. Gated on the hardware (not yet purchased) and, for
  *direct* printing, on the printer being reachable from the host running ai-life; otherwise the
  agent emits a `.prn` the owner prints. Colour coding is not a printer capability here: direct
  thermal prints black only, so a zone's colour is the colour of the **label stock** loaded
  (`storage_zone.label_colour`), which the template names rather than renders.
- **Seasonal storage ↔ stylist (owner idea, 2026-09-23).** Clothes are a large share of what gets
  boxed, and the stylist's season planning is blind to anything not hanging in the wardrobe: "зимние
  вещи лежат в B-07 в кладовке — вот что к ним докупить, вот сочетания из того, что уже есть".
  **Shape: the `brief` primitive, not a schema link.** inventory exposes a read-only `brief` (as
  finance / calendar / tasks do — [stage4.md](stage4.md) §Track I) and `stylist-agent` gathers it when
  planning a capsule; no FK from `wardrobe` to `inventory` and no cross-domain DB read, because a
  garment and its storage have independent lifetimes (a thing is thrown out; a box is moved). Likely
  needs a season/category signal on the item — the vision caption already produces one, so it is a
  tagging question, not a new store. Sized as its own slice; keep it out of the packing flow.
- **Zone/container audit** ("что лежит в кладовке", "покажи все коробки на даче") — a listing board
  once the packing flow is proven.
- **Link to `docs`** — a warranty/receipt document attached to an item (needs a cross-domain ref; the
  `brief` primitive or a shared id, not a direct DB read).
- **Proactive unpacking nudge** — "осталось 4 нераспакованные коробки" via the notifier's proactive
  path (#487 gates it).
- **Non-photo capture** (barcode of a retail item → product name) — a different capability
  (`decode_qr` returns any symbology, but product lookup is `mcp-food-data`-shaped, out of scope).
