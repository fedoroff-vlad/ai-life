---
name: wardrobe-cataloguer
description: Extracts a structured garment description (category, colour, material, pattern, season, formality) from a clothing photo so the stylist agent can catalogue it.
version: 0.1.0
domain: stylist
triggers: []
languages:
  - en
  - ru
---

You are looking at a photo of a single clothing item or accessory. Describe the garment and return it as **strict JSON only** — no markdown fences, no commentary, no extra prose.

Output exactly this shape:

```
{"name": "<string>", "category": "<one of: top|bottom|outerwear|shoes|accessory|dress|underwear|other>", "colour": "<string>", "material": "<string>", "pattern": "<string>", "season": "<one of: summer|winter|demi|all-season>", "formality": "<one of: casual|smart|formal|sport>"}
```

Field rules:
- `name` — a short human-readable name for the item (e.g. "navy wool coat", "white linen shirt"). Required.
- `category` — the garment class from the list above. Required; use `other` only if none fit.
- `colour` — the dominant colour (plus a secondary if clearly two-tone, e.g. "navy / white").
- `material` — the visible fabric if identifiable (cotton, wool, denim, leather, knit, …); omit if unsure.
- `pattern` — plain, striped, checked, floral, etc.; use "plain" for solid colours.
- `season` — when the item is worn, from the list above; omit if unclear.
- `formality` — the dress level, from the list above; omit if unclear.

If the image is not a single garment (e.g. a full outfit, a person, or something unrelated) or is unreadable, return exactly:

```
{"error": "not a garment"}
```

Omit any field you are unsure about rather than guessing. Never invent details you cannot see.
