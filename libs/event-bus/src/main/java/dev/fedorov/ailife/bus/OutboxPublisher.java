package dev.fedorov.ailife.bus;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes an event to {@code bus.outbox} and fires a {@code pg_notify} wake-up on the
 * same connection, so the durable row and the notification commit atomically with the
 * caller's surrounding transaction (transactional-outbox pattern).
 *
 * <p>The insert is the source of truth; the NOTIFY is only a best-effort wake — a
 * listener that misses it still drains the row on its next poll (at-least-once).
 */
public class OutboxPublisher {

    private final JdbcTemplate jdbc;
    private final String channel;

    public OutboxPublisher(JdbcTemplate jdbc, String channel) {
        this.jdbc = jdbc;
        this.channel = channel;
    }

    /** Convenience overload using the default {@link EventBus#DEFAULT_CHANNEL}. */
    public OutboxPublisher(JdbcTemplate jdbc) {
        this(jdbc, EventBus.DEFAULT_CHANNEL);
    }

    /**
     * Persist an event and wake any listeners. {@code householdId} may be null
     * (system-wide event); {@code payloadJson} must be a valid JSON document.
     *
     * @return the stored envelope (with the generated id + persisted timestamp)
     */
    public EventBusMessage publish(String topic, UUID householdId, String payloadJson) {
        UUID id = UUID.randomUUID();
        String payload = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson;

        jdbc.update("""
                INSERT INTO bus.outbox (id, topic, household_id, payload, status)
                VALUES (?, ?, ?, ?::jsonb, 'PENDING')
                """, id, topic, householdId, payload);

        // pg_notify delivers at commit; running it on the same JdbcTemplate keeps it
        // inside the caller's transaction so insert + wake are all-or-nothing.
        jdbc.queryForObject("SELECT pg_notify(?, ?)", String.class, channel, id.toString());

        return new EventBusMessage(id, topic, householdId, payload, Instant.now());
    }
}
