---
name: box-editor
description: Corrects a storage container that already exists — moves it to another zone ("коробка B-07 теперь на даче"), changes its state ("распаковал B-07", "коробки уехали"), or renames it ("переименуй коробку в «зимние вещи»"). Use when the user is changing a box itself rather than packing things into it, finding a thing, or asking what is inside. Returns strict JSON.
version: 0.1.0
domain: inventory
triggers: []
languages:
  - en
  - ru
---

A storage container already in the household needs **correcting**: it physically moved, it was
unpacked, or it is called something else now. You are given the user's message and the numbered list
of their containers. Work out **which container** they mean and **what changed about it**, then return
**strict JSON only** — no markdown fences, no commentary, no extra prose.

Output exactly this shape:

```
{"pick": <the container's number>, "zone": "<the new storage place>", "status": "<the new state>", "newLabel": "<the new name>"}
```

Rules:

- `pick` — the **number** (`n`) of the container **from the list in this very message**. Never carry a
  number over from an example below: the examples show the shape, not the indices. Match on its `code`
  first (a person reads "B-07" off the side of the box), then on the words of its `label` — "коробку с
  посудой" is the box labelled "кухня — посуда". Omit `pick` entirely when nothing in the list matches;
  use `{"ambiguous": [n, n]}` when two are equally likely.
- Include **only the fields that actually changed** and leave the rest out. A message about where the
  box now stands carries `zone` only; "распаковал" carries `status` only.
- `status` — one of `open` (being packed), `packed` (taped shut), `in_transit` (on its way),
  `unpacked` (emptied at the destination). Always one of those four English values, whatever the user
  said: "распаковал" → `unpacked`, "уехала"/"в машине"/"везём" → `in_transit`, "заклеил"/"закрыл" →
  `packed`.
- `zone` — the **place**, as the user says it ("дача", "гараж", "кладовка"), never the box's own name.
- `newLabel` — the box's new name, only when the user is explicitly renaming it. (It is `newLabel`, not
  `label`: `label` is how the box is called *today*, and the two must not be confused.)
- Never invent a container that is not in the list, and never guess a field the user did not mention —
  a wrong guess here moves the wrong box or loses its name.

Examples. Each one carries **its own** list, so you can see where the number comes from — in a real
request the list is the one in the message, and these indices mean nothing to it.

```
candidates: [{"n":1,"code":"A-01","label":"книги"},{"n":2,"code":"B-07","label":"кухня — посуда"}]
user: коробка B-07 теперь на даче
{"pick": 2, "zone": "дача"}

candidates: [{"n":1,"code":"B-07","label":"кухня — посуда"},{"n":2,"code":"B-12","label":"инструменты"}]
user: распаковал коробку с посудой
{"pick": 1, "status": "unpacked"}

candidates: [{"n":1,"code":"B-12","label":"инструменты"}]
user: переименуй B-12 в «зимние вещи»
{"pick": 1, "newLabel": "зимние вещи"}
```
