package dev.fedorov.ailife.llm;

import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Resilience wiring for the synchronous LLM path (#631): transient failures are retried with
 * backoff, caller errors (4xx) are not, and sustained failure trips the breaker so calls fail fast.
 */
class LlmClientResilienceTest {

    private MockWebServer server;

    private static final LlmChatRequest REQUEST =
            LlmChatRequest.of(LlmChannel.DEFAULT, List.of(LlmMessage.user("hi")));
    private static final String OK_BODY =
            "{\"model\":\"m\",\"content\":\"hello\",\"finishReason\":\"stop\"}";

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private LlmClient client(CircuitBreaker cb, Retry retry) {
        WebClient http = WebClient.builder()
                .baseUrl("http://localhost:" + server.getPort())
                .build();
        return new LlmClient(http, cb, retry, Duration.ofSeconds(5));
    }

    private static Retry retry(int maxAttempts) {
        return Retry.of("test", RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        Duration.ofMillis(10), 2.0, 0.5))
                .retryOnException(LlmClient::isTransientFailure)
                .failAfterMaxAttempts(true)
                .build());
    }

    private static CircuitBreaker breaker(int window, int min) {
        return CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(window)
                .minimumNumberOfCalls(min)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordException(LlmClient::isTransientFailure)
                .build());
    }

    @Test
    void retriesTransient5xxThenSucceeds() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse()
                .setHeader("content-type", "application/json").setBody(OK_BODY));

        // Large window so the breaker never trips during the retries.
        LlmChatResponse response = client(breaker(100, 50), retry(3)).chat(REQUEST).block();

        assertThat(response).isNotNull();
        assertThat(response.content()).isEqualTo("hello");
        assertThat(server.getRequestCount()).isEqualTo(3); // two failures + the success
    }

    @Test
    void doesNotRetryClientError4xx() {
        server.enqueue(new MockResponse().setResponseCode(400));

        assertThatThrownBy(() -> client(breaker(100, 50), retry(3)).chat(REQUEST).block())
                .isInstanceOf(WebClientResponseException.class);

        assertThat(server.getRequestCount()).isEqualTo(1); // a caller error is not retried
    }

    @Test
    void circuitBreakerOpensAfterSustainedFailureAndFailsFast() {
        // No retry (each chat = one call); breaker opens after 2 failing calls.
        CircuitBreaker cb = breaker(2, 2);
        Retry noRetry = retry(1);
        LlmClient client = client(cb, noRetry);

        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> client.chat(REQUEST).block())
                .isInstanceOf(WebClientResponseException.class);
        assertThatThrownBy(() -> client.chat(REQUEST).block())
                .isInstanceOf(WebClientResponseException.class);

        // The breaker is now OPEN: the third call fails fast without ever reaching the gateway.
        assertThatThrownBy(() -> client.chat(REQUEST).block())
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(server.getRequestCount()).isEqualTo(2); // no request for the fast-failed call
    }
}
