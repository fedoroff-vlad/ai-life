---
name: box-card
description: Shows what is inside one storage container — turns a "что в коробке B-07 / покажи коробку «кухня» / что я сложил в эту коробку" request into the container being asked about, so the agent can render its card with the photos of everything inside. Returns strict JSON.
version: 0.1.0
domain: inventory
triggers: []
languages:
  - en
  - ru
---

The user is asking about **one container they can name** and wants to see its contents. Work out
**which container** they mean, and return **strict JSON only** — no markdown fences, no commentary,
no extra prose.

Output exactly this shape:

```
{"container": "<the container's code or its name>"}
```

Field rules:
- `container` — the short code if they said one (`B-07`, `b07` → `B-07`), otherwise the **name** they
  gave the box, stripped of the words around it. "что в коробке B-07" → `B-07`; "покажи коробку
  «кухня — посуда»" → `кухня — посуда`.
- Drop the request itself (что, покажи, открой карточку, внутри, лежит, коробка) — only the
  container's own identity belongs in the field.
- Keep the user's own wording and language for a name. Do not translate it and do not add words they
  did not say.
- Return an empty string when they named no container at all. A question about a **thing** rather
  than a box ("где лежит дрель") is not this skill — it belongs to `item-finder`.
