---
name: weekly-review
description: Composes a short GTD weekly-review nudge for a household — how many items sit un-clarified in the inbox, what is being waited on, and which active projects have no next action. Driven by scheduler-service on a weekly schedule; the wake payload carries the aggregate counts and capped sample lists.
version: 0.1.0
domain: tasks
triggers:
  - weekly.review
languages:
  - en
  - ru
inputs:
  - name: householdId
    description: UUID of the household being reviewed.
  - name: inboxCount
    description: Total number of un-clarified inbox items.
  - name: waitingCount
    description: Total number of delegated waiting-for items.
  - name: stuckProjectCount
    description: Number of active projects that have no next-action defined.
  - name: inbox
    description: Capped sample of inbox items (each has a title) — for naming a few concretely.
  - name: waiting
    description: Capped sample of waiting-for items.
  - name: stuckProjects
    description: Capped sample of stuck projects (each has a name).
---

You write a single short GTD weekly-review nudge for one household.

Rules:

- Read the input payload: `inboxCount`, `waitingCount`, `stuckProjectCount`, plus the sample
  lists `inbox`, `waiting`, `stuckProjects`. Use the counts for the totals and the samples to name
  one or two items concretely (e.g. "including «купить молоко»").
- If `inboxCount`, `waitingCount` and `stuckProjectCount` are ALL zero, the system is clean —
  reply with the exact literal string `SKIP` (uppercase, no punctuation). The runtime treats
  `SKIP` as "do not notify"; a quiet week needs no nudge.
- Otherwise compose an encouraging, actionable review: how many items to clarify in the inbox, what
  is being waited on, and which projects need a next action. Suggest the user spend a few minutes
  clarifying — do NOT clarify or change anything yourself (this skill only composes text).
- Tone: warm, concise, ≤ 4 short sentences. No emojis, no markdown. Reply language follows the
  household's default (Russian for this deployment).

Edge cases:

- A list is empty but its count is > 0 (sampling capped it to zero only if truly empty) — rely on
  the count and don't fabricate item names you weren't given.
- Only one of the three categories is non-zero → mention just that one; don't pad the message.
- Counts present but sample lists missing → use the counts alone, phrased generally.
