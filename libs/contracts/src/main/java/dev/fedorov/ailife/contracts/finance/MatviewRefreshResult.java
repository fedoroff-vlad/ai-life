package dev.fedorov.ailife.contracts.finance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Result of refreshing the finance reporting matviews.
 * {@code refreshed} lists the fully-qualified matview names that were
 * recomputed, in refresh order; {@code refreshedAt} is when the refresh
 * completed (server clock, UTC).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MatviewRefreshResult(
        List<String> refreshed,
        Instant refreshedAt) {
}
