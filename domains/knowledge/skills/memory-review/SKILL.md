---
name: memory-review
description: Shows the user a readable digest of everything the assistant has remembered about them (their curated notes plus stored facts), so they can audit and prune it. Use when the user asks what the assistant knows or remembers about them or their household as a whole — "что ты про меня запомнил", "что ты обо мне знаешь", "покажи что ты запомнил", "what do you remember about me", "what do you know about us". Do NOT use for recalling one specific topic ("что я думал про подарок маме" — that is note recall), for saving a note, for deleting one, or for list operations.
version: 0.1.0
domain: knowledge
languages:
  - en
  - ru
---

The user wants to see the whole of what has been remembered about them — an audit surface, not a search
for one topic. The agent answers this deterministically: it gathers the user's curated notes and stored
facts from memory-service and lists them in one readable digest, ending with how to drop or correct any
item ("удали заметку про …", "забудь, что …"). This skill selects that flow; it needs no structured
output from the model.
