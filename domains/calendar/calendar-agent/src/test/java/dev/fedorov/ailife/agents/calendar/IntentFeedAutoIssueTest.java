package dev.fedorov.ailife.agents.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.calendar.CalendarFeedDto;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #195 — when {@code public-feed-base-url} is set, the agent auto-issues a feed on a user's first
 * calendar message and appends the subscribe link; on a later message (feed already exists) it doesn't.
 * llm-gateway + mcp-caldav are MockWebServers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IntentFeedAutoIssueTest {

    static MockWebServer llmGateway;
    static MockWebServer mcpCaldav;
    static final UUID HOUSEHOLD = UUID.randomUUID();
    static final UUID USER = UUID.randomUUID();
    static final String BASE = "https://ai-life-calendar.example.ts.net";

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
        r.add("calendar-agent.public-feed-base-url", () -> BASE);
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    private void enqueueChat() throws Exception {
        llmGateway.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new LlmChatResponse(
                        "mock-large", "Готово.", "stop", new LlmUsage(10, 5, 15)))));
    }

    private MockResponse jsonBody(Object body) throws Exception {
        return new MockResponse().setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(body));
    }

    @Test
    void firstMessageMintsFeedAndAppendsLink() throws Exception {
        enqueueChat();
        mcpCaldav.enqueue(jsonBody(List.of()));                       // GET /internal/feeds → none yet
        var minted = new CalendarFeedDto(UUID.randomUUID(), HOUSEHOLD, USER, "ai-life",
                "TKN-NEW", Instant.now(), null);
        mcpCaldav.enqueue(jsonBody(minted));                          // POST /internal/feeds → minted

        var msg = new NormalizedMessage(USER, HOUSEHOLD, MessageScope.PRIVATE,
                "что у меня в пятницу?", List.of(), "telegram", "1", Instant.now());

        http.post().uri("/agents/calendar/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange()
                .expectStatus().isOk()
                .expectBody(IntentResponse.class)
                .value(r -> assertThat(r.text())
                        .contains("Готово.")
                        .contains(BASE + "/ics/TKN-NEW.ics"));
    }

    @Test
    void laterMessageWithExistingFeedDoesNotAppendLink() throws Exception {
        enqueueChat();
        var existing = new CalendarFeedDto(UUID.randomUUID(), HOUSEHOLD, USER, "ai-life",
                "TKN-OLD", Instant.now(), null);
        mcpCaldav.enqueue(jsonBody(List.of(existing)));               // GET → already has a feed

        var msg = new NormalizedMessage(USER, HOUSEHOLD, MessageScope.PRIVATE,
                "добавь встречу завтра", List.of(), "telegram", "2", Instant.now());

        http.post().uri("/agents/calendar/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange()
                .expectStatus().isOk()
                .expectBody(IntentResponse.class)
                .value(r -> {
                    assertThat(r.text()).contains("Готово.");
                    assertThat(r.text()).doesNotContain("/ics/");
                });
    }
}
