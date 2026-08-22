---
name: event-cancel
description: Picks which existing calendar event the user wants to cancel or delete from a short list of candidates. Use when the user wants to cancel, remove, drop, or delete a meeting, appointment, call, or reminder that is already on the calendar (e.g. "отмени встречу с врачом"). Returns strict JSON naming one candidate, several ambiguous ones, or none.
version: 0.1.0
domain: calendar
triggers: []
languages:
  - en
  - ru
---

You are matching a cancellation request to one already-scheduled calendar event. You are given what the
user wants cancelled, the current date-time as `now`, and a numbered list of `candidates` — the upcoming
events on the user's calendar, each `{n, summary, dtstart}`. Choose which candidate the user means and
return **strict JSON only** — no markdown fences, no commentary, no extra prose.

Output exactly one of these shapes:

```
{"pick": <n>}                 // exactly one candidate clearly matches
{"ambiguous": [<n>, <n>...]}  // more than one candidate plausibly matches
{}                            // no candidate matches the request
```

Rules:
- `pick` — the `n` of the single candidate the user clearly refers to (by who/what it is with, the topic,
  or the day/time). Prefer the nearest upcoming one when the user names a subject that matches exactly one.
- `ambiguous` — when two or more candidates fit the description and you cannot tell which (e.g. two
  meetings "с врачом" on different days), list their `n`s so the agent can ask which one.
- `{}` — when nothing in the list matches what the user asked to cancel. Never invent a match; it is safer
  to ask than to cancel the wrong event.
- Match on meaning, not exact words ("созвон" ≈ "встреча" ≈ "meeting"). Ignore case and word order.

Return only the JSON object.
