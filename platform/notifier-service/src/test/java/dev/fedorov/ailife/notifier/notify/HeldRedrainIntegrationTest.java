package dev.fedorov.ailife.notifier.notify;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redrain behaviour against a real PG (#487 PX-1b): a held row whose window has opened is delivered and
 * removed; one held past the staleness TTL is dropped without delivering; a still-in-window row is left.
 */
@SpringBootTest(properties = {
        "notifier.internal-api-token=test-token",
        "event-bus.enabled=false",
        "notifier.held-redrain-enabled=false" // drive drain() directly, no auto-tick
})
class HeldRedrainIntegrationTest extends AbstractPostgresIntegrationTest {

    static MockWebServer profile;
    static MockWebServer gateway;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        registerDataSource(r);
        try {
            profile = new MockWebServer();
            profile.start();
            gateway = new MockWebServer();
            gateway.start();
        } catch (Exception e) {
            throw new IllegalStateException("failed to start mocks", e);
        }
        r.add("notifier.profile-base-url", () -> "http://localhost:" + profile.getPort());
        r.add("notifier.gateway-base-url", () -> "http://localhost:" + gateway.getPort());
    }

    @AfterAll
    static void stopMocks() throws Exception {
        profile.shutdown();
        gateway.shutdown();
    }

    @BeforeAll
    static void schema() {
        applySchema("test-schema.sql");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired NotifySender sender;
    @Autowired ObjectMapper json;

    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM core.notification_held");
    }

    private HeldRedrain redrain() {
        return new HeldRedrain(jdbc, sender, Clock.fixed(NOW, ZoneId.of("UTC")), 12);
    }

    private void insertHeld(UUID user, String body, Instant heldAt, Instant deliverAfter) {
        jdbc.update("INSERT INTO core.notification_held (user_id, body, source, held_at, deliver_after) "
                        + "VALUES (?, ?, ?, ?, ?)",
                user, body, "briefing", Timestamp.from(heldAt), Timestamp.from(deliverAfter));
    }

    private int heldCount() {
        return jdbc.queryForObject("SELECT count(*) FROM core.notification_held", Integer.class);
    }

    @Test
    void deliversAndRemovesADueFreshRow() throws Exception {
        UUID user = UUID.randomUUID();
        insertHeld(user, "утренний дайджест", NOW, Instant.parse("2026-08-25T08:00:00Z")); // window already closed
        profile.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new UserDto(
                        user, UUID.randomUUID(), "vlad", "ru-RU", 12345L, "admin", Instant.now()))));
        gateway.enqueue(new MockResponse().setResponseCode(204));

        int delivered = redrain().drain();

        assertThat(delivered).isEqualTo(1);
        assertThat(heldCount()).isZero();
        assertThat(gateway.getRequestCount()).isEqualTo(1);
    }

    @Test
    void dropsAStaleRowWithoutDelivering() {
        UUID user = UUID.randomUUID();
        // held 33h ago (> 12h TTL); its window is open, but it is too old to deliver late.
        insertHeld(user, "вчерашнее", Instant.parse("2026-08-24T00:00:00Z"), Instant.parse("2026-08-24T08:00:00Z"));

        int delivered = redrain().drain();

        assertThat(delivered).isZero();            // never delivered late
        assertThat(heldCount()).isZero();          // dropped
    }

    @Test
    void leavesARowWhoseWindowHasNotOpened() {
        UUID user = UUID.randomUUID();
        insertHeld(user, "вечером", NOW, Instant.parse("2026-08-25T20:00:00Z")); // deliver_after in the future

        int delivered = redrain().drain();

        assertThat(delivered).isZero();
        assertThat(heldCount()).isEqualTo(1);      // still parked
    }
}
