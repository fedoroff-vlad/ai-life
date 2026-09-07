package dev.fedorov.ailife.llm;

import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmEmbedRequest;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Thin reactive client for llm-gateway. Agents do not know which provider is active —
 * they just pick a channel ({@code default} / {@code fast} / {@code vision} / {@code embedding}).
 *
 * <p><b>Resilience (#631).</b> The gateway is a synchronous dependency of every agent turn, so a
 * degraded gateway must not hang or stampede the caller. Each unary call carries a response
 * {@link #timeout}, a bounded {@link Retry} that fires <em>only</em> on transient failures
 * (connect errors, request timeouts, 5xx) with exponential backoff, and a {@link CircuitBreaker}
 * that trips open after sustained failure so further calls fail fast instead of piling onto a dead
 * gateway. The circuit breaker sits <em>outside</em> the retry (CB(Retry(call))): a fully-retried
 * call records one outcome, and once the breaker is open no retries are even attempted. A 4xx is a
 * caller error — never retried, never counted against the breaker. Streaming is guarded by the
 * breaker + timeout but <b>not retried</b> (a re-subscribe would replay already-emitted tokens).
 */
public class LlmClient {

    private final WebClient http;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Duration timeout;

    public LlmClient(WebClient http, CircuitBreaker circuitBreaker, Retry retry, Duration timeout) {
        this.http = http;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.timeout = timeout;
    }

    public Mono<LlmChatResponse> chat(LlmChatRequest request) {
        return resilient(http.post()
                .uri("/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(LlmChatResponse.class));
    }

    public Flux<String> chatStream(LlmChatRequest request) {
        return http.post()
                .uri("/v1/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                // A stream can't be safely retried (re-subscribe replays emitted tokens), but the
                // breaker + timeout still protect the caller from a hung/failing gateway.
                .timeout(timeout)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<LlmEmbedResponse> embed(LlmEmbedRequest request) {
        return resilient(http.post()
                .uri("/v1/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(LlmEmbedResponse.class));
    }

    /** timeout → retry (transient only, backoff) → circuit breaker (outermost). */
    private <T> Mono<T> resilient(Mono<T> call) {
        return call
                .timeout(timeout)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    /**
     * A failure worth retrying / counting against the breaker: a transport error (connect refused,
     * DNS, reset), a response timeout, or a 5xx from the gateway. A 4xx is a caller mistake — not
     * transient — so it is neither retried nor recorded as a breaker failure.
     */
    public static boolean isTransientFailure(Throwable t) {
        if (t instanceof WebClientResponseException e) {
            return e.getStatusCode().is5xxServerError();
        }
        return t instanceof WebClientRequestException || t instanceof TimeoutException;
    }
}
