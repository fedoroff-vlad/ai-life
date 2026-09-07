package dev.fedorov.ailife.inbox;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

/**
 * Persists an inbound message to {@code bus.inbox} <b>before</b> it is dispatched downstream, so a
 * downstream outage (or a mid-request crash/restart) cannot silently drop it — the durable row is the
 * source of truth and {@link PostgresInboxRedriver} replays anything left un-{@code PROCESSED}.
 *
 * <p>Mirror of {@code OutboxPublisher}, opposite direction: the outbox durably records outbound events
 * on their way out; the inbox durably records inbound user messages on their way in.
 *
 * <p>Idempotency: {@link #record} inserts {@code ON CONFLICT (dedup_key) DO NOTHING}, so a re-recorded
 * update (Telegram re-delivering an unacked one, or a redrive re-entering) can never double-persist.
 * A freshly inserted row is scheduled {@code next_attempt_at = now() + initialDelay}: the in-request
 * path has that grace window to finish and {@link #markProcessed}, so the poll-based redriver only ever
 * claims rows the synchronous attempt failed to complete.
 */
public class InboxWriter {

    private final JdbcTemplate jdbc;
    private final Duration initialDelay;

    public InboxWriter(JdbcTemplate jdbc, Duration initialDelay) {
        this.jdbc = jdbc;
        this.initialDelay = initialDelay;
    }

    /**
     * Persist an inbound message as {@code PENDING}. {@code dedupKey} is the ingress-idempotency key
     * (Telegram {@code update_id}); {@code payloadJson} must be a valid JSON document (the caller's
     * serialised envelope).
     *
     * @return {@code true} if a new row was inserted, {@code false} if {@code dedupKey} was already
     *         recorded (duplicate — nothing written)
     */
    public boolean record(String dedupKey, String payloadJson) {
        String payload = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson;
        long graceSeconds = Math.max(0, initialDelay.getSeconds());
        int inserted = jdbc.update("""
                INSERT INTO bus.inbox (dedup_key, payload, status, next_attempt_at)
                VALUES (?, ?::jsonb, 'PENDING', now() + make_interval(secs => ?))
                ON CONFLICT (dedup_key) DO NOTHING
                """, dedupKey, payload, (double) graceSeconds);
        return inserted > 0;
    }

    /**
     * Mark the row {@code PROCESSED} after the synchronous ingress attempt delivered a reply — so the
     * redriver never touches it. Idempotent and safe to call for a {@code dedupKey} that was a duplicate
     * (no row of ours) — it simply updates zero rows.
     */
    public void markProcessed(String dedupKey) {
        jdbc.update("""
                UPDATE bus.inbox
                SET status = 'PROCESSED', processed_at = now()
                WHERE dedup_key = ? AND status <> 'PROCESSED'
                """, dedupKey);
    }
}
