package dev.fedorov.ailife.agents.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.tasks.TaskToEventRequest;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The task-to-event chain (Stage 4 / C1 closer): tasks-agent → orchestrator
 * {@code /v1/agents/invoke} (calendar create_event) → mcp-tasks {@code /internal/link-event}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskToEventFlowTest {

    static MockWebServer llm;
    static MockWebServer orchestrator;
    static MockWebServer mcpTasks;

    @BeforeAll
    static void start() throws Exception {
        llm = new MockWebServer();
        llm.start();
        orchestrator = new MockWebServer();
        orchestrator.start();
        mcpTasks = new MockWebServer();
        mcpTasks.start();
    }

    @AfterAll
    static void stop() throws Exception {
        llm.shutdown();
        orchestrator.shutdown();
        mcpTasks.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llm.getPort());
        r.add("tasks-agent.orchestrator-url", () -> "http://localhost:" + orchestrator.getPort());
        r.add("tasks-agent.mcp-tasks-url", () -> "http://localhost:" + mcpTasks.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void createsEventViaOrchestratorThenLinksToTask() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID household = UUID.randomUUID();

        orchestrator.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(AgentActionResult.ok(
                        json.createObjectNode().put("eventUid", "evt-9")))));
        // link-event response body is unused by the flow — minimal task JSON is enough.
        mcpTasks.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("{\"id\":\"" + taskId + "\",\"calendarEventUid\":\"evt-9\"}"));

        var req = new TaskToEventRequest(taskId, household, "Pay rent",
                Instant.parse("2026-07-01T09:00:00Z"));

        http.post().uri("/agents/tasks/internal/task-to-event")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isTrue();
                    assertThat(res.result().get("eventUid").asText()).isEqualTo("evt-9");
                    assertThat(res.result().get("taskId").asText()).isEqualTo(taskId.toString());
                });

        RecordedRequest invoke = orchestrator.takeRequest();
        assertThat(invoke.getPath()).isEqualTo("/v1/agents/invoke");
        assertThat(invoke.getBody().readUtf8())
                .contains("\"targetAgent\":\"calendar\"")
                .contains("\"action\":\"create_event\"")
                .contains("\"summary\":\"Pay rent\"")
                .contains("2026-07-01T09:00:00Z")
                .contains("\"householdId\":\"" + household + "\"");

        RecordedRequest link = mcpTasks.takeRequest();
        assertThat(link.getPath()).isEqualTo("/internal/link-event");
        assertThat(link.getBody().readUtf8())
                .contains("\"id\":\"" + taskId + "\"")
                .contains("\"calendarEventUid\":\"evt-9\"");
    }

    @Test
    void calendarErrorPropagatesAndSkipsLink() {
        // No link-event response enqueued: if the flow wrongly called mcp-tasks the error
        // message would differ (timeout), so asserting the propagated text proves it short-circuited.
        orchestrator.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(jsonError("calendar boom")));

        var req = new TaskToEventRequest(UUID.randomUUID(), UUID.randomUUID(), "Pay rent",
                Instant.parse("2026-07-01T09:00:00Z"));

        http.post().uri("/agents/tasks/internal/task-to-event")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isFalse();
                    assertThat(res.error()).contains("calendar boom");
                });
    }

    @Test
    void missingFieldsReturnsErrorResult() {
        var req = new TaskToEventRequest(UUID.randomUUID(), UUID.randomUUID(), "Pay rent", null);
        http.post().uri("/agents/tasks/internal/task-to-event")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .value(res -> {
                    assertThat(res.ok()).isFalse();
                    assertThat(res.error()).contains("dueAt");
                });
    }

    private String jsonError(String msg) {
        try {
            return json.writeValueAsString(AgentActionResult.error(msg));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
