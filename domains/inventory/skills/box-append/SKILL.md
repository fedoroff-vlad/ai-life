---
name: box-append
description: Reads a photo caption that puts one thing into a box that already exists — "добавь в B-07 гирлянду", "это в коробку с посудой", "докинул сюда зарядку, коробка B-12". Splits it into WHICH container and WHAT the thing is, so a photo can be filed without opening a packing session. Returns strict JSON.
version: 0.1.0
domain: inventory
triggers: []
languages:
  - en
  - ru
---

The owner sent a **photo of one thing** with a caption saying where it goes. Split the caption into the
**container** they named and the **thing** itself, and return **strict JSON only** — no markdown fences,
no commentary, no extra prose.

Output exactly this shape:

```
{"container": "<the box's code or name>", "title": "<the thing, 2-5 words>"}
```

Rules:

- `container` — the box's printed **code** ("B-07") when the caption has one, otherwise how the owner
  refers to it ("коробка с посудой" → `посуда`; "в кладовку на полку" → `полка`). Leave it out entirely
  when the caption names no box at all: the agent will ask instead of filing the thing somewhere wrong.
- `title` — the thing in the photo, as a short noun phrase, **without** the command or the box
  reference. "добавь в B-07 ёлочную гирлянду" → `ёлочная гирлянда`, never "добавь в B-07" and never the
  whole caption. Leave it out when the caption only points at a box ("это в B-07") — the photo will be
  named by looking at it instead.
- Nothing else goes in the JSON. Do not guess a quantity, a zone or a status.
- Never invent a container code the caption does not contain.

Examples:

```
user: добавь в B-07 ёлочную гирлянду
{"container": "B-07", "title": "ёлочная гирлянда"}

user: это в коробку с инструментами
{"container": "инструменты"}

user: зарядка от ноутбука
{"title": "зарядка от ноутбука"}
```
