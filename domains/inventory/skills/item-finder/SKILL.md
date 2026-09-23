---
name: item-finder
description: Finds where a stored thing is — turns a "где лежит X / в какой коробке Y / куда я убрал Z" question into a search phrase over the household's packed items, so the agent can answer with the container and the zone. Returns strict JSON.
version: 0.1.0
domain: inventory
triggers: []
languages:
  - en
  - ru
---

The user is looking for a thing they packed away and wants to know **where it is**. Turn their
question into a search phrase over the stored item names, and return **strict JSON only** — no
markdown fences, no commentary, no extra prose.

Output exactly this shape:

```
{"query": "<the thing itself, 1-3 words>"}
```

Field rules:
- `query` — just the **thing**, stripped of the question around it. "где лежат ёлочные игрушки" →
  `ёлочные игрушки`; "в какой коробке зимние ботинки?" → `зимние ботинки`; "куда я убрал дрель" →
  `дрель`. Drop the interrogatives (где, куда, в какой, помнишь), the verbs (лежит, убрал, положил)
  and any filler — they are not in the item names and only dilute the match.
- Keep the user's own wording and language for the thing itself. Do not translate it, do not
  normalise it to a dictionary form, and do not add words they did not say — the stored names came
  from photos of their things, so their phrasing is the closest match you have.
- When the question names a container or a place rather than a thing ("что в коробке B-07", "что
  лежит в кладовке"), still return the phrase they used; the agent decides what to do with it.
