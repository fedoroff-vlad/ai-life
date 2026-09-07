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
| `ailife.llm-client.resilience.timeout` | `60s` | Per-request response timeout (generous — local models are slow). |
| `ailife.llm-client.resilience.max-attempts` | `3` | Total attempts on a transient failure (`1` = no retry). |
| `ailife.llm-client.resilience.retry-backoff` | `300ms` | First-retry backoff; grows ×2 with ±50% jitter. |
| `ailife.llm-client.resilience.sliding-window-size` | `20` | Circuit-breaker window (recent calls scored). |
| `ailife.llm-client.resilience.minimum-number-of-calls` | `10` | Min calls before the breaker may trip. |
| `ailife.llm-client.resilience.failure-rate-threshold` | `50` | Failure-% across the window that opens the breaker. |
| `ailife.llm-client.resilience.wait-in-open-state` | `30s` | How long the breaker stays open before half-opening. |

## Resilience (#631)
The gateway is a synchronous dependency of every agent turn, so `LlmClient` wraps each **unary** call
(`chat`, `embed`) in: a response **timeout**, a bounded **retry** that fires only on *transient*
failures (connect errors, timeouts, 5xx) with exponential backoff + jitter, and a **circuit breaker**
(`CB(Retry(call))` — breaker outermost) that trips open after sustained failure so calls fail fast
(`CallNotPermittedException`) instead of piling onto a dead gateway. A **4xx is never retried and
never trips the breaker** (it's a caller error). **`chatStream` is guarded by the breaker + timeout
but not retried** — re-subscribing would replay already-emitted tokens. All knobs are internal
tunables (table above) with safe defaults.

## Key classes
- `LlmClient` — three methods: `chat`, `chatStream` (SSE), `embed`. Reactive (`Mono`/`Flux`).
  Resilience operators are applied here; `isTransientFailure` is the shared retry/record predicate.
- `LlmClientProperties` — `@ConfigurationProperties("ailife.llm-client")`, incl. the `resilience` block.
- `LlmClientAutoConfiguration` — builds the `WebClient`, the `CircuitBreaker` + `Retry` beans, and
  the `LlmClient`. Note: this autoconfig does NOT `.clone()` the shared builder — for outbound
  consumers that compose multiple clients, clone in your `@Configuration`
  (see `OutboundHttpConfig` in calendar-agent for the pattern).
