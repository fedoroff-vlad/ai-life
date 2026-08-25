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
 * The send-time proactive-UX gate (#487). For a <b>proactive</b> push it reads the user's preference and
 * decides one of:
 * <ul>
 *   <li>{@link Decision#HELD} — inside the user's quiet hours (PX-1): the message is parked in
 *       {@code core.notification_held} (with {@code deliver_after} = the window's next end) and redelivered
 *       later by {@link HeldRedrain};</li>
 *   <li>{@link Decision#SUPPRESSED} — the user has already had {@code daily_cap} proactive pushes today
 *       (PX-2): the overflow is dropped, not queued;</li>
 *   <li>{@link Decision#PASS} — deliver now; {@link NotifySender} then calls {@link #recordSent} so the
 *       delivery counts toward the cap.</li>
 * </ul>
 * Reactive sends and users with no preference row always {@code PASS}. JDBC, not JPA (notifier is thin):
 * the reads/writes are blocking, so {@link NotifySender} calls this off the request thread. When no
 * {@code DataSource} is configured (a DB-less slice test) the gate is <b>inert</b> — every send passes
 * (fail-open, the right default for a UX gate).
 */
@Component
public class NotificationGate {

    private static final Logger log = LoggerFactory.getLogger(NotificationGate.class);

    /** What the gate decided for a proactive send. */
    public enum Decision { PASS, HELD, SUPPRESSED }

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
     * Cheap pre-check: could a send with this {@code proactive} flag ever be gated here? False for a
     * reactive send or a node with no store configured — letting {@link NotifySender} keep those on the
     * original (non-blocking, same-thread) delivery path instead of hopping to a worker for a JDBC read
     * that would do nothing.
     */
    public boolean mightGate(boolean proactive) {
        return proactive && jdbc != null;
    }

    /**
     * Decide a proactive send: park it if the user is in quiet hours (writes the held row), suppress it if
     * today's proactive deliveries already hit the cap, else pass. Only called when {@link #mightGate}
     * held, so {@code jdbc} is non-null.
     */
    public Decision evaluate(UUID userId, String text, String source) {
        if (source != null && streamMuted(userId, source)) {
            log.info("suppressed proactive notify for user {} — stream '{}' muted", userId, source);
            return Decision.SUPPRESSED;
        }
        Preference pref = preferenceFor(userId);
        if (pref == null) {
            return Decision.PASS;
        }
        Instant now = clock.instant();
        if (pref.quietHours() != null && pref.quietHours().isQuietAt(now)) {
            Instant deliverAfter = pref.quietHours().nextWindowEnd(now);
            jdbc.update("""
                    INSERT INTO core.notification_held (user_id, body, source, deliver_after)
                    VALUES (?, ?, ?, ?)""",
                    userId, text, source, Timestamp.from(deliverAfter));
            log.info("held proactive notify for user {} until {} (quiet hours){}",
                    userId, deliverAfter, src(source));
            return Decision.HELD;
        }
        if (pref.dailyCap() != null && deliveredToday(userId, pref.tz(), now) >= pref.dailyCap()) {
            log.info("suppressed proactive notify for user {} — daily cap {} reached{}",
                    userId, pref.dailyCap(), src(source));
            return Decision.SUPPRESSED;
        }
        return Decision.PASS;
    }

    /** Record a delivered proactive push so it counts toward the user's daily cap. No-op when inert. */
    public void recordSent(UUID userId) {
        if (jdbc == null) {
            return;
        }
        jdbc.update("INSERT INTO core.notification_sent (user_id) VALUES (?)", userId);
    }

    /** True when the user has muted this stream (source). */
    private boolean streamMuted(UUID userId, String stream) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM core.notification_stream_optout WHERE user_id = ? AND stream = ?",
                Integer.class, userId, stream);
        return n != null && n > 0;
    }

    private int deliveredToday(UUID userId, ZoneId tz, Instant now) {
        Instant startOfDay = now.atZone(tz).toLocalDate().atStartOfDay(tz).toInstant();
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM core.notification_sent WHERE user_id = ? AND sent_at >= ?",
                Integer.class, userId, Timestamp.from(startOfDay));
        return n == null ? 0 : n;
    }

    /** The user's preference, or {@code null} when there is no row. */
    private Preference preferenceFor(UUID userId) {
        return jdbc.query(
                "SELECT quiet_start, quiet_end, tz, daily_cap FROM core.notification_preference WHERE user_id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    ZoneId tz;
                    try {
                        tz = ZoneId.of(rs.getString("tz"));
                    } catch (Exception e) {
                        tz = ZoneId.of("UTC");
                    }
                    LocalTime start = rs.getObject("quiet_start", LocalTime.class);
                    LocalTime end = rs.getObject("quiet_end", LocalTime.class);
                    QuietHours quiet = (start != null && end != null) ? new QuietHours(start, end, tz) : null;
                    Integer cap = rs.getObject("daily_cap", Integer.class);
                    return new Preference(quiet, cap, tz);
                },
                userId);
    }

    private static String src(String source) {
        return source == null ? "" : " source=" + source;
    }

    /** A user's proactive-UX preference: an optional quiet-hours window, an optional daily cap, and the tz. */
    private record Preference(QuietHours quietHours, Integer dailyCap, ZoneId tz) {
    }
}
