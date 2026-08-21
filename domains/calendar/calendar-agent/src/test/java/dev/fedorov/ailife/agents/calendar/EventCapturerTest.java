package dev.fedorov.ailife.agents.calendar;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The user-facing event-capture flow (road-test #486, Track H.2 / HC-1) through the agent's HTTP surface
 * ({@code POST /agents/calendar/intent}): a "запиши встречу …" message → the in-agent router classifies it
 * to {@code event-capture} → the LLM parses {@code {summary, dtstart}} → mcp-caldav's {@code /internal/event}
 * creates it → the reply confirms. MockWebServers stand in for llm-gateway and mcp-caldav. {@code userId} is
 * null so the household resolves to the envelope (no profile-routing hop), the same shortcut the
 * double-booking case uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class EventCapturerTest {

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
        // No userId in these cases → no household-routing hop; the learned-decision lookup soft-fails to
        // empty against a fast-fail address (behaviour unchanged, mirrors ActionControllerTest).
        r.add("calendar-agent.memory-service-url", () -> "http://127.0.0.1:1");
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void captureCueParsesTimeCreatesEventAndConfirms() throws Exception {
        UUID household = UUID.randomUUID();
        enqueueRouting("event-capture");
        // The capture turn parses summary + start.
        llmGateway.enqueue(chat("{\"summary\":\"Встреча с врачом\",\"dtstart\":\"2026-08-22T15:00:00Z\"}"));
        var dto = new CalendarEventDto(UUID.randomUUID(), household, "ours", "cal-uid-9",
                "Встреча с врачом", null, null, Instant.parse("2026-08-22T15:00:00Z"), null, null,
                List.of(), null);
        mcpCaldav.enqueue(new MockResponse().setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(dto)));

        NormalizedMessage msg = new NormalizedMessage(null, household, MessageScope.PRIVATE,
                "запиши встречу с врачом завтра в 15:00", List.of(), "telegram", "1", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.agent()).isEqualTo("calendar");
        assertThat(resp.text()).contains("Записал").contains("Встреча с врачом");

        // Two llm-gateway turns: routing classification, then the capture parse.
        assertThat(llmGateway.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/v1/chat");   // routing
        RecordedRequest parse = llmGateway.takeRequest(2, TimeUnit.SECONDS);                        // capture
        assertThat(parse.getBody().readUtf8()).contains("event").contains("now");   // skill body + now context

        RecordedRequest created = mcpCaldav.takeRequest(2, TimeUnit.SECONDS);
        assertThat(created.getPath()).isEqualTo("/internal/event");
        JsonNode body = json.readTree(created.getBody().readUtf8());
        assertThat(body.path("summary").asString()).isEqualTo("Встреча с врачом");
        assertThat(body.path("householdId").asString()).isEqualTo(household.toString());
        assertThat(body.path("dtstart").asString()).isEqualTo("2026-08-22T15:00:00Z");
    }

    @Test
    void noResolvableTimeAsksInsteadOfCreating() throws Exception {
        UUID household = UUID.randomUUID();
        enqueueRouting("event-capture");
        // The model can't resolve a time → returns an empty object; the flow must ask, not file a wrong event.
        llmGateway.enqueue(chat("{}"));

        NormalizedMessage msg = new NormalizedMessage(null, household, MessageScope.PRIVATE,
                "надо бы сходить к врачу", List.of(), "telegram", "2", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Когда");

        // No mcp-caldav create was attempted (nothing enqueued → a stray call would hang/other error).
        assertThat(mcpCaldav.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    private IntentResponse post(NormalizedMessage msg) {
        return http.post().uri("/agents/calendar/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
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
