# libs/llm-client

Reactive client for `llm-gateway`. Any service that calls the LLM depends on this lib
instead of speaking HTTP to the gateway directly. Agents never know which provider is
active — they pick a `LlmChannel` (`default` / `fast` / `vision` / `embedding`) and the
gateway routes.

## Auto-config
Spring Boot auto-configuration kicks in via
[META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports](src/main/resources/META-INF/spring).
Adding the dep is enough — no `@Import` needed.

| Property | Default | Purpose |
|---|---|---|
| `ailife.llm-client.base-url` | `http://llm-gateway:8081` | Where llm-gateway lives. |

## Key classes
- `LlmClient` — three methods: `chat`, `chatStream` (SSE), `embed`. Reactive (`Mono`/`Flux`).
- `LlmClientProperties` — `@ConfigurationProperties("ailife.llm-client")`.
- `LlmClientAutoConfiguration` — builds the bean from a shared `WebClient.Builder`.
  Note: this autoconfig does NOT `.clone()` the shared builder — for outbound
  consumers that compose multiple clients, clone in your `@Configuration`
  (see `OutboundHttpConfig` in calendar-agent for the pattern).
