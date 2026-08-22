package dev.fedorov.ailife.agents.calendar;

import tools.jackson.databind.JsonNode;
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
import org.junit.jupiter.api.BeforeEach;
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
 * The user-facing event-move flow (road-test #486, Track H.2 / HC-4) through the agent's HTTP surface: a
 * "перенеси встречу …" message routes to {@code event-move} → the agent reads the upcoming events → the LLM
 * picks the target + the new time → the reply <b>asks to confirm</b> (pendingAction set) and changes
 * <b>nothing</b>. Only the follow-up {@code /resume} with an affirmative PUTs the new time to mcp-caldav.
 * MockWebServers stand in for llm-gateway and mcp-caldav; {@code userId} is null so households resolve to the
 * envelope (no profile hop).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class EventMoverTest {

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

    /** The MockWebServers are shared across the class; drain any recorded requests so each test asserts only on its own. */
    @BeforeEach
    void drain() throws Exception {
        while (mcpCaldav.takeRequest(50, TimeUnit.MILLISECONDS) != null) { /* discard */ }
        while (llmGateway.takeRequest(50, TimeUnit.MILLISECONDS) != null) { /* discard */ }
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
    void moveByChatConfirmsBeforeRescheduling() throws Exception {
        UUID household = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        enqueueRouting("event-move");
        var event = new CalendarEventDto(eventId, household, "ours", "cal-uid-1",
                "Встреча с врачом", null, null, Instant.parse("2026-08-25T15:00:00Z"), null, null,
                List.of(), null);
        mcpCaldav.enqueue(new MockResponse().setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(List.of(event))));
        // The move turn: candidate #1, new start 16:00.
        llmGateway.enqueue(chat("{\"pick\":1,\"dtstart\":\"2026-08-25T16:00:00Z\"}"));

        NormalizedMessage msg = new NormalizedMessage(null, household, MessageScope.PRIVATE,
                "перенеси встречу с врачом на 16:00", List.of(), "telegram", "1", Instant.now());

        IntentResponse resp = post("/agents/calendar/intent", msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Перенести").contains("Встреча с врачом");
        assertThat(resp.pendingAction()).isNotNull();
        assertThat(resp.pendingAction().path("flow").asString()).isEqualTo("event-move-confirm");
        assertThat(resp.pendingAction().path("eventId").asString()).isEqualTo(eventId.toString());
        assertThat(resp.pendingAction().path("dtstart").asString()).isEqualTo("2026-08-25T16:00:00Z");

        // The only mcp-caldav call was the read; no PUT was issued yet.
        RecordedRequest read = mcpCaldav.takeRequest(2, TimeUnit.SECONDS);
        assertThat(read.getMethod()).isEqualTo("GET");
        assertThat(read.getPath()).startsWith("/internal/events");
        assertThat(mcpCaldav.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void pickedTargetWithNoNewTimeAsksForIt() throws Exception {
        UUID household = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        enqueueRouting("event-move");
        var event = new CalendarEventDto(eventId, household, "ours", "cal-uid-2",
                "Встреча с врачом", null, null, Instant.parse("2026-08-25T15:00:00Z"), null, null,
                List.of(), null);
        mcpCaldav.enqueue(new MockResponse().setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(List.of(event))));
        // Target clear, but no new time → the model returns just the pick; the flow must ask.
        llmGateway.enqueue(chat("{\"pick\":1}"));

        NormalizedMessage msg = new NormalizedMessage(null, household, MessageScope.PRIVATE,
                "перенеси встречу с врачом", List.of(), "telegram", "2", Instant.now());

        IntentResponse resp = post("/agents/calendar/intent", msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("На какое время");
        assertThat(resp.pendingAction()).isNull();   // nothing to confirm yet
    }

    @Test
    void resumeAffirmativePutsTheNewTime() throws Exception {
        UUID household = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var updated = new CalendarEventDto(eventId, household, "ours", "cal-uid-3",
                "Встреча с врачом", null, null, Instant.parse("2026-08-25T16:00:00Z"), null, null,
                List.of(), null);
        mcpCaldav.enqueue(new MockResponse().setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(updated)));

        ObjectNode pending = json.createObjectNode();
        pending.put("flow", "event-move-confirm");
        pending.put("eventId", eventId.toString());
        pending.put("summary", "Встреча с врачом");
        pending.put("dtstart", "2026-08-25T16:00:00Z");
        NormalizedMessage reply = new NormalizedMessage(null, household, MessageScope.PRIVATE,
                "да", List.of(), "telegram", "3", Instant.now());

        IntentResponse resp = post("/agents/calendar/resume", new ResumeRequest(reply, pending));
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Перенёс").contains("Встреча с врачом");
        assertThat(resp.pendingAction()).isNull();   // lock cleared

        RecordedRequest put = mcpCaldav.takeRequest(2, TimeUnit.SECONDS);
        assertThat(put.getMethod()).isEqualTo("PUT");
        assertThat(put.getPath()).isEqualTo("/internal/event/" + eventId);
        JsonNode body = json.readTree(put.getBody().readUtf8());
        assertThat(body.path("dtstart").asString()).isEqualTo("2026-08-25T16:00:00Z");
    }

    @Test
    void resumeDeclineLeavesTheEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        ObjectNode pending = json.createObjectNode();
        pending.put("flow", "event-move-confirm");
        pending.put("eventId", eventId.toString());
        pending.put("summary", "Встреча с врачом");
        pending.put("dtstart", "2026-08-25T16:00:00Z");
        NormalizedMessage reply = new NormalizedMessage(null, UUID.randomUUID(), MessageScope.PRIVATE,
                "нет", List.of(), "telegram", "4", Instant.now());

        IntentResponse resp = post("/agents/calendar/resume", new ResumeRequest(reply, pending));
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("без изменений");
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
