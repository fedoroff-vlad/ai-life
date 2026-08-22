package dev.fedorov.ailife.agents.calendar;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
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
 * The user-facing event-cancel flow (road-test #486, Track H.2 / HC-3) through the agent's HTTP surface: a
 * "отмени встречу …" message routes to {@code event-cancel} → the agent reads the upcoming events → the LLM
 * picks the target → the reply <b>asks to confirm</b> (pendingAction set) and deletes <b>nothing</b>. Only
 * the follow-up {@code /resume} with an affirmative deletes via mcp-caldav. MockWebServers stand in for
 * llm-gateway and mcp-caldav; {@code userId} is null so households resolve to the envelope (no profile hop).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class EventCancellerTest {

    static MockWebServer llmGateway;
    static MockWebServer mcpCaldav;

    @BeforeAll
    static void start() throws Exception {
        llmGateway = new MockWebServer();
        llmGateway.start();
        mcpCaldav = new MockWebServer();
        mcpCaldav.start();
    }

    @AfterAll
    static void stop() throws Exception {
        llmGateway.shutdown();
        mcpCaldav.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
        r.add("calendar-agent.mcp-caldav-url", () -> "http://localhost:" + mcpCaldav.getPort());
        r.add("calendar-agent.memory-service-url", () -> "http://127.0.0.1:1");
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void cancelByDescriptionConfirmsBeforeDeleting() throws Exception {
        UUID household = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        enqueueRouting("event-cancel");
        var event = new CalendarEventDto(eventId, household, "ours", "cal-uid-1",
                "Встреча с врачом", null, null, Instant.parse("2026-08-25T15:00:00Z"), null, null,
                List.of(), null);
        mcpCaldav.enqueue(new MockResponse().setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(List.of(event))));
        // The pick turn: the model chooses candidate #1.
        llmGateway.enqueue(chat("{\"pick\":1}"));

        NormalizedMessage msg = new NormalizedMessage(null, household, MessageScope.PRIVATE,
                "отмени встречу с врачом", List.of(), "telegram", "1", Instant.now());

        IntentResponse resp = post("/agents/calendar/intent", msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Отменить").contains("Встреча с врачом");
        // Confirm-before-delete: the reply carries a pendingAction (locks the conversation), nothing deleted.
        assertThat(resp.pendingAction()).isNotNull();
        assertThat(resp.pendingAction().path("flow").asString()).isEqualTo("event-cancel-confirm");
        assertThat(resp.pendingAction().path("eventId").asString()).isEqualTo(eventId.toString());

        // The only mcp-caldav call was the read; no DELETE was issued.
        RecordedRequest read = mcpCaldav.takeRequest(2, TimeUnit.SECONDS);
        assertThat(read.getMethod()).isEqualTo("GET");
        assertThat(read.getPath()).startsWith("/internal/events");
        assertThat(mcpCaldav.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void resumeAffirmativeDeletesTheEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        mcpCaldav.enqueue(new MockResponse().setResponseCode(204));

        ObjectNode pending = json.createObjectNode();
        pending.put("flow", "event-cancel-confirm");
        pending.put("eventId", eventId.toString());
        pending.put("summary", "Встреча с врачом");
        NormalizedMessage reply = new NormalizedMessage(null, UUID.randomUUID(), MessageScope.PRIVATE,
                "да", List.of(), "telegram", "2", Instant.now());

        IntentResponse resp = post("/agents/calendar/resume", new ResumeRequest(reply, pending));
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Отменил").contains("Встреча с врачом");
        assertThat(resp.pendingAction()).isNull();   // lock cleared

        RecordedRequest deleted = mcpCaldav.takeRequest(2, TimeUnit.SECONDS);
        assertThat(deleted.getMethod()).isEqualTo("DELETE");
        assertThat(deleted.getPath()).isEqualTo("/internal/event/" + eventId);
    }

    @Test
    void resumeDeclineLeavesTheEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        ObjectNode pending = json.createObjectNode();
        pending.put("flow", "event-cancel-confirm");
        pending.put("eventId", eventId.toString());
        pending.put("summary", "Встреча с врачом");
        NormalizedMessage reply = new NormalizedMessage(null, UUID.randomUUID(), MessageScope.PRIVATE,
                "нет", List.of(), "telegram", "3", Instant.now());

        IntentResponse resp = post("/agents/calendar/resume", new ResumeRequest(reply, pending));
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("без изменений");
        // Nothing was deleted.
        assertThat(mcpCaldav.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    private IntentResponse post(String uri, Object body) {
        return http.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();
    }

    private void enqueueRouting(String skill) throws Exception {
        llmGateway.enqueue(chat("{\"action\":\"skill\",\"name\":\"" + skill + "\"}"));
    }

    private MockResponse chat(String content) throws Exception {
        return new MockResponse().setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new LlmChatResponse(
                        "mock-large", content, "stop", new LlmUsage(20, 10, 30))));
    }
}
