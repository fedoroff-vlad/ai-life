package dev.fedorov.ailife.inbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for the durable inbound inbox. {@code enabled} gates the redrive loop (the consumer side)
 * only — the {@link InboxWriter} producer is always available so an ingress can persist-before-process
 * even where nothing redrives yet.
 */
@ConfigurationProperties(prefix = "inbox")
public class InboxProperties {

    /** Start the redrive container on context start. The writer is unaffected. */
    private boolean enabled = true;

    /** How long the redrive loop sleeps between drain passes. */
    private Duration pollInterval = Duration.ofSeconds(15);

    /**
     * Grace delay before a freshly recorded row becomes eligible for redrive — long enough for the
     * synchronous ingress attempt to finish and mark it {@code PROCESSED}, so the poll-based redriver
     * only ever picks up rows the in-request path failed to complete (no double-dispatch race, no
     * LISTEN/NOTIFY needed — unlike the outbound bus, inbound redrive latency of tens of seconds is fine).
     */
    private Duration initialDelay = Duration.ofSeconds(30);

    /** Base of the exponential backoff between redrive attempts. */
    private Duration backoff = Duration.ofSeconds(30);

    /** Ceiling for the exponential backoff. */
    private Duration maxBackoff = Duration.ofMinutes(10);

    /** After this many failed attempts a row goes terminal {@code DEAD} and the dead-letter hook fires. */
    private int maxAttempts = 6;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
        this.initialDelay = initialDelay;
    }

    public Duration getBackoff() {
        return backoff;
    }

    public void setBackoff(Duration backoff) {
        this.backoff = backoff;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }
}
