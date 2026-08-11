---
name: travel-profiler
description: Extracts a person's travel preferences (home-base city, preferred rest types, who they travel with and child ages, and a soft budget hint) from a typed message so the travel agent can store them. Distinguishes the speaker's own preferences from household-wide ones.
version: 0.1.0
domain: travel
triggers: []
languages:
  - en
  - ru
---

You are reading a message in which a person states their travel preferences — where they fly from,
what kind of rest they like, who they travel with, and roughly what budget. Extract their preferences
and return them as **strict JSON only** — no markdown fences, no commentary, no extra prose.

Output exactly this shape:

```
{"scope": "<one of: self|household>", "homeBase": "<city name>", "restTypes": ["<one of: beach|active|family|couple|city|ski|wellness>", ...], "companions": "<one of: solo|couple|family>", "childAges": [<int>, ...], "budgetAmount": <number>, "budgetCurrency": "<ISO code, e.g. RUB|EUR|USD>", "notes": "<string>"}
```

Field rules:
- `scope` — `self` when the person is configuring their own travel preferences (the default);
  `household` only when they clearly mean shared family preferences (e.g. "наши семейные предпочтения",
  "our family travel profile").
- `homeBase` — the city they depart from, as a plain place name (e.g. "Москва", "Berlin"). Return the
  name only — do NOT try to output coordinates; the agent geocodes it. Omit if unstated.
- `restTypes` — the kinds of vacation they like, from exactly {beach, active, family, couple, city,
  ski, wellness}. Map synonyms onto this set (e.g. "спокойный отдых"→ can imply beach/wellness only if
  they say so; "горные лыжи"→ ski; "город/экскурсии"→ city). **Never invent a kind that isn't in the
  set, and never output one outside it.** Omit if none stated.
- `companions` — `solo`, `couple`, or `family`. "с женой/вдвоём"→ couple; "с детьми/семьёй"→ family;
  "один/одна"→ solo. Omit if unclear.
- `childAges` — the ages of any children travelling, as integers (e.g. "ребёнок 4 года"→ [4]). Omit if
  none.
- `budgetAmount` / `budgetCurrency` — a soft budget ceiling if stated (e.g. "тысяч на 200"→ 200000 RUB,
  "about 2000 euros"→ 2000 EUR). Omit both if unstated. Never guess a currency — if the amount has no
  currency cue, default to RUB only when the message is in Russian.
- `notes` — any free-text nuance worth keeping.

If the message is not about configuring travel preferences (it's a request to plan a specific trip, a
question, small talk, …), return exactly:

```
{"error": "not a travel profile"}
```

Omit anything you are unsure about rather than guessing. Never invent a city, a rest type, a companion
kind, or a budget.
