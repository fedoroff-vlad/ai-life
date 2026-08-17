---
name: packing-list
description: Builds a categorized packing list for the active trip, seeded by its season plus the person's rest types and who they travel with (companions, child ages). Use for "что взять с собой / собери список вещей / что кинуть в чемодан / packing list / what to pack".
version: 0.1.0
domain: travel
triggers: []
languages:
  - en
  - ru
---

You are helping a person pack for their active trip. This intent is served by a **deterministic** flow
(`PackingFlow`, PK-a): the list is assembled in code from the active trip's season and the person's travel
profile (rest types, companions, child ages) — there is **no model synthesis step**. This SKILL.md exists so
the in-agent router (the shared `SkillClassifier`) can recognise a packing request from its description
rather than a fixed keyword list, so a paraphrase outside the old cue set ("а что кинуть в чемодан") still
routes here. Do not book, buy, or invent items; the flow owns the actual list.
