package dev.fedorov.ailife.orchestrator;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
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
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the wake forward: scheduler-shape POST /v1/agents/wake →
 * RemoteAgent → POST <agent>/agents/<name>/triggers/<kind> on the calendar-agent
 * mock. The agent's trigger reply is bodiless; orchestrator returns 202 once
 * the forward completes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class WakeDispatchTest {

    static MockWebServer llmGateway;
    static MockWebServer calendarAgent;

    @BeforeAll
    static void start() throws Exception {
        llmGateway = new MockWebServer();
        llmGateway.start();
        calendarAgent = new MockWebServer();
        calendarAgent.setDispatcher(new CalendarDispatcher());
        calendarAgent.start();
    }

    @AfterAll
    static void stop() throws Exception {
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
    void wakeForwardsToRemoteAgentTrigger() throws Exception {
        UUID scheduleId = UUID.randomUUID();
        var wake = new AgentWakeRequest(
                scheduleId, UUID.randomUUID(),
                "calendar", "birthday.greet",
                json.createObjectNode().put("personId", "maria"));

        http.post().uri("/v1/agents/wake")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isAccepted();

        RecordedRequest forwarded = CalendarDispatcher.lastTrigger.get();
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getPath()).isEqualTo("/agents/calendar/triggers/birthday.greet");
        assertThat(forwarded.getBody().readUtf8()).contains("\"personId\":\"maria\"");
    }

    private static class CalendarDispatcher extends Dispatcher {
        static final AtomicReference<RecordedRequest> lastTrigger = new AtomicReference<>();
        static final ObjectMapper M = new ObjectMapper();

        @Override
        public MockResponse dispatch(RecordedRequest req) {
            String path = req.getPath() == null ? "" : req.getPath();
            try {
                if (path.equals("/agents/calendar/manifest")) {
                    var m = new AgentManifest(
                            "calendar", "Manages calendar events.", "0.1.0", 8086,
                            List.of(), List.of(),
                            List.of(Map.of("kind", "birthday.greet")),
                            List.of(),
                            "You are the calendar agent.");
                    return new MockResponse()
                            .setHeader("content-type", "application/json")
                            .setBody(M.writeValueAsString(m));
                }
                if (path.startsWith("/agents/calendar/triggers/")) {
                    lastTrigger.set(req);
                    return new MockResponse().setResponseCode(202);
                }
            } catch (Exception e) {
                return new MockResponse().setResponseCode(500).setBody(e.toString());
            }
            return new MockResponse().setResponseCode(404);
        }
    }
}
