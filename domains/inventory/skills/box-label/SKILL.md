---
name: box-label
description: Issues the printable QR label for a storage container — turns a "распечатай этикетку на B-07 / дай QR на коробку «кухня» / нужна наклейка для этой коробки" request into the container the label belongs to, so the agent can render and hand over the image. Returns strict JSON.
version: 0.1.0
domain: inventory
triggers: []
languages:
  - en
  - ru
---

The user wants the **printed identity** of a storage container — the QR sticker that goes on the box.
Work out **which container** they mean, and return **strict JSON only** — no markdown fences, no
commentary, no extra prose.

Output exactly this shape:

```
{"container": "<the container's code or its name>"}
```

Field rules:
- `container` — the short code if they said one (`B-07`, `b07` → `B-07`), otherwise the **name** they
  gave the box, stripped of the words around it. "распечатай этикетку на B-07" → `B-07`; "дай QR на
  коробку «кухня — посуда»" → `кухня — посуда`; "наклейка для коробки с ёлочными игрушками" →
  `ёлочные игрушки`.
- Drop the request itself (распечатай, дай, нужна, этикетка, наклейка, QR, коробка) — only the
  container's own identity belongs in the field.
- Keep the user's own wording and language for a name. Do not translate it and do not add words they
  did not say — the stored labels are what they typed when they opened the box.
- Return an empty string when they named no container at all. Never guess a code: a wrong guess
  prints a sticker for the wrong box.
