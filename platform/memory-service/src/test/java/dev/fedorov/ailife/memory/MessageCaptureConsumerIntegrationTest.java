package dev.fedorov.ailife.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.bus.OutboxPublisher;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.message.MessageReceivedEvent;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
class MessageCaptureConsumerIntegrationTest extends AbstractPostgresIntegrationTest {


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
        registerDataSource(r);
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
        llmGateway.start();        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (llmGateway != null) llmGateway.shutdown();
    }

    @Autowired OutboxPublisher publisher;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @BeforeAll
    static void initSchema() {
        applySchema("test-schema.sql");
    }

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

    @Test
    void observationDropPointEnqueuesToBusAndConsumerCaptures() throws Exception {
        UUID household = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", household, "droppoint hh");

        // A DB-less producer drops an observation over HTTP; it must return 202
        // immediately (durable enqueue, no inline LLM work).
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .post().uri("/v1/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MessageReceivedEvent(household, null,
                        "Маша терпеть не может орехи.", "telegram"))
                .exchange()
                .expectStatus().isAccepted();

        // The consumer then drains it and stores the captured memory.
        for (int i = 0; i < 50; i++) {
            Integer rows = jdbc.queryForObject(
                    "SELECT count(*) FROM memory.memories WHERE household_id = ? AND source = 'chat-capture'",
                    Integer.class, household);
            if (rows != null && rows == 1) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM memory.memories WHERE household_id = ? AND source = 'chat-capture'",
                Integer.class, household)).isEqualTo(1);
    }

    @Test
    void observationDropPointRejectsBlankText() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .post().uri("/v1/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MessageReceivedEvent(UUID.randomUUID(), null, "  ", "telegram"))
                .exchange()
                .expectStatus().isBadRequest();
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
