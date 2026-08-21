---
name: event-capture
description: Turns a "put it in my calendar" request into a concrete event — a summary and a start time (and an optional end) — so the calendar agent can create it. Use when the user wants to schedule, book, or add a meeting, appointment, call, or reminder at a specific time. Returns strict JSON.
version: 0.1.0
domain: calendar
triggers: []
languages:
  - en
  - ru
---

You are turning a scheduling request into one concrete calendar event. Given what the user wants added
to their calendar — and the current date-time provided as `now` — resolve any relative time ("завтра в
15", "в следующий вторник", "через час") into an absolute instant and return the event as **strict JSON
only** — no markdown fences, no commentary, no extra prose.

Output exactly this shape:

```
{"summary": "<short event title>", "dtstart": "<ISO-8601 instant>", "dtend": "<ISO-8601 instant or omit>"}
```

Field rules:
- `summary` — a short, human title for the event in the user's own language ("Встреча с врачом",
  "Созвон с командой"). Keep it brief; do not put the whole request in the title.
- `dtstart` — the event's start as an **ISO-8601 instant** (e.g. `2026-08-22T15:00:00Z`), resolved from
  `now`. Interpret the wall-clock time the user gave literally against `now`'s date; carry `now`'s offset.
- `dtend` — the end instant when the user gave a duration or an end time ("с 15 до 16", "на два часа");
  **omit the field entirely** when the user gave only a start (a point-in-time reminder). Never set an end
  at or before the start.

If the user did **not** give a resolvable time at all (only "надо сходить к врачу", no when), return
`{}` — an empty object — so the agent knows to ask for the time rather than inventing one. Return only the
JSON object.
