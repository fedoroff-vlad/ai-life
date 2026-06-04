package dev.fedorov.ailife.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: orchestrator discovers the (mock) calendar-agent at startup, the
 * intent classifier picks "calendar", the orchestrator forwards to the agent's
 * /intent and returns its reply. Two MockWebServers: one for llm-gateway, one
 * for the calendar-agent itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentRoutingTest {

    static MockWebServer llmGateway;
    static MockWebServer calendarAgent;

    @BeforeAll
    static void startMocks() throws Exception {
        llmGateway = new MockWebServer();
        llmGateway.start();
        calendarAgent = new MockWebServer();
        calendarAgent.setDispatcher(new CalendarDispatcher());
        calendarAgent.start();
    }

    @AfterAll
    static void stopMocks() throws Exception {
        llmGateway.shutdown();
        calendarAgent.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("ailife.llm-client.base-url",
                () -> "http://localhost:" + llmGateway.getPort());
        r.add("orchestrator.agents[0].name", () -> "calendar");
        r.add("orchestrator.agents[0].base-url",
                () -> "http://localhost:" + calendarAgent.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void classifierPicksCalendarAndOrchestratorForwards() throws Exception {
        // LLM call #1 = classification. Reply must match a known agent name.
        var classifyResp = new LlmChatResponse(
                "mock-fast", "calendar", "stop", new LlmUsage(50, 1, 51));
        llmGateway.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(classifyResp)));

        var msg = new NormalizedMessage(
                UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                "Когда у Маши день рождения?",
                List.of(), "telegram", "42", Instant.now());

        http.post().uri("/v1/intent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(msg)
                .exchange()
                .expectStatus().isOk()
                .expectBody(IntentResponse.class)
                .value(r -> {
                    assertThat(r.agent()).isEqualTo("calendar");
                    assertThat(r.text()).isEqualTo("Maria's birthday: May 5");
                });

        // The classifier hit the LLM on the FAST channel.
        RecordedRequest classify = llmGateway.takeRequest();
        assertThat(classify.getPath()).isEqualTo("/v1/chat");
        assertThat(classify.getBody().readUtf8()).contains("\"channel\":\"fast\"");

        // The orchestrator forwarded to calendar-agent /agents/calendar/intent.
        assertThat(CalendarDispatcher.intentHits.get()).isEqualTo(1);
    }

    private static class CalendarDispatcher extends Dispatcher {
        static final java.util.concurrent.atomic.AtomicInteger intentHits =
                new java.util.concurrent.atomic.AtomicInteger();
        static final ObjectMapper M = new ObjectMapper();

        @Override
        public MockResponse dispatch(RecordedRequest req) {
            String path = req.getPath() == null ? "" : req.getPath();
            try {
                if (path.equals("/agents/calendar/manifest")) {
                    var manifest = new AgentManifest(
                            "calendar",
                            "Manages calendar events, birthdays, anniversaries.",
                            "0.1.0",
                            8086,
                            List.of("mcp-caldav"),
                            List.of(),
                            List.of(Map.of("kind", "birthday.greet")),
                            List.of(Map.of("example", "When is Maria's birthday?",
                                           "description", "Lookup person's birthday")),
                            "You are the calendar agent.");
                    return new MockResponse()
                            .setHeader("content-type", "application/json")
                            .setBody(M.writeValueAsString(manifest));
                }
                if (path.equals("/agents/calendar/intent")) {
                    intentHits.incrementAndGet();
                    var resp = new IntentResponse(
                            "calendar", "Maria's birthday: May 5", "mock-large");
                    return new MockResponse()
                            .setHeader("content-type", "application/json")
                            .setBody(M.writeValueAsString(resp));
                }
            } catch (Exception e) {
                return new MockResponse().setResponseCode(500).setBody(e.toString());
            }
            return new MockResponse().setResponseCode(404);
        }
    }
}
