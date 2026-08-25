package dev.fedorov.ailife.notifier.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * The send-time proactive-UX gate (#487 PX-1). Reads the user's quiet-hours preference and, for a
 * <b>proactive</b> push that lands inside the window, parks it in {@code core.notification_held}
 * (with {@code deliver_after} = the window's next end) instead of delivering now. Reactive sends and
 * users with no preference row pass straight through — the gate is inert by default (back-compat).
 *
 * <p>JDBC, not JPA (notifier is a thin service): the read + park are blocking, so {@link NotifySender}
 * calls this off the request thread. When no {@code DataSource} is configured (e.g. a DB-less slice
 * test) the gate is <b>inert</b> — it never holds, so a send is never blocked by a missing store
 * (fail-open, the right default for a UX gate). The redrain that redelivers a held row once its window
 * opens (and drops a stale one) is PX-1b.
 */
@Component
public class NotificationGate {

    private static final Logger log = LoggerFactory.getLogger(NotificationGate.class);

    private final JdbcTemplate jdbc; // null when no DataSource is configured → gate is inert
    private final Clock clock;

    @Autowired
    public NotificationGate(ObjectProvider<JdbcTemplate> jdbc) {
        this(jdbc.getIfAvailable(), Clock.systemUTC());
    }

    NotificationGate(JdbcTemplate jdbc, Clock clock) { // test seam for a fixed now / explicit template
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Cheap pre-check: could a send with this {@code proactive} flag ever be held here? False for a
     * reactive send or a node with no store configured — letting {@link NotifySender} keep those on the
     * original (non-blocking, same-thread) delivery path instead of hopping to a worker for a JDBC read
     * that would do nothing.
     */
    public boolean mightHold(boolean proactive) {
        return proactive && jdbc != null;
    }

    /**
     * If {@code proactive} and the user is inside their quiet hours right now, park the message and
     * return {@code true} (the caller reports "accepted" without delivering). Otherwise {@code false}
     * (deliver normally).
     */
    public boolean holdIfQuiet(UUID userId, String text, boolean proactive, String source) {
        if (!proactive || jdbc == null) {
            return false;
        }
        QuietHours quiet = preferenceFor(userId);
        if (quiet == null) {
            return false;
        }
        Instant now = clock.instant();
        if (!quiet.isQuietAt(now)) {
            return false;
        }
        Instant deliverAfter = quiet.nextWindowEnd(now);
        jdbc.update("""
                INSERT INTO core.notification_held (user_id, body, source, deliver_after)
                VALUES (?, ?, ?, ?)""",
                userId, text, source, Timestamp.from(deliverAfter));
        log.info("held proactive notify for user {} until {} (quiet hours){}",
                userId, deliverAfter, source == null ? "" : " source=" + source);
        return true;
    }

    /** The user's quiet-hours window, or {@code null} when there is no row or no window configured. */
    private QuietHours preferenceFor(UUID userId) {
        return jdbc.query(
                "SELECT quiet_start, quiet_end, tz FROM core.notification_preference WHERE user_id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    LocalTime start = rs.getObject("quiet_start", LocalTime.class);
                    LocalTime end = rs.getObject("quiet_end", LocalTime.class);
                    if (start == null || end == null) {
                        return null;
                    }
                    ZoneId tz;
                    try {
                        tz = ZoneId.of(rs.getString("tz"));
                    } catch (Exception e) {
                        tz = ZoneId.of("UTC");
                    }
                    return new QuietHours(start, end, tz);
                },
                userId);
    }
}
