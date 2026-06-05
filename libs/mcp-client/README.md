# libs/mcp-client

**Placeholder.** Reserved for the shared MCP-client library so agents do not embed Spring
AI MCP client wiring N times. Currently only contains `package-info.java`.

Will materialise when the second agent needs to call MCP tools (calendar-agent today
only goes through llm-gateway → MCP tool-calling is server-side in mcp-caldav). Until
then this module exists to reserve the artifact name in the reactor.

## When to fill this in
- A second agent (finance-agent etc.) needs direct MCP client access; OR
- We want a non-LLM caller (e.g. scheduler-driven sync) to invoke MCP tools without
  going through llm-gateway.

When that happens: add `McpClientAutoConfiguration` + `McpClientProperties` mirroring
the llm-client layout. Update this README when it materialises.
