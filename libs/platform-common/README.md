# libs/platform-common

Cross-cutting platform concerns shared by every runtime service (platform/*, agents/*,
mcp/*). Logging MDC, error envelopes, metrics, request context.

Currently only contains `package-info.java` as a placeholder — most services do not yet
need shared MDC. The artifact still ships because every other module depends on it via
`pom.xml`, so adding the first shared bean here doesn't require a wave of pom updates.

## When to fill this in
- First request-id MDC filter you find yourself copy-pasting into a second service.
- Shared error-response envelope.
- Shared metrics tags (household_id, agent name) when Prometheus dashboards demand
  consistency.

When you add a bean here, it must be opt-in (`@ConditionalOnProperty` or
auto-config-gated) — modules pull this lib in transitively and we don't want surprises.
