package dev.fedorov.ailife.notifier.notify;

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
 * Gate behaviour against a real PG (#487 PX-1): a proactive push inside quiet hours is parked in
 * {@code core.notification_held} with {@code deliver_after} = the window's next end; reactive sends,
 * sends outside the window, and users with no preference row pass through untouched.
 */
@SpringBootTest(properties = {
        "notifier.internal-api-token=test-token",
        "notifier.profile-base-url=http://localhost:1",
        "notifier.gateway-base-url=http://localhost:1",
        "event-bus.enabled=false"
})
class NotificationGateIntegrationTest extends AbstractPostgresIntegrationTest {

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
        jdbc.update("DELETE FROM core.notification_preference");
    }

    /** A gate whose "now" is fixed so quiet-hours evaluation is deterministic. */
    private NotificationGate gateAt(String instant) {
        return new NotificationGate(jdbc, Clock.fixed(Instant.parse(instant), ZoneId.of("UTC")));
    }

    private void quietHours(UUID userId, LocalTime start, LocalTime end, String tz) {
        jdbc.update("INSERT INTO core.notification_preference (user_id, quiet_start, quiet_end, tz) "
                + "VALUES (?, ?, ?, ?)", userId, Time.valueOf(start), Time.valueOf(end), tz);
    }

    private int heldCount(UUID userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM core.notification_held WHERE user_id = ?", Integer.class, userId);
    }

    @Test
    void holdsAProactivePushInsideQuietHours() {
        UUID user = UUID.randomUUID();
        quietHours(user, LocalTime.of(22, 0), LocalTime.of(8, 0), "UTC");

        boolean held = gateAt("2026-08-25T03:00:00Z").holdIfQuiet(user, "утренний дайджест", true, "briefing");

        assertThat(held).isTrue();
        assertThat(heldCount(user)).isEqualTo(1);
        jdbc.query("SELECT body, source, deliver_after FROM core.notification_held WHERE user_id = ?",
                rs -> {
                    assertThat(rs.getString("body")).isEqualTo("утренний дайджест");
                    assertThat(rs.getString("source")).isEqualTo("briefing");
                    assertThat(rs.getObject("deliver_after", java.time.OffsetDateTime.class).toInstant())
                            .isEqualTo(Instant.parse("2026-08-25T08:00:00Z")); // next window close
                }, user);
    }

    @Test
    void reactiveSendIsNeverHeld() {
        UUID user = UUID.randomUUID();
        quietHours(user, LocalTime.of(22, 0), LocalTime.of(8, 0), "UTC");

        boolean held = gateAt("2026-08-25T03:00:00Z").holdIfQuiet(user, "ты просил напомнить", false, null);

        assertThat(held).isFalse();
        assertThat(heldCount(user)).isZero();
    }

    @Test
    void proactiveSendOutsideQuietHoursIsNotHeld() {
        UUID user = UUID.randomUUID();
        quietHours(user, LocalTime.of(22, 0), LocalTime.of(8, 0), "UTC");

        boolean held = gateAt("2026-08-25T12:00:00Z").holdIfQuiet(user, "дневной пинг", true, "resurfacing");

        assertThat(held).isFalse();
        assertThat(heldCount(user)).isZero();
    }

    @Test
    void userWithNoPreferenceIsNeverHeld() {
        UUID user = UUID.randomUUID();

        boolean held = gateAt("2026-08-25T03:00:00Z").holdIfQuiet(user, "что угодно", true, "briefing");

        assertThat(held).isFalse();
        assertThat(heldCount(user)).isZero();
    }
}
