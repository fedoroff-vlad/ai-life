package dev.fedorov.ailife.notifier.notify;

import dev.fedorov.ailife.notifier.notify.NotificationGate.Decision;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Time;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate decisions against a real PG (#487 PX-1 + PX-2): a proactive push inside quiet hours is HELD (parked
 * in {@code core.notification_held}); one over the user's daily cap is SUPPRESSED; otherwise it PASSes, and
 * a recorded delivery counts toward the cap. Reactive sends never reach the gate; a user with no preference
 * always passes.
 */
@SpringBootTest(properties = {
        "notifier.internal-api-token=test-token",
        "notifier.profile-base-url=http://localhost:1",
        "notifier.gateway-base-url=http://localhost:1",
        "event-bus.enabled=false",
        "notifier.held-redrain-enabled=false"
})
class NotificationGateIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        registerDataSource(r);
    }

    @BeforeAll
    static void schema() {
        applySchema("test-schema.sql");
    }

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM core.notification_held");
        jdbc.update("DELETE FROM core.notification_sent");
        jdbc.update("DELETE FROM core.notification_preference");
    }

    private NotificationGate gateAt(String instant) {
        return new NotificationGate(jdbc, Clock.fixed(Instant.parse(instant), UTC));
    }

    private void preference(UUID userId, LocalTime quietStart, LocalTime quietEnd, Integer dailyCap) {
        jdbc.update("INSERT INTO core.notification_preference (user_id, quiet_start, quiet_end, tz, daily_cap) "
                        + "VALUES (?, ?, ?, 'UTC', ?)",
                userId, quietStart == null ? null : Time.valueOf(quietStart),
                quietEnd == null ? null : Time.valueOf(quietEnd), dailyCap);
    }

    private void recordSentAt(UUID userId, String instant) {
        jdbc.update("INSERT INTO core.notification_sent (user_id, sent_at) VALUES (?, ?)",
                userId, java.sql.Timestamp.from(Instant.parse(instant)));
    }

    private int heldCount(UUID userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM core.notification_held WHERE user_id = ?", Integer.class, userId);
    }

    // ---- PX-1: quiet hours ----------------------------------------------------------------------

    @Test
    void reactiveSendNeverReachesTheGate() {
        assertThat(gateAt("2026-08-25T03:00:00Z").mightGate(false)).isFalse();
        assertThat(gateAt("2026-08-25T03:00:00Z").mightGate(true)).isTrue(); // proactive + a store present
    }

    @Test
    void holdsAProactivePushInsideQuietHours() {
        UUID user = UUID.randomUUID();
        preference(user, LocalTime.of(22, 0), LocalTime.of(8, 0), null);

        Decision d = gateAt("2026-08-25T03:00:00Z").evaluate(user, "утренний дайджест", "briefing");

        assertThat(d).isEqualTo(Decision.HELD);
        assertThat(heldCount(user)).isEqualTo(1);
        jdbc.query("SELECT body, source, deliver_after FROM core.notification_held WHERE user_id = ?",
                rs -> {
                    assertThat(rs.getString("body")).isEqualTo("утренний дайджест");
                    assertThat(rs.getString("source")).isEqualTo("briefing");
                    assertThat(rs.getObject("deliver_after", java.time.OffsetDateTime.class).toInstant())
                            .isEqualTo(Instant.parse("2026-08-25T08:00:00Z"));
                }, user);
    }

    @Test
    void proactiveSendOutsideQuietHoursPasses() {
        UUID user = UUID.randomUUID();
        preference(user, LocalTime.of(22, 0), LocalTime.of(8, 0), null);

        Decision d = gateAt("2026-08-25T12:00:00Z").evaluate(user, "дневной пинг", "resurfacing");

        assertThat(d).isEqualTo(Decision.PASS);
        assertThat(heldCount(user)).isZero();
    }

    @Test
    void userWithNoPreferencePasses() {
        UUID user = UUID.randomUUID();
        Decision d = gateAt("2026-08-25T03:00:00Z").evaluate(user, "что угодно", "briefing");
        assertThat(d).isEqualTo(Decision.PASS);
        assertThat(heldCount(user)).isZero();
    }

    // ---- PX-2: daily cap ------------------------------------------------------------------------

    @Test
    void underDailyCapPasses() {
        UUID user = UUID.randomUUID();
        preference(user, null, null, 3);
        recordSentAt(user, "2026-08-25T09:00:00Z");
        recordSentAt(user, "2026-08-25T10:00:00Z"); // 2 today, cap 3

        assertThat(gateAt("2026-08-25T12:00:00Z").evaluate(user, "третий", "finance")).isEqualTo(Decision.PASS);
    }

    @Test
    void atDailyCapSuppresses() {
        UUID user = UUID.randomUUID();
        preference(user, null, null, 2);
        recordSentAt(user, "2026-08-25T09:00:00Z");
        recordSentAt(user, "2026-08-25T10:00:00Z"); // 2 today == cap 2

        Decision d = gateAt("2026-08-25T12:00:00Z").evaluate(user, "лишний", "tasks");

        assertThat(d).isEqualTo(Decision.SUPPRESSED);
        assertThat(heldCount(user)).isZero(); // suppressed, not queued
    }

    @Test
    void capCountsOnlyToday() {
        UUID user = UUID.randomUUID();
        preference(user, null, null, 2);
        recordSentAt(user, "2026-08-24T09:00:00Z");
        recordSentAt(user, "2026-08-24T10:00:00Z");
        recordSentAt(user, "2026-08-24T22:00:00Z"); // 3 yesterday — must not count

        assertThat(gateAt("2026-08-25T12:00:00Z").evaluate(user, "сегодняшний", "briefing"))
                .isEqualTo(Decision.PASS);
    }

    @Test
    void recordSentAddsToTodayCount() {
        UUID user = UUID.randomUUID();
        preference(user, null, null, 1);
        NotificationGate gate = gateAt("2026-08-25T12:00:00Z");

        assertThat(gate.evaluate(user, "first", "briefing")).isEqualTo(Decision.PASS);
        gate.recordSent(user);
        assertThat(gate.evaluate(user, "second", "briefing")).isEqualTo(Decision.SUPPRESSED);
    }
}
