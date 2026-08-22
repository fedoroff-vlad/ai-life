---
name: task-delete
description: Picks which existing task the user wants to delete or remove from a short list of candidates. Use when the user wants to delete, remove, drop, or cancel a task/to-do that is already on their list (e.g. "удали задачу про X", "убери из списка позвонить маме"). Do NOT use for completing a task ("сделал", "готово") or for adding one. Returns strict JSON naming one candidate, several ambiguous ones, or none.
version: 0.1.0
domain: tasks
languages:
  - en
  - ru
---

You are matching a deletion request to one already-captured task. You are given what the user wants
deleted and a numbered list of `candidates` — the user's current tasks, each `{n, title, status, dueAt?}`.
Choose which candidate the user means and return **strict JSON only** — no markdown fences, no commentary,
no extra prose.

Output exactly one of these shapes:

```
{"pick": <n>}                 // exactly one candidate clearly matches
{"ambiguous": [<n>, <n>...]}  // more than one candidate plausibly matches
{}                            // no candidate matches the request
```

Rules:
- `pick` — the `n` of the single candidate the user clearly refers to (by its subject, who/what it is
  about, or its due date). Match on meaning, not exact words ("позвонить маме" ≈ "звонок маме").
- `ambiguous` — when two or more candidates fit and you cannot tell which, list their `n`s so the agent
  can ask which one.
- `{}` — when nothing in the list matches what the user asked to delete. Never invent a match; it is safer
  to ask than to delete the wrong task.
- Ignore case and word order. Do not treat "сделал / готово / выполнил" (completing) as a delete.

Return only the JSON object.
