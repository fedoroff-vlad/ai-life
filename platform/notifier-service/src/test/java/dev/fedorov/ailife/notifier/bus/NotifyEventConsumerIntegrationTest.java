package dev.fedorov.ailife.notifier.bus;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.bus.OutboxPublisher;
import dev.fedorov.ailife.contracts.notify.NotifyRequestedEvent;
import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.contracts.schedule.ScheduleFiredEvent;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {
                        "notifier.internal-api-token=test-token",
                        "event-bus.poll-millis=500"
                })
class NotifyEventConsumerIntegrationTest extends AbstractPostgresIntegrationTest {


    static MockWebServer profile;
    static MockWebServer gateway;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        registerDataSource(r);
        // MockWebServers must be alive before property bindings resolve.
        try {
            profile = new MockWebServer();
            profile.start();
            gateway = new MockWebServer();
            gateway.start();
        } catch (Exception e) {
            throw new IllegalStateException("failed to start mocks", e);
        }        r.add("notifier.profile-base-url", () -> "http://localhost:" + profile.getPort());
        r.add("notifier.gateway-base-url", () -> "http://localhost:" + gateway.getPort());
    }

    @AfterAll
    static void stopMocks() throws Exception {
        profile.shutdown();
        gateway.shutdown();
    }

    @Autowired OutboxPublisher publisher;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void initSchema() {
        applySchema("test-schema.sql");
    }

    @BeforeEach
    void drain() throws InterruptedException {
        //noinspection StatementWithEmptyBody
        while (profile.takeRequest(50, TimeUnit.MILLISECONDS) != null) { }
        //noinspection StatementWithEmptyBody
        while (gateway.takeRequest(50, TimeUnit.MILLISECONDS) != null) { }
    }

    @Test
    void notifyRequestedEventIsDeliveredAndRowMarkedPublished() throws Exception {
        UUID userId = UUID.randomUUID();
        profile.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new UserDto(
                        userId, UUID.randomUUID(), "vlad", "ru-RU", 12345L, "admin", Instant.now()))));
        gateway.enqueue(new MockResponse().setResponseCode(204));

        UUID eventId = publish(new NotifyRequestedEvent(userId, "пора платить за аренду", "calendar.recurring"));

        RecordedRequest sendReq = gateway.takeRequest(5, TimeUnit.SECONDS);
        assertThat(sendReq).isNotNull();
        assertThat(sendReq.getPath()).isEqualTo("/internal/send");
        assertThat(sendReq.getHeader("Authorization")).isEqualTo("Bearer test-token");
        String body = sendReq.getBody().readUtf8();
        assertThat(body).contains("\"telegramUserId\":12345");
        assertThat(body).contains("пора платить");

        awaitStatus(eventId, "PUBLISHED");
    }

    @Test
    void unknownUserIsPermanentlyDroppedNotRetried() throws Exception {
        UUID userId = UUID.randomUUID();
        profile.enqueue(new MockResponse().setResponseCode(404));

        UUID eventId = publish(new NotifyRequestedEvent(userId, "ping", null));

        // Permanent failure (404) → no gateway send and the row settles PUBLISHED (not stuck PENDING).
        awaitStatus(eventId, "PUBLISHED");
        assertThat(gateway.getRequestCount()).isZero();
    }

    @Test
    void foreignTopicIsIgnoredButStillDrained() throws Exception {
        String payload = json.writeValueAsString(new ScheduleFiredEvent(
                UUID.randomUUID(), UUID.randomUUID(), "calendar", "birthday.greet", Instant.now()));
        UUID rowId = publisher.publish("schedule.fired", null, payload).id();

        // notifier is the sole consumer, so it drains the row, but the handler ignores
        // non-notify.requested topics: no gateway call.
        awaitStatus(rowId, "PUBLISHED");
        assertThat(gateway.getRequestCount()).isZero();
    }

    private UUID publish(NotifyRequestedEvent event) throws Exception {
        return publisher.publish(NotifyRequestedEvent.TOPIC, null, json.writeValueAsString(event)).id();
    }

    private void awaitStatus(UUID eventId, String expected) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM bus.outbox WHERE id = ?", String.class, eventId);
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM bus.outbox WHERE id = ?", String.class, eventId))
                .isEqualTo(expected);
    }
}
