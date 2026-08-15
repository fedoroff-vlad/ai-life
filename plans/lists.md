# lists — structured item lists on the note tier

Authority file for the **lists** capability (owner idea, 2026-08-14): everyday item lists
(grocery / things-to-pack / to-buy) captured as **structured checklists the owner can add to,
check off, and clear** — owned by [`notes-agent`](../domains/knowledge/notes-agent/README.md),
stored on the **`memory.note`** tier. Not a new domain, not a new store.

## Doctrine — a list is a note, not a new thing
A list reuses the second-brain substrate ([second-brain.md](second-brain.md)) end to end:

- **A list = one `memory.note`** with `type: list`, `title` = the list's name ("список покупок"),
  `ownerId = null` (household-shared — the grocery list is shared by design).
- **Items live in the body as a CommonMark task list** — one `- [ ]` / `- [x]` line per item:
  ```markdown
  - [ ] молоко
  - [x] яйца
  ```
  This is the interchange form already blessed by the manifest (CommonMark body) — recall (SB-2) and
  markdown export (SB-7) keep working with **zero new code**, and the vault renders the list natively.
- **Every operation = read the note → mutate the checklist lines → write it back** via the existing
  `POST` / `PUT /v1/notes`. **No new store, migration, endpoint, or contract** — same ethos as the
  travel packing-list PK-a. `type: list` is a small additive value in the free-text `type` column
  (blank → `fact` server-side), not a schema change.

## Boundaries (so lists don't grow a second everything)
- **vs authored notes:** a list is operational + mutable-in-place; a note is durable prose. Same tier,
  distinguished by `type = list`. Recall can still surface a list; that's a feature (find it by meaning).
- **vs packing-list #438 (travel):** travel's `PackingListComposer` is a *deterministic generator* seeded
  by a trip; a list here is a *user-maintained* checklist. LI-c may let travel **write its generated list
  as a list note** so the owner can then check items off — folding #438's output onto this tier — but the
  generator stays in travel.
- **vs ambient capture:** LI-a is explicit only ("добавь …"). Filling a list *without* a keyword
  ("надо купить молоко" → grocery list) rides [ambient-capture.md](ambient-capture.md) and is LI-b.

## Phased slices (each = one small vertical slice / PR)

### LI-a — explicit list operations ✅ DONE ([PR466](https://github.com/fedoroff-vlad/ai-life/pull/466))
The conversational front in `notes-agent`: the owner manages a named list by voice/text. One llm-gateway
turn with the **`list-manager`** SKILL classifies the message into `{op, list, item}` (strict JSON,
temperature 0, the `note-writer` shape); `ListManager` then resolves the list note (find-or-create by
title among the household's `type=list` notes) and applies the op to the checklist body via the pure
`MarkdownChecklist` util, persisting with `POST`/`PUT /v1/notes`.

Ops: `add` · `check` (check off / mark done) · `clear` (empty the list) · `show` (render current state).

**Reuse:** `NoteClient` (extended with `list` + `update`), the `SkillRegistry`/`LlmClient` turn shape from
`NoteWriter`, `memory.note` CRUD. No new store/endpoint/contract/migration.

**Acceptance criteria — WHEN/THEN (the spec + golden/E2E seed):**

- Scenario: **add to a new list creates it**
  WHEN the owner says "добавь молоко в список покупок" and no `type=list` note titled "список покупок"
  exists in the household
  THEN a household-shared note is created (`type=list`, `title="список покупок"`, body `- [ ] молоко`)
  and the reply confirms молоко was added.

- Scenario: **add to an existing list appends one line**
  WHEN "добавь хлеб в список покупок" and the list already holds `- [ ] молоко`
  THEN the note is updated in place (`PUT`) to `- [ ] молоко` + `- [ ] хлеб`.

- Scenario: **adding a duplicate item is a no-op**
  WHEN "добавь молоко" and `молоко` is already an item (case-insensitive)
  THEN the body is unchanged and the reply says it is already on the list.

- Scenario: **check off an item marks it done**
  WHEN "вычеркни яйца из списка покупок" and `- [ ] яйца` is present
  THEN that line becomes `- [x] яйца` and the reply confirms.

- Scenario: **clear empties the list**
  WHEN "очисти список покупок"
  THEN the list note body becomes empty (all items removed); the note itself is kept.

- Scenario: **show renders the current state**
  WHEN "покажи список покупок"
  THEN the reply lists every item with its ✅/⬜ state; an unknown list name replies "нет такого списка".

- Scenario: **check/show/clear on a missing list is graceful**
  WHEN the op is `check`/`clear`/`show` and no matching list exists
  THEN nothing is written and the reply says the list wasn't found (no empty note is created).

### LI-b — ambient list capture (deferred, rides ambient-capture)
Fill a list *without* a keyword: "надо купить молоко" / "заканчивается кофе" → append to the grocery
list. Extends the ambient decision engine (a fourth outcome / a list-intent branch in
`NoteWorthinessExtractor`), reusing LI-a's `MarkdownChecklist` write. LLM-gated; specced when picked.

### LI-c — travel packing-list as a list note (deferred)
Let travel's `PackingListComposer` emit its result as a `type=list` note so the owner can then check
items off through LI-a. Folds #438's output onto this tier without moving the generator.

## Verification
- **Unit** `MarkdownChecklistTest` — parse/add/dedup/check/clear/render on the `- [ ]`/`- [x]` form,
  including case-insensitive item match and idempotent add.
- **Slice (MockWebServer)** `ListManagerTest` — the four ops through `POST /agents/notes/intent`:
  add-creates (`POST /v1/notes`), add-appends (`GET` list → `PUT`), check (`PUT` with `[x]`), show/clear,
  missing-list graceful. llm-gateway + memory-service mocked.
- **Golden (opt-in, `GOLDEN_LLM`)** `GoldenListManagerTest` — the real local model classifies a natural
  list message into a parseable `{op, list, item}` (structure, not wording).
