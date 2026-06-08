---
name: bad-skill
description: Frontmatter with an unquoted colon-space inside a value — the exact shape that silently broke transaction-categorizer in the PR31 session. SnakeYAML rejects this as "mapping values are not allowed here".
version: 0.0.1
domain: test
triggers:
  - test.bad
languages:
  - en
inputs:
  - name: example
    description: This value triggers the YAML parser bug: a colon followed by a space inside an unquoted scalar is treated as a nested key.
---

Body of the bad skill — never parsed because frontmatter explodes first.
