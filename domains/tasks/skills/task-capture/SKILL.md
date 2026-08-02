---
name: task-capture
description: Use when the user wants to capture / remember a task or todo — e.g. «напомни купить молоко», «не забыть позвонить врачу», «нужно вынести мусор — это общее», «add buy flights». Turns the request into a task the agent captures and routes to the personal or shared household. Prefer this over the add_task tool for a plain capture — this path routes the task to the right household (the tool cannot).
version: 0.1.0
domain: tasks
triggers: []
languages:
  - en
  - ru
---

You turn a user's request to **capture a task** into a strict-JSON plan. The agent captures the task and
routes it to the right household (personal vs shared) itself — your only job is to produce the plan. You are
given a JSON object with:

- `userText` — the user's request in their own words (e.g. "напомни купить молоко", "нужно вынести мусор —
  это общее дело", "add buy flights for the trip").

Reply with **strict JSON ONLY** — no markdown fences, no commentary. Shape:

```
{"title":"<short task title>","note":"<optional detail>","shared":false}
```

Rules:
- `title` — the task in the user's own wording, as a short actionable phrase ("купить молоко", "позвонить
  врачу", "buy flights"). Strip filler like "напомни"/"не забыть"/"add" — keep the action itself. Required.
- `note` — any extra detail worth keeping (a phone number, a deadline hint, a where/why). Omit the field
  when the user gave none — do not invent one.
- `shared` — `true` **only** when the task clearly belongs on the **household / shared list**: a chore, a
  shared shopping item, or a task that involves another household member ("это общее", "общий список",
  "для семьи", "нам нужно", "купить домой", household errands). Otherwise `false` (a personal todo). This
  is what makes the task land in the shared household instead of the user's personal one — so set it
  deliberately, and default to `false` when the user did not clearly signal it is shared.
- If the request does not describe a task to capture, reply `{}`.
- Never invent a task the user did not ask for.
