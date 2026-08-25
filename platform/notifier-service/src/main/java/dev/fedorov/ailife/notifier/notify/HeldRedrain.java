package dev.fedorov.ailife.notifier.notify;

import dev.fedorov.ailife.notifier.config.NotifierProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Redelivers proactive pushes that were held during a user's quiet hours (#487 PX-1b), the second half
 * of {@link NotificationGate}. Each pass:
 * <ul>
 *   <li>drops any held row older than {@code notifier.held-stale-hours} — a message parked too long is
 *       never delivered late;</li>
 *   <li>delivers every remaining row whose window has opened ({@code deliver_after <= now}) via
 *       {@link NotifySender} (as a plain, non-gated send) and removes it on success; a permanent failure
 *       (404/422) is dropped too, a transient one is left for the next pass.</li>
 * </ul>
 * JDBC, blocking — driven off the scheduler thread by {@link HeldRedrainRunner}. Inert when no
 * {@code DataSource} is configured (a DB-less slice test), mirroring the gate.
 */
@Component
public class HeldRedrain {

    private static final Logger log = LoggerFactory.getLogger(HeldRedrain.class);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final JdbcTemplate jdbc; // null when no DataSource is configured → redrain is inert
    private final NotifySender sender;
    private final Clock clock;
    private final int staleHours;

    @Autowired
    public HeldRedrain(ObjectProvider<JdbcTemplate> jdbc, NotifySender sender, NotifierProperties props) {
        this(jdbc.getIfAvailable(), sender, Clock.systemUTC(), props.getHeldStaleHours());
    }

    HeldRedrain(JdbcTemplate jdbc, NotifySender sender, Clock clock, int staleHours) { // test seam
        this.jdbc = jdbc;
        this.sender = sender;
        this.clock = clock;
        this.staleHours = staleHours;
    }

    /**
     * Run one redrain pass; returns the number of messages actually delivered. Safe to call repeatedly —
     * a delivered/stale/permanently-failed row is removed, a transiently-failed one is left for next time.
     */
    public int drain() {
        if (jdbc == null) {
            return 0;
        }
        Instant now = clock.instant();
        int dropped = jdbc.update("DELETE FROM core.notification_held WHERE held_at < ?",
                Timestamp.from(now.minus(Duration.ofHours(staleHours))));

        List<Held> due = jdbc.query(
                "SELECT id, user_id, body FROM core.notification_held WHERE deliver_after <= ? ORDER BY deliver_after",
                (rs, i) -> new Held(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("body")),
                Timestamp.from(now));

        int delivered = 0;
        for (Held h : due) {
            try {
                ResponseEntity<Void> out = sender.send(h.userId(), h.body(), false, null).block(SEND_TIMEOUT);
                HttpStatusCode status = out == null ? null : out.getStatusCode();
                if (status != null && status.is2xxSuccessful()) {
                    delivered++;
                }
                // 2xx delivered, or 404/422 permanent — either way remove it (never loop a poisoned row).
                jdbc.update("DELETE FROM core.notification_held WHERE id = ?", h.id());
            } catch (Exception e) {
                // transient (5xx / timeout / network) — leave the row for the next pass.
                log.warn("redeliver failed for held {} (user {}): {} — leaving for next pass",
                        h.id(), h.userId(), e.toString());
            }
        }
        if (dropped > 0 || delivered > 0) {
            log.info("held redrain: delivered {}, dropped {} stale", delivered, dropped);
        }
        return delivered;
    }

    private record Held(UUID id, UUID userId, String body) {
    }
}
