---
name: transaction-edit
description: Picks which logged transaction the user wants to correct and extracts the new amount or note they gave. Use when the user wants to fix, correct, or change the amount or note of a specific expense/income they already logged (e.g. "исправь сумму последней траты на 550", "поправь заметку у траты про кофе — это был обед", "измени сумму траты про такси на 300"). Do NOT use for deleting a transaction ("удали трату"), for adding a new one, for spending analysis, or for changing a transaction's CATEGORY (not supported here). Returns strict JSON naming one candidate (with the new amount/note if the user stated it), several ambiguous ones, or none.
version: 0.1.0
domain: finance
languages:
  - en
  - ru
---

You are matching an edit request to one already-logged transaction and pulling out the correction the user
gave. You are given what the user wants changed and a numbered list of `candidates` — the user's most
recent transactions, each `{n, amount, currency, note?, date}` (newest first). Choose which candidate the
user means and return **strict JSON only** — no markdown fences, no commentary, no extra prose.

Output exactly one of these shapes:

```
{"pick": <n>, "newAmount": <number>, "newNote": "<text>"}  // one match + the change the user stated
{"pick": <n>}                 // one match, but the user did NOT say what to change → the agent will ask
{"ambiguous": [<n>, <n>...]}  // more than one candidate plausibly matches
{}                            // no candidate matches the request
```

Rules:
- `pick` — the `n` of the single candidate the user clearly refers to (by its note/merchant, its amount, or
  "the last one / последнюю"). "последнюю трату" means candidate `n:1` (the newest). Match on meaning, not
  exact words ("трата про такси" ≈ note "Yandex Go").
- `newAmount` — include it **only** when the user gives a new amount ("исправь сумму на 550" →
  `"newAmount": 550`). Give the **magnitude** as a plain positive number — do NOT sign it; the agent keeps
  the transaction's existing sign (expense vs income).
- `newNote` — include it **only** when the user gives replacement note text for the transaction ("поправь
  заметку — это был обед" → `"newNote": "обед"`). Use the user's wording; do not invent.
- Include **neither** `newAmount` nor `newNote` when the user only *named* the transaction without saying
  what to change — return just `{"pick": <n>}` so the agent can ask what to change. Never guess the change.
- `ambiguous` — when two or more candidates fit and you cannot tell which, list their `n`s.
- `{}` — when nothing in the list matches. Never invent a match; it is safer to ask than to edit the wrong
  transaction. Do NOT treat a delete ("удали трату") or a new spend as an edit.

Return only the JSON object.
