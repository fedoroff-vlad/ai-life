package dev.fedorov.ailife.scheduler.domain;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Tiny wrapper around Spring's {@link CronExpression}. UTC throughout — schedule
 * payloads carry their own tz when needed.
 */
@Component
public class NextRunCalculator {

    /** Returns the next run after {@code after}, or {@link Optional#empty()} if the cron is malformed or has no future fire. */
    public Optional<Instant> next(String cron, Instant after) {
        if (cron == null || cron.isBlank()) {
            return Optional.empty();
        }
        try {
            CronExpression expr = CronExpression.parse(cron);
            ZonedDateTime nextZ = expr.next(ZonedDateTime.ofInstant(after, ZoneOffset.UTC));
            return Optional.ofNullable(nextZ).map(ZonedDateTime::toInstant);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
