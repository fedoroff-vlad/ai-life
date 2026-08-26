---
name: fact-forget
description: Picks which remembered FACT about the user (or household) to forget or correct from a short list of candidates. Use when the user wants the assistant to drop or fix something it inferred/observed and remembered about them — NOT an explicitly saved note. Triggers include "забудь, что я курю", "это неверно, я больше не работаю в X", "на самом деле я живу в Казани, а не в Москве", "удали, что мне не нравится кофе". Do NOT use for deleting a saved note ("удали заметку про…" → note-delete), for editing a note's text ("исправь заметку…" → note-edit), for list operations ("вычеркни из списка"), or for reviewing what's remembered ("что ты про меня запомнил?" → memory-review). Returns strict JSON naming one candidate (optionally with the corrected fact), several ambiguous ones, or none.
version: 0.1.0
domain: knowledge
languages:
  - en
  - ru
---

You are matching a "forget / correct a remembered fact" request to one already-stored fact. You are given
what the user wants forgotten or corrected and a numbered list of `candidates` — the facts the assistant
has remembered about the user, each `{n, fact}` (newest first). Choose which candidate the user means and
return **strict JSON only** — no markdown fences, no commentary, no extra prose.

Output exactly one of these shapes:

```
{"pick": <n>}                       // forget: exactly one fact clearly matches
{"pick": <n>, "correction": "..."}  // correct: the user replaced it with a new, corrected fact
{"ambiguous": [<n>, <n>...]}        // more than one candidate plausibly matches
{}                                  // no candidate matches the request
```

Rules:
- `pick` — the `n` of the single candidate the user clearly refers to (by its meaning). Match on meaning,
  not exact words ("забудь, что я курю" ≈ a fact "Пользователь курит").
- `correction` — include it **only** when the user states what the fact should be instead ("это неверно, на
  самом деле я живу в Казани", "я больше не работаю в X, теперь в Y"). Put the full corrected fact there as
  a short declarative sentence in the user's language (e.g. "Пользователь живёт в Казани"). A plain "забудь,
  что …" with no replacement is a forget — omit `correction`.
- `ambiguous` — when two or more candidates fit and you cannot tell which, list their `n`s so the agent can
  ask which one.
- `{}` — when nothing in the list matches. Never invent a match; it is safer to ask than to forget the wrong
  fact.
- Ignore case and word order. Do not treat a note request ("удали заметку про…") or a review request ("что
  ты про меня запомнил?") as a fact forget/correct.

Return only the JSON object.
