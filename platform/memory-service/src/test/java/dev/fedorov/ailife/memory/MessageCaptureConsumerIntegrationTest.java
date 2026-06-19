package dev.fedorov.ailife.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.bus.OutboxPublisher;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.message.MessageReceivedEvent;
import okhttp3.mockwebserver.Dispatcher;
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

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * memory-from-chat consumer (MFC-b): a {@code message.received} bus event drains
 * through {@link dev.fedorov.ailife.memory.bus.MessageCaptureHandler} → capture →
 * stored memories. Mirrors notifier's {@code NotifyEventConsumerIntegrationTest}:
 * publish via {@link OutboxPublisher}, await the outbox row PUBLISHED.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "event-bus.poll-millis=500")
@Testcontainers
class MessageCaptureConsumerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("ailife").withUsername("ailife").withPassword("ailife")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("test-schema.sql"),
                    "/docker-entrypoint-initdb.d/00-test-schema.sql");

    static MockWebServer llmGateway;

    private static float[] embeddingFor(String text) {
        Random rnd = new Random(text.hashCode());
        float[] v = new float[384];
        for (int i = 0; i < v.length; i++) {
            v[i] = rnd.nextFloat() * 2f - 1f;
        }
        return v;
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) throws IOException {
        llmGateway = new MockWebServer();
        llmGateway.setDispatcher(new Dispatcher() {
            private final ObjectMapper json = new ObjectMapper();

            @Override
            public MockResponse dispatch(RecordedRequest req) {
                try {
                    String path = req.getPath() == null ? "" : req.getPath();
                    if (path.startsWith("/v1/chat")) {
                        LlmChatResponse chat = new LlmChatResponse(
                                "mock-chat",
                                "{\"facts\":[\"Maria is allergic to nuts.\"]}",
                                "stop", new LlmUsage(0, 0, 0));
                        return new MockResponse().setHeader("content-type", "application/json")
                                .setBody(json.writeValueAsString(chat));
                    }
                    var node = json.readTree(req.getBody().readUtf8());
                    String input = node.get("inputs").get(0).asText();
                    LlmEmbedResponse body = new LlmEmbedResponse(
                            "mock-embed", List.of(embeddingFor(input)), new LlmUsage(0, 0, 0));
                    return new MockResponse().setHeader("content-type", "application/json")
                            .setBody(json.writeValueAsString(body));
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500).setBody(e.toString());
                }
            }
        });
        llmGateway.start();
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (llmGateway != null) llmGateway.shutdown();
    }

    @Autowired OutboxPublisher publisher;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void messageReceivedEventIsCapturedAndRowMarkedPublished() throws Exception {
        UUID household = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", household, "consumer hh");

        var event = new MessageReceivedEvent(household, null,
                "Кстати, у Маши аллергия на орехи.", "telegram");
        UUID rowId = publisher.publish(MessageReceivedEvent.TOPIC, household,
                json.writeValueAsString(event)).id();

        awaitStatus(rowId, "PUBLISHED");

        // The captured fact landed as a chat-capture memory in the household.
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM memory.memories WHERE household_id = ? AND source = 'chat-capture'",
                Integer.class, household);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void foreignTopicIsIgnoredButStillDrained() throws Exception {
        UUID household = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", household, "foreign hh");

        UUID rowId = publisher.publish("schedule.fired", household, "{}").id();

        awaitStatus(rowId, "PUBLISHED");
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM memory.memories WHERE household_id = ?",
                Integer.class, household);
        assertThat(rows).isZero();
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
