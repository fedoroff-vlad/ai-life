---
name: event-move
description: Picks which existing calendar event the user wants to move or reschedule and resolves the new time. Use when the user wants to reschedule, move, shift, postpone, or bring forward a meeting, appointment, call, or reminder already on the calendar (e.g. "перенеси встречу с врачом на 16:00"). Returns strict JSON naming one candidate plus the new start/end, several ambiguous ones, or none.
version: 0.1.0
domain: calendar
triggers: []
languages:
  - en
  - ru
---

You are matching a reschedule request to one already-scheduled calendar event and resolving its new time.
You are given what the user wants moved, the current date-time as `now`, and a numbered list of
`candidates` — the upcoming events on the user's calendar, each `{n, summary, dtstart}`. Choose which
candidate the user means and compute the new start (and end, if given) as **strict JSON only** — no
markdown fences, no commentary, no extra prose.

Output exactly one of these shapes:

```
{"pick": <n>, "dtstart": "<ISO-8601 instant>", "dtend": "<ISO-8601 instant or omit>"}
{"pick": <n>}                 // the target is clear but the user gave no new time → the agent will ask
{"ambiguous": [<n>, <n>...]}  // more than one candidate plausibly matches
{}                            // no candidate matches the request
```

Rules:
- `pick` — the `n` of the single candidate the user refers to (by who/what it is with, the topic, or its
  current day/time). Match on meaning, not exact words ("созвон" ≈ "встреча" ≈ "meeting"); ignore case.
- `dtstart` — the event's **new** start as an ISO-8601 instant, resolved from `now` and, when the user only
  gives a wall-clock time ("на 16:00"), from the picked candidate's current date. Interpret the time the
  user gave literally; carry `now`'s offset. Omit `dtstart` only when the user named the event but gave no
  new time at all — then return just `{"pick": <n>}` so the agent asks for the time.
- `dtend` — include only when the user gave a new end or duration ("до 17:00", "на два часа"); otherwise
  omit it. Never set an end at or before the start.
- `ambiguous` — when two or more candidates fit and you cannot tell which, list their `n`s.
- `{}` — when nothing in the list matches. Never invent a match; it is safer to ask than to move the wrong
  event.

Return only the JSON object.
