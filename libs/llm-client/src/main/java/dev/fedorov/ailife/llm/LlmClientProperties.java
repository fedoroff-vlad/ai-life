package dev.fedorov.ailife.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ailife.llm-client")
public class LlmClientProperties {

    /** Base URL of llm-gateway, e.g. {@code http://llm-gateway:8081}. */
    private String baseUrl = "http://llm-gateway:8081";

    private final Resilience resilience = new Resilience();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Resilience getResilience() {
        return resilience;
    }

    /**
     * Resilience knobs for the synchronous gateway call (#631). Internal tunables with safe
     * defaults — override under {@code ailife.llm-client.resilience.*} only when a deployment needs
     * to. Defaults suit a local model (generous timeout, a few short retries, a forgiving breaker).
     */
    public static class Resilience {
        /** Per-request response timeout. Generous on purpose — a local model can be slow. */
        private Duration timeout = Duration.ofSeconds(60);
        /** Total attempts on a transient failure (1 = no retry). */
        private int maxAttempts = 3;
        /** Backoff before the first retry; grows exponentially (×2) with ±50% jitter. */
        private Duration retryBackoff = Duration.ofMillis(300);
        /** Circuit-breaker sliding window (number of recent calls it scores). */
        private int slidingWindowSize = 20;
        /** Minimum calls in the window before the breaker may trip (avoids flapping on a cold start). */
        private int minimumNumberOfCalls = 10;
        /** Failure-rate % across the window that trips the breaker open. */
        private float failureRateThreshold = 50f;
        /** How long the breaker stays open before it half-opens to probe recovery. */
        private Duration waitInOpenState = Duration.ofSeconds(30);

        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Duration getRetryBackoff() { return retryBackoff; }
        public void setRetryBackoff(Duration retryBackoff) { this.retryBackoff = retryBackoff; }
        public int getSlidingWindowSize() { return slidingWindowSize; }
        public void setSlidingWindowSize(int slidingWindowSize) { this.slidingWindowSize = slidingWindowSize; }
        public int getMinimumNumberOfCalls() { return minimumNumberOfCalls; }
        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }
        public float getFailureRateThreshold() { return failureRateThreshold; }
        public void setFailureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }
        public Duration getWaitInOpenState() { return waitInOpenState; }
        public void setWaitInOpenState(Duration waitInOpenState) { this.waitInOpenState = waitInOpenState; }
    }
}
