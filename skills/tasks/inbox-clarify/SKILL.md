---
name: inbox-clarify
description: Proposes a GTD clarification for the household's un-clarified inbox items — for each captured item, whether it is actionable, which project it belongs to, the next physical action, and a context tag. Reactive (user-invoked, e.g. "разбери инбокс" / "help me clarify my inbox"); emits a structured set of proposals the agent applies only after the user confirms.
version: 0.2.0
domain: tasks
triggers: []
languages:
  - en
  - ru
inputs:
  - name: inbox
    description: List of un-clarified inbox items (each carries id, title, and optional note) to clarify.
  - name: userText
    description: The user's original request, for any extra hint (e.g. "только покупки").
---

You run a GTD clarification pass over the household's captured inbox items and emit a STRUCTURED set
of proposed clarifications. You do not apply anything — the agent applies your proposals only after
the user confirms.

Read the input payload: `inbox` is a list of captured items (each has `id`, `title`, maybe a `note`);
`userText` is the user's original request.

For EACH inbox item you choose to clarify, decide:

- Is it actionable? If not, propose `dropped` (not actionable) — keep these rare.
- If actionable, the status that fits: `next` (a single next-action), `waiting` (delegated), or
  `scheduled` (has a hard date / should be deferred).
- A context tag for next-actions: `@home`, `@work`, `@errand`, `@calls`, `@computer`, … — pick the
  most natural one. Omit context for non-`next` statuses.

Output contract — reply with **strict JSON only**, no markdown fences, no prose:

```
{"proposals":[
  {"taskId":"<the item's id, verbatim>","title":"<the item's title>","status":"next","context":"@errand"}
]}
```

Rules:

- `taskId` MUST be copied verbatim from the matching inbox item's `id` — never invent one. Skip an
  item rather than guess its id.
- Include `context` only for `status":"next"`; omit it otherwise. Omit `projectId` unless you are
  given a concrete project id.
- If `userText` narrows the scope (e.g. "только звонки"), include only the matching items.
- If there is nothing sensible to clarify, reply `{"proposals":[]}`.

The agent renders your proposals into a human-readable confirmation for the user and applies them
(via `clarify_task`) only after an affirmative reply.
