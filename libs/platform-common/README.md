# libs/platform-common

Cross-cutting platform concerns shared by every runtime service (platform/*, agents/*,
mcp/*). Logging MDC, error envelopes, metrics, request context — plus small pure
utilities that a second module needs (the "second consumer lifts it" rule).

## Contents
- `jackson/Jackson3JsonFormatMapper` — Hibernate `AbstractJsonFormatMapper` over Jackson 3
  (`tools.jackson`), opted into per JPA service via `hibernate.type.json_format_mapper`
  (Hibernate is `provided` here so non-JPA consumers don't pull it).
- `list/MarkdownChecklist` — pure, immutable CommonMark task-list util (parse/render + idempotent
  add / check / clear) for the lists capability (plans/lists.md). Consumed by notes-agent's
  `ListManager` (LI-a explicit ops) and memory-service's ambient list capture (LI-b); lifted here when
  memory-service became the second consumer. Depends on nothing but the JDK.
- `package-info.java` — package marker.

## When to add here
- A cross-cutting bean (request-id MDC filter, error-response envelope, shared metrics tags) you find
  yourself copy-pasting into a second service. A bean must be opt-in
  (`@ConditionalOnProperty` / auto-config-gated) — modules pull this lib in transitively and we don't
  want surprises.
- A small **pure** utility (no Spring, no I/O) a second module needs — lift it here rather than copy it.

When you add a bean here, it must be opt-in (`@ConditionalOnProperty` or
auto-config-gated) — modules pull this lib in transitively and we don't want surprises.
