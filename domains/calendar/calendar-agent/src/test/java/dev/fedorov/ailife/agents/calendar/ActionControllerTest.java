package dev.fedorov.ailife.agents.calendar;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code create_event} inter-agent action (Stage 4 / C1c): maps the invoke
 * {@code args} to a {@code CreateEventInput} and persists it via mcp-caldav's
 * {@code /internal/event}, returning {@code {eventUid}}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ActionControllerTest {

    static MockWebServer llmGateway;
    static MockWebServer mcpCaldav;

    @BeforeAll
    static void startMocks() throws Exception {
        llmGateway = new MockWebServer();
        llmGateway.start();
        mcpCaldav = new MockWebServer();
        mcpCaldav.start();
    }

    @AfterAll
    static void stopMocks() throws Exception {
        llmGateway.shutdown();
        mcpCaldav.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
        r.add("calendar-agent.mcp-caldav-url", () -> "http://localhost:" + mcpCaldav.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void createEventMapsArgsAndReturnsEventUid() throws Exception {
        UUID household = UUID.randomUUID();
        var dto = new CalendarEventDto(
                UUID.randomUUID(), household, "ours", "cal-uid-1", "Pay rent",
                null, null, Instant.parse("2026-07-01T09:00:00Z"), null, null, List.of(), null);
        mcpCaldav.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(dto)));

        var args = json.createObjectNode()
                .put("summary", "Pay rent")
                .put("dtstart", "2026-07-01T09:00:00Z");
        var req = new AgentActionRequest("calendar", "create_event", household, null, "tasks", args);

        http.post().uri("/agents/calendar/actions/create_event")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isTrue();
                    assertThat(res.result().get("eventUid").asText()).isEqualTo("cal-uid-1");
                });

        RecordedRequest sent = mcpCaldav.takeRequest();
        assertThat(sent.getPath()).isEqualTo("/internal/event");
        assertThat(sent.getBody().readUtf8())
                .contains("\"summary\":\"Pay rent\"")
                .contains("\"householdId\":\"" + household + "\"");
    }

    @Test
    void unknownActionReturnsErrorResult() {
        var req = new AgentActionRequest("calendar", "frobnicate", UUID.randomUUID(), null, "tasks", null);
        http.post().uri("/agents/calendar/actions/frobnicate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isFalse();
                    assertThat(res.error()).contains("unknown action");
                });
    }

    @Test
    void missingRequiredFieldsReturnsErrorResult() {
        // args has summary but no dtstart → validation rejects before any mcp-caldav call
        // (no response enqueued — a stray call would surface as a different error message).
        var args = json.createObjectNode().put("summary", "Pay rent");
        var req = new AgentActionRequest("calendar", "create_event", UUID.randomUUID(), null, "tasks", args);

        http.post().uri("/agents/calendar/actions/create_event")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isFalse();
                    assertThat(res.error()).contains("dtstart");
                });
    }
}
