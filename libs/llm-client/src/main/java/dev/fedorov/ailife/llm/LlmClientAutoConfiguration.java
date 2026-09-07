package dev.fedorov.ailife.llm;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

@AutoConfiguration
@EnableConfigurationProperties(LlmClientProperties.class)
public class LlmClientAutoConfiguration {

    @Bean
    public WebClient llmGatewayWebClient(LlmClientProperties props, WebClient.Builder builder) {
        return builder.baseUrl(props.getBaseUrl()).build();
    }

    /** Trips open after sustained transient failure so calls fail fast instead of piling on a dead gateway. */
    @Bean
    public CircuitBreaker llmCircuitBreaker(LlmClientProperties props) {
        LlmClientProperties.Resilience r = props.getResilience();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(r.getSlidingWindowSize())
                .minimumNumberOfCalls(r.getMinimumNumberOfCalls())
                .failureRateThreshold(r.getFailureRateThreshold())
                .waitDurationInOpenState(r.getWaitInOpenState())
                // A 4xx is a caller error, not gateway ill-health — don't let it trip the breaker.
                .recordException(LlmClient::isTransientFailure)
                .build();
        return CircuitBreaker.of("llm-gateway", config);
    }

    /** Bounded retry on transient failures only, with exponential backoff + jitter. */
    @Bean
    public Retry llmRetry(LlmClientProperties props) {
        LlmClientProperties.Resilience r = props.getResilience();
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(r.getMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        r.getRetryBackoff(), 2.0, 0.5))
                .retryOnException(LlmClient::isTransientFailure)
                .failAfterMaxAttempts(true)
                .build();
        return Retry.of("llm-gateway", config);
    }

    @Bean
    public LlmClient llmClient(WebClient llmGatewayWebClient,
                               CircuitBreaker llmCircuitBreaker,
                               Retry llmRetry,
                               LlmClientProperties props) {
        return new LlmClient(llmGatewayWebClient, llmCircuitBreaker, llmRetry,
                props.getResilience().getTimeout());
    }
}
