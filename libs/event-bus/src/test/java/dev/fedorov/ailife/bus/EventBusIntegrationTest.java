package dev.fedorov.ailife.bus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
class EventBusIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("ailife")
            .withUsername("ailife")
            .withPassword("ailife")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("test-schema.sql"),
                    "/docker-entrypoint-initdb.d/00-test-schema.sql");

    private final DriverManagerDataSource ds = dataSource();
    private final JdbcTemplate jdbc = new JdbcTemplate(ds);
    private PostgresEventBusListener listener;

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    @AfterEach
    void cleanup() {
        if (listener != null) {
            listener.close();
        }
        jdbc.update("DELETE FROM bus.outbox");
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM bus.outbox WHERE id = ?", String.class, id);
    }

    @Test
    void publishWakesListenerWhichMarksRowPublished() {
        List<EventBusMessage> received = new CopyOnWriteArrayList<>();
        listener = new PostgresEventBusListener(ds, EventBus.DEFAULT_CHANNEL, received::add, 500);
        listener.start();

        UUID household = UUID.randomUUID();
        var publisher = new OutboxPublisher(jdbc);
        EventBusMessage published = publisher.publish(
                "tasks.created", household, "{\"taskId\":\"abc\"}");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(received).hasSize(1);
            EventBusMessage got = received.get(0);
            assertThat(got.id()).isEqualTo(published.id());
            assertThat(got.topic()).isEqualTo("tasks.created");
            assertThat(got.householdId()).isEqualTo(household);
            assertThat(got.payload()).contains("\"taskId\": \"abc\"");
            assertThat(status(published.id())).isEqualTo("PUBLISHED");
        });
    }

    @Test
    void rowInsertedBeforeListenerStartsIsDrainedOnStart() {
        // Publish with no listener running — only the durable outbox row exists, the
        // NOTIFY is lost. The listener must still deliver it (at-least-once via poll/start drain).
        var publisher = new OutboxPublisher(jdbc);
        EventBusMessage published = publisher.publish("finance.imported", null, "{}");

        List<EventBusMessage> received = new CopyOnWriteArrayList<>();
        listener = new PostgresEventBusListener(ds, EventBus.DEFAULT_CHANNEL, received::add, 500);
        listener.start();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(received).extracting(EventBusMessage::id).containsExactly(published.id());
            assertThat(status(published.id())).isEqualTo("PUBLISHED");
        });
    }

    @Test
    void failingHandlerLeavesRowPendingForRetry() {
        AtomicInteger attempts = new AtomicInteger();
        listener = new PostgresEventBusListener(ds, EventBus.DEFAULT_CHANNEL, msg -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        }, 500);
        listener.start();

        var publisher = new OutboxPublisher(jdbc);
        EventBusMessage published = publisher.publish("tasks.created", null, "{}");

        // The handler is retried each poll; the row never leaves PENDING.
        await().atMost(Duration.ofSeconds(10)).until(() -> attempts.get() >= 2);
        assertThat(status(published.id())).isEqualTo("PENDING");
    }
}
