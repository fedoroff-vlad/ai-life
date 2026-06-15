package dev.fedorov.ailife.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inter-agent sync path (Stage 4 / C1a): the orchestrator discovers the (mock)
 * calendar-agent at startup, then {@code POST /v1/agents/invoke} forwards a structured
 * action to the agent's {@code /actions/<action>} and relays the {@link AgentActionResult}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentInvokeControllerTest {

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
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
        r.add("orchestrator.agents[0].name", () -> "calendar");
        r.add("orchestrator.agents[0].base-url",
                () -> "http://localhost:" + calendarAgent.getPort());
        r.add("orchestrator.conversation.enabled", () -> "false");
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void invokeForwardsActionToTargetAgentAndRelaysResult() throws Exception {
        UUID household = UUID.randomUUID();
        var args = json.createObjectNode().put("summary", "Pay rent").put("dueAt", "2026-07-01");
        var req = new AgentActionRequest(
                "calendar", "create_event", household, null, "tasks", args);

        http.post().uri("/v1/agents/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isTrue();
                    assertThat(res.result().get("eventUid").asText()).isEqualTo("evt-123");
                });

        // The orchestrator forwarded to /agents/calendar/actions/create_event with the args.
        RecordedRequest forwarded = CalendarDispatcher.lastAction.get();
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getPath()).isEqualTo("/agents/calendar/actions/create_event");
        assertThat(forwarded.getBody().readUtf8())
                .contains("\"action\":\"create_event\"")
                .contains("\"summary\":\"Pay rent\"")
                .contains("\"requestingAgent\":\"tasks\"");
    }

    @Test
    void unknownTargetAgentReturns404() {
        var req = new AgentActionRequest(
                "no-such-agent", "create_event", UUID.randomUUID(), null, "tasks", null);
        http.post().uri("/v1/agents/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isNotFound();
    }

    private static class CalendarDispatcher extends Dispatcher {
        static final AtomicReference<RecordedRequest> lastAction = new AtomicReference<>();
        static final ObjectMapper M = new ObjectMapper();

        @Override
        public MockResponse dispatch(RecordedRequest req) {
            String path = req.getPath() == null ? "" : req.getPath();
            try {
                if (path.equals("/agents/calendar/manifest")) {
                    var manifest = new AgentManifest(
                            "calendar", "Manages calendar events.", "0.1.0", 8086,
                            List.of("mcp-caldav"), List.of(), List.of(), List.of(),
                            "You are the calendar agent.");
                    return new MockResponse()
                            .setHeader("content-type", "application/json")
                            .setBody(M.writeValueAsString(manifest));
                }
                if (path.equals("/agents/calendar/actions/create_event")) {
                    lastAction.set(req);
                    var result = AgentActionResult.ok(
                            M.createObjectNode().put("eventUid", "evt-123"));
                    return new MockResponse()
                            .setHeader("content-type", "application/json")
                            .setBody(M.writeValueAsString(result));
                }
            } catch (Exception e) {
                return new MockResponse().setResponseCode(500).setBody(e.toString());
            }
            return new MockResponse().setResponseCode(404);
        }
    }
}
