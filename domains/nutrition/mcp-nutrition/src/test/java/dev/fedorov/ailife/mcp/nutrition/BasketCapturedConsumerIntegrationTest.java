package dev.fedorov.ailife.mcp.nutrition;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.bus.OutboxPublisher;
import dev.fedorov.ailife.contracts.basket.BasketCapturedEvent;
import dev.fedorov.ailife.contracts.nutrition.BasketItem;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the IA-b consumer: a {@code basket.captured} row on {@code bus.outbox} is drained and
 * forwarded to nutritionist-agent's {@code /internal/basket-event}, then the row settles PUBLISHED.
 * A foreign topic is ignored (no forward) but still drained. Mirrors notifier's
 * {@code NotifyEventConsumerIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "event-bus.poll-millis=500")
@Testcontainers
class BasketCapturedConsumerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("ailife").withUsername("ailife").withPassword("ailife")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("test-schema.sql"),
                    "/docker-entrypoint-initdb.d/00-test-schema.sql");

    static MockWebServer agent;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        try {
            agent = new MockWebServer();
            agent.start();
        } catch (Exception e) {
            throw new IllegalStateException("failed to start mock", e);
        }
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("mcp-nutrition.nutritionist-agent-url", () -> "http://localhost:" + agent.getPort());
    }

    @AfterAll
    static void stopMock() throws Exception {
        agent.shutdown();
    }

    @Autowired OutboxPublisher publisher;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void basketCapturedIsForwardedToTheAgentAndRowPublished() throws Exception {
        agent.enqueue(new MockResponse().setResponseCode(202));

        UUID householdId = UUID.randomUUID();
        UUID receiptMediaId = UUID.randomUUID();
        BasketCapturedEvent event = new BasketCapturedEvent(householdId, null, "Лента",
                List.of(new BasketItem("молоко", "1 л", null, null, null, null)),
                receiptMediaId, Instant.now());
        UUID rowId = publisher.publish(
                BasketCapturedEvent.TOPIC, householdId, json.writeValueAsString(event)).id();

        RecordedRequest forwarded = agent.takeRequest(5, TimeUnit.SECONDS);
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getPath()).isEqualTo("/internal/basket-event");
        assertThat(forwarded.getMethod()).isEqualTo("POST");
        String body = forwarded.getBody().readUtf8();
        assertThat(body).contains("Лента").contains("молоко").contains(receiptMediaId.toString());

        awaitStatus(rowId, "PUBLISHED");
    }

    @Test
    void foreignTopicIsIgnoredButStillDrained() throws Exception {
        UUID rowId = publisher.publish("schedule.fired", null, "{\"foo\":\"bar\"}").id();

        // Drained (marked PUBLISHED) but never forwarded — not our topic. (No-new-request check
        // rather than a cumulative count, since the static server is shared across tests.)
        awaitStatus(rowId, "PUBLISHED");
        assertThat(agent.takeRequest(1, TimeUnit.SECONDS)).isNull();
    }

    private void awaitStatus(UUID rowId, String expected) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM bus.outbox WHERE id = ?", String.class, rowId);
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM bus.outbox WHERE id = ?", String.class, rowId))
                .isEqualTo(expected);
    }
}
