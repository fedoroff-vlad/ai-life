package dev.fedorov.ailife.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * memory-from-chat producer (MFC-b): the orchestrator fire-and-forgets every
 * inbound message's text to memory-service's durable {@code POST /v1/observations}
 * drop-point. Echo-only (no remote agents) keeps the test focused on the capture
 * side-effect; routing is unaffected.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "orchestrator.agents=")
class MemoryObserveTest {

    static MockWebServer llmGateway;
    static MockWebServer memory;

    @BeforeAll
    static void startMocks() throws Exception {
        llmGateway = new MockWebServer();
        llmGateway.setDispatcher(new Dispatcher() {
            private final ObjectMapper json = new ObjectMapper();
            @Override
            public MockResponse dispatch(RecordedRequest req) {
                try {
                    return new MockResponse()
                            .setHeader("content-type", "application/json")
                            .setBody(json.writeValueAsString(new LlmChatResponse(
                                    "mock-large", "hi", "stop", new LlmUsage(1, 1, 2))));
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
            }
        });
        llmGateway.start();

        memory = new MockWebServer();
        memory.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest req) {
                String path = req.getPath() == null ? "" : req.getPath();
                if (path.startsWith("/v1/observations")) {
                    return new MockResponse().setResponseCode(202);
                }
                // recall (or anything else) → empty hit list
                return new MockResponse()
                        .setHeader("content-type", "application/json")
                        .setBody("[]");
            }
        });
        memory.start();
    }

    @AfterAll
    static void stopMocks() throws Exception {
        llmGateway.shutdown();
        memory.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
        r.add("orchestrator.memory.url", () -> "http://localhost:" + memory.getPort());
        r.add("orchestrator.conversation.enabled", () -> "false");
    }

    @Autowired WebTestClient client;

    @Test
    void inboundMessageIsDroppedAtObservationsEndpoint() throws Exception {
        var msg = new NormalizedMessage(
                UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                "у Маши аллергия на орехи", List.of(), "telegram", "7", Instant.now());

        client.post().uri("/v1/intent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(msg)
                .exchange()
                .expectStatus().isOk();

        // Fire-and-forget runs async — collect memory-service requests until we
        // see the observation drop (or time out).
        RecordedRequest observation = null;
        for (int i = 0; i < 20 && observation == null; i++) {
            RecordedRequest req = memory.takeRequest(500, TimeUnit.MILLISECONDS);
            if (req == null) {
                break;
            }
            if (req.getPath() != null && req.getPath().startsWith("/v1/observations")) {
                observation = req;
            }
        }

        assertThat(observation).as("orchestrator must POST the inbound text to /v1/observations").isNotNull();
        assertThat(observation.getMethod()).isEqualTo("POST");
        String body = observation.getBody().readUtf8();
        assertThat(body).contains("у Маши аллергия на орехи");
        assertThat(body).contains("\"source\":\"telegram\"");
    }
}
